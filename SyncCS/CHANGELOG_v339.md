# SyncCS v339 — changes from v338

> This is an implementation summary. GitHub Actions compiles the plugin; two-device runtime interoperability requires separate validation.

| Area | v338 | v339 |
|---|---|---|
| Installed extension deletion | Did not propagate deletions to other devices | Reconciles remote inventory against local inventory and calls CloudStream's own deletePlugin() for removed entries |
| Extension inventory format | JSON array; lacked deletion history | Versioned object with items and persistent deleted identity keys; reads legacy arrays |
| Offline client restores deleted extensions | Could re-upload an old list | Server tombstones override preexisting entries from stale clients |
| Same-name extensions from multiple repositories | Some lookups selected solely by internal name | Inventory identity includes URL + repository URL; deletion also matches repository path |
| Default CloudStream repositories | Extensions from built-in repositories could lose their repository URL in backup | Resolves repository provenance from both user repositories and PREBUILT_REPOSITORIES |
| Newly linked device | Local/server inventory ambiguity | Existing server inventory is authoritative; local items migrate only when cloud inventory is absent |
| Concurrent inventory edits | No three-way reconciliation | Three-way merge with remembered local plugin baseline, tombstones and CAS retry |
| Server CAS conflicts | HTTP conflict shown as failure | Re-reads server up to three times and retries; avoids applying most local/remote preference updates before CAS success |
| Plugin lifecycle trigger | SharedPreferences listener only for tracked preferences | Listens for changes to PLUGINS_KEY and PLUGINS_KEY_LOCAL, with 700ms debouncing |
| Incomplete plugin load | Could treat transient empty local inventory as deletion | Requires both CloudStream local and online plugin-load flags to be true before any install/delete reconciliation |
| Plugin restore verification | Plugin manifest queue cleared on install attempts | Compares installed plugin identities and binary hashes before advancing the plugin baseline; failures remain pending |
| Local .cs3 restore | Some archived plugins registered as remote items | Restores verified files as local PluginData in PLUGINS_KEY_LOCAL |
| Account switch | Previous pending plugin/modified state could persist | Clears account-local queues and blocks an unsafe switch from pushing previous account's data |
| Supabase pagination | offset=500 paging | Stable item_key cursor paging prevents shifted pages on insertion before cursor |
| Automatic success freshness | Timestamp advanced even on failure | Updates lastSyncAt only when the whole sync reports 'Synced:' |
| First GitHub login completed | 'Sync complete' then immediate restart after 1.5s | 'Sync complete' for 1.5s then 'Restarting CloudStream...' for 0.85s, followed by restart |
| Manual Sync now | Shows completed dialog | No automatic restart (unchanged) |
| Recovery encryption | AES-GCM with local keystore-wrapped sync key | Unchanged; existing backups and passphrases remain compatible |
| Sync settings whitelist | CineStream/StreamPlay settings as v338 | Unchanged, retains v338 settings work |

## Known limitations not claimed as complete

- Full per-key *delta download* and server change-event cursors are **not implemented**; the new keyset pagination still downloads and decrypts the full dataset each sync.
- Concurrent writes to the *same non-extension preference key* still follow the existing changed-local/changed-remote reconciliation rules. Arbitrary conflicting API tokens/settings cannot always be semantically merged.
- Per-CloudStream-profile namespace partitioning isn't implemented because the current CloudStream stores a number of settings outside a profile context.
- True all-or-nothing transactions across plugin files, SharedPreferences and cloud writes are impossible with the current host APIs; retries and pending lists reduce but don't eliminate partial application.
- The current local binary registry behavior must be verified against the installed CloudStream version, especially local .cs3 paths on Android 11+ and Android TV.
- Since v338 did not store plugin tombstones, the *first* v339 plugin reconciliation migrates the legacy manifest conservatively. Check source and destination device inventory after both are updated.
- Switching an existing installation to a **different GitHub account** is blocked to avoid cross-account data leakage; a dedicated account migration/reset UX is not yet provided.
- Real-device A/B concurrent sync, process-death recovery and CloudStream restart remain unverified until device testing.

## Two-device verification checklist

1. Update **both** A and B to v339 before running synchronization: v338 readers cannot parse the new versioned inventory object. Use the same GitHub sync account and passphrase. Sync the intact source A first; then B.
2. Install one online plugin on A; verify download on B, binary hash and provider loading after restart.
3. Install one standalone .cs3 on A; verify B registers it as local and it loads after restart.
4. Remove plugin on A; verify server manifest marks it deleted and B removes it on next sync; reboot B and verify it doesn't return.
5. Reopen stale/offline B, then sync A; confirm removed plugin does not reappear.
6. Concurrently add different plugins on A and B, with overlapping syncs; verify both remain installed.
7. Remove repository on A; confirm only plugins installed from that repository are removed from B.
8. Delete all plugins on A and sync; B must not re-upload the old list. **Do not test this on production data unless backed up.**
9. Change CineStream and StreamPlay settings on A, verify B, and ensure the restored settings aren't uploaded back unchanged.
10. Simulate interrupted download, HTTP 409 conflict and app termination mid-sync; verify retries, pending status and intact cloud data.
11. Disconnect/reconnect the same account, confirm pending state resets. Check cross-account prevention separately with disposable profiles.
12. Confirm first-login notification shows 'Sync complete' -> 'Restarting CloudStream...' and launches CloudStream; verify manual Sync now doesn't restart.
