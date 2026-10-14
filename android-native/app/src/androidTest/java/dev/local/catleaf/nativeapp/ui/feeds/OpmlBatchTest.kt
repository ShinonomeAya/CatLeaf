package dev.local.catleaf.nativeapp.ui.feeds

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.FeedRegistry
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.opml.OpmlImporter
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpmlBatchTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun result_row_201_retries_only_that_row_after_two_failed_batches() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val file = app.cacheDir.resolve("isolated-opml-targeted-row.opml")
        val attempts = mutableListOf<String>()
        val lastUrl = "https://feeds.example.org/201"
        var retryLast = false
        val store = object : FeedRegistry {
            override suspend fun local() = emptyList<FeedEntity>()
            override suspend fun knownUrls() = Result.success(emptyList<String>())
            override suspend fun add(url: String, category: String, customTitle: String): Result<FeedRegistry.AddResult> {
                attempts += url
                return if (retryLast && url == lastUrl) Result.success(FeedRegistry.AddResult("saved-201", true))
                    else Result.failure(PublicFetch.FetchFailure.Http(503))
            }
            override suspend fun verify(feedId: String): Result<Unit> = error("Failed rows must be added, not verified")
            override suspend fun refresh() = Result.success(emptyList<FeedEntity>())
            override suspend fun updateMeta(feedId: String, category: String, customTitle: String) = Result.success(Unit)
            override suspend fun delete(feedId: String) = Result.success(Unit)
        }
        val vm = OpmlViewModel(app)
        vm.attach(store, OpmlImporter(store))
        try {
            file.writeText("<opml><body>" + (1..201).joinToString("") {
                "<outline text=\"源$it\" xmlUrl=\"https://feeds.example.org/$it\"/>"
            } + "</body></opml>")
            vm.loadDocument(Uri.fromFile(file))
            withTimeout(5000) { vm.state.first { it.document != null } }
            vm.startImport()
            withTimeout(5000) { vm.state.first { it.report?.failed == 200 && !it.importing } }
            vm.nextBatch()
            vm.startImport()
            val report = withTimeout(5000) { vm.state.first { it.report?.failed == 201 && !it.importing } }.report!!
            assertEquals(201, attempts.size)
            composeRule.setContent {
                CatLeafTheme {
                    val state by vm.state.collectAsState()
                    state.report?.let { current -> OpmlResultSurface(current, state.importing, true,
                        onRetry = vm::retryUnresolved, onDismiss = vm::dismiss) }
                }
            }
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(200)
            composeRule.onNodeWithText("源201").assertIsDisplayed()
            instrumentation.runOnMainSync { retryLast = true; attempts.clear() }
            composeRule.onAllNodesWithText("重试", substring = false).onLast().performClick()
            composeRule.waitUntil(5000) { vm.state.value.report?.added == 1 && !vm.state.value.importing }
            assertEquals(listOf(lastUrl), attempts)
            assertEquals(report.items.take(200), vm.state.value.report!!.items.take(200))
            assertEquals(OpmlImporter.Outcome.Added("saved-201", true), vm.state.value.report!!.items[200].outcome)
        } finally { file.delete(); instrumentation.runOnMainSync { vm.dismiss() } }
    }

    @Test fun next_batch_keeps_previous_results_and_scope_change_discards_the_preview() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val file = app.cacheDir.resolve("isolated-opml-batch.opml")
        val adds = mutableListOf<String>()
        val verified = mutableListOf<String>()
        fun registry() = object : FeedRegistry {
            override suspend fun local() = emptyList<FeedEntity>()
            override suspend fun knownUrls() = Result.success(adds.toList())
            override suspend fun add(url: String, category: String, customTitle: String): Result<FeedRegistry.AddResult> {
                adds += url
                return Result.success(FeedRegistry.AddResult(url, adds.size != 1, PublicFetch.FetchFailure.Http(503)))
            }
            override suspend fun verify(feedId: String): Result<Unit> { verified += feedId; return Result.success(Unit) }
            override suspend fun refresh() = Result.success(emptyList<FeedEntity>())
            override suspend fun updateMeta(feedId: String, category: String, customTitle: String) = Result.success(Unit)
            override suspend fun delete(feedId: String) = Result.success(Unit)
        }
        val store = registry()
        val vm = OpmlViewModel(app)
        vm.attach(store, OpmlImporter(store))
        try {
            file.writeText("<opml><body>" + (1..201).joinToString("") {
                "<outline text=\"源$it\" xmlUrl=\"https://feeds.example.org/$it\"/>"
            } + "</body></opml>")
            vm.loadDocument(Uri.fromFile(file))
            withTimeout(5000) { vm.state.first { it.document != null } }
            assertEquals(200, vm.state.value.selected.size)
            vm.toggle(200)
            assertEquals("超出批次不能多选", 200, vm.state.value.selected.size)
            vm.startImport()
            val first = withTimeout(5000) { vm.state.first { it.report != null && !it.importing } }.report!!
            assertEquals(200, first.added)
            assertEquals(1, first.unverified)
            assertEquals(1, first.remaining)
            vm.nextBatch()
            assertEquals(setOf(200), vm.state.value.selected)
            vm.startImport()
            val last = withTimeout(5000) { vm.state.first { it.report?.remaining == 0 && !it.importing } }.report!!
            assertEquals(201, last.added)
            assertEquals(201, adds.toSet().size)
            vm.retryUnresolved()
            withTimeout(5000) { vm.state.first { it.report?.retryable == 0 && !it.importing } }
            assertEquals(201, adds.size)
            assertEquals(listOf("https://feeds.example.org/1"), verified)
            val nextScope = registry()
            vm.attach(nextScope, OpmlImporter(nextScope))
            assertTrue(vm.state.value.document == null && vm.state.value.report == null)
        } finally { file.delete(); vm.dismiss() }
    }
}
