use anyhow::{Context, Result};
use std::fs;

use crate::{settings, shell};

fn ip_forward_owner_marker() -> std::path::PathBuf {
    settings::working_root_path().join("ip_forward_owned")
}

fn ipv4_forward_is_enabled() -> Result<bool> {
    let raw = fs::read_to_string("/proc/sys/net/ipv4/ip_forward")
        .context("read net.ipv4.ip_forward")?;
    Ok(raw.trim() == "1")
}

fn should_claim_forwarding(already_enabled: bool, already_owned: bool) -> bool {
    already_owned || !already_enabled
}

/// Manage IPv4 forwarding without clobbering state owned by Android tethering,
/// VPN Hotspot or another root tool.
///
/// Enabling from ZDT-D records ownership. Disabling only writes 0 when ZDT-D
/// previously enabled forwarding itself. If the marker is absent, "off" means
/// "do not manage" and the current system value is preserved.
pub fn set_ipv4_forward(enabled: bool) -> Result<()> {
    let marker = ip_forward_owner_marker();

    if enabled {
        let already_owned = marker.is_file();
        let already_enabled = ipv4_forward_is_enabled()?;
        if !already_enabled {
            let arg = "net.ipv4.ip_forward=1".to_string();
            shell::okv("sysctl", &["-w".to_string(), arg])
                .context("sysctl net.ipv4.ip_forward=1")?;
        }
        if should_claim_forwarding(already_enabled, already_owned) {
            if let Some(parent) = marker.parent() {
                let _ = fs::create_dir_all(parent);
            }
            fs::write(&marker, b"1\n")
                .with_context(|| format!("write {}", marker.display()))?;
        } else {
            log::info!(
                "sysctl: ip_forward was already enabled externally -> leaving ownership external"
            );
        }
        return Ok(());
    }

    if !marker.is_file() {
        log::info!(
            "sysctl: ip_forward is not owned by ZDT-D -> preserving current system state"
        );
        return Ok(());
    }

    let arg = "net.ipv4.ip_forward=0".to_string();
    shell::okv("sysctl", &["-w".to_string(), arg])
        .context("sysctl net.ipv4.ip_forward=0")?;
    let _ = fs::remove_file(&marker);
    Ok(())
}

pub fn sync_ipv4_forward_from_settings_best_effort() {
    let st = match settings::load_api_settings() {
        Ok(st) => st,
        Err(e) => {
            log::warn!("sysctl: failed to load settings for ip_forward sync: {e:#}");
            return;
        }
    };
    if let Err(e) = set_ipv4_forward(st.ip_forward_enabled) {
        log::warn!(
            "sysctl: failed to sync net.ipv4.ip_forward setting={}: {e:#}",
            st.ip_forward_enabled
        );
    }
}

pub fn apply_start_settings_best_effort() {
    sync_ipv4_forward_from_settings_best_effort();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forwarding_ownership_only_tracks_changes_made_by_zdtd() {
        assert!(should_claim_forwarding(false, false));
        assert!(should_claim_forwarding(true, true));
        assert!(!should_claim_forwarding(true, false));
    }
}
