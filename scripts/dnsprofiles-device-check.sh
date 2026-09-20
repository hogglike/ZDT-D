#!/system/bin/sh
# Read-only capability report. Does not create networks or modify DNS/firewall.
# Run: su -c 'sh /sdcard/Download/dnsprofiles-device-check.sh'
# No DNS queries, credentials, full package list, or query history are collected.

if ! command -v timeout >/dev/null 2>&1; then
  echo 'Cannot run bounded checks: timeout utility is unavailable.'
  exit 1
fi

check() {
  echo
  echo "[$1]"
  shift
  timeout 8 "$@" 2>&1
  zdns_result=$?
  echo "exit_code=$zdns_result"
}

echo 'ZDT-D DNS Profiles capability report v1 (read-only)'
check root id -u
check android_sdk getprop ro.build.version.sdk
check android_release getprop ro.build.version.release
check architecture getprop ro.product.cpu.abi
check selinux getenforce
check private_dns_mode settings get global private_dns_mode
check resolver_service service check dnsresolver
check netd_service service check netd
# No arguments: command discovery / usage only. Never call setnetdns here.
check ndc_resolver_command ndc resolver
check ndc_network_command ndc network
check ipv6_rules ip6tables -w 2 -t filter -S ZDT_VPN_NETD_V6
check ipv6_owner_support ip6tables -m owner -h
check tun_device ls -l /dev/tun /dev/net/tun
check youtube_uid cmd package list packages -U com.google.android.youtube
check chrome_uid cmd package list packages -U com.android.chrome
check singbox_version /data/adb/modules/ZDT-D/bin/sing-box version
check dnscrypt_version /data/adb/modules/ZDT-D/bin/dnscrypt -version

echo
echo 'Report complete. This does not prove DNS isolation or authorize activation.'
