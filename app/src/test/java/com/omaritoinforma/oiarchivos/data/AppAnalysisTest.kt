package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class AppAnalysisTest {
    private fun risk(label: String, granted: Set<SensitiveGroup> = emptySet(), requested: Set<SensitiveGroup> = emptySet()) =
        AppRisk(label, "com.$label", false, 1000, 34, granted, requested)

    @Test
    fun classifiesPermissionsIntoGroupsAndSeparatesGrantedOnes() {
        val asked = listOf("android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.INTERNET")
        val (granted, onlyRequested) = AppAnalysis.classify(asked, setOf("android.permission.CAMERA", "android.permission.INTERNET"))
        assertEquals(setOf(SensitiveGroup.CAMERA), granted)
        assertEquals(setOf(SensitiveGroup.LOCATION), onlyRequested)
    }

    @Test
    fun harmlessPermissionsAreIgnoredAndOneGrantedPermissionCountsForTheGroup() {
        assertEquals(emptySet<SensitiveGroup>() to emptySet<SensitiveGroup>(), AppAnalysis.classify(listOf("android.permission.INTERNET", "android.permission.VIBRATE"), emptySet()))
        val (granted, onlyRequested) = AppAnalysis.classify(listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"), setOf("android.permission.ACCESS_COARSE_LOCATION"))
        assertEquals(setOf(SensitiveGroup.LOCATION), granted)
        assertTrue(onlyRequested.isEmpty())
    }

    @Test
    fun everyPermissionBelongsToOneGroupOnly() {
        val all = SensitiveGroup.entries.flatMap { it.permissions }
        assertEquals("Un permiso está en dos grupos", all.size, all.toSet().size)
    }

    @Test
    fun rankPutsTheMostExposedAppsFirstAndCountsPerGroup() {
        val a = risk("a", granted = setOf(SensitiveGroup.CAMERA))
        val b = risk("b", granted = setOf(SensitiveGroup.CAMERA, SensitiveGroup.SMS, SensitiveGroup.LOCATION))
        val c = risk("c", requested = setOf(SensitiveGroup.CAMERA, SensitiveGroup.MICROPHONE))
        val d = risk("d")
        assertEquals(listOf("b", "a", "c", "d"), AppAnalysis.rank(listOf(d, c, a, b)).map { it.label })
        val counts = AppAnalysis.counts(listOf(a, b, c, d))
        assertEquals(3, counts[SensitiveGroup.CAMERA])
        assertEquals(1, counts[SensitiveGroup.MICROPHONE])
        assertNull(counts[SensitiveGroup.CALENDAR])
    }
}
