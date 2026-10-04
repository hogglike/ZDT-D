package com.android.zdtd.service.diagnostics.connection

/** Multiple independent failures are evidence of restrictions, not proof of their cause. */
object HealthRules {
  fun summary(domestic: List<Boolean>, foreign: List<Boolean>): String = when {
    domestic.isEmpty() || foreign.isEmpty() -> "Недостаточно данных"
    domestic.all { it } && foreign.all { it } -> "Российские и зарубежные проверки доступны"
    domestic.size >= 2 && foreign.size >= 2 && domestic.all { it } && foreign.none { it } ->
      "Возможны ограничения зарубежного доступа. Причина не установлена"
    domestic.none { it } && foreign.none { it } -> "Проверенные сервисы недоступны. Проверь сеть и DNS"
    else -> "Часть сервисов недоступна. Открой подробности"
  }

  fun httpOk(code: Int, expected: Int?): Boolean = if (expected != null) code == expected else code in 200..299
}
