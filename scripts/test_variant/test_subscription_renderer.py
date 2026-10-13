"""Compile the actual subscription renderer; export a synthetic Reality fixture."""
from pathlib import Path
import json, re, subprocess, sys, tempfile
ROOT = Path(__file__).resolve().parents[2]
source=(ROOT/'rust/zdtd/src/programs/mihomo_subscription.rs').read_text()
def section(start,end): return source[source.index(start):source.index(end,source.index(start))]
functions='\n'.join([section('fn selection_definition(', 'fn preserve_existing_node_ids('),section('fn json_string_any(', 'fn json_u16_any('),section('fn bool_any(', 'fn safe_server_name('),section('fn tls_json(', 'pub(crate) fn singbox_outbound('),section('pub(crate) fn singbox_outbound(', 'fn render_singbox_config(')])
mode_source=(ROOT/'rust/zdtd/src/programs/connection_modes.rs').read_text()
mode_types=mode_source[mode_source.index('#[derive(Clone, Serialize, Deserialize)]'):mode_source.index('static CONFIG_LOCK:')]
mode_key_functions=mode_source[mode_source.index('fn stable_mode_key('):mode_source.index('fn save_config(')]
latency_function=mode_source[mode_source.index('fn latency_layout('):mode_source.index('fn start_latency_core(')]
keys=subprocess.check_output([sys.argv[1],'generate','reality-keypair'],text=True)
private=re.search(r'PrivateKey:\s*(\S+)',keys).group(1)
public=re.search(r'PublicKey:\s*(\S+)',keys).group(1)
with tempfile.TemporaryDirectory(prefix='zdtd-renderer-') as work:
 work=Path(work);(work/'src').mkdir()
 (work/'Cargo.toml').write_text('[package]\nname="zdtd-renderer-test"\nversion="0.1.0"\nedition="2021"\n[dependencies]\nserde_json="1.0"\nanyhow="1.0"\nsha2="0.10"\nhex="0.4"\nserde={version="1.0",features=["derive"]}\n')
 (work/'src/mode_bootstrap.rs').write_text((ROOT/'rust/zdtd/src/programs/mode_bootstrap.rs').read_text())
 main=r'''
use serde_json::{json, Value as JsonValue, Value};
use sha2::{Digest,Sha256};
use serde::{Serialize,Deserialize};
use std::collections::{BTreeMap,BTreeSet};
use anyhow::{Result,bail};
mod mode_bootstrap;
const MODES:[&str;3]=["white","normal","browser"];
#[derive(Clone)]
struct SubscriptionNode {id:String,name:String,protocol:String,server:String,port:u16,definition:JsonValue}
fn main() {
 let public=std::env::args().nth(1).unwrap();
 let definition=json!({"type":"vless","uuid":"550e8400-e29b-41d4-a716-446655440000","flow":"xtls-rprx-vision","security":"reality","sni":"localhost","fp":"chrome","pbk":public,"sid":"abcd"});
 let node=SubscriptionNode{id:"a".into(),name:"Server".into(),protocol:"vless".into(),server:"127.0.0.1".into(),port:1,definition};
 let out=singbox_outbound(&node).unwrap();
 assert_eq!(out["tls"]["reality"]["public_key"],public);assert_eq!(out["flow"],"xtls-rprx-vision");assert_eq!(out["tls"]["server_name"],"localhost");
 let clash=tls_json(&json!({"reality-opts":{"public-key":public,"short-id":"abcd"},"servername":"localhost"}),false).unwrap();
 assert_eq!(clash["enabled"],true);assert_eq!(clash["utls"]["fingerprint"],"chrome");
 let raw=tls_json(&json!({"tls":{"server_name":"localhost","reality":{"enabled":true,"public_key":public}}}),false).unwrap();assert_eq!(raw["enabled"],true);assert_eq!(raw["utls"]["enabled"],true);
 let old:Config=serde_json::from_str(r#"{"active":"","modes":{"browser":{"apps":["org.browser"],"app_policy":"selected"}}}"#).unwrap();
 assert_eq!(old.modes["browser"].check_sites.len(),3);assert!(old.modes["browser"].check_enabled);assert_eq!(old.latency_timeout_seconds,8);assert!(!old.refresh_enabled);assert_eq!(old.refresh_interval_minutes,60);
 let mut schedule=old.clone();schedule.refresh_enabled=true;schedule.refresh_interval_minutes=120;let schedule:Config=serde_json::from_str(&serde_json::to_string(&schedule).unwrap()).unwrap();assert!(schedule.refresh_enabled);assert_eq!(schedule.refresh_interval_minutes,120);
 let mut disabled=old.modes["browser"].clone();disabled.check_enabled=false;disabled.check_sites[0].enabled=false;
 let saved=serde_json::to_string(&disabled).unwrap();let reread:ModeSettings=serde_json::from_str(&saved).unwrap();assert!(!reread.check_enabled);assert!(!reread.check_sites[0].enabled);
 assert!(!reread.auto_enabled);assert!(!reread.manual_override);
 for mode in ["normal","browser","white"] {
  let mut settings=ModeSettings::default();settings.auto_enabled=true;settings.node_keys=vec!["first".into(),"second".into()];settings.name_filters=vec!["LTE".into()];
  settings.choose(mode,"outside-list").unwrap();assert!(settings.manual_override);assert_eq!(settings.selected_key,"outside-list");
  let raw=serde_json::to_string(&settings).unwrap();let mut settings:ModeSettings=serde_json::from_str(&raw).unwrap();
  settings.choose(mode,"auto").unwrap();assert!(!settings.manual_override);assert_eq!(settings.node_keys,["first","second"]);assert_eq!(settings.name_filters,["LTE"]);
  settings.check_enabled=false;assert!(settings.choose(mode,"auto").is_err());
 }
 assert!(ModeSettings::default().choose("browser","auto").is_err());
 let mut rotated=node.clone();rotated.id="generated".into();rotated.definition["server"]=json!("new.fixture");
 let mut old_node=node.clone();old_node.definition["server"]=json!("old.fixture");
 preserve_node_ids_from(&[old_node.clone()],std::slice::from_mut(&mut rotated));assert_eq!(rotated.id,"a");
 let mut renamed=old_node.clone();renamed.id="generated".into();renamed.name="Renamed".into();renamed.definition["name"]=json!("Renamed");
 preserve_node_ids_from(&[old_node.clone()],std::slice::from_mut(&mut renamed));assert_eq!(renamed.id,"a");
 let mut second=old_node.clone();second.id="b".into();second.definition["server"]=json!("second.fixture");
 let mut reordered=vec![second.clone(),old_node.clone()];for n in &mut reordered {n.id="new".into();}
 preserve_node_ids_from(&[old_node.clone(),second],&mut reordered);assert_eq!(reordered[0].id,"b");assert_eq!(reordered[1].id,"a");
 let mut renamed_collision=old_node.clone();renamed_collision.id="generated-b".into();renamed_collision.name="Renamed".into();
 let mut new_collision=old_node.clone();new_collision.definition["server"]=json!("new-different.fixture");new_collision.name="New".into();
 let mut collision=vec![renamed_collision,new_collision];preserve_node_ids_from(&[old_node.clone()],&mut collision);
 assert_eq!(collision[0].id,"a");assert_ne!(collision[0].id,collision[1].id);
 let stable=stable_mode_key("subscription","a");assert_eq!(stable,stable_mode_key("subscription",&rotated.id));assert_ne!(stable,stable_mode_key("another","a"));
 let legacy=legacy_mode_key("subscription",&old_node.definition);
 let saved=SavedNode{subscription_id:"subscription".into(),node_id:"a".into(),name:"Server".into(),protocol:"vless".into(),legacy_key:legacy.clone()};
 let catalog=BTreeMap::from([(stable.clone(),saved.clone())]);
 let mut c=Config::default();let m=c.modes.get_mut("browser").unwrap();m.node_keys=vec![legacy.clone(),"obsolete".into(),legacy.clone()];m.selected_key=legacy.clone();m.last_key=legacy.clone();
 assert_eq!(c.reconcile(&catalog,true),1);assert_eq!(c.modes["browser"].node_keys,vec![stable.clone()]);assert_eq!(c.modes["browser"].selected_key,stable);assert_eq!(c.modes["browser"].last_key,stable);
 // Endpoint changes retain IDs; disabled subscriptions still appear in selection metadata.
 let mut refreshed=saved.clone();refreshed.legacy_key="new-definition".into();let catalog=BTreeMap::from([(stable.clone(),refreshed)]);
 c.reconcile(&catalog,true);assert_eq!(c.modes["browser"].node_keys,vec![stable.clone()]);
 let raw=serde_json::to_string(&c).unwrap();let mut c:Config=serde_json::from_str(&raw).unwrap();
 c.reconcile(&BTreeMap::new(),false);assert_eq!(c.modes["browser"].selected_key,stable);assert!(!c.catalog.is_empty());
 c.reconcile(&BTreeMap::new(),true);assert!(c.modes["browser"].node_keys.is_empty());assert!(c.modes["browser"].selected_key.is_empty());
 // Ambiguous legacy aliases never silently choose a different duplicate server.
 let mut c=Config::default();c.modes.get_mut("normal").unwrap().selected_key=legacy.clone();
 let duplicate=BTreeMap::from([("n2_a".into(),saved.clone()),("n2_b".into(),saved)]);c.reconcile(&duplicate,true);assert!(c.modes["normal"].selected_key.is_empty());
 let mut bootstrap=vec![out.clone(),out.clone(),out.clone(),json!({"type":"socks","server":"127.0.0.1"}),json!({"type":"socks","server":"missing.invalid"})];
 bootstrap[0]["server"]=json!("proxy.fixture.invalid.");bootstrap[1]["server"]=json!("proxy.fixture.invalid");bootstrap[2]["server"]=json!("implicit.fixture.invalid");
 bootstrap[2]["tls"].as_object_mut().unwrap().remove("server_name");
 let before=bootstrap[0]["tls"].clone();let mut calls=Vec::new();
 let report=mode_bootstrap::resolve_outbounds(&mut bootstrap,|host|{calls.push(host.to_owned());if host=="missing.invalid"{vec![]}else{vec!["::1".into(),"127.0.0.1".into()]}});
 assert_eq!(calls,vec!["proxy.fixture.invalid","implicit.fixture.invalid","missing.invalid"]);
 assert_eq!(bootstrap[0]["server"],"127.0.0.1");assert_eq!(bootstrap[0]["tls"],before);assert_eq!(bootstrap[0]["uuid"],out["uuid"]);assert_eq!(bootstrap[0]["flow"],out["flow"]);
 assert_eq!(bootstrap[1]["server"],"127.0.0.1");assert_eq!(bootstrap[2]["tls"]["server_name"],"implicit.fixture.invalid");
 assert_eq!(bootstrap[4]["server"],"missing.invalid");assert!(report.last().unwrap()["ip"].is_null());
 let latency=latency_layout(serde_json::from_str(include_str!("mode_core.json")).unwrap(),19976,19975);
 assert_eq!(latency["inbounds"].as_array().unwrap().len(),1);assert_eq!(latency["inbounds"][0]["type"],"mixed");assert_eq!(latency["route"]["final"],"TEST");
 println!("{}",json!({"client":out,"latency_layout":latency}));
}
'''
 (work/'src/mode_core.json').write_text((ROOT/'rust/zdtd/src/programs/mode_core.json').read_text())
 (work/'src/main.rs').write_text(main+'\n'+functions+'\n'+mode_types+'\n'+mode_key_functions+'\n'+latency_function)
 host=re.search(r'^host: (.+)$',subprocess.check_output(['rustc','-vV'],text=True),re.M).group(1)
 out=json.loads(subprocess.check_output(['cargo','run','--quiet','--target',host,'--manifest-path',str(work/'Cargo.toml'),'--',public],text=True,cwd=work))
 Path(sys.argv[2]).write_text(json.dumps({'client':out['client'],'latency_layout':out['latency_layout'],'private_key':private,'public_key':public}))
 Path(sys.argv[2]).chmod(0o600)
 print('PASS: production VLESS/Reality renderer preserves Vision/SNI/keys; settings migration, stable refreshed/renamed/reordered nodes, legacy cleanup, incomplete catalog protection; Android bootstrap caches names, preserves explicit/implicit SNI and leaves failed nodes retryable',flush=True)

