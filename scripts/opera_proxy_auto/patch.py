#!/usr/bin/env python3
"""Small, guarded extension of pinned Opera source. Never alter TLS verification."""
from pathlib import Path
import sys, shutil
root = Path(sys.argv[1]); source = root / 'main.go'; text = source.read_text()
def replace(old, new, count=1):
 global text
 assert text.count(old) == count, f'Pinned Opera source changed: {old[:70]}'
 text = text.replace(old, new)
replace('type CLIArgs struct {', 'type CLIArgs struct {\n\tzdtProxyList string\n\tzdtStatePath string\n\tzdtPool *dialer.ZdtPool')
replace('\tflag.StringVar(&args.proxy, "proxy",', '\tflag.StringVar(&args.zdtProxyList, "zdt-proxy-list", "", "ZDT authenticated upstream pool JSON")\n\tflag.StringVar(&args.zdtStatePath, "zdt-upstream-state", "", "ZDT sanitized pool status JSON")\n\tflag.StringVar(&args.proxy, "proxy",')
replace('\tif args.proxy != "" {\n\t\tproxyURL, err := url.Parse(args.proxy)', '''\tif args.zdtProxyList != "" {
\t\tpool, err := dialer.NewZdtPoolFile(args.zdtProxyList, args.zdtStatePath, d)
\t\tif err != nil { mainLogger.Critical("ZDT proxy list: %v", err); return 7 }
\t\targs.zdtPool = pool
\t\td = pool
\t} else if args.proxy != "" {
\t\tproxyURL, err := url.Parse(args.proxy)''')
replace('\tif len(args.proxyBypass.values) == 0 {', '\tif args.zdtPool == nil && len(args.proxyBypass.values) == 0 {')
# Pin the same candidate for complete API TLS handshakes, including bootstrap resolution.
indent='\t\t\t'
old='\n'+indent+'conn, err := seclientDialer.DialContext(ctx, network, addr)'
replace(old,'\n'+indent+'if args.zdtPool != nil { return args.zdtPool.DialTLS(ctx, network, addr, seclientDialer, tlsConfig) }'+old,2)
replace('\t\treturn dialer.NewProxyDialer(\n\t\t\tdialer.WrapStringToCb(endpointAddr),','\t\tproxyDialer := dialer.NewProxyDialer(\n\t\t\tdialer.WrapStringToCb(endpointAddr),')
replace('\t\t\td)\n\t}\n\n\tif args.listProxies', '\t\t\td)\n\t\tif args.zdtPool != nil { return args.zdtPool.Wrap(proxyDialer) }\n\t\treturn proxyDialer\n\t}\n\n\tif args.listProxies')
source.write_text(text)
for name in ['zdt_pool.go','zdt_pool_test.go']:
 shutil.copyfile(Path(__file__).parent/name,root/'dialer'/name)
print('ZDT upstream pool extension applied')
