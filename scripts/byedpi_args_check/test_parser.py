#!/usr/bin/env python3
import os, socket, subprocess, sys, tempfile
from pathlib import Path
binary=str(Path(sys.argv[1]).resolve())
with tempfile.TemporaryDirectory() as temp, socket.socket() as sock:
    sock.bind(('127.0.0.1',0));sock.listen()
    pid=Path(temp)/'must-not-exist.pid'
    cases=[(['-i','127.0.0.1','-p',str(sock.getsockname()[1]),'-s','1'],True),
           (['--zdt-invalid'],False),(['-p','banana'],False),(['-s'],False),(['-D','-w',str(pid)],True)]
    for args,valid in cases:
        result=subprocess.run([binary,*args],env=dict(os.environ,ZDT_CHECK_ARGS='1'),capture_output=True,timeout=3)
        assert (result.returncode==0 and b'ZDT_ARGS_OK' in result.stdout)==valid,(args,result.returncode)
    assert not pid.exists(), 'parser check created a pid file'
print('ByeDPI parser: 5 checks passed without listener/pid-file side effects')
