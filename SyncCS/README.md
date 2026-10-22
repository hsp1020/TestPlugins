# SyncCS — GitHub-only encrypted sync

**Backend:** GitHub API, private repository owned by the signed-in GitHub account.
There is no Supabase login, database, Edge Function, or migration dependency.

## Connect a source device

1. Create a GitHub **OAuth App** in GitHub Developer Settings, and **enable Device Flow**. Copy the app's Client ID. Never embed a GitHub Client Secret in the Android extension.
2. In SyncCS, open **Sync account** and enter the GitHub Client ID, a recovery passphrase of at least 12 characters, and a device name.
3. Authorize on the GitHub Device Flow page. Permission scope is **repo**, because GitHub OAuth Apps cannot currently narrow this permission to one automatically created private repository. For the safest isolation, use a separate GitHub account. Do not approve unknown OAuth apps.
4. SyncCS creates a private repository named **cloudstream-synccs-backup** if missing.
5. If the repository has no complete SyncCS backup, SyncCS explicitly asks whether **this device is the source**. Only approve a trusted, clean source device. The currently installed settings are backed up as the first Git revision in one atomic commit.
6. On later devices, authorize the **same GitHub account** and enter the **same recovery passphrase**. The complete GitHub backup replaces synchronized local preferences. A pre-link local recovery copy is stored encrypted in CloudStream's private app directory.

**Do not use the GitHub source-device confirmation on a device whose settings were contaminated by the previous SyncCS implementation.** This backend deliberately performs **no import** from the old Supabase cloud account.

The repository is **always private**. SyncCS refuses to use a public repository. The GitHub API access token and derived AES key are encrypted by Android Keystore before storage on the device. Content is encrypted per key with AES-256-GCM, derived using PBKDF2-HMAC-SHA256 (210,000 rounds). GitHub can see path names and commit messages but not decrypted preference values.

## Safety guarantees and limitations

- New GitHub backups are published as a **single Git commit** after all 16 encrypted data shards and plugin binary archives have been uploaded as Git objects. Crashes prior to the ref update do not publish an incomplete backup.
- Writes are guarded by the last observed Git commit and use **non-forced fast-forward ref updates**. Conflicting writes are rejected for retry; no force push.
- Scalar settings retain server-authoritative conflict behavior. Only keys with a local change listener event may be sent as deletions; unexpected local snapshot omissions are repaired from the server.
- Automatic deletion is rejected when it would remove more than 25 keys or 1% of the snapshot. More than 1,000 value changes in one write are also rejected.
- Plugin archives are SHA-256 verified before replacement. An absent archive stops restore instead of silently discarding an installed plugin.
- Every successful Git commit remains a versioned rollback point. **Restore previous GitHub backup** creates a new commit, preserving the earlier history and increasing recovery generation. Devices with an old generation cannot upload until re-linked.
- GitHub is not a real-time transactional database. CloudStream's native profiles, app preferences, and plugin binaries cannot be changed atomically across the Android filesystem. Restoration failures may still leave partial local changes, even when a pre-link encrypted copy exists. Test with a disposable account before production use.
- Each device must finish initialization before synchronizing normally; the first backup requires user confirmation.
- The encrypted local pre-link snapshot is stored in the app's private files and is **not** a substitute for a portable export or an off-device backup. Clearing app data destroys its Keystore key.
- GitHub commit history preserves earlier snapshots even when current data is deleted; plan long-term retention and sensitive-data erasure accordingly.

## Development/testing

The branch-specific workflow **SyncCS GitHub Backend CI** compiles the extension without publishing the .cs3 file to the public build repository.

Before release, validate with two disposable devices and a test GitHub account:
- first backup / interrupted initial commit / B-device restore with obsolete catalogs
- conflicting A-B writes / explicit delete vs missing preference key
- first installation failure / missing archived .cs3 / network loss
- manual rollback / re-link stale device / profile changes
- OAuth errors and expiry / Android TV device code entry

No Supabase data is automatically migrated or restored. Removing the previous Supabase project is a separate administrative action and does not happen during GitHub sign-in.
