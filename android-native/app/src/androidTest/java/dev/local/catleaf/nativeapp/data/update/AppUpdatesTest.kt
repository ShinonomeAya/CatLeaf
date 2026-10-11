package dev.local.catleaf.nativeapp.data.update

import dev.local.catleaf.nativeapp.data.network.HttpClients
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AppUpdatesTest {
    private val server = MockWebServer()
    private val packageId = "dev.local.catleaf.nativeapp"
    private val valid = """{"packageId":"dev.local.catleaf.nativeapp","published":true,"version":"0.1.20","versionCode":21,"downloadUrl":"https://example.org/download/","releaseNotes":"修复阅读问题"}"""
    private val checker = AppUpdates(PublicFetch(targetPolicy = { Result.success(it) }))

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }
    private fun check(body: String, installed: Long = 20): UpdateCheck {
        server.enqueue(MockResponse().setBody(body))
        return checker.check(server.url("/release.json").toString(), packageId, installed)
    }

    @Test fun newer_equal_and_older_use_code_and_never_send_credentials() {
        val available = check(valid) as UpdateCheck.Available
        assertEquals("修复阅读问题", available.release.notes)
        assertEquals("https://example.org/download/", available.release.downloadUrl)
        assertEquals(UpdateCheck.Latest, check(valid, 21))
        assertEquals(UpdateCheck.Latest, check(valid, 22))
        repeat(3) {
            val request = server.takeRequest()
            for (header in listOf("Authorization", "Cookie", "x-admin-secret", "Referer")) assertEquals(null, request.getHeader(header))
        }
    }

    @Test fun invalid_or_unpublished_metadata_never_claims_latest_or_opens_download() {
        for (body in listOf(
            "<html>error</html>", valid + " trailing", "[]",
            valid.replace("\"published\":true", "\"published\":false"),
            valid.replace(packageId, "other.package"),
            valid.replace("\"versionCode\":21", "\"versionCode\":\"21\""),
            valid.replace("\"versionCode\":21", "\"versionCode\":21.5"),
            valid.replace("\"versionCode\":21", "\"versionCode\":0"),
            valid.replace("\"versionCode\":21", "\"versionCode\":2147483648"),
            valid.replace("0.1.20", ""),
            valid.replace("https://example.org/download/", "http://example.org/download/"),
            valid.replace("https://example.org/download/", "https://user:secret@example.org/"),
            valid.replace("https://example.org/download/", "https://127.0.0.1/"),
        )) assertTrue(body, check(body) is UpdateCheck.Failed)
    }

    @Test fun declared_and_chunked_oversized_manifests_fail() {
        val oversized = "x".repeat(AppUpdates.MAX_MANIFEST_BYTES + 1)
        for (chunked in listOf(false, true)) {
            val response = MockResponse()
            server.enqueue(if (chunked) response.setChunkedBody(oversized, 1024) else response.setBody(oversized))
            val result = checker.check(server.url("/release.json").toString(), packageId, 20)
            assertTrue(result is UpdateCheck.Failed)
        }
    }

    @Test fun network_failures_are_distinct_from_latest() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(checker.check(server.url("/release.json").toString(), packageId, 20) is UpdateCheck.Failed)
        server.enqueue(MockResponse().setBody(valid).setBodyDelay(500, TimeUnit.MILLISECONDS))
        val slow = AppUpdates(PublicFetch(client = HttpClients.forPublic().newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS).build(), targetPolicy = { Result.success(it) }))
        val result = slow.check(server.url("/release.json").toString(), packageId, 20) as UpdateCheck.Failed
        assertEquals("请求超时", result.reason)
    }

    @Test fun redirect_into_local_network_is_blocked_before_followup() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://127.0.0.1/private"))
        val startUrl = server.url("/release.json").toString()
        val controlled = AppUpdates(PublicFetch(targetPolicy = {
            if (it == startUrl) Result.success(it) else dev.local.catleaf.nativeapp.data.network.EndpointPolicy.requirePublicFetchTarget(it)
        }))
        assertTrue(controlled.check(startUrl, packageId, 20) is UpdateCheck.Failed)
        assertEquals(1, server.requestCount)
    }
}
