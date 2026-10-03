#!/usr/bin/env python3
"""Rebuild the bundled 7-Zip 26.03 executable with Android NDK r27c.

ANDROID_NDK_HOME must point to NDK 27.2.12479018. Pass an ABI or --host.
The source archive is pinned, kept in this repository, and independently hashed.
"""
import hashlib
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "third_party/7zip/7z2603-src.tar.xz"
EXPECTED = "9cbde5099c6deb73691b0579063da5827522ccbbcba3f0020fd04e8c8c16c0d4"
TARGETS = {"arm64-v8a": "aarch64-linux-android", "armeabi-v7a": "armv7a-linux-androideabi", "x86_64": "x86_64-linux-android", "x86": "i686-linux-android"}

def main():
    abi = sys.argv[1] if len(sys.argv) == 2 else "arm64-v8a"
    if hashlib.sha256(SOURCE.read_bytes()).hexdigest() != EXPECTED:
        raise SystemExit("7-Zip source checksum mismatch")
    source = ROOT / "build/native-archives/source"
    source.mkdir(parents=True, exist_ok=True)
    if not (source / "CPP/7zip/Bundles/Alone2/makefile.gcc").exists():
        with tarfile.open(SOURCE) as archive:
            archive.extractall(source, filter="data")
    bundle = source / "CPP/7zip/Bundles/Alone2"
    output = ROOT / "build/native-archives" / abi
    output.mkdir(parents=True, exist_ok=True)
    if abi == "--host":
        args = ["-f", "../../cmpl_gcc.mak", "CC=gcc", "CXX=g++"]
    else:
        if abi not in TARGETS:
            raise SystemExit("Unknown ABI")
        ndk = pathlib.Path(os.environ["ANDROID_NDK_HOME"])
        if "27.2.12479018" not in (ndk / "source.properties").read_text():
            raise SystemExit("Use Android NDK 27.2.12479018")
        compiler = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        args = ["-f", "../../cmpl_clang.mak", f"CC={compiler}/{TARGETS[abi]}26-clang", f"CXX={compiler}/{TARGETS[abi]}26-clang++", "CXXFLAGS_EXTRA=-Wno-shorten-64-to-32 -Wno-implicit-int-conversion", "LDFLAGS=-pie -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,--gc-sections", "LIB2=-ldl"]
    subprocess.run(["make", "-j4", *args, f"O={output}"], cwd=bundle, check=True)
    executable = output / "7zz"
    if abi != "--host":
        destination = ROOT / "app/src/main/jniLibs" / abi / "lib7zz.so"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(executable, destination)
        print(f"Built {destination.relative_to(ROOT)}")
    else:
        print(f"Host test executable: {executable}")

if __name__ == "__main__":
    main()
