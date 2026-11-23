# QuickJS P0-0 / P0-1 testbed (Nuvio only)

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

## How to collect failure details

- Use the dialog **COPY** button.
- With ADB: `adb logcat -s NuvioQuickJsProbe:I '*:S'`.
- To inspect the built package: `unzip -l Nuvio.cs3 | grep -E 'classes.dex|libquickjs.so'`.

Failure interpretation:

- **PACKAGE** error: the .cs3 lacks a native library matching the device ABI, or the package/path cannot be accessed.
- **DEX** error: QuickJS classes were not included in classes.dex.
- **EXECUTION** error: native loading, Kotlin/JNI binary compatibility, or JavaScript evaluation failed. Report the full exception message; do not infer success from a successful Gradle build.

This is a basic JNI + language-feature smoke test, not proof of long-run memory stability or scraper compatibility. QuickJS requires Android API 23+; the diagnostic is opt-in, and the existing Rhino path remains available on earlier devices.
