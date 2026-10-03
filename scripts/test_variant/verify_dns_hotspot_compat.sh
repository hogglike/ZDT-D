#!/system/bin/sh
# Read-only on-device smoke test for the per-app DNS/tethering boundary.
# Run after starting ZDT-D Test:
#   su -c 'sh /sdcard/Download/verify_dns_hotspot_compat.sh'

set -u

module_root=
for candidate in /data/adb/modules/*; do
  [ -f "$candidate/module.prop" ] || continue
  module_id="$(sed -n 's/^id=//p' "$candidate/module.prop" | head -n 1)"
  case "$module_id" in
    ZDT-D-Test|ZDT-D) module_root=$candidate; break ;;
  esac
done
if [ -z "$module_root" ]; then
  echo "FAIL: ZDT-D module was not found"
  exit 1
fi

failures=0
warnings=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; failures=$((failures + 1)); }
warn() { echo "WARN: $*"; warnings=$((warnings + 1)); }

echo "ZDT-D DNS/hotspot compatibility smoke test"
echo "module: $module_root"

applied="$module_root/working_folder/vpn_netd/applied.json"
config="$module_root/working_folder/dnsprofiles/profiles.json"
suspended=false
if [ -s "$config" ] && grep -q '"suspend_for_tethering"[[:space:]]*:[[:space:]]*true' "$config"; then
  suspended=true
fi

if [ "$suspended" = true ]; then
  echo "INFO: DNS profiles are suspended for tethering"
  if [ -s "$applied" ] && grep -q '"owner_program"[[:space:]]*:[[:space:]]*"dnsprofiles"' "$applied"; then
    fail "dnsprofiles remains in vpn_netd applied state; restart ZDT-D"
  else
    pass "dnsprofiles is absent from vpn_netd applied state"
  fi
  if ip -o link show 2>/dev/null | grep -q 'zdt_dns'; then
    fail "zdt_dns interface remains; restart ZDT-D"
  else
    pass "no DNS TUN is active"
  fi
  if ss -lnup 2>/dev/null | grep -qE '10\.253\.240\.[0-9]+:53'; then
    fail "profile DNS listener remains active; restart ZDT-D"
  else
    pass "no profile DNS listener is active"
  fi
  echo "Result: failures=$failures warnings=$warnings"
  [ "$failures" -eq 0 ]
  exit $?
fi

if [ -s "$applied" ] && grep -q '"owner_program"[[:space:]]*:[[:space:]]*"dnsprofiles"' "$applied"; then
  pass "dnsprofiles is present in vpn_netd applied state"
else
  fail "dnsprofiles is missing from vpn_netd applied state"
fi

if ip -o -4 addr show dev lo 2>/dev/null | grep -q '10\.253\.240\.'; then
  fail "synthetic DNS address is still attached to loopback"
else
  pass "loopback has no synthetic 10.253.240.x address"
fi

tuns="$(ip -o link show 2>/dev/null | sed -n 's/^[0-9][0-9]*: \(zdt_dns[^:@]*\).*/\1/p')"
if [ -n "$tuns" ]; then
  pass "DNS TUN present: $(echo "$tuns" | tr '\n' ' ')"
else
  fail "no zdt_dns TUN is present"
fi

default_routes="$(ip -4 route show table all 2>/dev/null | grep -E '^default .*dev zdt_dns' || true)"
if [ -n "$default_routes" ]; then
  pass "DNS profile owns the full IPv4 route required by UID-bound apps"
else
  fail "DNS profile has no IPv4 default route; selected apps may lose connectivity"
fi

profile_routes="$(ip -4 route show table all 2>/dev/null | grep -E '10\.253\.240\.[0-9]+/30 .*dev zdt_dns' || true)"
if [ -n "$profile_routes" ]; then
  pass "profile-local DNS route is present"
else
  warn "profile-local /30 route was not found in ip route table all"
fi

if ss -lnup 2>/dev/null | grep -qE '10\.253\.240\.[0-9]+:53'; then
  pass "profile DNS listener is active"
else
  fail "profile DNS listener on 10.253.240.x:53 is missing"
fi

if [ -e "$module_root/working_folder/ip_forward_owned" ]; then
  warn "ZDT-D owns net.ipv4.ip_forward; confirm this is intentional in advanced settings"
else
  pass "ZDT-D does not claim ownership of system IPv4 forwarding"
fi

ip_forward="$(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null || echo unknown)"
echo "INFO: net.ipv4.ip_forward=$ip_forward (Android may change it while tethering is active)"

if dumpsys tethering >/dev/null 2>&1; then
  pass "Android tethering service responds"
else
  warn "dumpsys tethering is unavailable"
fi

echo "Result: failures=$failures warnings=$warnings"
[ "$failures" -eq 0 ]
