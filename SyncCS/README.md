# SyncCS v2 – Supabase synchronization

This version is **not compatible with the original Gist backend**. No Gist import is attempted.

## Server setup

**Ordinary SyncCS users do not register on Supabase.** They create a SyncCS account inside CloudStream. Only the server administrator manages a Supabase project.

1. Create a Supabase project. Enable email/password authentication.
2. Run [supabase.sql](supabase.sql) in the SQL editor. The script creates a user-scoped `sync_entries` table, a private `sync-files` bucket and row-level security policies.
3. Install SyncCS and open its settings. The project's HTTPS URL and public publishable key are configured in `SyncBackend.kt`. Enter your email, account password, device name and a recovery passphrase with at least 12 characters.
4. Choose **Sign up** on the first device, confirm email if the project requires it, then **Sign in**. Sign in on another device with the **same recovery passphrase**. The encryption passphrase cannot be reset if the encrypted backup is the only copy of your data.
5. Select **Sync now** on the second device to restore data and queued extensions. Restart CloudStream to load extensions registered by restoration.

## Public signup and email verification

- Admin-only **required** setup: Supabase Dashboard → Authentication → URL Configuration → Site URL must be set to `https://zbmofheqyswjbziecmyn.supabase.co/functions/v1/synccs-confirmed` instead of `http://localhost:3000`. The public `synccs-confirmed` Edge Function has already been deployed as the confirmation destination. The CloudStream user will then return to the app and tap **Sign in**.
- An **existing email confirmation link** may still include the old localhost redirect. Verification normally occurs before redirection, so try Sign in after opening that link; an error page alone does not prove verification failed.
- **Not ready for arbitrary public signups:** Supabase's built-in email service sends only to organization-authorized email addresses and is rate limited to two emails per hour. The server administrator must configure custom SMTP (or a supported Send Email hook) before distributing SyncCS to other users.
- Sign up with **SyncCS account** email and password, not the Supabase administrator password. Recovery passphrase encrypts the data and is independent from that password.

Do not use the Supabase service_role key in the plugin. E2E-encrypted payloads use AES-256-GCM (per-record authentication) with a PBKDF2-HMAC-SHA256 recovery key. A local Android Keystore key wraps the cached recovery key and Supabase session. The server can see item identifiers and timestamps but not plaintext payloads.

## Included

- Shared CloudStream library state: bookmarks, favorites, subscriptions, resume positions, per-episode state, and the configured source/quality priority profile fields.
- All CloudStream external service accounts stored under `auth_tokens` and `auth_ids` (subject to provider token validity); account caches refreshed after import.
- Extension preferences saved in ordinary SharedPreferences files, excluding recognized cache and Android/system preference files.
- Online extension list and repositories, plugin file archive (up to 20 MB per file) and redownload via CloudStream's public plugin installer.
- Best-effort local extension file restoration to the CloudStream3/plugins directory; this requires the host app's external-storage permission.
- Device-separated appearance, subtitle display and hardware/player settings, which are not overwritten by sync.
- On-demand sync, foreground pull, debounced local change detection, a 15-minute foreground poll and deletion tombstones.
- Optimistic server version checks so simultaneous writes produce a conflict rather than silently overriding unobserved remote changes.

## Important limitations

- SyncCS uses the provisioned Seoul Supabase project `zbmofheqyswjbziecmyn`. To use an independently hosted server, change the public URL and publishable key in `SyncBackend.kt`.
- Unknown plugin-owned databases, private files or Android Keystore-bound secrets cannot be moved generically. SharedPreferences-based settings are supported.
- Local .cs3 automatic restoration may fail on Android versions lacking legacy CloudStream3/plugins filesystem access. In that case, manually install the archived extension.
- External service sessions are restored without a new login **if their original token remains valid or refreshable**. Revoked or expired non-refreshable tokens require provider reauthentication.
- Plugin installation and some restored settings require restarting CloudStream. No CloudStream core or UI code is patched.
- Device-specific settings are not shared. Server-side sync records do not provide detailed device management UI yet.
- The initial client does not expose a complete Nuvio-equivalent history/collection model; it syncs the corresponding state that CloudStream persists and exposes to extensions.
- The schema performs atomic batches with expected row versions; on a concurrent change the client reports the conflict and retries at the next sync.

This implementation should be verified on Android phone, tablet and TV with a deployed Supabase project before being treated as production-ready. The GitHub Actions build and device tests have not been executed as part of the commit.
