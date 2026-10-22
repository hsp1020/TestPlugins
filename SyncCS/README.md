# SyncCS — GitHub-only encrypted backup (safety candidate)

The GitHub-only implementation does not connect to Supabase and never imports previous Supabase records.

Create a dedicated private GitHub repository. The device writes an AES-GCM encrypted snapshot and encrypted verified plugin archives on its own `synccs-backup` branch. Every commit is retained for selective rollback, and any rollback creates a new generation to block stale devices.

Background synchronization and automatic plugin installation/uninstall are disabled pending reliability validation. Initial backup, restore, historical restore and manual sync require explicit action.

See [GitHub migration status](GITHUB_SYNC_MIGRATION.md). Do not release before remaining tests pass.
