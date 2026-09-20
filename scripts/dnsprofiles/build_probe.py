#!/usr/bin/env python3
"""Build a self-contained read-only probe using an existing Android SDK."""
import argparse
import base64
import hashlib
from pathlib import Path
import subprocess
import tempfile
import textwrap

parser = argparse.ArgumentParser()
parser.add_argument('--android-jar', required=True, type=Path)
parser.add_argument('--d8-jar', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent
source = root / 'DnsResolverProbe.java'
with tempfile.TemporaryDirectory(prefix='zdtd-dns-probe-build-') as temporary:
    work = Path(temporary)
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                    '--release', '8', '-cp', str(args.android_jar),
                    '-d', str(work), str(source)], check=True)
    subprocess.run(['java', '-cp', str(args.d8_jar), 'com.android.tools.r8.D8',
                    '--min-api', '26', '--lib', str(args.android_jar),
                    '--output', str(work), str(work / 'DnsResolverProbe.class')], check=True)
    dex = (work / 'classes.dex').read_bytes()
    assert dex.startswith(b'dex\n'), 'D8 did not produce a DEX'
    output = (root / 'dnscheck-v2.template.sh').read_text()
    output = output.replace('@@DEX_BASE64@@', '\n'.join(textwrap.wrap(base64.b64encode(dex).decode(), 76)))
    output = output.replace('@@DEX_SHA256@@', hashlib.sha256(dex).hexdigest())
    output = output.replace('@@JAVA_SOURCE_COMMENTS@@', '\n'.join('# ' + line for line in source.read_text().splitlines()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding='utf-8')
    subprocess.run(['sh', '-n', str(args.output)], check=True)
    print(f'Built {args.output.name}: {len(dex)} DEX bytes; shell syntax OK')
