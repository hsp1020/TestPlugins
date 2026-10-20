-- SyncCS v2 schema. Run in the Supabase SQL editor once per project.
-- Do not put the service_role key in the Android plugin.
create table if not exists public.sync_entries (
    user_id uuid not null references auth.users(id) on delete cascade,
    item_key text not null check (length(item_key) between 1 and 512),
    payload text,
    deleted boolean not null default false,
    device_id text not null check (length(device_id) between 1 and 80),
    version bigint not null default 1,
    updated_at timestamptz not null default clock_timestamp(),
    primary key (user_id, item_key),
    constraint sync_payload_valid check (
        (deleted and payload is null) or
        (not deleted and payload is not null and length(payload) <= 1048576)
    )
);
alter table public.sync_entries enable row level security;
drop policy if exists "SyncCS own rows" on public.sync_entries;
create policy "SyncCS own rows" on public.sync_entries
    for all to authenticated using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));
revoke all on table public.sync_entries from anon;
grant select, insert, update, delete on table public.sync_entries to authenticated;

create or replace function public.sync_apply_changes(p_changes jsonb)
returns integer
language plpgsql
security invoker
set search_path = public
as $$
declare
    item jsonb;
    owner uuid := auth.uid();
    current_version bigint;
    expected_version bigint;
    key_name text;
    changed integer := 0;
begin
    if owner is null then raise exception 'Authentication required'; end if;
    if jsonb_typeof(p_changes) <> 'array' or jsonb_array_length(p_changes) > 50 then
        raise exception 'Invalid change batch';
    end if;
    for item in select value from jsonb_array_elements(p_changes) loop
        key_name := item->>'item_key';
        if key_name is null or length(key_name) not between 1 and 512 then
            raise exception 'Invalid item key';
        end if;
        expected_version := case when item ? 'expected_version' and
            jsonb_typeof(item->'expected_version') <> 'null'
            then (item->>'expected_version')::bigint else null end;
        select version into current_version from public.sync_entries
            where user_id = owner and item_key = key_name for update;
        if not found then
            if expected_version is not null then
                raise exception 'Sync conflict for %', key_name using errcode = '40001';
            end if;
            insert into public.sync_entries (user_id, item_key, payload, deleted, device_id)
            values (owner, key_name, item->>'payload',
                (item->>'deleted')::boolean, item->>'device_id')
            on conflict do nothing;
            if not found then
                raise exception 'Concurrent sync conflict for %', key_name using errcode = '40001';
            end if;
        else
            if expected_version is null or expected_version <> current_version then
                raise exception 'Sync conflict for %', key_name using errcode = '40001';
            end if;
            update public.sync_entries set
                payload = item->>'payload',
                deleted = (item->>'deleted')::boolean,
                device_id = item->>'device_id',
                updated_at = clock_timestamp(),
                version = version + 1
            where user_id = owner and item_key = key_name;
        end if;
        changed := changed + 1;
    end loop;
    return changed;
end;
$$;
revoke all on function public.sync_apply_changes(jsonb) from public, anon;
grant execute on function public.sync_apply_changes(jsonb) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit)
    values ('sync-files', 'sync-files', false, 20971520)
    on conflict (id) do update set public = false, file_size_limit = 20971520;

drop policy if exists "SyncCS read files" on storage.objects;
drop policy if exists "SyncCS write files" on storage.objects;
drop policy if exists "SyncCS update files" on storage.objects;
create policy "SyncCS read files" on storage.objects for select to authenticated
    using (bucket_id = 'sync-files' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "SyncCS write files" on storage.objects for insert to authenticated
    with check (bucket_id = 'sync-files' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "SyncCS update files" on storage.objects for update to authenticated
    using (bucket_id = 'sync-files' and (storage.foldername(name))[1] = (select auth.uid())::text)
    with check (bucket_id = 'sync-files' and (storage.foldername(name))[1] = (select auth.uid())::text);
