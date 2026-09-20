//! DNS profile drafts. Deliberately has no routing or process side effects.
//! Runtime activation is gated on device verification of resolver + IPv6 setup.
use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    net::Ipv4Addr,
    os::unix::fs::OpenOptionsExt,
    path::Path,
    sync::Mutex,
};

pub const CONFIG_PATH: &str = "/data/adb/modules/ZDT-D/working_folder/dnsprofiles/profiles.json";
pub const MAX_BYTES: usize = 256 * 1024;
static STORE_LOCK: Mutex<()> = Mutex::new(());

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct DnsProfile {
    pub display_name: String,
    pub endpoint: String,
    pub bootstrap: Vec<Ipv4Addr>,
    pub timeout_ms: u32,
    pub apps: Vec<String>,
    // Kept explicit in the wire format so future clients cannot accidentally
    // activate a configuration on this draft-only backend.
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ProfileDocument {
    pub schema_version: u32,
    pub revision: u64,
    pub profiles: BTreeMap<String, DnsProfile>,
}

impl Default for ProfileDocument {
    fn default() -> Self {
        Self {
            schema_version: 1,
            revision: 0,
            profiles: BTreeMap::new(),
        }
    }
}

fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 48
        && id
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
}

fn valid_package(package: &str) -> bool {
    package.len() <= 255
        && package.contains('.')
        && package.split('.').all(|part| {
            !part.is_empty()
                && part.as_bytes()[0].is_ascii_alphabetic()
                && part.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
        })
}

pub fn validate(document: &ProfileDocument) -> Result<()> {
    if document.schema_version != 1 {
        bail!("unsupported_schema_version");
    }
    if document.profiles.len() > 16 {
        bail!("too_many_profiles: maximum 16 drafts");
    }
    let mut assigned = BTreeMap::<&str, &str>::new();
    for (id, profile) in &document.profiles {
        if !valid_id(id) {
            bail!("invalid_profile_id: {id}");
        }
        if profile.enabled {
            bail!("runtime_not_available: {id}: DNS/netd and IPv6 verification required");
        }
        let name = profile.display_name.trim();
        if name.is_empty() || name.chars().count() > 80 || name.chars().any(char::is_control) {
            bail!("invalid_display_name: {id}");
        }
        if profile.endpoint.len() > 2048 || profile.endpoint.chars().any(char::is_whitespace) {
            bail!("invalid_doh_endpoint: {id}");
        }
        let url = reqwest::Url::parse(&profile.endpoint).context("invalid_doh_endpoint")?;
        if url.scheme() != "https"
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.fragment().is_some()
        {
            bail!("invalid_doh_endpoint: {id}: HTTPS without credentials or fragment required");
        }
        if url.port() == Some(0) {
            bail!("invalid_doh_port: {id}");
        }
        if !(250..=30000).contains(&profile.timeout_ms) {
            bail!("invalid_timeout: {id}");
        }
        if profile.bootstrap.is_empty() || profile.bootstrap.len() > 8 {
            bail!("invalid_bootstrap: {id}: specify 1..8 IPv4 addresses");
        }
        let mut bootstrap = BTreeSet::new();
        for ip in &profile.bootstrap {
            if ip.is_unspecified()
                || ip.is_loopback()
                || ip.is_multicast()
                || ip.is_broadcast()
                || !bootstrap.insert(*ip)
            {
                bail!("invalid_bootstrap: {id}: {ip}");
            }
        }
        if profile.apps.len() > 512 {
            bail!("too_many_apps: {id}");
        }
        for package in &profile.apps {
            if !valid_package(package) {
                bail!("invalid_package: {package}");
            }
            if package == "com.android.zdtd.service" {
                bail!("controller_app_excluded: {package}");
            }
            if let Some(other) = assigned.insert(package, id) {
                bail!("package_conflict: {package}: {other}, {id}");
            }
        }
    }
    Ok(())
}

/// The UID map must come from the device, never from a client-supplied UID.
/// Unresolved packages are errors, not silently treated as unassigned.
pub fn validate_uids(document: &ProfileDocument, uids: &BTreeMap<String, u32>) -> Result<()> {
    validate(document)?;
    let mut owners = BTreeMap::<u32, &str>::new();
    for (id, profile) in &document.profiles {
        for package in &profile.apps {
            let uid = *uids
                .get(package)
                .with_context(|| format!("unresolved_package: {package}"))?;
            // Stage 1 only models regular apps of the primary Android user.
            if !(10000..=19999).contains(&uid) {
                bail!("unsupported_uid: {package}: {uid}; primary-user application UID required");
            }
            if let Some(other) = owners.insert(uid, id) {
                if other != id {
                    bail!("shared_uid_conflict: UID {uid}: {other}, {id}");
                }
            }
        }
    }
    Ok(())
}

pub fn packages(document: &ProfileDocument) -> Vec<String> {
    document
        .profiles
        .values()
        .flat_map(|p| p.apps.iter().cloned())
        .collect()
}

fn read_unlocked(path: &Path) -> Result<ProfileDocument> {
    let file = match File::open(path) {
        Ok(file) => file,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(ProfileDocument::default()),
        Err(e) => return Err(e).context("read DNS profile store"),
    };
    let mut bytes = Vec::new();
    file.take((MAX_BYTES + 1) as u64).read_to_end(&mut bytes)?;
    if bytes.len() > MAX_BYTES {
        bail!("dns_profile_store_too_large");
    }
    let document =
        serde_json::from_slice(&bytes).context("invalid DNS profile store; not overwritten")?;
    validate(&document)?;
    Ok(document)
}

pub fn load(path: &Path) -> Result<ProfileDocument> {
    let _guard = STORE_LOCK
        .lock()
        .map_err(|_| anyhow::anyhow!("dns_profile_store_lock"))?;
    read_unlocked(path)
}

/// Replace the document with compare-and-swap semantics. Removing a map entry
/// deletes that draft. Saving never changes netd, DNSCrypt, or firewall state.
pub fn save(path: &Path, mut document: ProfileDocument) -> Result<ProfileDocument> {
    validate(&document)?;
    let _guard = STORE_LOCK
        .lock()
        .map_err(|_| anyhow::anyhow!("dns_profile_store_lock"))?;
    let current = read_unlocked(path)?;
    if document.revision != current.revision {
        bail!("revision_conflict: reload profiles before saving");
    }
    document.revision = current
        .revision
        .checked_add(1)
        .context("revision_overflow")?;
    let bytes = serde_json::to_vec_pretty(&document)?;
    if bytes.len() > MAX_BYTES {
        bail!("dns_profile_store_too_large");
    }
    let parent = path.parent().context("missing store parent")?;
    fs::create_dir_all(parent)?;
    let temporary = path.with_extension("json.tmp");
    // O_NOFOLLOW protects the fixed temporary name from symlink redirection.
    let result = (|| -> Result<()> {
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&temporary)?;
        file.write_all(&bytes)?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        File::open(parent)?.sync_all()?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result?;
    Ok(document)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    static NEXT: AtomicUsize = AtomicUsize::new(0);

    fn fixture() -> ProfileDocument {
        ProfileDocument {
            profiles: BTreeMap::from([(
                "xbox".into(),
                DnsProfile {
                    display_name: "Xbox DNS".into(),
                    endpoint: "https://xbox-dns.ru/dns-query".into(),
                    bootstrap: vec![Ipv4Addr::new(1, 1, 1, 1), Ipv4Addr::new(9, 9, 9, 9)],
                    timeout_ms: 7000,
                    apps: vec!["com.google.android.youtube".into()],
                    enabled: false,
                },
            )]),
            ..ProfileDocument::default()
        }
    }
    fn temp_store() -> std::path::PathBuf {
        std::env::temp_dir()
            .join(format!(
                "zdtd-dns-test-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ))
            .join("profiles.json")
    }
    #[test]
    fn accepts_xbox_doh_and_custom_https_port() {
        let mut doc = fixture();
        validate(&doc).unwrap();
        doc.profiles.get_mut("xbox").unwrap().endpoint =
            "https://dns.example:8443/custom?key=value".into();
        validate(&doc).unwrap();
    }
    #[test]
    fn rejects_unsafe_or_ambiguous_urls() {
        for endpoint in [
            "http://dns.example/dns-query",
            "https://u:p@dns.example/",
            "https://dns.example/#x",
            "https://dns.example:0/",
            "https://dns.example/\n",
        ] {
            let mut doc = fixture();
            doc.profiles.get_mut("xbox").unwrap().endpoint = endpoint.into();
            assert!(validate(&doc).is_err(), "{endpoint}");
        }
    }
    #[test]
    fn rejects_enable_and_unknown_fields() {
        let mut doc = fixture();
        doc.profiles.get_mut("xbox").unwrap().enabled = true;
        assert!(validate(&doc)
            .unwrap_err()
            .to_string()
            .contains("runtime_not_available"));
        let mut value = serde_json::to_value(fixture()).unwrap();
        value["profiles"]["xbox"]["policy"] = "fallback".into();
        assert!(serde_json::from_value::<ProfileDocument>(value).is_err());
    }
    #[test]
    fn rejects_paths_bad_packages_and_bootstrap() {
        let mut doc = fixture();
        let profile = doc.profiles.remove("xbox").unwrap();
        doc.profiles.insert("../escape".into(), profile);
        assert!(validate(&doc).is_err());
        for package in ["com.test;reboot", "com..test", "com.android.zdtd.service"] {
            let mut doc = fixture();
            doc.profiles.get_mut("xbox").unwrap().apps = vec![package.into()];
            assert!(validate(&doc).is_err());
        }
        for ip in ["127.0.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255"] {
            let mut doc = fixture();
            doc.profiles.get_mut("xbox").unwrap().bootstrap = vec![ip.parse().unwrap()];
            assert!(validate(&doc).is_err());
        }
    }
    #[test]
    fn rejects_package_and_shared_uid_conflicts() {
        let mut doc = fixture();
        doc.profiles
            .insert("other".into(), doc.profiles["xbox"].clone());
        assert!(validate(&doc).is_err());
        doc.profiles.get_mut("other").unwrap().apps = vec!["com.example.alias".into()];
        let mut uids = BTreeMap::from([
            ("com.google.android.youtube".into(), 10123),
            ("com.example.alias".into(), 10123),
        ]);
        assert!(validate_uids(&doc, &uids)
            .unwrap_err()
            .to_string()
            .contains("shared_uid_conflict"));
        uids.insert("com.example.alias".into(), 10124);
        validate_uids(&doc, &uids).unwrap();
        uids.remove("com.example.alias");
        assert!(validate_uids(&doc, &uids)
            .unwrap_err()
            .to_string()
            .contains("unresolved_package"));
    }
    #[test]
    fn rejects_system_and_secondary_user_uids() {
        for uid in [0, 1000, 2000, 9999, 99001, 1010123] {
            let uids = BTreeMap::from([("com.google.android.youtube".into(), uid)]);
            assert!(validate_uids(&fixture(), &uids).is_err());
        }
    }
    #[test]
    fn roundtrip_delete_and_stale_writer() {
        let path = temp_store();
        let first = save(&path, fixture()).unwrap();
        assert_eq!(load(&path).unwrap(), first);
        assert_eq!(first.revision, 1);
        assert!(save(&path, fixture())
            .unwrap_err()
            .to_string()
            .contains("revision_conflict"));
        let mut next = first;
        next.profiles.clear();
        assert!(save(&path, next).unwrap().profiles.is_empty());
        fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }
    #[test]
    fn never_overwrites_corrupt_store() {
        let path = temp_store();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, b"broken").unwrap();
        assert!(save(&path, fixture()).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"broken");
        fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }
    #[test]
    fn refuses_symlink_temporary_file() {
        let path = temp_store();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        let target = path.with_extension("sentinel");
        fs::write(&target, b"keep").unwrap();
        std::os::unix::fs::symlink(&target, path.with_extension("json.tmp")).unwrap();
        assert!(save(&path, fixture()).is_err());
        assert_eq!(fs::read(target).unwrap(), b"keep");
        fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }
}
