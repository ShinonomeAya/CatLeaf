package dev.local.catleaf.nativeapp.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseTest {
    @Test fun missing_endpoint_is_not_reported_as_latest() {
        assertEquals(UpdateCheck.NotConfigured, AppUpdates().check("", "dev.local.catleaf.nativeapp", 20))
    }

    @Test fun production_rejects_insecure_endpoints_before_requesting() {
        assertTrue(AppUpdates().check("http://127.0.0.1/update.json", "dev.local.catleaf.nativeapp", 20) is UpdateCheck.Failed)
    }

    @Test fun version_code_determines_updates_even_when_version_names_sort_differently() {
        val newer = UpdateRelease("0.1.9", 21, "https://example.org/download/", "")
        assertTrue(newer.compareWith(20) is UpdateCheck.Available)
        assertEquals(UpdateCheck.Latest, newer.compareWith(21))
        assertEquals(UpdateCheck.Latest, newer.copy(version = "9.0", code = 19).compareWith(20))
    }
}
