package dev.local.catleaf.nativeapp.data.opml

import dev.local.catleaf.nativeapp.data.FeedRegistry
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlRetryTest {
    private class RetryRegistry : FeedRegistry {
        var reconcileFails = false
        var knownReads = 0
        var adds = 0
        val addUrls = mutableListOf<String>()
        val rejectedUrls = mutableSetOf<String>()
        val verified = mutableListOf<String>()
        override suspend fun local() = emptyList<FeedEntity>()
        override suspend fun knownUrls(): Result<List<String>> {
            knownReads++
            return if (reconcileFails) Result.failure(IllegalStateException("isolated lookup failure")) else Result.success(emptyList())
        }
        override suspend fun add(url: String, category: String, customTitle: String): Result<FeedRegistry.AddResult> {
            adds++
            addUrls += url
            if (url in rejectedUrls) return Result.failure(IllegalStateException("isolated repeated failure"))
            return Result.success(FeedRegistry.AddResult(url, true))
        }
        override suspend fun verify(feedId: String): Result<Unit> { verified += feedId; return Result.success(Unit) }
        override suspend fun refresh() = Result.success(emptyList<FeedEntity>())
        override suspend fun updateMeta(feedId: String, category: String, customTitle: String) = Result.success(Unit)
        override suspend fun delete(feedId: String) = Result.success(Unit)
    }

    @Test fun failed_reconciliation_does_not_prevent_verifying_an_already_saved_id() = runBlocking {
        val registry = RetryRegistry().apply { reconcileFails = true }
        val saved = OpmlImporter.ItemResult("已保存", "https://feeds.example.org/saved", "",
            OpmlImporter.Outcome.Added("saved-id", false, "订阅地址返回 503"))
        val failed = OpmlImporter.ItemResult("未保存", "https://feeds.example.org/failed", "", OpmlImporter.Outcome.Failed("添加失败"))
        val report = OpmlImporter(registry).retryUnresolved(OpmlImporter.Report(listOf(saved, failed)))
        assertEquals(listOf("saved-id"), registry.verified)
        assertTrue((report.items[0].outcome as OpmlImporter.Outcome.Added).verified)
        assertEquals(failed, report.items[1])
        assertEquals(0, registry.adds)
        assertEquals(1, report.retryable)
    }

    @Test fun retries_are_limited_to_200_and_keep_later_items_for_the_next_attempt() = runBlocking {
        val registry = RetryRegistry()
        val items = (1..300).map { index ->
            OpmlImporter.ItemResult("源$index", "https://feeds.example.org/$index", "", OpmlImporter.Outcome.Failed("添加失败"))
        }
        val importer = OpmlImporter(registry)
        val first = importer.retryUnresolved(OpmlImporter.Report(items))
        assertEquals(200, registry.adds)
        assertEquals(200, first.added)
        assertEquals(100, first.retryable)
        assertEquals(items.drop(200), first.items.drop(200))
        val second = importer.retryUnresolved(first)
        assertEquals(300, registry.adds)
        assertEquals(300, second.added)
        assertEquals(0, second.retryable)
    }

    @Test fun targeted_retry_reaches_item_201_while_the_first_200_keep_failing() = runBlocking {
        val registry = RetryRegistry()
        val items = (1..201).map { index ->
            OpmlImporter.ItemResult("源$index", "https://feeds.example.org/$index", "", OpmlImporter.Outcome.Failed("添加失败"))
        }
        registry.rejectedUrls += items.take(200).map { it.xmlUrl }
        val importer = OpmlImporter(registry)
        val first = importer.retryUnresolved(OpmlImporter.Report(items))
        assertEquals(200, registry.adds)
        assertEquals(201, first.retryable)
        registry.addUrls.clear()
        val retried = importer.retryUnresolved(first, target = first.items[200])
        assertEquals(listOf(items[200].xmlUrl), registry.addUrls)
        assertEquals(first.items.take(200), retried.items.take(200))
        assertTrue((retried.items[200].outcome as OpmlImporter.Outcome.Added).verified)
        assertEquals(200, retried.retryable)
    }

    @Test fun targeted_saved_item_201_verifies_only_its_existing_id_without_reconciliation() = runBlocking {
        val registry = RetryRegistry().apply { reconcileFails = true }
        val failures = (1..200).map { index ->
            OpmlImporter.ItemResult("源$index", "https://feeds.example.org/$index", "", OpmlImporter.Outcome.Failed("添加失败"))
        }
        val saved = OpmlImporter.ItemResult("已保存", "https://feeds.example.org/saved", "",
            OpmlImporter.Outcome.Added("saved-201", false, "订阅地址返回 503"))
        val report = OpmlImporter.Report(failures + saved)
        val retried = OpmlImporter(registry).retryUnresolved(report, target = saved)
        assertEquals(failures, retried.items.take(200))
        assertEquals(listOf("saved-201"), registry.verified)
        assertTrue((retried.items[200].outcome as OpmlImporter.Outcome.Added).verified)
        assertEquals(0, registry.adds)
        assertEquals(0, registry.knownReads)
    }

    @Test fun stale_ambiguous_successful_or_invalid_retry_targets_do_not_make_registry_requests() = runBlocking {
        val registry = RetryRegistry()
        val importer = OpmlImporter(registry)
        val failed = OpmlImporter.ItemResult("失败", "https://feeds.example.org/rss", "", OpmlImporter.Outcome.Failed("添加失败"))
        val success = failed.copy(outcome = OpmlImporter.Outcome.Added("saved", true))
        val report = OpmlImporter.Report(listOf(failed, success))
        assertEquals(report, importer.retryUnresolved(report, target = failed.copy(title = "另一条")))
        assertEquals(report, importer.retryUnresolved(report, target = failed.copy(outcome = OpmlImporter.Outcome.PendingConfirm)))
        assertEquals(report, importer.retryUnresolved(report, target = success))
        val ambiguous = OpmlImporter.Report(listOf(failed, failed))
        assertEquals(ambiguous, importer.retryUnresolved(ambiguous, target = failed))
        val invalid = failed.copy(xmlUrl = "not a URL")
        val invalidReport = OpmlImporter.Report(listOf(invalid))
        assertEquals(invalidReport, importer.retryUnresolved(invalidReport, target = invalid))
        assertEquals(0, registry.knownReads)
        assertEquals(0, registry.adds)
        assertTrue(registry.verified.isEmpty())
    }

    @Test fun saved_but_unverified_items_keep_the_reason_and_retry_the_same_id() = runBlocking {
        var adds = 0
        val verifiedIds = mutableListOf<String>()
        var verificationFails = true
        val registry = object : FeedRegistry {
            override suspend fun local() = emptyList<FeedEntity>()
            override suspend fun knownUrls() = Result.success(emptyList<String>())
            override suspend fun add(url: String, category: String, customTitle: String): Result<FeedRegistry.AddResult> {
                adds++
                return Result.success(FeedRegistry.AddResult("saved-id", false, PublicFetch.FetchFailure.Http(503)))
            }
            override suspend fun verify(feedId: String): Result<Unit> {
                verifiedIds += feedId
                return if (verificationFails) Result.failure(PublicFetch.FetchFailure.Timeout()) else Result.success(Unit)
            }
            override suspend fun refresh() = Result.success(emptyList<FeedEntity>())
            override suspend fun updateMeta(feedId: String, category: String, customTitle: String) = Result.success(Unit)
            override suspend fun delete(feedId: String) = Result.success(Unit)
        }
        val importer = OpmlImporter(registry)
        val report = importer.import(listOf(Opml.Entry("新闻", "https://feeds.example.org/rss")))
        assertEquals("订阅地址返回 503", (report.items.single().outcome as OpmlImporter.Outcome.Added).verifyReason)
        assertTrue(report.needsRetry)
        val failed = importer.retryUnresolved(report)
        assertEquals("请求超时", (failed.items.single().outcome as OpmlImporter.Outcome.Added).verifyReason)
        verificationFails = false
        val success = importer.retryUnresolved(failed)
        assertTrue((success.items.single().outcome as OpmlImporter.Outcome.Added).verified)
        assertEquals(1, adds)
        assertEquals(listOf("saved-id", "saved-id"), verifiedIds)
        assertEquals(0, success.retryable)
    }
}
