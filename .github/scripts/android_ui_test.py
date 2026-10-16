#!/usr/bin/env python3
"""Android emulator UI evidence + guarded StremioC transition checks.

Tests the *actual* CloudStream host. Missing UI states never count as success.
Only Python stdlib and Android SDK adb are required.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape

PACKAGE = "com.lagradost.cloudstream3.prerelease"
CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"


def run(args, *, timeout=45, ok=True, binary=False):
    p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if ok and p.returncode:
        raise RuntimeError(f"{args!r} failed ({p.returncode}): {p.stderr.decode(errors='replace')[:600]}")
    return p.stdout if binary else p.stdout.decode(errors="replace").strip()


def adb(*args, **kw):
    return run(["adb", *args], **kw)


class UITest:
    def __init__(self, args):
        self.args = args
        self.out = Path(args.output) / args.device
        self.out.mkdir(parents=True, exist_ok=True)
        self.results = []
        self.counter = 0
        self.last_xml = ""
        self.size = (args.width, args.height)

    def result(self, name, status, note=""):
        self.results.append(dict(step=name, status=status, note=note))
        print(f"[UI][{status}] {name}: {note}", flush=True)

    def screenshot(self, name):
        self.counter += 1
        stem = f"{self.counter:02d}-{re.sub('[^a-z0-9_-]', '-', name.lower())}"
        try:
            png = adb("exec-out", "screencap", "-p", timeout=30, binary=True)
            if not png.startswith(b"\x89PNG\r\n\x1a\n"):
                raise ValueError("adb screencap did not return PNG")
            (self.out / (stem + ".png")).write_bytes(png)
        except Exception as e:
            self.result(name + "_screenshot", "FAIL", str(e))
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/window.xml", timeout=35)
            xml = adb("exec-out", "cat", "/sdcard/window.xml", timeout=20)
            ET.fromstring(xml)
            self.last_xml = xml
            (self.out / (stem + ".xml")).write_text(xml, encoding="utf-8")
        except Exception as e:
            self.result(name + "_ui_tree", "FAIL", str(e))
            self.last_xml = ""
        try:
            focus = adb("shell", "dumpsys", "window", "windows", timeout=20)
            (self.out / (stem + "-focus.txt")).write_text(
                "\n".join(x for x in focus.splitlines() if "mCurrentFocus" in x or "mFocusedApp" in x),
                encoding="utf-8")
        except Exception:
            pass
        return self.last_xml

    def nodes(self):
        if not self.last_xml:
            return []
        try:
            return list(ET.fromstring(self.last_xml).iter("node"))
        except ET.ParseError:
            return []

    @staticmethod
    def label(node):
        return " ".join((node.get("text", ""), node.get("content-desc", ""))).strip()

    def find(self, pattern):
        exp = re.compile(pattern, re.I)
        for node in self.nodes():
            if node.get("enabled") == "false":
                continue
            if exp.search(self.label(node)):
                return node
        return None

    def tap(self, pattern):
        node = self.find(pattern)
        if node is None:
            return False
        bounds = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if bounds is None:
            return False
        x1, y1, x2, y2 = map(int, bounds.groups())
        if x2 <= x1 or y2 <= y1:
            return False
        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(2)
        self.screenshot("after-tap-" + pattern[:24])
        return True

    def wait(self, pattern, seconds=35):
        stop = time.monotonic() + seconds
        while time.monotonic() < stop:
            self.screenshot("wait-" + pattern[:12])
            if self.find(pattern):
                return True
            time.sleep(3)
        return False

    def foreground(self):
        raw = adb("shell", "dumpsys", "activity", "activities", timeout=30, ok=False)
        return PACKAGE in "\n".join(s for s in raw.splitlines() if "topResumedActivity" in s or "mResumedActivity" in s)

    def logs(self):
        text = adb("logcat", "-d", "-v", "threadtime", timeout=60, ok=False)
        (self.out / "logcat.txt").write_text(text, encoding="utf-8")
        return text

    def configure_display(self):
        a = self.args
        adb("shell", "wm", "size", f"{a.width}x{a.height}")
        adb("shell", "wm", "density", str(a.density))
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb("shell", "settings", "put", "system", "user_rotation", str(a.rotation))
        self.result("display_config", "PASS", f"{a.device}: wm {a.width}x{a.height} @ {a.density}, rotation={a.rotation}")

    def seed(self):
        """Seed a *public* deterministic test catalog before the app first launches.

        Root is required because an official release APK is not run-as debuggable.
        If the system image disallows root, fail visibly instead of silently testing
        an empty CloudStream installation.
        """
        adb("root", timeout=45, ok=False)
        adb("wait-for-device", timeout=60)
        if adb("shell", "id", "-u", ok=False) != "0":
            self.result("catalog_fixture", "FAIL", "adbd root unavailable: cannot seed StremioC SharedPreferences")
            return False
        fixture = [{"id": 1, "name": "Cinemeta", "link": CINEMETA,
                    "enabled": True, "type": "StremioC"}]
        value = escape(json.dumps(fixture, separators=(",", ":")))
        xml = '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n'
        xml += '<map>\n<string name="stremio_saved_links">' + value + '</string>\n</map>\n'
        local = self.out / "test-fixture.xml"
        local.write_text(xml, encoding="utf-8")
        adb("push", str(local), "/data/local/tmp/stremioc-pref.xml")
        path = f"/data/data/{PACKAGE}/shared_prefs/StremioC.xml"
        uid = adb("shell", "stat", "-c", "%u", f"/data/data/{PACKAGE}")
        if not uid.isdigit():
            raise RuntimeError("Unable to determine CloudStream UID")
        adb("shell", "mkdir", "-p", f"/data/data/{PACKAGE}/shared_prefs")
        adb("shell", "cp", "/data/local/tmp/stremioc-pref.xml", path)
        adb("shell", "chown", f"{uid}:{uid}", path)
        adb("shell", "chmod", "600", path)
        self.result("catalog_fixture", "PASS", "Seeded public Cinemeta manifest in StremioC prefs")
        return True

    def install(self):
        adb("install", "-r", "-g", self.args.apk, timeout=180)
        if PACKAGE not in adb("shell", "pm", "list", "packages", PACKAGE):
            raise RuntimeError("Official CloudStream prerelease package not found after installation")
        self.result("host_install", "PASS", PACKAGE)
        if not self.seed():
            return False
        adb("shell", "appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
        adb("shell", "mkdir", "-p", "/sdcard/Cloudstream3/plugins")
        adb("push", self.args.plugin, "/sdcard/Cloudstream3/plugins/StremioC.cs3", timeout=90)
        self.result("plugin_staged", "PASS", "Local .cs3 copied to Cloudstream3/plugins")
        adb("logcat", "-c", ok=False)
        adb("shell", "am", "force-stop", PACKAGE, ok=False)
        adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1", timeout=35)
        return True

    def bootstrap(self):
        """Advance only obvious first-run confirmations; capture every transition."""
        self.screenshot("first-launch")
        for _ in range(9):
            if self.find(r"StremioC|Cinemeta|Home|Search"):
                return
            changed = False
            for label in (r"^English$", r"^(Next|Continue|Get Started|Finish|Done|Skip|Start)$"):
                if self.tap(label):
                    changed = True
                    break
            if not changed:
                return

    def check_plugin(self):
        for i in range(16):
            logs = self.logs()
            if re.search(r"Loaded plugin StremioC(?:\.cs3)? successfully", logs, re.I):
                self.result("plugin_loaded", "PASS", "CloudStream PluginManager success log")
                return True
            time.sleep(3)
        self.result("plugin_loaded", "FAIL", "No PluginManager success log for local StremioC.cs3")
        return False

    def transitions(self):
        # The host must visibly display StremioC and its Cinemeta provider.
        self.bootstrap()
        self.screenshot("home-before-provider-selection")
        if self.find(r"^Cinemeta$"):
            selected = self.tap(r"^Cinemeta$")
        else:
            for trigger in (r"^StremioC$", r"^None$", r"Select (Provider|Source)", r"^Home$"):
                if self.tap(trigger) and self.find(r"^Cinemeta$"):
                    break
            selected = self.tap(r"^Cinemeta$")
        self.screenshot("home-after-provider-selection")
        if not selected:
            self.result("provider_selection", "FAIL", "Cinemeta provider not selectable in accessibility tree")
            return
        self.result("provider_selection", "PASS", "Selected Cinemeta StremioC provider")

        if not self.foreground():
            self.result("home_foreground", "FAIL", "CloudStream left foreground after provider selection")
            return
        self.result("home_foreground", "PASS", "CloudStream is foreground")
        self.screenshot("home-catalog-loaded")
        before = self.last_xml

        # Open catalog browser only from an actual visible catalog-row control.
        if self.tap(r"(popular|top|trending|recent|movie|series).*[→›]$|[→›].*(popular|top)"):
            self.result("catalog_browser_entry", "PASS", "Tapped visible catalog row")
            self.screenshot("catalog-browser")
            browser = self.last_xml
            if browser and browser != before:
                self.result("catalog_browser_transition", "PASS", "UI hierarchy changed")
            else:
                self.result("catalog_browser_transition", "FAIL", "No observable hierarchy change")
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(2)
            self.screenshot("catalog-browser-back")
            self.result("catalog_browser_back", "PASS" if self.foreground() else "FAIL",
                        "Back returned to CloudStream" if self.foreground() else "Host not foreground")
        else:
            self.result("catalog_browser_entry", "NOT_REACHED", "No matching catalog row visible")

        # Inspect a detail result only if a clickable poster/card is visible.
        self.screenshot("before-detail")
        detail_before = self.last_xml
        candidates = []
        for node in self.nodes():
            bounds = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if not bounds:
                continue
            x1,y1,x2,y2 = map(int,bounds.groups())
            if (node.get("clickable") == "true" and y1 > 120 and
                    x2-x1 > 70 and y2-y1 > 100 and
                    x2-x1 < 0.65*self.args.width):
                candidates.append(((y1,x1), (x1+x2)//2, (y1+y2)//2))
        if candidates:
            _, x, y = sorted(candidates)[0]
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(8)
            self.screenshot("detail")
            if self.last_xml and self.last_xml != detail_before:
                self.result("detail_transition", "PASS", "Tapped visible card; UI hierarchy changed")
            else:
                self.result("detail_transition", "FAIL", "Card tap produced no observable UI transition")
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(2)
            self.screenshot("detail-back")
            self.result("detail_back", "PASS" if self.foreground() else "FAIL", "Back navigation foreground")
        else:
            self.result("detail_transition", "NOT_REACHED", "No safely identifiable clickable poster/card")

        if self.tap(r"^Stremio Settings$"):
            self.screenshot("stremio-settings")
            self.result("settings_dialog", "PASS", "Stremio settings action tapped")
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(1)
            self.screenshot("settings-back")
        else:
            self.result("settings_dialog", "NOT_REACHED", "Settings control not visible on current screen")

        if self.args.device == "android-tv":
            for key in ("KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_CENTER", "KEYCODE_BACK"):
                adb("shell", "input", "keyevent", key)
                time.sleep(1)
                self.screenshot("tv-" + key.lower())
            self.result("tv_remote_input", "PASS" if self.foreground() else "FAIL",
                        "DPAD key sequence dispatched; host foreground inspected")

    def finish(self):
        logs = self.logs()
        fatals = [line for line in logs.splitlines()
                  if "FATAL EXCEPTION" in line or "Fatal signal" in line]
        self.result("crash_scan", "FAIL" if fatals else "PASS",
                    f"{len(fatals)} fatal markers in collected logcat")
        report = {
            "profile": vars(self.args), "results": self.results,
            "evidence": "Screenshot and XML files are emulator output, not simulated renders",
            "host_package": PACKAGE,
            "crash_excerpt": fatals[:5],
        }
        report["profile"].pop("apk", None)
        report["profile"].pop("plugin", None)
        (self.out / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        failures = [r for r in self.results if r["status"] == "FAIL"]
        incomplete = [r for r in self.results if r["status"] == "NOT_REACHED"]
        with (self.out / "summary.md").open("w", encoding="utf-8") as f:
            f.write(f"# Android UI: {self.args.device}\n\n")
            f.write("| Check | Outcome | Evidence / limitation |\n|---|---|---|\n")
            for r in self.results:
                note = r["note"].replace("|", "/").replace("\n", " ")
                f.write(f"| {r['step']} | {r['status']} | {note} |\n")
        print(f"[UI] {len(failures)} failures; {len(incomplete)} unreachable checks", flush=True)
        return 1 if failures or incomplete else 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--device", required=True)
    for key in ("width", "height", "density", "rotation"):
        p.add_argument("--" + key, type=int, required=True)
    for key in ("apk", "plugin", "output"):
        p.add_argument("--" + key, required=True)
    args = p.parse_args()
    test = UITest(args)
    try:
        test.configure_display()
        if test.install():
            if test.check_plugin():
                test.transitions()
    except Exception as e:
        test.result("harness_exception", "FAIL", repr(e))
        print(repr(e), file=sys.stderr)
    finally:
        status = test.finish()
    raise SystemExit(status)


if __name__ == "__main__":
    main()
