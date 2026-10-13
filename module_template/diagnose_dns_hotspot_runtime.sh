#!/system/bin/sh
# Read-only 75-second DNS/hotspot runtime capture for rooted Android.
# Installed with the module; no separate download is required.

set -u

duration="${1:-75}"
case "$duration" in
  *[!0-9]*|'') duration=75 ;;
esac
if [ "$duration" -lt 30 ] || [ "$duration" -gt 300 ]; then
  duration=75
fi

module_root=
for candidate in /data/adb/modules/ZDT-D /data/adb/modules/*; do
  [ -f "$candidate/module.prop" ] || continue
  module_id="$(sed -n 's/^id=//p' "$candidate/module.prop" | head -n 1)"
  case "$module_id" in
    ZDT-D-Test|ZDT-D) module_root="$candidate"; break ;;
  esac
done
if [ -z "$module_root" ]; then
  echo "FAIL: ZDT-D module was not found"
  exit 1
fi

stamp="$(date +%Y%m%d_%H%M%S 2>/dev/null || echo now)"
out="/sdcard/Download/ZDTD_dns_hotspot_$stamp"
mkdir -p "$out" || exit 1
report="$out/REPORT.txt"
timeline="$out/timeline.txt"
pids=""

say() {
  echo "$*"
  echo "$*" >> "$report"
}

capture() {
  name="$1"
  shift
  "$@" > "$out/$name.txt" 2>&1 || true
}

cleanup() {
  for pid in $pids; do
    kill "$pid" >/dev/null 2>&1 || true
  done
  wait >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

: > "$report"
: > "$timeline"
say "ZDT-D extended DNS/hotspot runtime test"
say "module=$module_root"
say "duration_seconds=$duration"
say "output=$out"
say "NOTE: PCAP files contain packet headers and may reveal destination addresses; do not publish them openly."

config="$module_root/working_folder/dnsprofiles/profiles.json"
applied="$module_root/working_folder/vpn_netd/applied.json"
daemon_log="$module_root/log/zdtd.log"
[ -f "$daemon_log" ] || daemon_log="$module_root/log/daemon.log"
[ -f "$config" ] && cp -f "$config" "$out/dns_profiles.json" 2>/dev/null || true
[ -f "$applied" ] && cp -f "$applied" "$out/vpn_netd_applied.json" 2>/dev/null || true
[ -f "$daemon_log" ] && tail -n 2000 "$daemon_log" > "$out/daemon_tail_before.txt" 2>/dev/null || true
capture module_prop cat "$module_root/module.prop"
capture properties getprop
capture packages_with_uids sh -c "cmd package list packages -U 2>&1 | grep -Ei 'openai|chatgpt|gemini|bard|grok|xai|googlequicksearchbox' || true"
capture addr_before ip -br addr
capture rules_before ip rule show
capture routes_before ip -4 route show table all
capture routes6_before ip -6 route show table all
capture links_before ip -s link show
capture sockets_before ss -lntup
capture ndc_network_before ndc network list
capture tethering_before dumpsys tethering
capture wifi_before dumpsys wifi
capture connectivity_before dumpsys connectivity
capture iptables_before iptables-save
capture ip6tables_before ip6tables-save

say ""
say "Local DNS probes (real UDP query for example.com):"
dns_addresses="$(ss -lnup 2>/dev/null | sed -n 's/.*\(10\.253\.240\.[0-9][0-9]*\):53.*/\1/p' | sort -u)"
if [ -z "$dns_addresses" ]; then
  say "WARN: no 10.253.240.x:53 listener found (expected when tethering suspension is active)."
else
  nslookup_bin="$(command -v nslookup 2>/dev/null || true)"
  [ -x /data/data/com.termux/files/usr/bin/nslookup ] && nslookup_bin=/data/data/com.termux/files/usr/bin/nslookup
  dig_bin="$(command -v dig 2>/dev/null || true)"
  [ -x /data/data/com.termux/files/usr/bin/dig ] && dig_bin=/data/data/com.termux/files/usr/bin/dig
  nc_bin="$(command -v nc 2>/dev/null || true)"
  for dns_ip in $dns_addresses; do
    probe_file="$out/dns_probe_${dns_ip}.txt"
    if [ -n "$nslookup_bin" ]; then
      "$nslookup_bin" example.com "$dns_ip" > "$probe_file" 2>&1
      rc=$?
    elif [ -n "$dig_bin" ]; then
      "$dig_bin" "@$dns_ip" example.com A +time=3 +tries=1 > "$probe_file" 2>&1
      rc=$?
    elif [ -n "$nc_bin" ]; then
      # Binary DNS request: standard query, one A question for example.com.
      printf '\021\127\001\000\000\001\000\000\000\000\000\000\007example\003com\000\000\001\000\001' \
        | "$nc_bin" -u -w 3 "$dns_ip" 53 > "$probe_file" 2>&1
      bytes="$(wc -c < "$probe_file" 2>/dev/null || echo 0)"
      [ "$bytes" -ge 12 ]
      rc=$?
    else
      echo "No nslookup, dig, or nc binary is available." > "$probe_file"
      rc=127
    fi
    if [ "$rc" -eq 0 ]; then
      say "PASS: DNS response received from $dns_ip"
    elif [ "$rc" -eq 127 ]; then
      say "WARN: cannot send shell DNS probe; use the Test button in the DNS profiles screen."
    else
      say "FAIL: DNS query to $dns_ip failed (details: $probe_file)"
    fi
  done
fi

capture logcat_initial logcat -d -v threadtime -t 1200
logcat -v threadtime > "$out/logcat_live.txt" 2>&1 &
pids="$pids $!"

tcpdump_bin="$(command -v tcpdump 2>/dev/null || true)"
[ -x /data/data/com.termux/files/usr/bin/tcpdump ] && tcpdump_bin=/data/data/com.termux/files/usr/bin/tcpdump
tuns="$(ip -o link show 2>/dev/null | sed -n 's/^[0-9][0-9]*: \(zdt_dns[^:@]*\).*/\1/p')"
if [ -n "$tcpdump_bin" ] && [ -n "$tuns" ]; then
  for tun in $tuns; do
    "$tcpdump_bin" -i "$tun" -nn -s 96 -w "$out/${tun}.pcap" > "$out/${tun}_tcpdump.txt" 2>&1 &
    pids="$pids $!"
    say "Packet capture started on $tun (96-byte snapshots)."
  done
else
  say "INFO: tcpdump or zdt_dns TUN unavailable; interface counters will still be tracked."
fi

(
  elapsed=0
  while [ "$elapsed" -le "$duration" ]; do
    echo "===== +${elapsed}s $(date 2>/dev/null) ====="
    echo "ip_forward=$(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null || echo unknown)"
    echo "wifi_ap_state=$(getprop wlan.softap.status 2>/dev/null)"
    ip -br addr 2>&1
    for tun in $tuns; do
      ip -s link show dev "$tun" 2>&1
    done
    dumpsys tethering 2>&1 | grep -Ei 'tether|softap|error|upstream|state' | head -n 120
    echo
    sleep 5
    elapsed=$((elapsed + 5))
  done
) >> "$timeline" 2>&1 &
pids="$pids $!"

say ""
say "NOW: during the next $duration seconds:"
say "1) Open Gemini, Grok and ChatGPT and send one short test message in each."
say "2) Try to enable the system Wi-Fi hotspot and, if possible, connect one client."
say "3) Return here and wait for capture completion."
sleep "$duration"
cleanup
pids=""

capture addr_after ip -br addr
capture rules_after ip rule show
capture routes_after ip -4 route show table all
capture routes6_after ip -6 route show table all
capture links_after ip -s link show
capture sockets_after ss -lntup
capture ndc_network_after ndc network list
capture tethering_after dumpsys tethering
capture wifi_after dumpsys wifi
capture connectivity_after dumpsys connectivity
capture iptables_after iptables-save
capture ip6tables_after ip6tables-save
[ -f "$daemon_log" ] && tail -n 3000 "$daemon_log" > "$out/daemon_tail_after.txt" 2>/dev/null || true

grep -Ei 'dnsprofiles|vpn_netd|tether|softap|hostapd|ip_forward|DnsResolver|network.*(lost|unavailable)|exception|fatal|error' \
  "$out/logcat_live.txt" > "$out/logcat_relevant.txt" 2>/dev/null || true
grep -Ei 'dnsprofiles|vpn_netd|tether|softap|ip_forward|error|failed|warn' \
  "$out/daemon_tail_after.txt" > "$out/daemon_relevant.txt" 2>/dev/null || true

say ""
say "Capture complete."
say "Report folder: $out"
say "Share REPORT.txt, timeline.txt, *_relevant.txt and the before/after text files."
say "Share PCAP files only privately; they contain network metadata."
