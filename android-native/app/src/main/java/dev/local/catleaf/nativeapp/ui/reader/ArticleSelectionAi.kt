package dev.local.catleaf.nativeapp.ui.reader

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextSelection
import android.webkit.WebView
import androidx.core.content.ContextCompat
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.ai.BilingualArticle
import java.util.UUID

/** Chromium's primary assist icon can override RemoteAction.shouldShowIcon. */
internal fun articleSelectionActionMode(callback: ActionMode.Callback) = object : ActionMode.Callback2() {
    private fun showAiLabel(menu: Menu) {
        for (index in 0 until menu.size()) {
            val item = menu.getItem(index)
            if (item.itemId == android.R.id.textAssist && item.title?.toString() == AI_READING_TITLE) item.icon = null
        }
    }
    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = callback.onCreateActionMode(mode, menu).also { showAiLabel(menu) }
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = callback.onPrepareActionMode(mode, menu).also { showAiLabel(menu) }
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = callback.onActionItemClicked(mode, item)
    override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)
    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
    }
}

/** 原生选区只开面板；调用方仍须核对当前文章、范围与正文版本。 */
fun attachArticleSelectionAi(view: WebView, onSelection: (String) -> Unit): () -> Unit {
    check(Looper.myLooper() == Looper.getMainLooper())
    val context = view.context
    val manager = context.getSystemService(TextClassificationManager::class.java)
    val previousManager = manager?.textClassifier
    val previousView = view.textClassifier
    val classifier = ArticleSelectionClassifier(context, previousView, onSelection)
    view.setTextClassifier(classifier)
    return {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (classifier.close()) {
            val currentManager = manager?.textClassifier
            if (view.textClassifier === classifier) view.setTextClassifier(activeClassifier(previousView))
            manager?.setTextClassifier(if (currentManager === classifier) activeClassifier(previousManager) else currentManager)
        }
    }
}

private fun activeClassifier(value: TextClassifier?): TextClassifier? {
    var current = value
    while (current is ArticleSelectionClassifier && current.isDestroyed()) current = current.previous
    return current
}

private class ArticleSelectionClassifier(
    private val context: Context,
    val previous: TextClassifier?,
    onSelection: (String) -> Unit,
) : TextClassifier {
    private val lock = Any()
    private val action = "${context.packageName}.ARTICLE_SELECTION_AI.${UUID.randomUUID()}"
    private var callback: ((String) -> Unit)? = onSelection
    private var closed = false
    private var generation = 0
    private var pending: PendingIntent? = null
    private var selected: String? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val delivery = synchronized(lock) {
                if (closed || intent.action != action || intent.getIntExtra("generation", -1) != generation) null
                else selected?.let { text -> callback?.let { text to it } }.also {
                    selected = null
                    pending = null
                }
            } ?: return
            delivery.second(delivery.first)
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), null, Handler(Looper.getMainLooper()),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun isDestroyed(): Boolean = synchronized(lock) { closed }

    override fun suggestSelection(request: TextSelection.Request): TextSelection =
        TextSelection.Builder(request.startIndex, request.endIndex).build()

    @Suppress("DEPRECATION")
    override fun classifyText(request: TextClassification.Request): TextClassification {
        val ticket = synchronized(lock) {
            if (closed) return TextClassification.Builder().build()
            pending?.cancel()
            pending = null
            selected = null
            ++generation
        }
        var delegate = previous
        while (delegate is ArticleSelectionClassifier) delegate = delegate.previous
        val base = try { delegate?.classifyText(request) ?: TextClassification.Builder().build() }
        catch (_: Exception) { TextClassification.Builder().build() }
        val length = request.endIndex - request.startIndex
        if (request.startIndex < 0 || request.endIndex > request.text.length || length !in 1..BilingualArticle.MAX_SELECTION_CHARS) return base
        val text = request.text.subSequence(request.startIndex, request.endIndex).toString()
        if (text.isBlank()) return base
        val ownAction = synchronized(lock) {
            if (closed || generation != ticket) return base
            selected = text
            val intent = Intent(action).setPackage(context.packageName).putExtra("generation", ticket)
            val operation = PendingIntent.getBroadcast(context, ticket, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            pending = operation
            RemoteAction(Icon.createWithResource(context, R.drawable.ic_ai_reading), AI_READING_TITLE, "${AI_READING_TITLE}所选文字", operation)
                .apply { setShouldShowIcon(false) }
        }
        // WebView 74 needs legacy primary metadata before its additional RemoteAction menu items.
        return TextClassification.Builder().setText(text).addAction(ownAction)
            .setLabel(ownAction.title.toString()).setIcon(null)
            .setOnClickListener {
                try { ownAction.actionIntent.send() }
                catch (_: PendingIntent.CanceledException) { }
            }.apply {
                base.actions.forEach { addAction(it) }
            }.build()
    }

    fun close(): Boolean {
        synchronized(lock) {
            if (closed) return false
            closed = true
            ++generation
            pending?.cancel()
            pending = null
            selected = null
            callback = null
        }
        context.unregisterReceiver(receiver)
        return true
    }
}
