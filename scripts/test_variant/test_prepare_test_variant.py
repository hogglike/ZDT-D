"""Host-only regression tests: never touch Android networking or the real checkout."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class TestVariantPreparation(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="zdtd-variant-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        # Stable fixture, independent of the checkout's next mod version.
        self.put("module.prop", "id=ZDT-D\nname=ZDT-D\nversion=4.2.0-mod22\nversionCode=42022\nupstreamVersion=4.2.0\nmodVersion=22\n")
        self.put("application/app/src/main/res/values/strings.xml",
                 '<resources><string name="app_name">ZDT-D</string></resources>')
        self.put("application/app/build.gradle",
                 "applicationId 'com.android.zdtd.service'\n")
        self.put("application/PluginContract.kt", '\n'.join([
            "package com.android.zdtd.service.tgwsplugin",
            'const val HOST = "com.android.zdtd.service"',
            'const val PLUGIN = "com.android.zdtd.service.plugin.com"',
            'const val ACTION = "com.android.zdtd.service.plugin.com.action.BIND_TGWS"',
        ]))
        self.put("application/app/src/main/aidl/Plugin.aidl",
                 "package com.android.zdtd.service.plugin.com.ipc;\n")
        self.put("plugin/app/build.gradle",
                 "applicationId 'com.android.zdtd.service.plugin.com'\n")
        self.put("rust/paths.rs", '\n'.join([
            '"/data/adb/modules/ZDT-D/bin/zdtd"',
            '"/data/adb/modules_update/ZDT-D/working_folder"',
            '"/data/adb/ZDT-D/zygisk"',
        ]))
        self.put("module_template/customize.sh", "module=ZDT-D\n")
        self.put("zygisk/config.cpp", '"com.android.zdtd.service"')
        self.script = self.root / "scripts/test_variant/prepare_test_variant.py"
        self.script.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / "scripts/test_variant/prepare_test_variant.py", self.script)
        self.environment = self.root / "github-env"

    def put(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def prepare(self, success=True):
        result = subprocess.run(
            [sys.executable, str(self.script)], text=True, capture_output=True,
            env=dict(os.environ, GITHUB_ENV=str(self.environment)),
        )
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        return result

    def test_version_and_identifiers(self):
        self.prepare()
        properties = (self.root / "module.prop").read_text()
        self.assertIn("id=ZDT-D-Test\n", properties)
        self.assertIn("version=4.2.0-mod22\n", properties)
        self.assertIn("versionCode=42022\n", properties)
        self.assertIn("com.hogglike.zdtd.test", (self.root / "application/app/build.gradle").read_text())
        self.assertIn("ZDTD_DIST_STEM=ZDT-D_4.2.0_mod22", self.environment.read_text())
        self.assertIn("version=4.2.0-mod22", (self.root / "module_template/TEST_VARIANT.txt").read_text())

    def test_official_plugin_contract_is_not_renamed(self):
        self.prepare()
        contract = (self.root / "application/PluginContract.kt").read_text()
        self.assertIn("package com.hogglike.zdtd.test.tgwsplugin", contract)
        self.assertIn('HOST = "com.hogglike.zdtd.test"', contract)
        self.assertIn('PLUGIN = "com.android.zdtd.service.plugin.com"', contract)
        self.assertIn("com.android.zdtd.service.plugin.com.action.BIND_TGWS", contract)
        self.assertIn("package com.android.zdtd.service.plugin.com.ipc;",
                      (self.root / "application/app/src/main/aidl/Plugin.aidl").read_text())
        self.assertIn("com.android.zdtd.service.plugin.com",
                      (self.root / "plugin/app/build.gradle").read_text())

    def test_preparation_is_idempotent(self):
        self.prepare()
        snapshot = {path.relative_to(self.root): path.read_bytes()
                    for path in self.root.rglob("*") if path.is_file() and path != self.environment}
        self.prepare()
        for name, data in snapshot.items():
            self.assertEqual((self.root / name).read_bytes(), data, str(name))
        self.assertNotIn("ZDT-D-Test-Test", (self.root / "rust/paths.rs").read_text())

    def test_bad_version_is_rejected_before_rewrites(self):
        self.put("module.prop", "version=4.2.0\nupstreamVersion=4.2.0\nmodVersion=22\n")
        self.prepare(success=False)
        self.assertIn("com.android.zdtd.service", (self.root / "application/app/build.gradle").read_text())

    def test_dns_cli_matches_new_tun2socks(self):
        source = (ROOT / "rust/zdtd/src/programs/dnsprofiles.rs").read_text()
        for argument in ("--device", "--proxy", "--loglevel"):
            self.assertIn(f'.arg("{argument}")', source)
        for argument in ("-device", "-proxy", "-loglevel"):
            self.assertNotIn(f'.arg("{argument}")', source)
        self.assertIn("suspend_for_tethering", source)


if __name__ == "__main__":
    unittest.main()
