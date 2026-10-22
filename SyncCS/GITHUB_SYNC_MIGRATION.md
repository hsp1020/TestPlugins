# SyncCS GitHub migration — safety branch

This is a non-production safety candidate, not a fully complete migration. Do not merge or deploy until Android builds and real-device tests pass.

## Implemented

- Removes legacy Supabase automatic synchronization from this branch's plugin entrypoint.
- GitHub App device-code authorization (requires an independently registered app) or fine-grained GitHub token.
- Only uses a pre-existing PRIVATE GitHub repository with Contents write permissions; never modifies Supabase.
- Writes one AES-GCM-encrypted, GZIP-packed snapshot to a dedicated synccs-backup Git branch. Git history preserves earlier versions. Commits use non-forced ref updates.
- First backup requires explicit user action and is refused if a remote already exists.
- Second-device restore must be explicitly chosen and does not upload the device's pre-existing data before restoration.
- Writes an encrypted independent local recovery checkpoint before applying a remote snapshot; rollback is attempted after an application error.
- Uses a local baseline and generation check and blocks mass changes and any implicit deletion caused by missing keys.
- Stores the installed-plugin manifest but does not mutate installed binaries until verified safe restoration is implemented.
- Manual sync only. There are no automatic Supabase or GitHub writes.

## Not production ready

- An independent GitHub App configuration, private repository, and user authorization are required.
- Github App token expiry and refresh must be handled and device-tested.
- Binary preflight, byte-level hash validation, rollback-safe installation and removal remain unfinished.
- Explicit deletion operation protocol, admin rollback with generation bump, efficient sharded snapshots, profile slot incarnation checks, and resilient resumable partial restores remain unfinished.
- The current contaminated Supabase dataset is NEVER migrated automatically.
- Full Android build, concurrent-device, Android TV and fault-injection tests remain required.

## Preconditions

1. Create a private GitHub repository, e.g. hsp1020/synccs-backup, with an initial README.
2. Create and install a GitHub App on ONLY that repository with Contents read/write and Device Flow enabled, and enter its Client ID in SyncCS. Alternatively generate a fine-grained PAT scoped to only that repository.
3. Supply the same recovery passphrase on every device. The GitHub repository stores no plaintext settings.

## Test gates

Initial interrupted upload; initial second device with pre-existing catalogs; invalid or missing snapshot; app crash during preferences restoration; stale device writes; >1000 deletions; concurrent branch writes; profile number reuse; binary SHA mismatch; network interruption; Android 11+ local .cs3 handling. Do not merge unless confirmed.

## Historical recovery

Enter a verified 40-character Git commit SHA from the dedicated backup branch. The engine reads and authenticates the old encrypted snapshot, creates a new Git commit with a NEW generation, then applies it locally. Old devices cannot publish again until explicit reinitialization. This is not equivalent to restoring the contaminated Supabase database.
