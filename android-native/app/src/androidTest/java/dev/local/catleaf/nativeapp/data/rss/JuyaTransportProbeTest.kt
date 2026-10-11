package dev.local.catleaf.nativeapp.data.rss

import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.network.HttpClients
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import okhttp3.Protocol
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Diagnostic only: no subscriptions, credentials or production timeout changes. */
@ManualProbe
class JuyaTransportProbeTest {
    @Test fun verify_existing_juya_with_production_feed_fetcher() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, dev.local.catleaf.nativeapp.data.local.CatLeafDatabase::class.java).build()
        val scope = "juya-network-isolated"
        try {
            db.feeds().upsert(dev.local.catleaf.nativeapp.data.local.FeedEntity(scope, "juya", "https://daily.juya.uk/rss.xml", "橘鸦AI早报", "科技", "failed", null))
            val store = dev.local.catleaf.nativeapp.data.LocalFeedStore(db, scope, dev.local.catleaf.nativeapp.data.local.CacheCleanup(db, context))
            val started = System.nanoTime()
            val verified = store.verify("juya")
            val row = db.feeds().list(scope).single()
            val articles = db.articles().listByFeed(scope, "juya", 200, 0)
            val result = JSONObject().put("success", verified.isSuccess).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                .put("feedCount", db.feeds().list(scope).size).put("status", row.status).put("items", articles.size)
                .put("error", verified.exceptionOrNull()?.toString().orEmpty())
            File(context.getExternalFilesDir(null), "juya-production.json").writeText(result.toString(2))
            org.junit.Assert.assertTrue(result.toString(), verified.isSuccess)
            org.junit.Assert.assertTrue(articles.isNotEmpty())
            org.junit.Assert.assertEquals("ok", row.status)
        } finally {
            db.close()
        }
    }

    @Test fun compare_public_feed_transport() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("mode") ?: "default"
        val timeout = args.getString("timeout")?.toLong() ?: 15L
        val result = JSONObject().put("mode", mode).put("timeoutSeconds", timeout)
        val started = System.nanoTime()
        var bytes = 0L
        val builder = HttpClients.forPublic().newBuilder().callTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(HttpClients.GENERAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .eventListener(object : okhttp3.EventListener() {
                override fun connectStart(call: okhttp3.Call, address: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                    result.put("address", address.toString())
                }
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                    result.put("callFailure", ioe.toString())
                }
            })
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder().apply { if (mode == "identity") header("Accept-Encoding", "identity") }.build()
                val response = chain.proceed(request)
                result.put("headersMs", (System.nanoTime() - started) / 1_000_000)
                    .put("protocol", response.protocol.toString()).put("status", response.code)
                    .put("contentLength", response.header("Content-Length")).put("encoding", response.header("Content-Encoding"))
                val body = response.body!!
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                        if (it > 0) {
                            bytes += it
                            result.put("lastReadMs", (System.nanoTime() - started) / 1_000_000)
                        }
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            }
        if (mode == "http1") builder.protocols(listOf(Protocol.HTTP_1_1))
        val fetched = PublicFetch(builder.build()).fetch("https://daily.juya.uk/rss.xml")
        val parsed = fetched.mapCatching { FeedParser.parse(FeedXmlDecoder.decode(it.bytes, it.contentType).getOrThrow(), "https://daily.juya.uk/rss.xml").getOrThrow() }
        result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000).put("wireBytes", bytes)
            .put("decodedBytes", fetched.getOrNull()?.bytes?.size ?: 0).put("items", parsed.getOrNull()?.items?.size ?: 0)
            .put("success", parsed.isSuccess).put("error", parsed.exceptionOrNull()?.toString().orEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "juya-$mode-$timeout.json").writeText(result.toString(2))
        android.util.Log.i("JuyaTransportProbe", result.toString())
    }
}
