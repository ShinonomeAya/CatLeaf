package dev.local.catleaf.nativeapp.data.rss

import dev.local.catleaf.nativeapp.data.network.HttpClients
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LocalFeedFetcherTest {
    @Test fun progressing_rss_download_survives_the_old_total_deadline() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val xml = "<rss><channel><description>${"x".repeat(10 * 1024)}</description></channel></rss>"
            server.enqueue(MockResponse().setHeader("Content-Type", "application/rss+xml; charset=utf-8")
                .setBody(xml).throttleBody(2048, 4, TimeUnit.SECONDS))
            val client = HttpClients.forPublicFeed()
            assertEquals(15_000, client.connectTimeoutMillis)
            assertEquals(15_000, client.readTimeoutMillis)
            assertEquals(60_000, client.callTimeoutMillis)
            assertEquals(15_000, HttpClients.forPublic().callTimeoutMillis)
            val fetcher = LocalFeedFetcher(PublicFetch(client = client, targetPolicy = { Result.success(it) }))
            val started = System.nanoTime()
            assertEquals(xml, fetcher.fetchXml(server.url("/rss.xml").toString()).getOrThrow())
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) >= 15)
            val request = server.takeRequest()
            for (header in listOf("Authorization", "Cookie", "x-admin-secret")) assertEquals(null, request.getHeader(header))
        } finally {
            server.shutdown()
        }
    }
}
