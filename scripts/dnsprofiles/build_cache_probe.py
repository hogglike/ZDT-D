#!/usr/bin/env python3
"""Build the standalone Binder cache test from frozen AOSP AIDL sources."""
import argparse
import base64
import hashlib
from pathlib import Path
import subprocess
import tempfile
import textwrap
from generate_info_reader import generate

parser = argparse.ArgumentParser()
parser.add_argument('--android-jar', required=True, type=Path)
parser.add_argument('--d8-jar', required=True, type=Path)
parser.add_argument('--aidl', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--test', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent
source = root / 'DnsResolverCacheProbe.java'
aidl = root / 'aidl'
snapshots = [aidl / 'resolver-v1', aidl / 'listener-v1']
with tempfile.TemporaryDirectory(prefix='zdtd-cache-probe-build-') as temporary:
    work = Path(temporary)
    for snapshot in snapshots:
        subprocess.run([str(args.aidl), '--lang=java', '--structured', '--version=1',
                        '--hash=' + (snapshot / '.hash').read_text().strip(),
                        '--min_sdk_version=26', '--out=' + str(work)]
                       + ['-I' + str(p) for p in snapshots]
                       + [str(p) for p in sorted(snapshot.rglob('*.aidl'))], check=True)
    generate(work / 'android/net/IDnsResolver.java', work / 'android/net/ResolverInfoReader.java')
    java_sources = [source] + sorted(work.rglob('*.java'))
    if args.test:
        java_sources.append(root / 'tests' / 'DnsResolverCacheProbeTest.java')
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                    '--release', '8', '-cp', str(args.android_jar), '-d', str(work)]
                   + [str(p) for p in java_sources], check=True)
    if args.test:
        subprocess.run(['java', '-cp', str(work) + ':' + str(args.android_jar),
                        'DnsResolverCacheProbeTest'], check=True)
    classes = [p for p in sorted(work.rglob('*.class')) if not p.name.startswith('DnsResolverCacheProbeTest')]
    subprocess.run(['java', '-cp', str(args.d8_jar), 'com.android.tools.r8.D8',
                    '--min-api', '26', '--lib', str(args.android_jar), '--output', str(work)]
                   + [str(p) for p in classes], check=True)
    dex = (work / 'classes.dex').read_bytes()
    if not dex.startswith(b'dex\n'):
        raise RuntimeError('D8 did not produce a DEX')
    review_files = [source, Path(__file__), root / 'dnscheck-v3.template.sh',
                    root / 'generate_info_reader.py',
                    root / 'tests' / 'DnsResolverCacheProbeTest.java',
                    root.parent.parent / 'LICENSE']
    review_files += sorted(p for p in aidl.rglob('*') if p.is_file())
    comments = []
    for path in review_files:
        comments.append('# SOURCE: ' + str(path.relative_to(root.parent.parent)))
        comments.extend('# ' + line for line in path.read_text().splitlines())
    comments.append('# GENERATED SOURCE: android/net/ResolverInfoReader.java')
    comments.extend('# ' + line for line in (work / 'android/net/ResolverInfoReader.java').read_text().splitlines())
    output = (root / 'dnscheck-v3.template.sh').read_text()
    output = output.replace('@@DEX_BASE64@@', '\n'.join(textwrap.wrap(base64.b64encode(dex).decode(), 76)))
    output = output.replace('@@DEX_SHA256@@', hashlib.sha256(dex).hexdigest())
    output = output.replace('@@SOURCE_COMMENTS@@', '\n'.join(comments))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding='utf-8')
    subprocess.run(['sh', '-n', str(args.output)], check=True)
    print(f'Built {args.output.name}: {len(dex)} DEX bytes; shell syntax OK')
