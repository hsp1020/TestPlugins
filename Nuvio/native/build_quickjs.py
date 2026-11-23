#!/usr/bin/env python3
"""Build QuickJS-KT 1.0.5 JNI with safe shared reference ownership.

The original Kotlin classes.jar stays unchanged; only libquickjs.so is rebuilt.
"""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

REPO = "https://github.com/dokar3/quickjs-kt.git"
REVISION = "57ce096200ac36bceb4e1ee5b6ec411b12357eb8"  # v1.0.5
NDK_VERSION = "27.2.12479018"
CMAKE_VERSION = "3.22.1"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")


def run(*args, cwd=None):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)


def replace_once(path, old, new):
    source = path.read_text()
    if source.count(old) != 1:
        raise RuntimeError(f"QuickJS source changed: expected one match in {path}: {old[:70]!r}")
    path.write_text(source.replace(old, new, 1))


def patch_native(native):
    jni = native / "jni"
    header = jni / "jni_globals.h"
    replace_once(
        header,
        "#endif //QJS_KT_JNI_GLOBALS_H",
        "void lock_jni_cache_lifecycle(void);\n"
        "void unlock_jni_cache_lifecycle(void);\n\n"
        "#endif //QJS_KT_JNI_GLOBALS_H",
    )

    globals_c = jni / "jni_globals.c"
    replace_once(
        globals_c,
        '#include "log_util.h"\n',
        '#include "log_util.h"\n#include <pthread.h>\n\n'
        "/* Synchronize cache initialization and final teardown, not JS execution. */\n"
        "static pthread_mutex_t jni_cache_lifecycle = PTHREAD_MUTEX_INITIALIZER;\n"
        "void lock_jni_cache_lifecycle(void) { pthread_mutex_lock(&jni_cache_lifecycle); }\n"
        "void unlock_jni_cache_lifecycle(void) { pthread_mutex_unlock(&jni_cache_lifecycle); }\n",
    )

    generated = jni / "jni_globals_generated.c"
    for name in ("unit", "ubyte_array"):
        old = (
            f"void set_cls_{name}(JNIEnv *env, jclass cls) {{\n"
            f"    if (_cls_{name} != NULL) {{\n"
            f"        (*env)->DeleteGlobalRef(env, _cls_{name});\n"
            "    }\n"
            f"    _cls_{name} = (*env)->NewGlobalRef(env, cls);\n"
            "}"
        )
        new = (
            f"void set_cls_{name}(JNIEnv *env, jclass cls) {{\n"
            f"    /* Shared by active runtimes: never invalidate an existing global ref. */\n"
            f"    if (_cls_{name} == NULL) {{\n"
            f"        _cls_{name} = (*env)->NewGlobalRef(env, cls);\n"
            "    }\n"
            "}"
        )
        replace_once(generated, old, new)

    # Eagerly resolve all generated class/method/field caches under the
    # lifecycle mutex. Otherwise lazy getters race when JS jobs run in parallel.
    declarations = (jni / "jni_globals_generated.h").read_text()
    names = re.findall(
        r"^(?:jclass|jmethodID|jfieldID)\s+"
        r"((?:cls|method|field)_[a-zA-Z0-9_]+)\(JNIEnv \*env\);$",
        declarations,
        re.MULTILINE,
    )
    if len(names) < 55 or len(names) != len(set(names)):
        raise RuntimeError(f"Unexpected JNI cache declarations: {len(names)}")
    eager = "".join(
        f"        (void) {name}(env);\n"
        for name in names if name not in ("cls_unit", "cls_ubyte_array")
    )

    bridge = jni / "quickjs_jni.c"
    replace_once(
        bridge,
        "    cache_java_vm(env);\n\n"
        "    jclass cls_unit = (*env)->GetObjectArrayElement(env, classes, 0);",
        "    lock_jni_cache_lifecycle();\n"
        "    cache_java_vm(env);\n\n"
        "    jclass cls_unit = (*env)->GetObjectArrayElement(env, classes, 0);",
    )
    replace_once(
        bridge,
        "    set_cls_ubyte_array(env, cls_ubyte_array);\n\n"
        "    JSRuntime *runtime = runtime_from_ptr(env, runtime_ptr);",
        "    set_cls_ubyte_array(env, cls_ubyte_array);\n"
        "    (*env)->DeleteLocalRef(env, cls_unit);\n"
        "    (*env)->DeleteLocalRef(env, cls_ubyte_array);\n"
        "    /* Populate shared JNI caches only when the first runtime starts. */\n"
        "    if (get_qjs_instance_count() == 1) {\n"
        + eager + "    }\n\n"
        "    JSRuntime *runtime = runtime_from_ptr(env, runtime_ptr);",
    )
    replace_once(
        bridge,
        "    return (jlong) globals;\n}",
        "    unlock_jni_cache_lifecycle();\n"
        "    return (jlong) globals;\n}",
    )

    content = bridge.read_text()
    start = content.index("Java_com_dokar_quickjs_QuickJs_releaseGlobals(")
    end = content.index("Java_com_dokar_quickjs_QuickJs_releaseContext(", start)
    section = content[start:end]
    before = "    Globals *globals = globals_from_ptr(env, globals_ptr);"
    if section.count(before) != 1:
        raise RuntimeError("Cannot lock JNI releaseGlobals")
    section = section.replace(before, "    lock_jni_cache_lifecycle();\n" + before, 1)
    tail = "    free(globals);\n}"
    if section.count(tail) != 1:
        raise RuntimeError("Cannot unlock JNI releaseGlobals")
    section = section.replace(
        tail, "    free(globals);\n    unlock_jni_cache_lifecycle();\n}", 1
    )
    bridge.write_text(content[:start] + section + content[end:])

    # Catch accidental native serialization or removal of the per-runtime cleanup.
    assert "clear_jni_refs_cache(env)" in bridge.read_text()
    assert "DeleteGlobalRef(env, _cls_unit)" not in generated.read_text().split(
        "void set_cls_unit(", 1
    )[1].split("jmethodID ", 1)[0].split("void set_cls_ubyte_array(", 1)[0]
    print(f"Patched QuickJS-KT 1.0.5 JNI: {len(names)} eager cache entries", flush=True)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: build_quickjs.py <Nuvio/build/quickjs-patched>")
    output = Path(sys.argv[1]).resolve()
    output.mkdir(parents=True, exist_ok=True)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("ANDROID_HOME or ANDROID_SDK_ROOT required")
    ndk = Path(sdk) / "ndk" / NDK_VERSION
    cmake = Path(sdk) / "cmake" / CMAKE_VERSION / "bin" / "cmake"
    if not (ndk / "build/cmake/android.toolchain.cmake").is_file():
        raise RuntimeError(f"Missing Android NDK {NDK_VERSION}: {ndk}")
    if not cmake.is_file():
        raise RuntimeError(f"Missing Android CMake {CMAKE_VERSION}: {cmake}")

    source = output / "source"
    stamp = output / "patched.sha256"
    digest = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    if source.exists() and (not stamp.is_file() or stamp.read_text() != digest):
        shutil.rmtree(source)
    if not source.exists():
        run("git", "clone", "--quiet", "--depth", "1", "--branch", "v1.0.5",
            "--recurse-submodules", "--shallow-submodules", REPO, source)
        sha = subprocess.check_output(
            ["git", "-C", str(source), "rev-parse", "HEAD"], text=True
        ).strip()
        if sha != REVISION:
            raise RuntimeError(f"Unexpected QuickJS upstream commit: {sha}")
        for path in ("quickjs/native/quickjs/quickjs.c",
                     "quickjs/native/c-vector/cvector.h"):
            if not (source / path).is_file():
                raise RuntimeError(f"QuickJS submodule missing {path}")
        patch_native(source / "quickjs/native")
        stamp.write_text(digest)

    staging = output / "jni"
    for abi in ABIS:
        build = output / ("build-" + abi)
        run(
            cmake, "-S", source / "quickjs/native", "-B", build,
            "-G", "Ninja", "-DTARGET_PLATFORM=android",
            "-DLIBRARY_TYPE=shared", "-DBUILD_WITH_JNI=ON",
            "-DANDROID_ABI=" + abi,
            "-DANDROID_PLATFORM=android-21",
            "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
            "-DCMAKE_MAKE_PROGRAM=" + str(Path(sdk) / "cmake" / CMAKE_VERSION / "bin/ninja"),
            "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
            "-DCMAKE_C_FLAGS_RELWITHDEBINFO=-O2 -g -DNDEBUG -fno-omit-frame-pointer",
        )
        run(cmake, "--build", build, "--parallel", "2")
        artifact = build / "libquickjs.so"
        if not artifact.is_file() or artifact.stat().st_size < 10_000:
            raise RuntimeError(f"Missing patched QuickJS library for {abi}")
        destination = staging / abi / "libquickjs.so"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(artifact, destination)
        with destination.open("rb") as binary:
            if binary.read(4) != b"\x7fELF":
                raise RuntimeError(f"Invalid ELF library for {abi}")
        print(f"Native patched {abi}: {destination.stat().st_size} bytes", flush=True)


if __name__ == "__main__":
    main()
