package com.android.zdtd.service.diagnostics.connection

import org.junit.Assert.*
import org.junit.Test

class HealthRulesTest {
  @Test fun restrictionsRequireIndependentEvidence() {
    assertTrue(HealthRules.summary(listOf(true, true), listOf(false, false)).startsWith("Возможны ограничения"))
    assertFalse(HealthRules.summary(listOf(true), listOf(false)).startsWith("Возможны ограничения"))
    assertFalse(HealthRules.summary(listOf(true, false), listOf(false, false)).startsWith("Возможны ограничения"))
  }
  @Test fun partialFailuresAreNotHealthy() {
    assertTrue(HealthRules.summary(listOf(true, true), listOf(true, false)).startsWith("Часть"))
    assertTrue(HealthRules.summary(listOf(false, false), listOf(false, false)).startsWith("Проверенные сервисы недоступны"))
    assertEquals("Недостаточно данных", HealthRules.summary(emptyList(), listOf(true)))
  }
  @Test fun captiveRedirectAndWrong204AreFailures() {
    assertTrue(HealthRules.httpOk(204, 204))
    assertFalse(HealthRules.httpOk(200, 204))
    assertFalse(HealthRules.httpOk(302, 204))
    assertFalse(HealthRules.httpOk(403, 200))
    assertFalse(HealthRules.httpOk(302, null))
  }
}
