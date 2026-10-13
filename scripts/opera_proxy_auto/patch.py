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
# Bounded diagnostics and sanitized stage reporting; normal TLS verification stays intact.
replace('type CLIArgs struct {', 'type CLIArgs struct {\n\tzdtRuntimePath string\n\tzdtDiagnosePath string')
replace('\tflag.StringVar(&args.zdtProxyList,', '\tflag.StringVar(&args.zdtRuntimePath, "zdt-diagnostic-state", "", "ZDT sanitized stage status")\n\tflag.StringVar(&args.zdtDiagnosePath, "zdt-diagnose", "", "ZDT one-shot diagnosis JSON; does not listen")\n\tflag.StringVar(&args.zdtProxyList,')
replace('\tif args.fetchFreeProxyOut != "" {\n\t\tcount, err :=', '\tif args.zdtDiagnosePath != "" { return zdtDiagnose(args) }\n\tif args.fetchFreeProxyOut != "" {\n\t\tcount, err :=')
replace('\ttry := retryPolicy(rootCtx, args.initRetries, args.initRetryInterval, mainLogger)', '''\tretry := retryPolicy(rootCtx, args.initRetries, args.initRetryInterval, mainLogger)
\ttry := func(name string, f func() error) error {
\t\treturn retry(name, func() error {
\t\t\tzdtRecord(args,name,"connecting",nil,"",false)
\t\t\terr := f(); state := "ready"; if err != nil { state = "error" }
\t\t\tzdtRecord(args,name,state,err,"",false); return err
\t\t})
\t}''')
replace('\t\tresult, err := selectAPIProxyCandidate(rootCtx, args, d, caPool, mainLogger, candidates, needDiscover, args.listCountries)', '\t\tzdtRecord(args,"api","connecting",nil,"",false)\n\t\tresult, err := selectAPIProxyCandidate(rootCtx, args, d, caPool, mainLogger, candidates, needDiscover, args.listCountries)\n\t\tstate := "ready"; if err != nil { state = "error" }; zdtRecord(args,"api",state,err,zdtEndpoint(result.candidate),false)')
replace('\t\tif args.zdtPool != nil { return args.zdtPool.Wrap(proxyDialer) }\n\t\treturn proxyDialer', '\t\tvar result dialer.ContextDialer = proxyDialer\n\t\tif args.zdtPool != nil { result = args.zdtPool.Wrap(proxyDialer) }\n\t\treturn zdtObservedDialer{next:result,args:args}')
replace('\tmainLogger.Info("Starting proxy server...")', '\tzdtRecord(args,"local_socks","connecting",nil,"",false)\n\tmainLogger.Info("Starting proxy server...")')
replace('\t\tmainLogger.Critical("Server terminated with a fatal error: %v", err)', '\t\tzdtRecord(args,"local_socks","error",err,"",false)\n\t\tmainLogger.Critical("Server terminated with a fatal error: %v", err)')
# Do not log full authenticated candidate URLs from the upstream's selection code.
replace('job.idx+1, len(candidates), job.candidate)', 'job.idx+1, len(candidates), zdtEndpoint(job.candidate))')
replace('"Using API proxy candidate: %s", result.candidate)', '"Using API proxy candidate: %s", zdtEndpoint(result.candidate))')
replace('"API proxy candidate %s failed: %v", result.candidate, result.err)', '"API proxy candidate %s failed (details in local ZDT diagnostics)", zdtEndpoint(result.candidate))')
replace('\t\tresult := <-results', '''\t\tvar result apiProxyCandidateResult
\t\tselect { case result = <-results: case <-ctx.Done(): cancel(); wg.Wait(); return apiProxyCandidateResult{},ctx.Err() }
\t\tif result.err != nil { zdtRecord(args,"api_candidate","error",result.err,zdtEndpoint(result.candidate),true) }''')
replace('result := testAPIProxyCandidate(ctx, args, baseDialer, caPool, job.candidate, needDiscover, needCountries)', 'result := zdtTestAPIProxyCandidate(ctx, args, baseDialer, caPool, job.candidate, needDiscover, needCountries)')
replace('func selectAPIProxyCandidate(', 'var zdtTestAPIProxyCandidate = testAPIProxyCandidate\n\nfunc selectAPIProxyCandidate(')
replace('\t\t\tmainLogger.Critical("All API proxy candidates failed. Last error: %v", err)', '\t\t\tcode,message:=zdtReason(err); mainLogger.Critical("All API proxy candidates failed: %s · %s",code,message)')
# x/net accepts socks5; socks5h has identical remote-resolution semantics here.
replace('\t\treturn raw, nil\n\t}', '\t\tu,err:=url.Parse(raw);if err!=nil{return "",errors.New("invalid API proxy URL")}\n\t\tif u.Scheme == "socks5h" { u.Scheme="socks5" };return u.String(),nil\n\t}')
source.write_text(text)
for name in ['zdt_pool.go','zdt_pool_test.go']:
 shutil.copyfile(Path(__file__).parent/name,root/'dialer'/name)
for name in ['zdt_diagnostics.go','zdt_diagnostics_test.go']:
 shutil.copyfile(Path(__file__).parent/name,root/name)
print('ZDT proxy pool and diagnostic extension applied')
