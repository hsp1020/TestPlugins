# SyncCS v2 — GitHub sign-in and encrypted cloud sync

## v340 — server-authoritative CloudStream profiles and incremental updates

- CloudStream's existing `keyIndex` values and profile metadata are synchronized through the encrypted `x/profiles` manifest; no second profile UI or device-specific UUID mapping is introduced. Existing server profile slots are authoritative on a newly linked device.
- Profile-scoped bookmarks, history and playback state stay under CloudStream's original `<keyIndex>/...` keys. Installed extensions and explicitly shared plugin preferences remain application-wide. The selected profile stays local to each device.
- After the first full snapshot, `sync_change_events` enables event-cursor pulls of changed rows only, including deletion tombstones. The client replays events after its initial snapshot to avoid missing concurrent writes. A server without the migration remains on v339-compatible full reads.
- Concurrent scalar edits use server version compare-and-swap (CAS); the committed remote value wins and a losing local value is retained in encrypted local `pending_conflicts`. Supported collection preferences use raw-value three-way membership merging.
- A failed sync never advances the successful cursor. Pending plugin installs/deletions and missed events are retried after restart. Installation verification and the first-login restart flow remain unchanged.

**Required deployment:** run the updated `SyncCS/supabase.sql` in the connected Supabase project before expecting incremental reads; a plugin-only upgrade is not sufficient to create database change events. Run v340 on all devices connected to an account before relying on its profile manifest or cursor semantics.

**Scope:** v340 does not claim a cross-system atomic transaction across the Android preferences, extension files and Supabase. Android TV / Android 11+ local `.cs3` recovery and two-device concurrent-update scenarios still require device execution tests. Cross-GitHub-account migration UI remains outside this release.


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

## QualityDataHelper runtime ABI compatibility (v6)

- Do not directly call `QualityDataHelper.getSourcePriorityCache()` or `getQualityPriorityCache()`: older installed CloudStream binaries may not expose the getters, causing `NoSuchMethodError` and an app-wide crash.
- Try to invalidate priority caches via runtime-reflected getter or backing field, if available. If the cache is absent on that version, preserve saved preferences and request a CloudStream restart for refreshed priorities.
- Refresh CloudStream service account caches through runtime-detected companion APIs; do not hard-link to cache setters missing in older host builds.
- `SyncCoordinator` catches and reports `LinkageError` as a host compatibility failure rather than allowing a fatal app restart. Persistent sync database and plugin backups are not reset.

## First GitHub sign-in restore and restart (v7)

After the *first* successful GitHub sign-in and fully successful `SyncCoordinator.synchronize()`, show `Sync complete` for 1.5 seconds, then restart CloudStream using the same `Intent.makeRestartActivityTask`/process exit mechanism already used by StremioC settings imports. Do not restart for manual `Sync now`, subsequent account sign-ins, incomplete plugin installation, or sync errors. This lets CloudStream reload restored plugins and settings before use.

## CineStream / StreamPlay settings audit (v7)

CineStream reference: [CineStream Settings.kt](https://github.com/SaurabhKaperwan/CSX/blob/master/CineStream/src/main/kotlin/com/megix/settings/Settings.kt), [SettingsDialog.kt](https://github.com/SaurabhKaperwan/CSX/blob/master/CineStream/src/main/kotlin/com/megix/settings/SettingsDialog.kt), [SettingsProviders.kt](https://github.com/SaurabhKaperwan/CSX/blob/master/CineStream/src/main/kotlin/com/megix/settings/SettingsProviders.kt). Cross-checked with StremioC `SettingsBottomFragment.kt` export/import.

- Read CineStream global settings from CloudStream's DataStore (not a `CineStream` XML file): concurrency, download-only links, catalog activation, provider ordering/default/seen state, Stremio addon metadata and provider switches, ShowBox/Wyzie/GramCinema tokens, Cloudflare bypass switch and per-domain cookies.
- Discover keys from CineStream's loaded `com.megix.settings.Settings` string fields where available. Also preserve a safe fallback list for when CineStream hasn't loaded yet; `p_*`, `stremio_*` and `cf_cookie_*` are discovered by namespace.
- Include all these entries directly as encrypted `d/<key>` DataStore items: no duplicated parallel backups. DataStore change listeners then trigger regular debounced sync for CineStream switches and tokens, and restore writes back to the original CloudStream storage before restart.
- StreamPlay reference: [settings code](https://github.com/hsp1020/cloudstream-extensions-phisher2/tree/main/StreamPlay/src/main/kotlin/com/Phisher98/settings). Its account token (`token`), Wyzie key (`wyzie_key`), disabled provider set, provider profiles, extension toggles, language, concurrency and Stremio addon/catalog URLs are in the `StreamPlay` preference file and are already represented by `p/StreamPlay/<key>`.
- Exclude only StreamPlay runtime telemetry `STREAMPLAY_TELEMETRY_V2` and obsolete `provider_stats_*` from backup and restore because they describe device performance, not the user's settings.
- CineStream cookie values are session material that can expire independently on other devices; exporting them does not guarantee an active session.

## Scheduling and triggers (v7)

All tracked data types share the *same* sync loop; there is no separate polling frequency for movies, account tokens, addons or settings. The loop compares the local snapshot with encrypted cloud entries and only transfers changed items.

| Trigger | Delay / condition |
| --- | --- |
| Manual Sync now | Immediately (foreground Activity) |
| First GitHub login | Immediately after token exchange; successful sync followed by restart |
| Tracked SharedPreferences change | Debounced 1.8 seconds after last change |
| Watch/bookmark update event | Debounced 2 seconds |
| Home/account reload event | Debounced 3 seconds |
| Plugins loaded event | Debounced 4 seconds; refresh observed preference files |
| Activity started | Debounced 2.5 seconds (requires signed-in/ready/active owner) |
| Activity resumed | If last sync started over 120 seconds ago |
| Activity paused | Debounced 1.5 seconds |
| Last Activity stopped | Debounced 0.5 seconds |
| Foreground periodic check | Every 15 minutes while signed in and ready |
| Restore a missing `.cs3` | Only when pending plugin list exists and an Activity is available; manual or resume-based sync |
| Upload plugin binaries | When `x/plugins` inventory differs; uploads files before manifest |

Automatic trigger timers replace a previously pending trigger of the same type, so densely clustered changes cause only one sync. The sync mutex serializes concurrent runs. Android does not guarantee execution after the app process is killed; this is **not** a background scheduler or a fixed cloud-backup interval.
