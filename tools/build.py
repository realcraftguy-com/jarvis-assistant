#!/usr/bin/env python3
"""Gradle-free build for both Jarvis APKs.

    python3 tools/build.py core      -> build/jarvis-core.apk   (S21 FE)
    python3 tools/build.py call      -> build/jarvis-call.apk   (Xiaomi)
    python3 tools/build.py all

The signing keystore lives outside the repository in ~/.config/jarvis-build/
(created on first build, mode 600). No secrets are compiled into the APKs.
"""
import os
import secrets
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
TOOLS = SDK / "build-tools/36.0.0"
ANDROID_JAR = SDK / "platforms/android-36/android.jar"
JAVA_HOME = Path(os.environ.get("JARVIS_JAVA_HOME", "/usr/lib/jvm/java-21-openjdk"))
KEYDIR = Path.home() / ".config/jarvis-build"
CACHE = Path.home() / ".cache/jarvis-build"

# third-party libraries per app: (maven path, sha1 from Maven Central)
DEPS = {
    "call": [("com/microsoft/onnxruntime/onnxruntime-android/1.31.0/onnxruntime-android-1.31.0.aar",
              "09e434725ac3aea8a313f74d942c1b6e0ad5091e")],
    "core": [],
}


def fetch(path, sha1):
    import hashlib
    import urllib.request
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / Path(path).name
    if not f.exists():
        urllib.request.urlretrieve("https://repo1.maven.org/maven2/" + path, f)
    if hashlib.sha1(f.read_bytes()).hexdigest() != sha1:
        f.unlink()
        raise SystemExit(f"Prüfsumme falsch: {f.name}")
    return f
OUT = ROOT / "build"


def run(*cmd, **kw):
    cmd = [str(c) for c in cmd]
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.stderr.write(r.stdout + r.stderr)
        raise SystemExit(f"FAILED: {' '.join(cmd[:3])} …")
    return r.stdout


def keystore():
    KEYDIR.mkdir(parents=True, exist_ok=True)
    os.chmod(KEYDIR, 0o700)
    ks, pw = KEYDIR / "jarvis.keystore", KEYDIR / "keystore.pass"
    if not pw.exists():
        pw.write_text(secrets.token_urlsafe(24))
        os.chmod(pw, 0o600)
    if not ks.exists():
        run(JAVA_HOME / "bin/keytool", "-genkeypair", "-keystore", ks, "-storepass:file", pw,
            "-keypass:file", pw, "-alias", "jarvis", "-keyalg", "RSA", "-keysize", "3072",
            "-validity", "10000", "-dname", "CN=Jarvis")
        os.chmod(ks, 0o600)
    return ks, pw


def build(app: str):
    src_dirs = [ROOT / app / "src", ROOT / "shared/src"]
    work = OUT / app
    if work.exists():
        shutil.rmtree(work)
    (work / "classes").mkdir(parents=True)
    out_apk = OUT / f"jarvis-{app}.apk"

    jars, natives = [], []
    for path, sha1 in DEPS.get(app, []):
        aar = fetch(path, sha1)
        d = work / ("dep-" + aar.stem)
        with zipfile.ZipFile(aar) as z:
            z.extractall(d)
        jars.append(d / "classes.jar")
        natives += list((d / "jni/arm64-v8a").glob("*.so"))

    run(TOOLS / "aapt2", "compile", "--dir", ROOT / app / "res", "-o", work / "res.zip")
    run(TOOLS / "aapt2", "link", "-o", work / "base.apk", "--manifest", ROOT / app / "AndroidManifest.xml",
        "-I", ANDROID_JAR, "--java", work / "gen", "--auto-add-overlay",
        *(["--debug-mode"] if os.environ.get("JARVIS_DEBUG") == "1" else []), work / "res.zip")

    sources = [str(p) for d in src_dirs for p in d.rglob("*.java")]
    sources += [str(p) for p in (work / "gen").rglob("*.java")]
    run(JAVA_HOME / "bin/javac", "--release", "17", "-encoding", "UTF-8", "-Xlint:-options",
        "-classpath", os.pathsep.join([str(ANDROID_JAR)] + [str(j) for j in jars]), "-d", work / "classes", *sources)

    classes = sorted(str(p) for p in (work / "classes").rglob("*.class"))
    run(TOOLS / "d8", "--release", "--min-api", "31", "--lib", ANDROID_JAR, "--output", work, *classes, *jars)

    unsigned = work / "unsigned.apk"
    shutil.copy(work / "base.apk", unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        for dex in sorted(work.glob("classes*.dex")):
            z.write(dex, dex.name)
        for so in natives:
            z.write(so, "lib/arm64-v8a/" + so.name)
        if app == "core" and (ROOT / "jarvis.properties").exists():
            z.write(ROOT / "jarvis.properties", "assets/jarvis.properties")  # starting values, see README
        assets = ROOT / app / "assets"
        if assets.exists():
            for f in assets.rglob("*"):
                if f.is_file():
                    z.write(f, "assets/" + str(f.relative_to(assets)))
    aligned = work / "aligned.apk"
    run(TOOLS / "zipalign", "-f", "-p", "4", unsigned, aligned)
    ks, pw = keystore()
    run(TOOLS / "apksigner", "sign", "--ks", ks, "--ks-pass", f"file:{pw}", "--ks-key-alias", "jarvis",
        "--out", out_apk, aligned)
    run(TOOLS / "apksigner", "verify", out_apk)
    print(f"{out_apk}  ({out_apk.stat().st_size // 1024} KiB)")


if __name__ == "__main__":
    targets = sys.argv[1:] or ["all"]
    for t in (["call", "core"] if "all" in targets else targets):
        build(t)
        if t == "call":
            # the core app carries the call app so Jarvis can send it to the Boss via Telegram
            (ROOT / "core/assets").mkdir(exist_ok=True)
            shutil.copy(OUT / "jarvis-call.apk", ROOT / "core/assets/jarvis-call.apk")
