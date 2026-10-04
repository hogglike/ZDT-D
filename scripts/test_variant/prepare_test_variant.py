#!/usr/bin/env python3
"""Convert a checkout into the isolated ZDT-D Test build variant.

This is intentionally build-time only. The normal source branch stays readable, while every
GitHub Actions job that compiles or packages artifacts sees the isolated identifiers.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SELF = Path(__file__).resolve()

module_properties = dict(
    line.split("=", 1) for line in (ROOT / "module.prop").read_text(encoding="utf-8").splitlines()
    if "=" in line and not line.lstrip().startswith("#")
)
UPSTREAM_VERSION = module_properties["upstreamVersion"].strip()
MOD_VERSION = module_properties["modVersion"].strip()
VERSION_NAME = f"{UPSTREAM_VERSION}-mod{MOD_VERSION}"
if not re.fullmatch(r"\d+\.\d+\.\d+", UPSTREAM_VERSION) or not MOD_VERSION.isdigit():
    raise SystemExit("Invalid upstreamVersion/modVersion in module.prop")
if module_properties.get("version", "").strip() != VERSION_NAME:
    raise SystemExit("module.prop version does not match upstreamVersion/modVersion")
DIST_STEM = f"ZDT-D_{UPSTREAM_VERSION}_mod{MOD_VERSION}"

# Keep the official TGWS plugin IPC identity, permissions and bind action.
HOST_PACKAGE = re.compile(r"com\.android\.zdtd\.service(?!\.plugin\.com\b)")

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
        if old.startswith("/data/"):
            # Make preparation safe to run twice in the same checkout.
            text = re.sub(re.escape(old) + r"(?!-Test\b)", lambda _: new, text)
        else:
            text = text.replace(old, new)
    text = HOST_PACKAGE.sub("com.hogglike.zdtd.test", text)

    if path == ROOT / "module.prop":
        text = text.replace("id=ZDT-D\n", "id=ZDT-D-Test\n")
        text = text.replace("name=ZDT-D\n", "name=ZDT-D Test (Per-App DNS)\n")
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
    f"upstream_version={UPSTREAM_VERSION}\n"
    f"mod_version={MOD_VERSION}\n"
    f"version={VERSION_NAME}\n"
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
        f.write(f"ZDTD_DIST_STEM={DIST_STEM}\n")

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
        if HOST_PACKAGE.search(text_value):
            raise SystemExit(f"production Android package remains in {path.relative_to(ROOT)}")

module_prop = (ROOT / "module.prop").read_text(encoding="utf-8")
required = [
    "id=ZDT-D-Test",
    "name=ZDT-D Test (Per-App DNS)",
    f"version={VERSION_NAME}",
]
for needle in required:
    if needle not in module_prop:
        raise SystemExit(f"test module.prop invariant missing: {needle}")

print(f"[ZDT-D Test] isolated test variant prepared; changed {len(changed)} files")
print("[ZDT-D Test] module id: ZDT-D-Test")
print("[ZDT-D Test] Android application id: com.hogglike.zdtd.test")
print("[ZDT-D Test] module root: /data/adb/modules/ZDT-D-Test")
