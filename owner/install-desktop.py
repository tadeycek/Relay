#!/usr/bin/env python3
"""Install the Control Panel launcher and retire this checkout's obsolete launcher."""
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
APP_ID = "com.relay.ControlPanel.desktop"


def key_value(value):
    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")


def exec_arg(value):
    value = str(value).replace("%", "%%")
    for character in ("\\", '"', "`", "$"):
        value = value.replace(character, "\\" + character)
    return key_value('"' + value + '"')


def main():
    directory = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share") / "applications"
    directory.mkdir(parents=True, exist_ok=True)
    launcher = directory / APP_ID
    content = "\n".join([
        "[Desktop Entry]", "Type=Application", "Version=1.0", "Name=Control Panel",
        "Comment=Installation logs, optional diagnostics and manual recovery",
        "Exec=" + exec_arg(ROOT / "owner/run.sh"), "Path=" + key_value(ROOT),
        "Icon=" + key_value(ROOT / "owner/assets/control-panel.png"),
        "Terminal=false", "Categories=Utility;", "StartupNotify=false",
        "StartupWMClass=Controlpanel", "",
    ])
    temporary = directory / ("." + APP_ID + ".tmp")
    temporary.write_text(content)
    temporary.chmod(0o644)
    temporary.replace(launcher)

    legacy = directory / "calculator-owner.desktop"
    if legacy.exists() and str(ROOT / "owner/run.sh") in legacy.read_text():
        backups = ROOT / ".local/desktop-backups"
        backups.mkdir(parents=True, exist_ok=True, mode=0o700)
        shutil.copy2(legacy, backups / f"{time.time_ns()}-calculator-owner.desktop")
        legacy.unlink()
        print("Removed the obsolete Calculator Owner launcher (backup saved in .local/desktop-backups).")
    updater = shutil.which("update-desktop-database")
    if updater:
        subprocess.run([updater, str(directory)], check=True)
    print(f"Installed Control Panel: {launcher}")


if __name__ == "__main__":
    main()
