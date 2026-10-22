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
- Scalar settings retain server-authoritative conflict behavior. Missing snapshots are NOT deletions. Explicit user review is required for deletions and is bound to the exact Git HEAD and exact key set.
- Automatic deletion is blocked. Even approved deletion batches may not exceed 25 keys or 1% of the snapshot; a rolling seven-day 25-item budget includes deletions **inside** plugin and profile manifests. More than 1,000 value changes in one commit are also rejected.
- Plugin archives are SHA-256 verified before replacement. An absent archive stops restore instead of silently discarding an installed plugin.
- Every successful Git commit remains a versioned rollback point. **Restore previous GitHub backup** creates a new commit, preserving the earlier history and increasing recovery generation. Devices with an old generation cannot upload until re-linked.
- A **durable encrypted local undo journal** captures tracked preferences, profile state, SyncCS metadata and installed plugin binaries before local mutation. On restore failure or process death, normal sync is paused until foreground recovery verifies local settings, plugin digests and profiles. Recovery can still fail (e.g. disk corruption, missing CloudStream API compatibility); it then **keeps the journal and blocks sync**, rather than claiming success. The Android host does not offer a true filesystem-wide atomic transaction.
- Each device must finish initialization before synchronizing normally; the first backup requires user confirmation.
- The pre-link backup is an encrypted JSON snapshot of the ORIGINAL native preference keys, profile UUIDs, and plugin manifest saved at `filesDir/synccs-recovery/pre-github-link-<device-id>-<timestamp>.enc`. It does NOT contain full plugin binaries; it is not yet available through a user-facing restore screen. Clearing app data destroys its Keystore key.
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

## Known limitations (do not describe as fully verified)

- SyncCS assigns a stable UUID independently of CloudStream's numeric profile slot. Native account deletion removes a `<slot>/synccs_profile_uuid` marker in CloudStream storage; when a number is reused, a new UUID is issued even if SyncCS missed the deletion event. Watch-state backup keys use `u/<uuid>/...` while CloudStream continues to use its native numeric paths. Reuse of slot 1 is therefore ALLOWED. A stale B device with the old UUID adopts the new server UUID and its stale numeric-slot watch data is cleared under the encrypted rollback journal before the new profile is restored. This relies on the stock CloudStream `removeKeys(slot)` deletion contract and needs device-level validation after core upgrades.
- GitHub OAuth and two real Android clients have not been exercised against a disposable GitHub OAuth App. Offline JVM safety tests and extension compilation are **not** substitutes for this end-to-end verification.
- The GitHub OAuth App `repo` permission covers more than the dedicated backup repository. Use a dedicated GitHub account if least privilege is mandatory.
