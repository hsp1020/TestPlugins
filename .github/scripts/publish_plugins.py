#!/usr/bin/env python3
"""Prepare monotonic CloudStream versions and publish immutable plugin artifacts."""
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path
from urllib.parse import quote

VERSION = re.compile(r"(?m)^(\s*version\s*=\s*)(\d+)(?=\s*(?://[^\n]*)?$)")
REPOSITORY = "hsp1020/TestPlugins-build"
RESET_MARKER = ".plugins-v1-reset"


def read_index(path: Path) -> list[dict]:
    result = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(result, list) or not all(isinstance(x, dict) for x in result):
        raise ValueError(f"Invalid plugins index: {path}")
    names = [x["internalName"] for x in result]
    if len(names) != len(set(names)):
        raise ValueError(f"Duplicate plugin names in {path}")
    return result


def version_in(path: Path) -> tuple[int, str]:
    contents = path.read_text(encoding="utf-8")
    matches = list(VERSION.finditer(contents))
    if len(matches) != 1:
        raise ValueError(f"Expected one numeric Gradle version in {path}; got {len(matches)}")
    return int(matches[0].group(2)), contents


def prepare(src: Path, builds: Path) -> None:
    old = {p["internalName"]: int(p["version"]) for p in read_index(builds / "plugins.json")}
    first_build_after_reset = not (builds / RESET_MARKER).exists()
    modules = sorted(src.glob("*/build.gradle.kts"))
    if not modules:
        raise ValueError("No plugin modules found")
    for gradle_file in modules:
        module = gradle_file.parent.name
        current, contents = version_in(gradle_file)
        target = 1 if first_build_after_reset else max(current, old.get(module, 0) + 1)
        gradle_file.write_text(VERSION.sub(lambda m: m.group(1) + str(target), contents, count=1), encoding="utf-8")
        print(f"Version {module}: {current} -> {target}")


def publish(src: Path, builds: Path) -> None:
    index = read_index(src / "build/plugins.json")
    artifacts = {p.stem: p for p in src.glob("*/build/*.cs3")}
    names = {p["internalName"] for p in index}
    if names != set(artifacts):
        raise ValueError(f"Metadata/artifact mismatch: missing={sorted(names - set(artifacts))}, extra={sorted(set(artifacts) - names)}")
    if not index:
        raise ValueError("Refusing to publish an empty plugin index")
    published_files = set()
    for plugin in index:
        name = plugin["internalName"]
        gradle = src / name / "build.gradle.kts"
        expected_version, _ = version_in(gradle)
        if int(plugin["version"]) != expected_version:
            raise ValueError(f"Generated metadata version mismatch for {name}: {plugin['version']} != {expected_version}")
        original = artifacts[name]
        digest = hashlib.sha256(original.read_bytes()).hexdigest()
        file_name = f"{name}-v{expected_version}-{digest[:12]}.cs3"
        target = builds / file_name
        if target.exists():
            if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
                raise ValueError(f"Refusing to overwrite different bytes: {target}")
        else:
            shutil.copyfile(original, target)
        published_files.add(file_name)
        plugin["url"] = f"https://raw.githubusercontent.com/{REPOSITORY}/builds/{quote(file_name)}"
        plugin["fileSize"] = target.stat().st_size
        plugin["fileHash"] = f"sha256-{digest}"
        print(f"Publish {name} v{expected_version}: {file_name} ({plugin['fileSize']} bytes)")
    for old in builds.glob("*.cs3"):
        if old.name not in published_files:
            old.unlink()
    (builds / "plugins.json").write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (builds / RESET_MARKER).touch()


if __name__ == "__main__":
    if len(sys.argv) != 4 or sys.argv[1] not in ("prepare", "publish"):
        sys.exit("Usage: publish_plugins.py prepare|publish SRC BUILDS")
    command, src_root, builds_root = sys.argv[1:]
    (prepare if command == "prepare" else publish)(Path(src_root), Path(builds_root))
