"""Compile the actual subscription renderer; export a synthetic Reality fixture."""
from pathlib import Path
import json, re, subprocess, sys, tempfile
ROOT = Path(__file__).resolve().parents[2]
source=(ROOT/'rust/zdtd/src/programs/mihomo_subscription.rs').read_text()
def section(start,end): return source[source.index(start):source.index(end,source.index(start))]
functions='\n'.join([section('fn json_string_any(', 'fn json_u16_any('),section('fn bool_any(', 'fn safe_server_name('),section('fn tls_json(', 'pub(crate) fn singbox_outbound('),section('pub(crate) fn singbox_outbound(', 'fn render_singbox_config(')])
mode_source=(ROOT/'rust/zdtd/src/programs/connection_modes.rs').read_text()
mode_types=mode_source[mode_source.index('#[derive(Clone, Serialize, Deserialize)]'):mode_source.index('static CONFIG_LOCK:')]
keys=subprocess.check_output([sys.argv[1],'generate','reality-keypair'],text=True)
private=re.search(r'PrivateKey:\s*(\S+)',keys).group(1)
public=re.search(r'PublicKey:\s*(\S+)',keys).group(1)
with tempfile.TemporaryDirectory(prefix='zdtd-renderer-') as work:
 work=Path(work);(work/'src').mkdir()
 (work/'Cargo.toml').write_text('[package]\nname="zdtd-renderer-test"\nversion="0.1.0"\nedition="2021"\n[dependencies]\nserde_json="1.0"\nanyhow="1.0"\nserde={version="1.0",features=["derive"]}\n')
 (work/'src/mode_bootstrap.rs').write_text((ROOT/'rust/zdtd/src/programs/mode_bootstrap.rs').read_text())
 main=r'''
use serde_json::{json, Value as JsonValue};
use serde::{Serialize,Deserialize};
use std::collections::BTreeMap;
use anyhow::{Result,bail};
mod mode_bootstrap;
const MODES:[&str;3]=["white","normal","browser"];
struct SubscriptionNode {protocol:String,server:String,port:u16,definition:JsonValue}
fn main() {
 let public=std::env::args().nth(1).unwrap();
 let definition=json!({"type":"vless","uuid":"550e8400-e29b-41d4-a716-446655440000","flow":"xtls-rprx-vision","security":"reality","sni":"localhost","fp":"chrome","pbk":public,"sid":"abcd"});
 let node=SubscriptionNode{protocol:"vless".into(),server:"127.0.0.1".into(),port:1,definition};
 let out=singbox_outbound(&node).unwrap();
 assert_eq!(out["tls"]["reality"]["public_key"],public);assert_eq!(out["flow"],"xtls-rprx-vision");assert_eq!(out["tls"]["server_name"],"localhost");
 let clash=tls_json(&json!({"reality-opts":{"public-key":public,"short-id":"abcd"},"servername":"localhost"}),false).unwrap();
 assert_eq!(clash["enabled"],true);assert_eq!(clash["utls"]["fingerprint"],"chrome");
 let raw=tls_json(&json!({"tls":{"server_name":"localhost","reality":{"enabled":true,"public_key":public}}}),false).unwrap();assert_eq!(raw["enabled"],true);assert_eq!(raw["utls"]["enabled"],true);
 let old:Config=serde_json::from_str(r#"{"active":"","modes":{"browser":{"apps":["org.browser"],"app_policy":"selected"}}}"#).unwrap();
 assert_eq!(old.modes["browser"].check_sites.len(),3);assert!(old.modes["browser"].check_enabled);assert_eq!(old.latency_timeout_seconds,8);
 let mut disabled=old.modes["browser"].clone();disabled.check_enabled=false;disabled.check_sites[0].enabled=false;
 let saved=serde_json::to_string(&disabled).unwrap();let reread:ModeSettings=serde_json::from_str(&saved).unwrap();assert!(!reread.check_enabled);assert!(!reread.check_sites[0].enabled);
 let mut bootstrap=vec![out.clone(),out.clone(),out.clone(),json!({"type":"socks","server":"127.0.0.1"}),json!({"type":"socks","server":"missing.invalid"})];
 bootstrap[0]["server"]=json!("proxy.fixture.invalid.");bootstrap[1]["server"]=json!("proxy.fixture.invalid");bootstrap[2]["server"]=json!("implicit.fixture.invalid");
 bootstrap[2]["tls"].as_object_mut().unwrap().remove("server_name");
 let before=bootstrap[0]["tls"].clone();let mut calls=Vec::new();
 let report=mode_bootstrap::resolve_outbounds(&mut bootstrap,|host|{calls.push(host.to_owned());if host=="missing.invalid"{vec![]}else{vec!["::1".into(),"127.0.0.1".into()]}});
 assert_eq!(calls,vec!["proxy.fixture.invalid","implicit.fixture.invalid","missing.invalid"]);
 assert_eq!(bootstrap[0]["server"],"127.0.0.1");assert_eq!(bootstrap[0]["tls"],before);assert_eq!(bootstrap[0]["uuid"],out["uuid"]);assert_eq!(bootstrap[0]["flow"],out["flow"]);
 assert_eq!(bootstrap[1]["server"],"127.0.0.1");assert_eq!(bootstrap[2]["tls"]["server_name"],"implicit.fixture.invalid");
 assert_eq!(bootstrap[4]["server"],"missing.invalid");assert!(report.last().unwrap()["ip"].is_null());
 println!("{}",out);
}
'''
 (work/'src/main.rs').write_text(main+'\n'+functions+'\n'+mode_types)
 host=re.search(r'^host: (.+)$',subprocess.check_output(['rustc','-vV'],text=True),re.M).group(1)
 out=json.loads(subprocess.check_output(['cargo','run','--quiet','--target',host,'--manifest-path',str(work/'Cargo.toml'),'--',public],text=True,cwd=work))
 Path(sys.argv[2]).write_text(json.dumps({'client':out,'private_key':private,'public_key':public}))
 Path(sys.argv[2]).chmod(0o600)
 print('PASS: production VLESS/Reality renderer preserves Vision/SNI/keys; settings migration; Android bootstrap caches names, preserves explicit/implicit SNI and leaves failed nodes retryable',flush=True)
