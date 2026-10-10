package com.android.zdtd.service.watch

/** An empty Android VPN allowlist means every app. Always reject it explicitly. */
internal object WatchDnsPolicy {
  fun allowedPackages(requested: Set<String>, installed: Set<String>, hostPackage: String): Set<String> {
    val result = requested.filterTo(linkedSetOf()) { it in installed && it != hostPackage }
    require(result.isNotEmpty()) { "Выбери хотя бы одно установленное приложение для связи с часами" }
    return result
  }

  fun requireProfile(ready: Boolean, port: Int): Int {
    check(ready && port in 1024..65535) {
      "DNS-профиль не запущен. Обнови APK и модуль до mod32, включи профиль и перезапусти ZDT-D"
    }
    return port
  }

  fun suggestedPackage(packageName: String, label: String): Boolean {
    if (!packageName.startsWith("com.samsung.")) return false
    val value = "$packageName $label".lowercase()
    return "watchmanager" in value || "wearable" in value || "watch7" in value || "watch 7" in value
  }
}
