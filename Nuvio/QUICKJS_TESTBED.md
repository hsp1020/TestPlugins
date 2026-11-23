# QuickJS P0-0 / P0-1 / P0-2 / P0-3 testbed (Nuvio only)

This diagnostic does **not** replace Rhino or modify scraper execution.

## Procedure

1. Install or update **Nuvio** from the existing TestPlugins-build plugin repository after the normal build publishes the updated .cs3.
2. Open **Nuvio → Settings → QuickJS compatibility test**.
3. Tap **RUN QUICKJS TEST**. Copy the dialog text using **COPY**.
4. Expect **PACKAGE: PASS**, **DEX: PASS**, and **EXECUTION: PASS**.
5. Repeat after reopening settings, and test on other supported device ABIs if available.

The test checks the installed .cs3 ZIP for `lib/<device-ABI>/libquickjs.so`, extracts only the required ABI into the app's code cache, and tells the QuickJS Android loader to use that path for this test. It then creates/evaluates/closes three QuickJS runtimes using `class`, `const`, generators, and `async/await`. No scraper or network request is executed.

## P0-1 getStreams adapter test

1. Update the Nuvio .cs3 from TestPlugins-build once the regular build publishes it.
2. Open **Nuvio → Settings → P0-1 getStreams adapter test**.
3. Tap **RUN P0-1 TEST**, and use **COPY** to capture the results.
4. Expect **PACKAGE: PASS**, **DEX: PASS**, and **ADAPTER: PASS**.
5. Reopen settings and repeat the test.

P0-1 creates a QuickJS runtime and an async `module.exports.getStreams` function. It checks both movie (TMDB 550, null season/episode) and TV (TMDB 1399, season 2 episode 3), awaits responses, and validates the JSON output, arguments, URL and quality. The stream URL uses the reserved `example.invalid` domain. **No HTTP, actual scraper or CloudStream stream lookup is performed.**

The original **RUN QUICKJS TEST** remains the independent P0-0 test, and Rhino remains in use for real scrapers. P0-1 does not validate fetch, DOM, crypto, require, settings, or real scraper compatibility.

## P0-2 QuickJS host API test

1. Install/update the Nuvio .cs3 from the standard TestPlugins-build publication.
2. Open **Nuvio → Settings → P0-2 QuickJS host API test**.
3. Tap **RUN P0-2 TEST**, then **COPY** in the result dialog.
4. Expect **PACKAGE: PASS**, **DEX: PASS**, **HOST: PASS**. Repeat after reopening settings.

The test registers native QuickJS bindings and reuses existing NuvioDom, NuvioCrypto and
JavaScript axios/cheerio/crypto-js/require modules. It checks asynchronous fetch (POST,
headers, status and JSON), axios GET, DOM selection, SHA-256, SCRAPER_SETTINGS,
unknown-module errors, rejected HTTP calls and coroutine cancellation.

HTTP responses are injected from a deterministic in-memory fixture using the reserved
`example.invalid` host. No external HTTP request or real scraper is run in the test.
The bridge has a transport backed by the existing NuvioHttp for later integration,
but real network behavior and cancellation of in-flight native HTTP calls are NOT yet verified.
Neither full Nuvio API parity nor actual scraper compatibility is established until P0-3.
The original P0-0 and P0-1 tests remain available, and Rhino scraper execution is unchanged.

## P0-3 automated full-repository testbed

1. Update **Nuvio** from the usual TestPlugins-build plugin repository.
2. Open **Nuvio → Settings → repository → SCRAPERS**.
3. Tap **RUN ALL QUICKJS TESTS** once, below ENABLE ALL / DISABLE ALL.
4. A progress dialog displays the number completed and the last scraper. **CANCEL** stops the batch.
5. When finished, tap **COPY ALL** for the full per-scraper report.

The batch automatically tests **every scraper in the selected repository** that supports
movies or TV, regardless of its ON/OFF switch. It reads the already downloaded JS,
including the existing per-scraper settings, and uses actual HTTP. No manual selection,
ID typing, or individual test buttons are required.

One representative input is used per scraper to avoid duplicate work: movie-capable
scrapers receive movie TMDB **550**; TV-only scrapers receive TV TMDB **1399**,
season **1**, episode **1**. This is a compatibility smoke test, **not** an exhaustive
movie/TV content coverage test. Some sources may return EMPTY due to missing tokens
or unsupported titles even when their JS runtime works.

At most **3 QuickJS test runtimes** operate concurrently, each using a diagnostic
timeout of at most **15 seconds** (or the existing shorter timeout setting). This
limits simultaneous work but cannot guarantee interruption of a synchronous
infinite JavaScript loop or an already-blocking native HTTP call.
The batch runs **only when pressed**; no polling or scheduled tests are added.

Result categories: `REAL: PASS` (at least one URL/infoHash),
`REAL: EMPTY` (no streams), `REAL: INVALID` (items without valid links),
and `REAL: FAIL` (script/HTTP/timeout/parse errors). Summary totals and
per-scraper diagnostics are copied together; any tokenized full URLs are omitted.
PASS does not prove actual video playback.

The original Rhino playback path, enabled/disabled settings, P0-0, P0-1,
and P0-2 tests are unchanged.

## How to collect failure details

- Use the dialog **COPY** button.
- With ADB: `adb logcat -s NuvioQuickJsProbe:I '*:S'`.
- To inspect the built package: `unzip -l Nuvio.cs3 | grep -E 'classes.dex|libquickjs.so'`.

Failure interpretation:

- **PACKAGE** error: the .cs3 lacks a native library matching the device ABI, or the package/path cannot be accessed.
- **DEX** error: QuickJS classes were not included in classes.dex.
- **EXECUTION** error: native loading, Kotlin/JNI binary compatibility, or JavaScript evaluation failed. Report the full exception message; do not infer success from a successful Gradle build.

This is a basic JNI + language-feature smoke test, not proof of long-run memory stability or scraper compatibility. QuickJS requires Android API 23+; the diagnostic is opt-in, and the existing Rhino path remains available on earlier devices.
