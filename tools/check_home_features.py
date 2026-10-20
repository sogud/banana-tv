#!/usr/bin/env python3
"""Run focused, offline JVM checks. Requires JDK 17 and Android SDK Platform 34."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ["JAVA_HOME"])
sdk = Path(os.environ["ANDROID_HOME"])
android_jar = sdk / "platforms/android-34/android.jar"
sources = root / "app/src/main/java/com/sogud/bbtv"
with tempfile.TemporaryDirectory(prefix="banana-home-checks-") as folder:
    files = [sources / name for name in ["AmbientColors.java", "WeatherModel.java", "RemoteKeyPolicy.java"]]
    files.append(root / "tools/HomeFeaturesTest.java")
    subprocess.run([str(java_home / "bin/javac"), "-cp", str(android_jar), "-d", folder, *map(str, files)], check=True)
    subprocess.run([str(java_home / "bin/java"), "-cp", folder, "com.sogud.bbtv.HomeFeaturesTest"], check=True)
