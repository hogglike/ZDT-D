#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Adapt the SDK-generated AIDL proxy to return native variable-length arrays.

Only output allocation changes. Transaction ID, input encoding, output order,
exception handling and parcel cleanup come from the AIDL compiler. Fail closed
if the compiler's expected method shape changes.
"""
from pathlib import Path


def generate(proxy: Path, destination: Path) -> None:
    source = proxy.read_text()
    marker = '@Override public void getResolverInfo('
    start = source.rindex(marker)
    end = source.index('@Override public void startPrefix64Discovery(', start)
    method = source[start:end].strip()
    body = method[method.index('{'):]
    fields = [('String', name) for name in ('servers', 'domains', 'tlsServers')]
    fields += [('int', name) for name in ('params', 'stats', 'wait_for_pending_req_timeout_count')]
    for kind, name in fields:
        old = f'_reply.read{"String" if kind == "String" else "Int"}Array({name});'
        new = f'result.{name} = _reply.create{"String" if kind == "String" else "Int"}Array();'
        if body.count(old) != 1:
            raise ValueError(f'Unexpected generated proxy: {old}')
        body = body.replace(old, new)
    tail = 'result.wait_for_pending_req_timeout_count = _reply.createIntArray();'
    body = body.replace(tail, tail + '\n          return result;')
    body = body.replace('mRemote.transact(', 'remote.transact(')
    body = body.replace('Stub.TRANSACTION_', 'IDnsResolver.Stub.TRANSACTION_')
    body = body.replace('writeInterfaceToken(DESCRIPTOR)', 'writeInterfaceToken(IDnsResolver.DESCRIPTOR)')
    declarations = '\n'.join(f'        {kind}[] {name} = new {kind}[0];' for kind, name in fields)
    body = body.replace('{', '{\n        Info result = new Info();\n' + declarations, 1)
    fields_java = '\n'.join(f'        public {kind}[] {name};' for kind, name in fields)
    destination.write_text('''// Generated from the SDK AIDL compiler output; see generate_info_reader.py.
// AOSP interface: Apache-2.0. Adaptation: GPL-3.0-only.
package android.net;
public final class ResolverInfoReader {
    public static final class Info {
''' + fields_java + '''
    }
    public static Info read(android.os.IBinder remote, int netId) throws android.os.RemoteException
''' + body + '\n}\n')
