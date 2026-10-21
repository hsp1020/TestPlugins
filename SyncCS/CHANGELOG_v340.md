# SyncCS v340 — profile / incremental / merge

Changes relative to v339:

| Area | v339 | v340 |
| --- | --- | --- |
| CloudStream profile metadata | Local account list not synchronized | Encrypted server-authoritative `x/profiles` with native `keyIndex`; profile list creation/edit/deletion replicated |
| Per-profile library and history | Already stored under CloudStream numeric prefixes but lacked a shared profile index manifest | Resolve profile list before reading scoped data; retain inactive profile data without automatic deletion or tombstones |
| Remote reads | Every row, paginated by item_key | Full snapshot for initial sync; journal event cursor for subsequent changed rows only |
| Server schema | `sync_entries` CAS updates | Append-only `sync_change_events` journal inside the same CAS transaction |
| Scalar conflicts | Device clock may override committed remote | Server-committed value wins; local losing values retained encrypted |
| Collection conflicts | Plugin inventory three-way only | Plugin inventory unchanged; known list/set preferences merge independent additions/removals |
| Failure / account switch | Settings baseline + pending plugin manifest | Event cursor persisted after successful synchronization, isolated revision and profile state reset on account change |
| Compatibility | v339 schema | Fallback full read until Supabase SQL migration has been applied |

No CloudStream core or built-in profile UI changes. Per-device settings remain excluded. Application-wide plugins and shared settings remain global. Profile selection stays local. Profile-roster removals do not automatically delete local or remote library, watch-history or playback data; inactive profiles are excluded from record sync until their index is present again.

## Deployment and verification

1. Execute `SyncCS/supabase.sql` against the actual Supabase project. This creates the journal and replaces `sync_apply_changes`; committing SQL alone does not deploy it.
2. Install v340 on both test devices. Link A, then B to the same GitHub account, with the same recovery passphrase.
3. Confirm both CloudStream profile lists use the same native key indices, and per-profile watch state stays isolated. Add, rename, then remove a secondary profile; verify the roster updates without SyncCS deleting stored history or uploading history tombstones.
4. Change one shared scalar and two different list members from separate devices; verify CAS handling and preservation of independent list edits.
5. Change one item out of a large library, synchronize, and confirm subsequent requests read journal changes rather than all `sync_entries` payloads.
6. Interrupt a sync before its final baseline commit, restart, and verify pending local writes and journal events are replayed.
7. Test local `.cs3` restore on Android TV and Android 11+, and both devices editing concurrently. CI build success does not count as these tests.

Known limits: no global atomic transaction across DB/files/preferences, no migration UI for switching GitHub accounts, and unrecognized serialized list formats use scalar conflict policy.
