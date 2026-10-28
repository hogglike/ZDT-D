package main
import (
 "context"
 "crypto/x509"
 "encoding/json"
 "errors"
 "io"
 "log"
 "os"
 "path/filepath"
 "strings"
 "testing"
 "time"
 "github.com/Alexey71/opera-proxy/dialer"
 clog "github.com/Alexey71/opera-proxy/log"
)
func TestZdtReportDoesNotPublishCredentials(t *testing.T){
 args:=&CLIArgs{zdtRuntimePath:filepath.Join(t.TempDir(),"state.json")}
 zdtRecord(args,"api_candidate","error",errors.New("407 http://login:secret@127.0.0.1:99/?token=private"),zdtEndpoint("http://login:secret@127.0.0.1:99/?token=private"),true)
 raw,err:=os.ReadFile(args.zdtRuntimePath);if err!=nil{t.Fatal(err)}
 for _,secret:=range []string{"login","secret","private","token"}{if strings.Contains(string(raw),secret){t.Fatalf("report exposes %s",secret)}}
 var report zdtReport;if json.Unmarshal(raw,&report)!=nil||report.Failures[0].Code!="proxy_auth"{t.Fatal(string(raw))}
}
func TestZdtSNIControlRunsOnlyAfterFailure(t *testing.T){
 count:=0;control:=func(context.Context)error{count++;return nil}
 e,c:=zdtCompare(context.Background(),func(context.Context)error{return nil},control)
 if e!=nil||c!=nil||count!=0{t.Fatal("unneeded control")}
 e,c=zdtCompare(context.Background(),func(context.Context)error{return errors.New("TLS EOF")},control)
 if e==nil||c!=nil||count!=1{t.Fatal("control comparison lost")}
}
func TestZdtAPIPoolChecksRealCandidateResult(t *testing.T){
 original:=zdtTestAPIProxyCandidate;defer func(){zdtTestAPIProxyCandidate=original}()
 zdtTestAPIProxyCandidate=func(ctx context.Context,args *CLIArgs,base dialer.ContextDialer,ca *x509.CertPool,candidate string,discover,countries bool)apiProxyCandidateResult{
  if candidate=="http://bad:pass@127.0.0.1:1"{return apiProxyCandidateResult{candidate:candidate,err:errors.New("anonymous registration failed: 403")}}
  return apiProxyCandidateResult{candidate:candidate}
 }
 args:=&CLIArgs{apiProxyParallel:1,zdtRuntimePath:filepath.Join(t.TempDir(),"state.json")}
 logger:=clog.NewCondLogger(log.New(io.Discard,"",0),clog.SILENT)
 result,err:=selectAPIProxyCandidate(context.Background(),args,nil,nil,logger,[]string{"http://bad:pass@127.0.0.1:1","http://good:pass@127.0.0.1:2"},true,false)
 if err!=nil||result.candidate!="http://good:pass@127.0.0.1:2"{t.Fatalf("no API failover: %v",err)}
 raw,_:=os.ReadFile(args.zdtRuntimePath);if !strings.Contains(string(raw),"access_denied"){t.Fatal("API rejection missing")}
 ctx,cancel:=context.WithTimeout(context.Background(),time.Millisecond);defer cancel()
 zdtTestAPIProxyCandidate=func(ctx context.Context,args *CLIArgs,base dialer.ContextDialer,ca *x509.CertPool,candidate string,discover,countries bool)apiProxyCandidateResult{<-ctx.Done();return apiProxyCandidateResult{err:ctx.Err()}}
 _,err=selectAPIProxyCandidate(ctx,args,nil,nil,logger,[]string{"one","two"},true,false)
 if err==nil{t.Fatal("selection ignored cancellation")}
}
