package com.android.zdtd.service.proxy

import android.content.Context
import org.json.JSONArray

/** App-private catalog, separate from Opera's root configuration. */
class ProxyCatalogStore(context: Context) {
  private val preferences = context.getSharedPreferences("proxy_catalog", Context.MODE_PRIVATE)
  fun read(): List<SavedProxy> {
    val array = JSONArray(preferences.getString("entries", "[]"))
    return (0 until array.length()).map { SavedProxy(array.getString(it)) }
  }
  fun write(entries: List<SavedProxy>) {
    require(entries.size <= MAX_PROXY_ENTRIES) { "В каталоге больше $MAX_PROXY_ENTRIES серверов" }
    check(preferences.edit().putString("entries", JSONArray(entries.map { it.url }).toString()).commit()) {
      "Не удалось сохранить список"
    }
  }
}
