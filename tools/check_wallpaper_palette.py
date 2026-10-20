"""Check wallpaper glass colors with the project's JDK, without Android or dependencies."""
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    project = Path(__file__).resolve().parents[1]
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else "javac"
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    temporary = project.parents[1] / "tmp/banana-palette"
    temporary.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=temporary) as classes:
        subprocess.run([javac, "-d", classes,
                        str(project / "app/src/main/java/com/sogud/bbtv/WallpaperPalette.java"),
                        str(project / "tools/WallpaperPaletteTest.java")], check=True)
        subprocess.run([java, "-cp", classes, "com.sogud.bbtv.WallpaperPaletteTest"], check=True)


if __name__ == "__main__":
    main()
