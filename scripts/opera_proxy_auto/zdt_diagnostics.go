package main

// Local, explicitly requested diagnostics. No listener, route changes or telemetry.
import (
 "context"
 "crypto/tls"
 "encoding/json"
 "errors"
 "io"
 "log"
 "net"
 "net/url"
 "os"
 "strings"
 "sync"
 "time"

 "github.com/Alexey71/opera-proxy/dialer"
 clog "github.com/Alexey71/opera-proxy/log"
 xproxy "golang.org/x/net/proxy"
)

var zdtStatusMu sync.Mutex
type zdtStep struct { Stage string `json:"stage"`; State string `json:"state"`; Code string `json:"code"`; Message string `json:"message"`; Endpoint string `json:"endpoint,omitempty"` }
type zdtReport struct { PID int `json:"pid"`; Updated int64 `json:"updated_at"`; State string `json:"state"`; Steps []zdtStep `json:"steps"`; Failures []zdtStep `json:"failures,omitempty"` }

func zdtEndpoint(raw string) string {
 u,err:=url.Parse(raw);if err!=nil||u.Hostname()=="" {return "прямое подключение"}
 return u.Scheme+"://"+u.Host
}
// Never publish arbitrary errors: they can include proxy credentials and API tokens.
func zdtReason(err error)(string,string) {
 if err==nil{return "ok","Проверка пройдена"}
 s:=strings.ToLower(err.Error())
 switch {
 case strings.Contains(s,"407")||strings.Contains(s,"authentication failed")||strings.Contains(s,"auth failed")||strings.Contains(s,"username/password"):
  return "proxy_auth","Прокси отклонил авторизацию. Проверь логин, пароль и выбранный HTTP/SOCKS5."
 case strings.Contains(s,"403")||strings.Contains(s,"801"):
  return "access_denied","Доступ отклонён. На этапе API проверь регион и API-прокси; на этапе туннеля — ответ сервера Opera."
 case strings.Contains(s,"certificate")||strings.Contains(s,"x509"):
  return "certificate","Сертификат не прошёл проверку. Проверь время телефона и цепочку прокси."
 case strings.Contains(s,"lookup")||strings.Contains(s,"no such host"):
  return "dns","Не удалось разрешить адрес. Проверь Bootstrap DNS."
 case strings.Contains(s,"refused"):
  return "refused","Соединение отклонено. Проверь адрес, порт и запуск локального ByeDPI."
 case errors.Is(err,context.DeadlineExceeded)||strings.Contains(s,"timeout")||strings.Contains(s,"deadline"):
  return "timeout","Истёк таймаут. Причиной могут быть сеть, прокси или сервер; это не доказывает неисправность SNI."
 case strings.Contains(s,"reset")||strings.Contains(s,"eof"):
  return "reset","Соединение оборвано. Сравни результат контрольного подключения без подмены SNI."
 case strings.Contains(s,"tls")||strings.Contains(s,"handshake"):
  return "tls","Не удалось выполнить TLS-рукопожатие."
 case strings.Contains(s,"bad status"):
  return "http_status","Проверочный сайт вернул неожиданный HTTP-статус. Попробуй другой адрес проверки."
 default:return "connection","Ошибка соединения или ответа сервера. Смотри этап проверки."
 }
}
func zdtRecord(args *CLIArgs,stage,state string,err error,endpoint string,candidate bool) {
 path:=args.zdtRuntimePath;if path==""{return}
 zdtStatusMu.Lock();defer zdtStatusMu.Unlock()
 var report zdtReport
 if b,e:=os.ReadFile(path);e==nil{_ = json.Unmarshal(b,&report)}
 if report.PID!=os.Getpid(){report=zdtReport{PID:os.Getpid()}}
 report.Updated=time.Now().Unix()
 code,message:=zdtReason(err)
 step:=zdtStep{stage,state,code,message,endpoint}
 if candidate {
  if errors.Is(err,context.Canceled){return}
  found:=false;for i,s:=range report.Failures{if s.Endpoint==endpoint{report.Failures[i]=step;found=true;break}}
  if !found&&len(report.Failures)<1000{report.Failures=append(report.Failures,step)}
 } else {
  report.State=state
  found:=false;for i,s:=range report.Steps{if s.Stage==stage{report.Steps[i]=step;found=true;break}}
  if !found{report.Steps=append(report.Steps,step)}
 }
 raw,_:=json.Marshal(report);temp:=path+".tmp"
 if os.WriteFile(temp,raw,0600)==nil{_ = os.Rename(temp,path)}
}
type zdtObservedDialer struct { next dialer.ContextDialer; args *CLIArgs }
func (d zdtObservedDialer) Address()(string,error){
 if next,ok:=d.next.(interface{Address()(string,error)});ok{return next.Address()};return "",errors.New("address unavailable")
}
func (d zdtObservedDialer) Dial(n,a string)(net.Conn,error){return d.DialContext(context.Background(),n,a)}
func (d zdtObservedDialer) DialContext(ctx context.Context,n,a string)(net.Conn,error){
 conn,err:=d.next.DialContext(ctx,n,a);state:="ready";if err!=nil{state="error"}
 zdtRecord(d.args,"opera_tunnel",state,err,"",false);return conn,err
}
type zdtTrackedDialer struct { next dialer.ContextDialer; mu sync.Mutex; conns []net.Conn; stops []func()bool }
func (d *zdtTrackedDialer) Dial(n,a string)(net.Conn,error){return d.DialContext(context.Background(),n,a)}
func (d *zdtTrackedDialer) DialContext(ctx context.Context,n,a string)(net.Conn,error){
 conn,err:=d.next.DialContext(ctx,n,a);if err!=nil{return nil,err}
 if deadline,ok:=ctx.Deadline();ok{_ = conn.SetDeadline(deadline)}
 stop:=context.AfterFunc(ctx,func(){_ = conn.Close()})
 d.mu.Lock();d.conns=append(d.conns,conn);d.stops=append(d.stops,stop);d.mu.Unlock()
 return conn,nil
}
func (d *zdtTrackedDialer) close(){d.mu.Lock();defer d.mu.Unlock();for _,s:=range d.stops{s()};for _,c:=range d.conns{_ = c.Close()}}

// Compare on the same endpoint, credentials, route and target. A successful
// control is evidence of a difference involving SNI, not proof of ISP blocking.
func zdtCompare(ctx context.Context,configured,control func(context.Context)error)(error,error){
 err:=configured(ctx);if err==nil{return nil,nil};return err,control(ctx)
}
func zdtDiagnose(args *CLIArgs) int {
 args.zdtRuntimePath=args.zdtDiagnosePath
 _=os.Remove(args.zdtRuntimePath)
 logger:=clog.NewCondLogger(log.New(io.Discard,"",0),clog.SILENT)
 caPool,code:=buildCAPool(args.caFile,logger);if code!=0{zdtRecord(args,"config","error",errors.New("certificate bundle"),"",false);return code}
 zdtRecord(args,"config","ready",nil,"",false)
 root,cancel:=context.WithTimeout(context.Background(),90*time.Second);defer cancel()
 args.timeout=10*time.Second
 xproxy.RegisterDialerType("http",proxyFromURLWrapper);xproxy.RegisterDialerType("https",proxyFromURLWrapper)
 tracked:=&zdtTrackedDialer{next:&net.Dialer{Timeout:10*time.Second}}
 defer tracked.close()
 var base dialer.ContextDialer=tracked
 if args.zdtProxyList!=""{
  pool,err:=dialer.NewZdtPoolFile(args.zdtProxyList,"",base);if err!=nil{zdtRecord(args,"config","error",err,"",false);return 7};args.zdtPool=pool;base=pool
 }else if args.proxy!=""{
  u,err:=url.Parse(args.proxy);if err!=nil{zdtRecord(args,"config","error",err,"",false);return 7}
  pd,err:=xproxy.FromURL(u,base);if err!=nil{zdtRecord(args,"config","error",err,"",false);return 7};base=pd.(dialer.ContextDialer)
 }
 candidates:=[]string{args.apiProxy}
 if args.apiProxyFile!=""{var err error;candidates,err=loadAPIProxyList(args.apiProxyFile);if err!=nil||len(candidates)==0{if err==nil{err=errors.New("empty proxy list")};zdtRecord(args,"api_list","error",err,"",false);return 8}}
 zdtRecord(args,"api","connecting",nil,"",false)
 // Two attempts maximum per candidate: configured SNI, then a control only
 // if the configured route fails. Never change the user's saved settings.
 result,err:=selectAPIProxyCandidate(root,args,base,caPool,logger,candidates,true,false)
 if err!=nil{
  zdtRecord(args,"api","error",err,"",false)
  if args.fakeSNI!=""&&root.Err()==nil{
   control:=*args;control.fakeSNI="";control.zdtRuntimePath=""
   _,ce:=selectAPIProxyCandidate(root,&control,base,caPool,logger,candidates,true,false)
   zdtRecord(args,"api_control_without_sni",map[bool]string{true:"ready",false:"error"}[ce==nil],ce,"",false)
  }
  zdtRecord(args,"complete","error",err,"",false);return 12
 }
 zdtRecord(args,"api","ready",nil,zdtEndpoint(result.candidate),false)
 zdtRecord(args,"discovery","ready",nil,"",false)
 var probeErr,errorControl error
 for i,entry:=range result.ips{
  if i>=3||root.Err()!=nil{break}
  endpoint:=entry.NetAddr();if args.overrideProxyAddress!=""{endpoint=sanitizeFixedProxyAddress(args.overrideProxyAddress)}
  realName:=entry.Host;if realName==""{realName=strings.ToLower(entry.Geo.CountryCode)+"0."+PROXY_SUFFIX}
  makeDialer:=func(sni string)dialer.ContextDialer{
   pd:=dialer.NewProxyDialer(dialer.WrapStringToCb(endpoint),dialer.WrapStringToCb(realName),dialer.WrapStringToCb(sni),func()(string,error){return dialer.BasicAuthHeader(result.client.GetProxyCredentials()),nil},caPool,base)
   if args.zdtPool!=nil{return args.zdtPool.Wrap(pd)};return pd
  }
  target,_:=url.Parse(args.serverSelectionTestURL)
  targetAddr:=net.JoinHostPort(target.Hostname(),target.Port());if target.Port()==""{p:="443";if target.Scheme=="http"{p="80"};targetAddr=net.JoinHostPort(target.Hostname(),p)}
  tunnel:=func(sni string)func(context.Context)error{return func(parent context.Context)error{
   ctx,cl:=context.WithTimeout(parent,8*time.Second);defer cl()
   conn,e:=makeDialer(sni).DialContext(ctx,"tcp",targetAddr);if conn!=nil{_ = conn.Close()};return e
  }}
  zdtRecord(args,"opera_tunnel","connecting",nil,endpoint,false)
  probeErr,errorControl=zdtCompare(root,tunnel(args.fakeSNI),tunnel(""))
  if probeErr!=nil{
   zdtRecord(args,"opera_tunnel","error",probeErr,endpoint,false)
   zdtRecord(args,"tunnel_control_without_sni",map[bool]string{true:"ready",false:"error"}[errorControl==nil],errorControl,endpoint,false)
   if args.overrideProxyAddress!=""{break};continue
  }
  zdtRecord(args,"opera_tunnel","ready",nil,endpoint,false)
  ctx,cl:=context.WithTimeout(root,10*time.Second)
  probeErr=probeProxyEndpoint(ctx,makeDialer(args.fakeSNI),args.serverSelectionTestURL,1024,&tls.Config{RootCAs:caPool});cl()
  zdtRecord(args,"test_site",map[bool]string{true:"ready",false:"error"}[probeErr==nil],probeErr,"",false)
  if probeErr==nil{zdtRecord(args,"complete","ready",nil,"",false);return 0}
  if args.overrideProxyAddress!=""{break}
 }
 if probeErr==nil{probeErr=errors.New("empty endpoints")}
 zdtRecord(args,"complete","error",probeErr,"",false);return 12
}
