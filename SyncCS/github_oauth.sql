-- Applied to the SyncCS Supabase project as migration synccs_github_oauth_pending_codes.
-- Auth codes are ephemeral and accessible to the trusted Edge Function using service_role only.
-- Run this migration on other deployments alongside supabase.sql.
create table if not exists public.synccs_oauth_pending (
  nonce_hash text primary key check (nonce_hash ~ '^[a-f0-9]{64}$'),
  auth_code text not null check (length(auth_code) between 1 and 2048),
  expires_at timestamptz not null default (clock_timestamp() + interval '5 minutes')
);
alter table public.synccs_oauth_pending enable row level security;
revoke all on public.synccs_oauth_pending from public, anon, authenticated;
grant select, insert, update, delete on public.synccs_oauth_pending to service_role;
comment on table public.synccs_oauth_pending is
  'Short-lived GitHub OAuth PKCE authorization codes keyed by unguessable client nonce; service_role only';
