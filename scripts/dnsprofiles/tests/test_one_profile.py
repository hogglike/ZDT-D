#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run the real launcher against fake OS commands. Never changes host networking.

These tests verify sequencing, ownership and rollback, not Android Binder/netd.
"""
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time

MOCK = r'''#!/usr/bin/env python3
import os,sys,time,signal
from pathlib import Path
root=Path(os.environ['DNS_TEST_ROOT']); state=root/'state'
name=Path(sys.argv[0]).name; a=sys.argv[1:]; scenario=os.environ['DNS_TEST_SCENARIO']
def has(n): return (state/n).exists()
def put(n): (state/n).touch()
def rm(n): (state/n).unlink(missing_ok=True)
def bad(message='mock failure'):
 print(message); sys.exit(1)
with (root/'calls').open('a') as f: f.write(name+' '+' '.join(a)+'\n')
if name=='sleep': time.sleep(0.04); sys.exit(0)
if name=='settings': print('off')
elif name=='cmd': print('package:com.google.android.youtube uid:10453\npackage:com.android.chrome uid:10253\npackage:com.google.android.apps.bard uid:10555')
elif name.endswith('tables-save'):
 if scenario=='firewall_read_fail': bad('Permission denied reading firewall')
 if scenario=='redirect': print('*nat\n-A OUTPUT -j DNAT --to-destination 127.0.0.1:863\nCOMMIT')
 if scenario=='nested_redirect': print('*nat\n-A OUTPUT -j FIRST\n-A SECOND -j REDIRECT --to-ports 863\n-A FIRST -g SECOND\n-A SECOND -j FIRST\nCOMMIT')
 if scenario=='postrouting_queue': print('*mangle\n-A POSTROUTING -j NFQUEUE --queue-num 200\nCOMMIT')
 if scenario=='ipv6_redirect' and name=='ip6tables-save': print('*nat\n-A OUTPUT -j REDIRECT --to-ports 863\nCOMMIT')
 if scenario=='malformed_firewall': print('-A OUTPUT -j REDIRECT --to-ports 863')
 if scenario=='unrelated_rules': print('*nat\n-A PREROUTING -j TETHER\n-A TETHER -j DNAT --to-destination 192.0.2.2\n-A UNUSED -j REDIRECT --to-ports 863\n-A POSTROUTING -j MASQUERADE\n-A SHARED -j DNAT --to-destination 192.0.2.3\nCOMMIT\n*filter\n-A OUTPUT -j SHARED\n-A SHARED -j RETURN\nCOMMIT')
elif name=='ip':
 if a[:2]==['link','show']: sys.exit(0 if has('tun') else 1)
 elif a[:2]==['rule','show']:
  for uid in ('10253','10555'):
   if has('uid.'+uid): print('12000: from all uidrange '+uid+'-'+uid+' lookup zdt_dns_one')
elif name=='ip6tables':
 a=a[2:] if a[:1]==['-w'] else a
 op=a[0]
 if op=='-S': sys.exit(0 if has('v6chain') else 1)
 elif op=='-N':
  if has('v6chain'): bad()
  put('v6chain')
 elif op=='-A':
  if scenario=='v6_fail': bad()
  put('v6rule.'+a[a.index('--uid-owner')+1])
 elif op=='-I': put('v6hook')
 elif op=='-C':
  if scenario=='v6_lost' and has('uid.10555') and a[1]!='OUTPUT': bad()
  sys.exit(0 if has('v6hook' if a[1]=='OUTPUT' else 'v6rule.'+a[a.index('--uid-owner')+1]) else 1)
 elif op=='-D': rm('v6hook')
 elif op=='-F':
  for uid in ('10253','10555'): rm('v6rule.'+uid)
 elif op=='-X': rm('v6chain')
elif name=='ndc':
 if a[:2]==['network','create']:
  if scenario=='netd_fail': print('400 0 create failed'); sys.exit(0)
  put('network')
 elif a[:2]==['network','destroy']:
  for n in ('network','cache','uid.10253','uid.10555','configured'): rm(n)
 elif a[:3]==['network','users','add']:
  if scenario=='bind_fail': print('400 0 add failed'); sys.exit(0)
  uid=a[4].split('-')[0]
  if scenario=='second_bind_fail' and uid=='10555': print('400 0 second add failed'); sys.exit(0)
  assert uid in ('10253','10555')
  assert has('network') and has('v6rule.10253') and has('v6rule.10555') and has('v6hook') and has('configured')
  put('uid.'+uid)
 elif a[:3]==['network','users','remove']: rm('uid.'+a[4].split('-')[0])
 elif a[:3]==['network','route','add'] and scenario=='route_fail': print('400 0 route failed'); sys.exit(0)
 print('200 0 operation succeeded')
elif name=='sing-box':
 if a==['version']: print('sing-box version 1.13.14')
 elif a[:1]==['run']:
  put('tun')
  def stop(*args): rm('tun'); sys.exit(0)
  signal.signal(signal.SIGTERM,stop)
  while True: time.sleep(.05)
elif name=='app_process':
 a=a[2:]; action=a[0]
 if action=='create':
  if has('cache'): bad('EEXIST')
  put('cache')
 elif action=='destroy': rm('cache'); rm('configured')
 elif action=='configure':
  assert has('cache') and has('network')
  if scenario=='config_fail': bad()
  put('configured')
 elif action=='verify-empty':
  assert not has('configured')
 elif action=='query-network':
  assert has('configured') and has('tun')
  if scenario=='doh_fail': bad()
 elif action=='query-uid':
  expected=a[2]=='profile'
  actual=has('uid.'+a[1])
  assert expected==actual
  if expected and scenario=='readback_fail': bad()
 print('MOCK_OK='+action)
else: bad('Unexpected fake command '+name)
'''

def run_case(script, scenario, kill_main=False, request_stop=False):
    with tempfile.TemporaryDirectory(prefix='dns-one-test-') as td:
        root = Path(td)
        bindir = root / 'bin'; bindir.mkdir()
        state = root / 'state'; state.mkdir()
        mock = bindir / 'mock'; mock.write_text(MOCK); mock.chmod(0o755)
        for name in ('sleep','settings','cmd','iptables-save','ip6tables-save','ip','ip6tables','ndc','sing-box','app_process'):
            (bindir/name).symlink_to(mock)
        if scenario == 'occupied': (state/'cache').touch()
        work = root / 'work'
        patched = script.replace('PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin', f'PATH={bindir}:/usr/bin:/bin')
        patched = patched.replace('/data/local/tmp/zdtd-dns-one', str(work))
        patched = patched.replace('/sdcard/Download/dns-one-report.txt', str(root/'report'))
        patched = patched.replace('/data/adb/modules/ZDT-D/bin/sing-box', str(bindir/'sing-box'))
        patched = patched.replace('/system/bin/sh', '/bin/sh').replace('/system/bin/true','/usr/bin/true')
        patched = patched.replace('/data/adb/modules/ZDT-D/working_folder/dnscrypt/active.json', str(root/'active.json'))
        (root/'active.json').write_text('{"enabled":false}')
        if not kill_main and not request_stop and scenario!='v6_lost':
            patched = patched.replace('end=$(awk \'{printf "%.0f", $1+300}\' /proc/uptime)', 'end=0')
        runner=root/'runner.sh'; runner.write_text(patched)
        env=dict(os.environ,DNS_TEST_ROOT=str(root),DNS_TEST_SCENARIO=scenario)
        proc=subprocess.Popen(['/bin/sh',str(runner)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,env=env)
        if kill_main or request_stop:
            ready=False
            while True:
                line=proc.stdout.readline()
                if not line: break
                if line.startswith('READY:'):
                    ready=True
                    if kill_main: proc.kill()
                    else:
                        subprocess.run(['/bin/sh',str(runner),'stop'],env=env,check=True,stdout=subprocess.PIPE,timeout=5)
                    break
            assert ready, (scenario, (root/'report').read_text())
        try:
            output=proc.communicate(timeout=25)[0]
        except subprocess.TimeoutExpired:
            proc.kill()
            raise AssertionError(f'{scenario} timed out; report='+(root/'report').read_text())
        # The detached watcher may close the inherited console pipe just after cleanup.
        for _ in range(100):
            if not work.exists(): break
            time.sleep(.03)
        expected={'cache'} if scenario=='occupied' else set()
        remaining={p.name for p in state.iterdir()}
        assert remaining==expected, (scenario,remaining,(root/'report').read_text())
        assert not work.exists(), (scenario,'private journal remains',(root/'report').read_text())
        if not kill_main:
            assert (proc.returncode==0)==(scenario in ('ok','unrelated_rules')), (scenario,proc.returncode,output)
        calls=(root/'calls').read_text()
        if scenario in ('occupied','redirect','nested_redirect','postrouting_queue','ipv6_redirect','firewall_read_fail','malformed_firewall'):
            assert 'ndc network create' not in calls
            assert 'app_process /system/bin DnsProfileControl destroy' not in calls
        report=(root/'report').read_text()
        if scenario in ('redirect','nested_redirect','postrouting_queue','ipv6_redirect'):
            assert 'FIREWALL_CONFLICT:' in report and 'table=' in report, report
        if scenario=='firewall_read_fail':
            assert 'FIREWALL_READ_ERROR:' in report and 'Permission denied' in report, report
        if scenario=='malformed_firewall':
            assert 'FIREWALL_PARSE_ERROR:' in report, report
        print('PASS',scenario,'watchdog recovery' if kill_main else 'manual stop' if request_stop else '')

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('script',type=Path);args=parser.parse_args()
    script=args.script.read_text()
    for case in ('ok','occupied','redirect','nested_redirect','postrouting_queue','ipv6_redirect','firewall_read_fail','malformed_firewall','unrelated_rules','netd_fail','route_fail','config_fail','doh_fail','v6_fail','bind_fail','second_bind_fail','readback_fail','v6_lost'):
        run_case(script,case)
    run_case(script,'ok',kill_main=True)
    run_case(script,'ok',request_stop=True)
    print('20 launcher scenarios passed; Android execution remains required.')
