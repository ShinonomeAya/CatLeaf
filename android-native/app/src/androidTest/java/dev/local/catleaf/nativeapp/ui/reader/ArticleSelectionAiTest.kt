package dev.local.catleaf.nativeapp.ui.reader

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Looper
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextSelection
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.R
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native requests and private PendingIntent delivery only; actual long-press is a separate UI probe. */
@RunWith(AndroidJUnit4::class)
class ArticleSelectionAiTest {
    private val main = InstrumentationRegistry.getInstrumentation()
    private val context = main.targetContext
    private val views = mutableListOf<WebView>()
    private val cleanups = mutableListOf<() -> Unit>()
    private lateinit var manager: TextClassificationManager
    private var previousManager: TextClassifier? = null
    private lateinit var systemAction: RemoteAction
    private lateinit var systemClassifier: FakeClassifier

    private class FakeClassifier(private val action: RemoteAction) : TextClassifier {
        var fails = false
        override fun classifyText(request: TextClassification.Request): TextClassification {
            check(!fails) { "isolated classifier failure" }
            return TextClassification.Builder().setText("系统分类").addAction(action).build()
        }
    }

    @Before fun setup() {
        main.runOnMainSync {
            manager = context.getSystemService(TextClassificationManager::class.java)
            previousManager = manager.textClassifier
            val operation = PendingIntent.getBroadcast(context, 7301,
                Intent("${context.packageName}.ISOLATED_SYSTEM_CLASSIFIER").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            systemAction = RemoteAction(Icon.createWithResource(context, R.drawable.ic_ai_reading), "系统动作", "系统动作", operation)
            systemClassifier = FakeClassifier(systemAction)
            manager.setTextClassifier(systemClassifier)
        }
    }

    @After fun cleanup() {
        main.runOnMainSync {
            cleanups.forEach { it() }
            views.forEach { it.destroy() }
            systemAction.actionIntent.cancel()
            manager.setTextClassifier(previousManager)
        }
    }

    private fun attach(onSelected: (String) -> Unit): TextClassifier {
        lateinit var classifier: TextClassifier
        main.runOnMainSync {
            val view = WebView(context)
            configureArticleWebView(view, context)
            views += view
            cleanups += attachArticleSelectionAi(view, onSelected)
            classifier = view.textClassifier
            assertFalse(view.settings.javaScriptEnabled)
        }
        return classifier
    }

    private fun request(text: String, selected: String = text): TextClassification.Request {
        val start = text.indexOf(selected)
        return TextClassification.Request.Builder(text, start, start + selected.length).build()
    }

    private fun aiAction(classification: TextClassification): RemoteAction =
        classification.actions.single { it.title.toString() == "AI解读" }

    private fun assertCancelled(operation: PendingIntent) {
        assertTrue(runCatching { operation.send() }.exceptionOrNull() is PendingIntent.CanceledException)
    }

    @Test fun real_request_substring_is_delivered_on_main_by_an_immutable_single_use_action() {
        val arrived = CountDownLatch(1)
        val onMain = AtomicBoolean(false)
        val received = Collections.synchronizedList(mutableListOf<String>())
        val classifier = attach {
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            received += it
            arrived.countDown()
        }
        val text = "Context 🍃 selected passage. Tail."
        val selected = "selected passage"
        val classification = classifier.classifyText(request(text, selected))
        assertTrue(classification.actions.any { it.title.toString() == "系统动作" })
        val action = aiAction(classification)
        assertEquals(context.packageName, action.actionIntent.creatorPackage)
        action.actionIntent.send(context, 0, Intent().putExtra("generation", Int.MAX_VALUE))
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertTrue(onMain.get())
        assertEquals(listOf(selected), received)
        assertCancelled(action.actionIntent)
        main.runOnMainSync { assertFalse(views.single().settings.javaScriptEnabled) }
    }

    @Test fun a_new_selection_and_disposal_revoke_previous_actions() {
        val arrived = CountDownLatch(1)
        val received = Collections.synchronizedList(mutableListOf<String>())
        val classifier = attach { received += it; arrived.countDown() }
        val first = aiAction(classifier.classifyText(request("first")))
        val second = aiAction(classifier.classifyText(request("second")))
        assertCancelled(first.actionIntent)
        second.actionIntent.send()
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("second"), received)
        val late = aiAction(classifier.classifyText(request("late")))
        main.runOnMainSync { cleanups.single()(); cleanups.single()() }
        assertCancelled(late.actionIntent)
        assertTrue(classifier.isDestroyed)
        assertTrue(classifier.classifyText(request("closed")).actions.isEmpty())
        main.runOnMainSync { assertSame(systemClassifier, manager.textClassifier) }
    }

    @Suppress("DEPRECATION")
    @Test fun legacy_webview_primary_assist_metadata_dispatches_the_same_single_use_private_action() {
        val arrived = CountDownLatch(1)
        val received = Collections.synchronizedList(mutableListOf<String>())
        val classifier = attach { received += it; arrived.countDown() }
        val classification = classifier.classifyText(request("legacy selection"))
        assertEquals("AI解读", classification.label.toString())
        assertNull(classification.icon)
        assertFalse(aiAction(classification).shouldShowIcon())
        assertNotNull(classification.onClickListener)
        main.runOnMainSync { classification.onClickListener!!.onClick(views.single()) }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("legacy selection"), received)
        assertCancelled(aiAction(classification).actionIntent)
        main.runOnMainSync { classification.onClickListener!!.onClick(views.single()) }
        assertEquals(1, received.size)
    }

    @Test fun bounds_and_classifier_failure_preserve_standard_actions_without_network() {
        val classifier = attach { fail("No selection action was clicked") }
        val max = "文".repeat(2000)
        assertNotNull(aiAction(classifier.classifyText(request(max))))
        for (text in listOf("文".repeat(2001), "   ")) {
            val result = classifier.classifyText(request(text))
            assertEquals(listOf("系统动作"), result.actions.map { it.title.toString() })
        }
        systemClassifier.fails = true
        assertNotNull(aiAction(classifier.classifyText(request("valid"))))
        val selection = classifier.suggestSelection(TextSelection.Request.Builder("prefix word suffix", 7, 11).build())
        assertEquals(7, selection.selectionStartIndex)
        assertEquals(11, selection.selectionEndIndex)
        if (android.os.Build.VERSION.SDK_INT >= 31) assertNull(selection.textClassification)
    }

    @Test fun webview_nonces_are_isolated_and_closing_one_never_restores_a_retired_classifier() {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val arrived = CountDownLatch(1)
        val firstClassifier = attach { received += "first:$it" }
        val secondClassifier = attach { received += "second:$it"; arrived.countDown() }
        val first = aiAction(firstClassifier.classifyText(request("one")))
        val secondResult = secondClassifier.classifyText(request("two"))
        assertEquals(1, secondResult.actions.count { it.title.toString() == "AI解读" })
        val second = aiAction(secondResult)
        assertNotEquals(first.actionIntent, second.actionIntent)
        main.runOnMainSync {
            val managerBeforeClose = manager.textClassifier
            cleanups[0]()
            assertSame(secondClassifier, views[1].textClassifier)
            assertSame(systemClassifier, views[0].textClassifier)
            assertSame(if (managerBeforeClose === firstClassifier) systemClassifier else managerBeforeClose, manager.textClassifier)
        }
        assertCancelled(first.actionIntent)
        second.actionIntent.send()
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("second:two"), received)
        main.runOnMainSync {
            cleanups[1]()
            assertSame(systemClassifier, manager.textClassifier)
        }
    }
}
