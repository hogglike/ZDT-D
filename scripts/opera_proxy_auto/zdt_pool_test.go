package dialer

import (
 "bufio"
 "context"
 "crypto/tls"
 "crypto/x509"
 "encoding/base64"
 "encoding/json"
 "io"
 "net"
 "net/http"
 "net/http/httptest"
 "os"
 "strings"
 "sync/atomic"
 "testing"
 "time"
)
func fixtureHTTP(t *testing.T,handle func(net.Conn,*http.Request),calls *atomic.Int32) string {
 t.Helper(); ln,err:=net.Listen("tcp","127.0.0.1:0");if err!=nil{t.Fatal(err)};t.Cleanup(func(){ln.Close()})
 go func(){for{conn,err:=ln.Accept();if err!=nil{return};go func(){defer conn.Close();conn.SetDeadline(time.Now().Add(2*time.Second)); req,err:=http.ReadRequest(bufio.NewReader(conn));if err!=nil{return};if calls!=nil{calls.Add(1)};handle(conn,req)}()}}()
 return ln.Addr().String()
}
func TestZdtAuthenticatedFailoverStickyAndNoSecretStatus(t *testing.T){
 var denied,good atomic.Int32
 a:=fixtureHTTP(t,func(c net.Conn,r *http.Request){io.WriteString(c,"HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\n\r\n")},&denied)
 auth:="Basic "+base64.StdEncoding.EncodeToString([]byte("alice:p@ss:word"))
 b:=fixtureHTTP(t,func(c net.Conn,r *http.Request){if r.Header.Get("Proxy-Authorization")!=auth{t.Error("credentials were not URL-decoded")};io.WriteString(c,"HTTP/1.1 200 Connection established\r\n\r\n");io.Copy(c,c)},&good)
 path:=t.TempDir()+"/state.json"
 p,err:=NewZdtPool([]string{"http://alice:p%40ss%3Aword@"+a,"http://alice:p%40ss%3Aword@"+b},path,&net.Dialer{});if err!=nil{t.Fatal(err)}
 for i:=0;i<2;i++ {c,err:=p.DialContext(context.Background(),"tcp","target.example:443");if err!=nil{t.Fatal(err)};c.Write([]byte("ok"));var answer [2]byte;if _,err=c.Read(answer[:]);err!=nil||string(answer[:])!="ok"{t.Fatal("tunnel not usable",err)};c.Close()}
 if denied.Load()!=1||good.Load()!=2{t.Fatalf("last working proxy was not first: %d/%d",denied.Load(),good.Load())}
 raw,_:=os.ReadFile(path);if strings.Contains(string(raw),"alice")||strings.Contains(string(raw),"p@ss")||strings.Contains(string(raw),"%40"){t.Fatal("credentials in status")}
 var state map[string]any;json.Unmarshal(raw,&state);if state["state"]!="ready"{t.Fatal(state)}
}
func TestZdtRetriesCompleteTLSHandshake(t *testing.T){
 server:=httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){io.WriteString(w,"success")}));defer server.Close()
 roots:=x509.NewCertPool();roots.AddCert(server.Certificate())
 a:=fixtureHTTP(t,func(c net.Conn,r *http.Request){io.WriteString(c,"HTTP/1.1 200 Connection established\r\n\r\nbad tls\n")},nil)
 b:=fixtureHTTP(t,func(c net.Conn,r *http.Request){up,err:=net.Dial("tcp",r.Host);if err!=nil{return};defer up.Close();io.WriteString(c,"HTTP/1.1 200 Connection established\r\n\r\n");go io.Copy(up,c);io.Copy(c,up)},nil)
 p,err:=NewZdtPool([]string{"http://"+a,"http://"+b},"",&net.Dialer{});if err!=nil{t.Fatal(err)}
 c,err:=p.DialTLS(context.Background(),"tcp",strings.TrimPrefix(server.URL,"https://"),p,&tls.Config{RootCAs:roots,ServerName:"example.com"});if err!=nil{t.Fatal(err)};defer c.Close()
 io.WriteString(c,"GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
 response,err:=http.ReadResponse(bufio.NewReader(c),nil);if err!=nil{t.Fatal(err)};body,_:=io.ReadAll(response.Body);response.Body.Close();if string(body)!="success"{t.Fatalf("bad response %s",body)}
}
func TestZdtTimeoutThenFallbackAndCancellation(t *testing.T){
 a:=fixtureHTTP(t,func(c net.Conn,r *http.Request){time.Sleep(200*time.Millisecond)},nil)
 b:=fixtureHTTP(t,func(c net.Conn,r *http.Request){io.WriteString(c,"HTTP/1.1 200 Connection established\r\n\r\n");io.Copy(c,c)},nil)
 p,err:=NewZdtPool([]string{"http://"+a,"http://"+b},"",&net.Dialer{});if err!=nil{t.Fatal(err)};p.timeout=30*time.Millisecond
 start:=time.Now();c,err:=p.Dial("tcp","target:443");if err!=nil{t.Fatal(err)};c.Close();if time.Since(start)>time.Second{t.Fatal("unbounded timeout")}
 ctx,cancel:=context.WithCancel(context.Background());cancel();if _,err=p.DialContext(ctx,"tcp","target:443");err==nil{t.Fatal("ignored cancellation")}
}
func TestZdtAllFailedNeverUsesDirectTarget(t *testing.T){
 a:=fixtureHTTP(t,func(c net.Conn,r *http.Request){io.WriteString(c,"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")},nil)
 p,err:=NewZdtPool([]string{"http://"+a},"",&net.Dialer{});if err!=nil{t.Fatal(err)}
 if _,err=p.Dial("tcp","127.0.0.1:1");err==nil{t.Fatal("failed pool accepted")}
 for _,raw:=range []string{"vless://secret@127.0.0.1:80","http://secret:password@127.0.0.1:0"}{if _,err=NewZdtPool([]string{raw},"",&net.Dialer{});err==nil||strings.Contains(err.Error(),"secret"){t.Fatal("validation leaked credentials",err)}}
}
func TestZdtSOCKS5Authentication(t *testing.T){
 ln,err:=net.Listen("tcp","127.0.0.1:0");if err!=nil{t.Fatal(err)};defer ln.Close()
 done:=make(chan bool,1)
 go func(){c,err:=ln.Accept();if err!=nil{done<-false;return};defer c.Close();c.SetDeadline(time.Now().Add(time.Second))
  header:=make([]byte,2);if _,err=io.ReadFull(c,header);err!=nil{done<-false;return};methods:=make([]byte,int(header[1]));io.ReadFull(c,methods);c.Write([]byte{5,2})
  io.ReadFull(c,header);user:=make([]byte,int(header[1]));io.ReadFull(c,user);length:=make([]byte,1);io.ReadFull(c,length);pass:=make([]byte,int(length[0]));io.ReadFull(c,pass)
  if string(user)!="alice"||string(pass)!="p@ss:word"{done<-false;return};c.Write([]byte{1,0})
  request:=make([]byte,4);io.ReadFull(c,request);if request[3]!=3{done<-false;return};io.ReadFull(c,length);address:=make([]byte,int(length[0])+2);io.ReadFull(c,address)
  c.Write([]byte{5,0,0,1,127,0,0,1,0,1});done<-true;io.Copy(c,c)
 }()
 p,err:=NewZdtPool([]string{"socks5h://alice:p%40ss%3Aword@"+ln.Addr().String()},"",&net.Dialer{});if err!=nil{t.Fatal(err)}
 c,err:=p.Dial("tcp","target.example:443");if err!=nil{t.Fatal(err)};c.Close();if !<-done{t.Fatal("SOCKS5 auth/CONNECT failed")}
}
