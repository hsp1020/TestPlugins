# QuickJS P0-0 testbed (Nuvio only)

This diagnostic does **not** replace Rhino or modify scraper execution.

## Procedure

1. Install or update **Nuvio** from the existing TestPlugins-build plugin repository after the normal build publishes the updated .cs3.
2. Open **Nuvio → Settings → QuickJS compatibility test**.
3. Tap **RUN QUICKJS TEST**. Copy the dialog text using **COPY**.
4. Expect **PACKAGE: PASS**, **DEX: PASS**, and **EXECUTION: PASS**.
5. Repeat after reopening settings, and test on other supported device ABIs if available.

The test checks the installed .cs3 ZIP for `lib/<device-ABI>/libquickjs.so`, extracts only the required ABI into the app's code cache, and tells the QuickJS Android loader to use that path for this test. It then creates/evaluates/closes three QuickJS runtimes using `class`, `const`, generators, and `async/await`. No scraper or network request is executed.

## How to collect failure details

- Use the dialog **COPY** button.
- With ADB: `adb logcat -s NuvioQuickJsProbe:I '*:S'`.
- To inspect the built package: `unzip -l Nuvio.cs3 | grep -E 'classes.dex|libquickjs.so'`.

Failure interpretation:

- **PACKAGE** error: the .cs3 lacks a native library matching the device ABI, or the package/path cannot be accessed.
- **DEX** error: QuickJS classes were not included in classes.dex.
- **EXECUTION** error: native loading, Kotlin/JNI binary compatibility, or JavaScript evaluation failed. Report the full exception message; do not infer success from a successful Gradle build.

This is a basic JNI + language-feature smoke test, not proof of long-run memory stability or scraper compatibility. QuickJS requires Android API 23+; the diagnostic is opt-in, and the existing Rhino path remains available on earlier devices.
