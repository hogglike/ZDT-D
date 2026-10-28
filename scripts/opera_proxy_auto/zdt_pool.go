package dialer

// ZDT's list mode retries authenticated upstreams without changing the listening
// port or restarting Opera. A retry can include the complete Opera TLS/CONNECT.
import (
 "context"
 "crypto/tls"
 "encoding/json"
 "errors"
 "fmt"
 "io"
 "net"
 "net/url"
 "os"
 "strconv"
 "strings"
 "sync"
 "time"
 xproxy "golang.org/x/net/proxy"
)

type zdtSlotKey struct{}
type zdtTrackerKey struct{}
type zdtEndpoint struct { dialer ContextDialer; label string }
type ZdtPool struct {
 endpoints []zdtEndpoint
 mu sync.Mutex
 preferred int
 statePath string
 failures map[int]string
 timeout time.Duration
}
type zdtTracker struct { mu sync.Mutex; connections []net.Conn; stops []func() bool }
type zdtBoundedDirect struct { next ContextDialer }
func (d zdtBoundedDirect) Dial(n,a string)(net.Conn,error) { return d.DialContext(context.Background(),n,a) }
func (d zdtBoundedDirect) DialContext(ctx context.Context,n,a string)(net.Conn,error) {
 conn,err:=d.next.DialContext(ctx,n,a); if err!=nil{return nil,err}
 if deadline,ok:=ctx.Deadline();ok{ _=conn.SetDeadline(deadline) }
 tracker,_:=ctx.Value(zdtTrackerKey{}).(*zdtTracker)
 stop:=context.AfterFunc(ctx,func(){_ = conn.Close()})
 if tracker!=nil {tracker.mu.Lock();tracker.connections=append(tracker.connections,conn);tracker.stops=append(tracker.stops,stop);tracker.mu.Unlock()}
 return conn,nil
}
func (t *zdtTracker) finish(success bool) {
 t.mu.Lock();defer t.mu.Unlock()
 for _,stop:=range t.stops{stop()}
 for _,conn:=range t.connections{if success{_ = conn.SetDeadline(time.Time{})}else{_ = conn.Close()}}
}
func NewZdtPoolFile(path,statePath string,next ContextDialer)(*ZdtPool,error) {
 file,err:=os.Open(path);if err!=nil{return nil,errors.New("не удалось открыть список прокси")};defer file.Close()
 raw,err:=io.ReadAll(io.LimitReader(file,1024*1024+1));if err!=nil||len(raw)>1024*1024{return nil,errors.New("список прокси не читается или больше 1 МБ")}
 var entries []string
 if json.Unmarshal(raw,&entries)!=nil{return nil,errors.New("неверный формат списка прокси")}
 return NewZdtPool(entries,statePath,next)
}
func NewZdtPool(entries []string,statePath string,next ContextDialer)(*ZdtPool,error) {
 if len(entries)==0||len(entries)>1000{return nil,errors.New("нужно от 1 до 1000 прокси")}
 pool:=&ZdtPool{statePath:statePath,failures:map[int]string{},timeout:6*time.Second}
 seen:=map[string]bool{}
 for i,raw:=range entries{
  u,err:=url.Parse(strings.TrimSpace(raw)); if err!=nil||u.Hostname()==""||u.RawQuery!=""||u.Fragment!=""||(u.Path!=""&&u.Path!="/"){return nil,fmt.Errorf("неверная строка прокси %d",i+1)}
  port,err:=strconv.Atoi(u.Port());if err!=nil||port<1||port>65535{return nil,fmt.Errorf("неверный порт в строке %d",i+1)}
  u.Scheme=strings.ToLower(u.Scheme)
  if u.Scheme!="http"&&u.Scheme!="https"&&u.Scheme!="socks5"&&u.Scheme!="socks5h"{return nil,fmt.Errorf("неподдерживаемый протокол в строке %d",i+1)}
  label:=u.Scheme+"://"+u.Host
  if u.Scheme=="socks5h"{u.Scheme="socks5"}
  u.Path=""
  if seen[u.String()]{continue};seen[u.String()]=true
  var pd xproxy.Dialer
  bounded:=zdtBoundedDirect{next:next}
  if u.Scheme=="http"||u.Scheme=="https"{pd,err=ProxyDialerFromURL(u,bounded)}else{pd,err=xproxy.FromURL(u,bounded)}
  if err!=nil{return nil,fmt.Errorf("не удалось настроить строку %d",i+1)}
  cd,ok:=pd.(ContextDialer);if !ok{return nil,errors.New("прокси не поддерживает таймаут")}
  pool.endpoints=append(pool.endpoints,zdtEndpoint{cd,label})
 }
 pool.report("waiting",-1,"")
 return pool,nil
}
func zdtSafeError(err error) string {
 if errors.Is(err,context.Canceled){return "Запрос отменён"}
 if errors.Is(err,context.DeadlineExceeded){return "Истёк таймаут подключения"}
 var ne net.Error;if errors.As(err,&ne)&&ne.Timeout(){return "Истёк таймаут подключения"}
 // Inspect error type/message locally, but never expose a raw URL or credential.
 text:=strings.ToLower(err.Error())
 if strings.Contains(text,"407")||strings.Contains(text,"authentication")||strings.Contains(text,"username/password"){return "Прокси отклонил логин или пароль"}
 if strings.Contains(text,"403"){return "Прокси запретил соединение (403)"}
 if strings.Contains(text,"certificate"){return "Ошибка сертификата TLS"}
 if strings.Contains(text,"refused"){return "Сервер отклонил подключение"}
 return "Не удалось установить соединение через прокси; проверь протокол и доступность"
}
func (p *ZdtPool) report(state string,index int,message string) {
 p.mu.Lock();defer p.mu.Unlock();p.reportLocked(state,index,message)
}
func (p *ZdtPool) reportLocked(state string,index int,message string) {
 if p.statePath==""{return}
 rows:=make([]map[string]any,0,len(p.failures))
 for i:=range p.endpoints{if reason,ok:=p.failures[i];ok{rows=append(rows,map[string]any{"endpoint":p.endpoints[i].label,"error":reason})}}
 endpoint:="";if index>=0&&index<len(p.endpoints){endpoint=p.endpoints[index].label}
 value:=map[string]any{"state":state,"endpoint":endpoint,"total":len(p.endpoints),"error":message,"failures":rows,"pid":os.Getpid(),"updated_at":time.Now().Unix()}
 raw,_:=json.Marshal(value)
 // Serialized, atomic replacement; neither URL userinfo nor target domains are stored.
 temp:=p.statePath+".tmp"
 if os.WriteFile(temp,raw,0600)==nil{_ = os.Rename(temp,p.statePath)}
}
func (p *ZdtPool) run(ctx context.Context,operation func(context.Context)(net.Conn,error))(net.Conn,error) {
 p.mu.Lock();first:=p.preferred;p.mu.Unlock()
 for attempt:=0;attempt<len(p.endpoints);attempt++{
  if err:=ctx.Err();err!=nil{return nil,err}
  index:=(first+attempt)%len(p.endpoints)
  p.report("connecting",index,"")
  bounded,cancel:=context.WithTimeout(context.WithValue(ctx,zdtSlotKey{},index),p.timeout)
  tracker:=&zdtTracker{}
  conn,err:=operation(context.WithValue(bounded,zdtTrackerKey{},tracker))
  if err==nil && bounded.Err()!=nil{err=bounded.Err()}
  tracker.finish(err==nil);cancel()
  if err==nil {
   p.mu.Lock();p.preferred=index;delete(p.failures,index);p.reportLocked("ready",index,"");p.mu.Unlock()
   return conn,nil
  }
  if conn!=nil{_ = conn.Close()}
  if ctx.Err()!=nil{return nil,ctx.Err()}
  reason:=zdtSafeError(err)
  p.mu.Lock();p.failures[index]=reason;if p.preferred==index{p.preferred=(index+1)%len(p.endpoints)};p.reportLocked("connecting",index,reason);p.mu.Unlock()
 }
 p.report("error",-1,"Ни один прокси из списка не установил соединение")
 return nil,errors.New("ни один прокси из списка не установил соединение; причины см. в разделе Прокси")
}
func (p *ZdtPool) Dial(n,a string)(net.Conn,error){return p.DialContext(context.Background(),n,a)}
func (p *ZdtPool) DialContext(ctx context.Context,n,a string)(net.Conn,error) {
 if index,ok:=ctx.Value(zdtSlotKey{}).(int);ok{return p.endpoints[index].dialer.DialContext(ctx,n,a)}
 return p.run(ctx,func(c context.Context)(net.Conn,error){index:=c.Value(zdtSlotKey{}).(int);return p.endpoints[index].dialer.DialContext(c,n,a)})
}
type zdtRetry struct{pool *ZdtPool;outer ContextDialer}
func (p *ZdtPool) Wrap(outer ContextDialer) ContextDialer{return zdtRetry{p,outer}}
func (d zdtRetry) Dial(n,a string)(net.Conn,error){return d.DialContext(context.Background(),n,a)}
func (d zdtRetry) DialContext(ctx context.Context,n,a string)(net.Conn,error){return d.pool.run(ctx,func(c context.Context)(net.Conn,error){return d.outer.DialContext(c,n,a)})}
func (p *ZdtPool) DialTLS(ctx context.Context,n,a string,outer ContextDialer,config *tls.Config)(net.Conn,error){
 return p.run(ctx,func(c context.Context)(net.Conn,error){conn,err:=outer.DialContext(c,n,a);if err!=nil{return nil,err};tc:=tls.Client(conn,config);if err=tc.HandshakeContext(c);err!=nil{_ = tc.Close();return nil,err};return tc,nil})
}
