#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Build a source-included standalone Android one-profile runtime prototype."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import textwrap
from generate_info_reader import generate

p = argparse.ArgumentParser()
p.add_argument('--android-jar', required=True, type=Path)
p.add_argument('--d8-jar', required=True, type=Path)
p.add_argument('--aidl', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
args = p.parse_args()
root = Path(__file__).resolve().parent
snapshots = [root / 'aidl/resolver-v1', root / 'aidl/listener-v1']
with tempfile.TemporaryDirectory(prefix='zdtd-one-profile-') as temporary:
    work = Path(temporary)
    for snapshot in snapshots:
        subprocess.run([str(args.aidl), '--lang=java', '--structured', '--version=1',
                        '--hash=' + (snapshot / '.hash').read_text().strip(), '--min_sdk_version=26',
                        '--out=' + str(work)] + ['-I' + str(s) for s in snapshots]
                       + [str(f) for f in sorted(snapshot.rglob('*.aidl'))], check=True)
    generate(work / 'android/net/IDnsResolver.java', work / 'android/net/ResolverInfoReader.java')
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '--release', '8',
                    '-cp', str(args.android_jar), '-d', str(work), str(root / 'DnsProfileControl.java')]
                   + [str(f) for f in sorted(work.rglob('*.java'))], check=True)
    subprocess.run(['java', '-cp', str(args.d8_jar), 'com.android.tools.r8.D8', '--min-api', '26',
                    '--lib', str(args.android_jar), '--output', str(work)]
                   + [str(f) for f in sorted(work.rglob('*.class'))], check=True)
    dex = (work / 'classes.dex').read_bytes()
    if not dex.startswith(b'dex\n'): raise ValueError('Invalid DEX')
    config = (root / 'one-profile.json').read_text()
    json.loads(config)
    source_files = [root / name for name in ('DnsProfileControl.java', 'dns-one.template.sh',
                    'one-profile.json', 'generate_info_reader.py', 'build_one_profile.py')]
    source_files += [root.parent.parent / 'LICENSE']
    source_files += [root / 'tests/test_one_profile.py', root.parent.parent / 'docs/DNS_PROFILES_ONE_PROFILE.md']
    source_files += sorted(f for f in (root / 'aidl').rglob('*') if f.is_file())
    comments = []
    for source in source_files:
        comments.append('# SOURCE: ' + str(source.relative_to(root.parent.parent)))
        comments.extend('# ' + line for line in source.read_text().splitlines())
    comments.append('# GENERATED SOURCE: android/net/ResolverInfoReader.java')
    comments.extend('# ' + line for line in (work / 'android/net/ResolverInfoReader.java').read_text().splitlines())
    script = (root / 'dns-one.template.sh').read_text()
    script = script.replace('@@DEX_BASE64@@', '\n'.join(textwrap.wrap(base64.b64encode(dex).decode(), 76)))
    script = script.replace('@@DEX_SHA256@@', hashlib.sha256(dex).hexdigest())
    script = script.replace('@@CONFIG@@', config.rstrip())
    script = script.replace('@@SOURCE_COMMENTS@@', '\n'.join(comments))
    args.output.write_text(script)
    subprocess.run(['sh', '-n', str(args.output)], check=True)
    print(f'Built {args.output.name}: {len(dex)} DEX bytes, {args.output.stat().st_size} total bytes')
