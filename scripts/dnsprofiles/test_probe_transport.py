"""Regression: commands must receive pipes even when the report is a file."""
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest


class ProbeTransportTest(unittest.TestCase):
    def test_file_report_does_not_reach_child_fds_and_preserves_failure(self):
        text = Path(__file__).with_name('dnscheck-v2.template.sh').read_text()
        start = text.index('check() {')
        end = text.index('\n}\n', start) + len('\n}')
        function = text[start:end].replace('/system/bin/timeout', shutil.which('timeout'))
        probe = (
            'import os,stat,sys; '
            'assert stat.S_ISFIFO(os.fstat(1).st_mode); '
            'assert stat.S_ISFIFO(os.fstat(2).st_mode); '
            'assert stat.S_ISCHR(os.fstat(0).st_mode); '
            'print("pipe_transport_ok"); '
            'print("stderr_captured",file=sys.stderr); '
            'sys.exit(7)'
        )
        command = shlex.join(['check', 'transport', sys.executable, '-c', probe])
        with tempfile.TemporaryFile(mode='w+') as report:
            result = subprocess.run(['sh', '-c', 'exec 3>/dev/null\n' + function + '\n' + command],
                                    stdout=report, stderr=report, timeout=20)
            report.seek(0)
            content = report.read()
        self.assertEqual(result.returncode, 7, content)
        self.assertIn('pipe_transport_ok', content)
        self.assertIn('stderr_captured', content)
        self.assertIn('exit_code=7', content)


if __name__ == '__main__':
    unittest.main()
