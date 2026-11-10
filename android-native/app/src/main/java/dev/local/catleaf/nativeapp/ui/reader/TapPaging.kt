package dev.local.catleaf.nativeapp.ui.reader

import android.content.Context
import android.annotation.SuppressLint
import android.view.ActionMode
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal class TapPagingWebView(context: Context) : WebView(context) {
    var pagingEnabled: () -> Boolean = { false }
    var overlapPixels: () -> Int = { 0 }
    var onPageTurn: () -> Unit = {}
    var interactionKey: () -> String = { "" }
    private var downKey = ""
    private var selectionActive = false
    private var selectionAtDown = false
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { downKey = interactionKey(); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!pagingEnabled() || downKey != interactionKey() || selectionActive || selectionAtDown || !hasWindowFocus() || accessibility.isTouchExplorationEnabled) return false
            // Links (including retranslate/citation links) retain the WebView's own click handling.
            if (hitTestResult.type !in listOf(HitTestResult.UNKNOWN_TYPE, HitTestResult.IMAGE_TYPE)) return false
            val direction = readerPageDirection(e.x, width.toFloat())
            if (direction == 0) return false
            onPageTurn()
            scrollTo(scrollX, readerPageTarget(scrollY, height, overlapPixels(), (computeVerticalScrollRange() - height).coerceAtLeast(0), direction))
            return true
        }
    })

    // Native WebView clicks and accessibility scrolling stay with super; paging only observes touch positions.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) selectionAtDown = selectionActive
        val handled = super.onTouchEvent(event)
        detector.onTouchEvent(event)
        return handled
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(articleSelectionActionMode(callback) { selectionActive = it }, type)
}

/** Passive observation leaves selection and scrolling gestures with their original handlers. */
@Composable
internal fun PagedReaderColumn(enabled: Boolean, fontSizeSp: Int, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var viewport by remember { mutableIntStateOf(0) }
    val overlap = (fontSizeSp * density.density * density.fontScale * 1.67f * 2).roundToInt()
    Column(
        Modifier.fillMaxSize().onSizeChanged { viewport = it.height }
            .pointerInput(enabled, overlap) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    var cancelled = false
                    var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (event.changes.size != 1 || change == null) { cancelled = true; break }
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) cancelled = true
                        if (change.changedToUpIgnoreConsumed()) up = change
                        if (!change.pressed && up == null) { cancelled = true; break }
                    } while (up == null)
                    val released = up
                    if (!cancelled && released != null && !released.isConsumed &&
                        released.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
                        !accessibility.isTouchExplorationEnabled) {
                        val direction = readerPageDirection(released.position.x, size.width.toFloat())
                        if (direction != 0) scope.launch {
                            scroll.scrollTo(readerPageTarget(scroll.value, viewport, overlap, scroll.maxValue, direction))
                        }
                    }
                }
            }.verticalScroll(scroll),
        content = content,
    )
}
