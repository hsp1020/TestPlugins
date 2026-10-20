# SyncCS v2 — GitHub sign-in and encrypted cloud sync

CloudStream **users sign in with GitHub only**. They never need a Supabase account, a separate SyncCS email/password, or an email-verification message. The server is operated centrally and all user records remain isolated by Supabase Auth user ID plus RLS.

## Using SyncCS

1. Update SyncCS and open **Sync account**.
2. Enter a recovery passphrase of at least 12 characters and choose a device name.
3. Tap **Sign in with GitHub**. Complete the sign-in in your external browser and return to CloudStream.
4. The plugin waits for a one-time login result and completes the PKCE code exchange automatically. If the sign-in succeeds, sync starts.
5. Sign in with the same GitHub account on other devices and supply the **same recovery passphrase**. This passphrase encrypts the backed-up data end to end; it is not a GitHub or Supabase password.

Supabase Edge Functions on the default project domain cannot render HTML and rewrite HTML responses to plain text. The OAuth callback therefore shows a short ASCII plaintext success notice instead of raw HTML; no custom domain is needed.\n\nOAuth codes and code verifiers never appear in the displayed browser page. The PKCE verifier is protected locally using the Android Keystore. The one-time callback handoff is keyed by a random 256-bit state, returns a code only to the waiting plugin, and is stored in a server table inaccessible to normal users. No GitHub client secret or Supabase service-role key is embedded in SyncCS.

## Server setup (operator only, one time)

The SyncCS Seoul Supabase project is **zbmofheqyswjbziecmyn**. The following has already been deployed:

- [supabase.sql](supabase.sql): original sync tables, RLS and extension archive bucket.
- [github_oauth.sql](github_oauth.sql): private five-minute OAuth code handoff table (applied).
- [supabase/synccs-github-oauth/index.ts](supabase/synccs-github-oauth/index.ts): GitHub PKCE callback + one-time polling endpoint (deployed and active).

**Additional manual OAuth provider configuration is required.** GitHub OAuth does *not* automatically become enabled when you sign in to the Supabase dashboard using GitHub. These controls are not exposed by the current connected management tools.

1. Open **[GitHub Developer Settings → OAuth Apps](https://github.com/settings/developers)** and create an OAuth App (name: `SyncCS`; homepage: `https://github.com/hsp1020/TestPlugins`). Use the **Authorization callback URL**:

   `https://zbmofheqyswjbziecmyn.supabase.co/auth/v1/callback`

2. Copy the generated **Client ID** and **Client Secret** directly into **[Supabase Authentication → Providers](https://supabase.com/dashboard/project/zbmofheqyswjbziecmyn/auth/providers)** → **GitHub**; switch it on and save. Do **not** commit the Client Secret to GitHub or put it in a CloudStream plugin.

3. In **[Supabase Authentication → URL Configuration](https://supabase.com/dashboard/project/zbmofheqyswjbziecmyn/auth/url-configuration)**, add this **Redirect URL allowlist pattern** (the final `/**` permits the nonce path segment):

   `https://zbmofheqyswjbziecmyn.supabase.co/functions/v1/synccs-github-oauth/**`

   Remove the default `http://localhost:3000` as the production **Site URL**, and change the Site URL to `https://zbmofheqyswjbziecmyn.supabase.co/functions/v1/synccs-github-oauth`. OAuth sign-in specifically relies on the explicit allowlisted redirect URL, not on a generic home page.

4. No SMTP provider is needed when GitHub is the only sign-in method.

GitHub OAuth requires separate operator configuration on both **GitHub** and **Supabase**. Until the GitHub provider is enabled the plugin stops before opening a browser and explains that server OAuth setup is incomplete.

## Login and sync UI

- CloudStream already registers `cloudstreamapp://` deep links. GitHub OAuth callback returns to that existing scheme without AndroidX Browser dependencies, so opening the settings dialog cannot fail with `AuthTabIntent` ClassNotFound.
- Browsers that allow OAuth callbacks to launch apps return to CloudStream automatically. **The browser tab may remain in browser history; Android does not let an ordinary external app forcibly close it.** If a browser blocks the launch, the server displays a short plain-text success notice and the user returns manually.
- SyncCS verifies the authorization via a one-time nonce-bound server polling endpoint. No OAuth code is exposed through the deep link.
- Successful login and manual **Sync now** show a native-themed indeterminate progress dialog, then a brief success message before dismissing. Sync failures show a separate error message.
- `Synced: downloaded 0, uploaded 0` means the current run found no changes; it does not imply an empty cloud backup.
- All UI code uses framework and AppCompat classes already provided by CloudStream; additional AndroidX Browser dependencies were removed after a runtime class-loading failure.

## Data and compatibility

- Sync data: CloudStream library, bookmarks, watch progress, source/quality priorities and standard SharedPreferences-based plugin settings.
- Provider account sessions saved by CloudStream may be transferred when valid or refreshable.
- Online plugin installation is best-effort; some local `.cs3` restorations require extra filesystem permissions or restart.
- Hardware decoder, subtitle layout, appearance, and download paths are device-local.
- User data from the **old email/password-based SyncCS account does not automatically move** to a new GitHub Auth user ID. Account migration requires a separately authorized export/import process.
- The OAuth callback is asynchronous: after browser login, return to CloudStream to complete session exchange.
- The latest code has **not been Android-built or exercised against a fully configured GitHub OAuth provider**. Server migrations and Edge Function deployment alone do not prove end-to-end login success.

## Extension installation and preference recovery fixes

- CloudStream compares plugin downloads against hashes prefixed with `sha256-`. SyncCS now passes the host-expected hash string instead of a raw 64-character digest.
- On a new device, the saved extension repository list is restored **before** the downloaded plugin files; missing repositories are reconstructed from the encrypted plugin manifest where possible. All missing online plugins are downloaded and registered in the host plugin inventory. Existing archived binary files are used as fallback.
- Android scoped-storage restrictions may prevent writing standalone local `.cs3` files to the legacy external storage folder. In that case SyncCS registers the verified archived binary in the app-private plugin directory so CloudStream loads it after restart.
- Plugin-prefixed CloudStream DataStore and default SharedPreferences keys are now included for CineStream, StreamPlay, StremioC, Ultima Evo, Elevate and YouTube, in addition to their dedicated preference files. Device-level settings and runtime caches are excluded.
- An incomplete plugin restore reports `Sync incomplete`, **not** `Synced`. Failures preserve the pending plugin inventory so the next sync can retry.
- The current per-device selected CloudStream account is not synchronized.
- After updating, sync the source device again before restoring another device; previously untracked settings cannot be recreated from older cloud snapshots.
- The code change addresses known omission paths but real-device validation is still required to establish complete plugin and preference restoration. CloudStream's own crash handler stores uncaught stack traces in the private `last_error` file, which may be needed if a device continues to exit.
