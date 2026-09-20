#!/usr/bin/env python3
"""Convert a checkout into the isolated ZDT-D Test build variant.

This is intentionally build-time only. The normal source branch stays readable, while every
GitHub Actions job that compiles or packages artifacts sees the isolated identifiers.
"""
from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SELF = Path(__file__).resolve()

TEXT_ROOTS = [
    ROOT / "application",
    ROOT / "rust",
    ROOT / "module_template",
    ROOT / "zygisk",
    ROOT / "scripts",
]
SINGLE_FILES = [ROOT / "build.sh", ROOT / "module.prop"]

REPLACEMENTS = [
    ("/data/adb/modules_update/ZDT-D", "/data/adb/modules_update/ZDT-D-Test"),
    ("/data/adb/modules/ZDT-D", "/data/adb/modules/ZDT-D-Test"),
    ("/data/adb/ZDT-D", "/data/adb/ZDT-D-Test"),
    ("com.android.zdtd.service", "com.hogglike.zdtd.test"),
    ('"ZDT-D"', '"ZDT-D-Test"'),
    ('/ZDT-D/zygisk/', '/ZDT-D-Test/zygisk/'),
]

SKIP_PARTS = {
    ".git", ".gradle", "build", "out", "target", "node_modules", "__pycache__"
}


def iter_text_files():
    for base in TEXT_ROOTS:
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if path == SELF or not path.is_file():
                continue
            if any(part in SKIP_PARTS for part in path.parts):
                continue
            yield path
    for path in SINGLE_FILES:
        if path.exists() and path != SELF:
            yield path


def rewrite_text(path: Path) -> bool:
    try:
        raw = path.read_bytes()
        text = raw.decode("utf-8")
    except (UnicodeDecodeError, OSError):
        return False

    original = text
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)

    if path == ROOT / "module.prop":
        text = text.replace("id=ZDT-D\n", "id=ZDT-D-Test\n")
        text = text.replace("name=ZDT-D\n", "name=ZDT-D Test (Per-App DNS)\n")
        if "version=4.0.0\n" in text:
            text = text.replace("version=4.0.0\n", "version=4.0.0-test\n")
        if "description=DPI bypass tool.\n" in text:
            text = text.replace(
                "description=DPI bypass tool.\n",
                "description=TEST BUILD - isolated per-app DNS experiment. Do not run together with normal ZDT-D.\n",
            )

    if path == ROOT / "application/app/src/main/res/values/strings.xml":
        text = text.replace(
            '<string name="app_name">ZDT-D</string>',
            '<string name="app_name">ZDT-D Test</string>',
        )

    if text != original:
        path.write_text(text, encoding="utf-8")
        return True
    return False


changed = []
seen = set()
for path in iter_text_files():
    rp = path.resolve()
    if rp in seen:
        continue
    seen.add(rp)
    if rewrite_text(path):
        changed.append(path.relative_to(ROOT).as_posix())

marker = ROOT / "module_template/TEST_VARIANT.txt"
marker.write_text(
    "ZDT-D Test isolated build\n"
    "module_id=ZDT-D-Test\n"
    "android_application_id=com.hogglike.zdtd.test\n"
    "module_root=/data/adb/modules/ZDT-D-Test\n"
    "WARNING=Do not enable this module at the same time as normal ZDT-D.\n",
    encoding="utf-8",
)
changed.append(marker.relative_to(ROOT).as_posix())

github_env = os.environ.get("GITHUB_ENV")
if github_env:
    with open(github_env, "a", encoding="utf-8") as f:
        f.write("AMNEZIAWG_UAPI_RUN_DIR=/data/adb/modules/ZDT-D-Test/working_folder/amneziawg/run\n")
        f.write("AMNEZIAWG_UAPI_SOCKET_DIR=/data/adb/modules/ZDT-D-Test/working_folder/amneziawg/run/amneziawg\n")
        f.write("ZDTD_TEST_VARIANT=1\n")

checks = [ROOT / "application", ROOT / "rust", ROOT / "module_template", ROOT / "zygisk"]
for base in checks:
    for path in base.rglob("*"):
        if not path.is_file() or any(part in SKIP_PARTS for part in path.parts):
            continue
        try:
            text_value = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if "/data/adb/modules/ZDT-D/" in text_value or "/data/adb/modules_update/ZDT-D/" in text_value:
            raise SystemExit(f"production module path remains in {path.relative_to(ROOT)}")
        if "com.android.zdtd.service" in text_value:
            raise SystemExit(f"production Android package remains in {path.relative_to(ROOT)}")

module_prop = (ROOT / "module.prop").read_text(encoding="utf-8")
required = [
    "id=ZDT-D-Test",
    "name=ZDT-D Test (Per-App DNS)",
    "version=4.0.0-test",
]
for needle in required:
    if needle not in module_prop:
        raise SystemExit(f"test module.prop invariant missing: {needle}")

print(f"[ZDT-D Test] isolated test variant prepared; changed {len(changed)} files")
print("[ZDT-D Test] module id: ZDT-D-Test")
print("[ZDT-D Test] Android application id: com.hogglike.zdtd.test")
print("[ZDT-D Test] module root: /data/adb/modules/ZDT-D-Test")
