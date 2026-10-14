package dev.local.catleaf.nativeapp.ui.reader

import dev.local.catleaf.nativeapp.data.ai.ArticleAssistant

data class AiReadingUiState(
    val open: Boolean = false,
    val mode: ArticleAssistant.Mode = ArticleAssistant.Mode.Overview,
    val question: String = "",
    val paragraphId: String? = null,
    val selection: String = "",
    val busy: Boolean = false,
    val answer: ArticleAssistant.Answer? = null,
    val cached: Boolean = false,
    val notice: String? = null,
    val baseUrl: String = "",
    val model: String = "",
)

data class ParagraphJump(val id: String, val requestId: Long = System.nanoTime())
