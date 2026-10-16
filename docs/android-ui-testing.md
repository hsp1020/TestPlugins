# Android UI emulator tests

Run the **Android UI Emulator Tests** workflow manually from Actions on `master`.
It is intentionally independent of `.github/workflows/build.yml` and never
publishes plugin artifacts to the release repository.

Each of the five matrix jobs builds `:StremioC:make`, installs the official
CloudStream pre-release APK, stages the resulting local `.cs3` file, and boots
an Android emulator: phone portrait/landscape, tablet portrait/landscape, and
Android TV. The TV configuration uses an Android TV system image and sends
DPAD key events. Screen sizes/densities are CI display configurations, not
claims of exact physical-device equivalence.

The harness seeds only the public Cinemeta manifest
(`https://v3-cinemeta.strem.io/manifest.json`) into StremioC preferences on
root-capable emulator images. It **fails** when root, extension loading,
provider selection, or an expected transition is unavailable. A captured
CloudStream onboarding screen or empty host is **not** a passing StremioC test.

Each artifact `android-ui-<device>` contains timestamp-order screenshots,
UIAutomator XML layout trees, foreground-window data, `logcat.txt`,
`report.json`, and `summary.md`. The host/plugin SHA-256 checksums are
also collected. `PASS` means the described observable check passed;
`FAIL` means an actual failed check; `NOT_REACHED` means the scenario could
not be executed and makes the job fail. This is not a pixel-perfect visual
regression system. Popup/actor/trailer checks need deterministic seeded
content and additional navigators before they can be claimed as covered.

This workflow is **manual-only** to avoid running five emulators on every
source commit. Opening the GitHub Actions run and reviewing each artifact is
necessary to inspect screenshots and identify layout anomalies. No production
settings, app features, or existing build steps are changed.
