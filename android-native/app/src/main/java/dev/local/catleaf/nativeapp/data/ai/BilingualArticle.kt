package dev.local.catleaf.nativeapp.data.ai

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Input is already sanitized; only local markers and escaped translation text are added. */
class BilingualArticle private constructor(private val markedHtml: String, val blocks: List<Block>) {
    data class Block(val id: String, val text: String)

    val fingerprint: String get() = Translator.hash("blocks-v1:" + blocks.joinToString("\u0000") { "${it.id}:${it.text}" })

    fun withoutTitle(): BilingualArticle = BilingualArticle(markedHtml, blocks.filter { it.id != TITLE_ID })

    fun inputJson(): String = JSONArray().apply {
        blocks.forEach { put(JSONObject().put("id", it.id).put("text", it.text)) }
    }.toString()

    fun parseTranslations(raw: String): Map<String, String>? = runCatching {
        require(raw.length <= 512_000)
        val tokener = JSONTokener(raw)
        val json = tokener.nextValue() as? JSONObject ?: error("object required")
        require(tokener.nextClean() == '\u0000')
        val array = json.getJSONArray("segments")
        require(array.length() == blocks.size && blocks.isNotEmpty())
        val translated = linkedMapOf<String, String>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val id = item.get("id") as? String ?: error("id required")
            val text = (item.get("text") as? String)?.trim() ?: error("text required")
            require(text.isNotEmpty() && !translated.containsKey(id))
            translated[id] = text
        }
        require(translated.keys == blocks.map { it.id }.toSet())
        blocks.associate { it.id to translated.getValue(it.id) }
    }.getOrNull()

    fun serializeTranslations(translations: Map<String, String>): String = JSONObject().put(
        "segments", JSONArray().apply { blocks.forEach { put(JSONObject().put("id", it.id).put("text", translations.getValue(it.id))) } },
    ).toString()

    fun render(translations: Map<String, String>? = null): String {
        if (translations == null) return markedHtml
        require(translations.keys == blocks.map { it.id }.toSet())
        val doc = Jsoup.parseBodyFragment(markedHtml)
        doc.outputSettings().prettyPrint(false)
        val originals = doc.select("span[id]").associateBy { it.id() }
        for (block in blocks) {
            originals.getValue(block.id).after(
                Element("span").addClass("catleaf-translation").attr("lang", "zh-Hans")
                    .text(translations.getValue(block.id)),
            )
        }
        return doc.body().html()
    }

    companion object {
        const val TITLE_ID = "catleaf-title"

        fun fromHtml(sanitizedHtml: String, title: String = ""): BilingualArticle {
            val doc = Jsoup.parseBodyFragment(sanitizedHtml)
            doc.outputSettings().prettyPrint(false)
            val blocks = mutableListOf<Block>()
            fun visit(parent: Element) {
                val run = mutableListOf<Node>()
                fun flush() {
                    if (run.isEmpty()) return
                    val text = run.joinToString("") { if (it is TextNode) it.text() else (it as? Element)?.text().orEmpty() }
                        .replace(Regex("\\s+"), " ").trim()
                    if (text.isNotBlank()) {
                        val id = "catleaf-b${blocks.size + 1}"
                        val wrapper = Element("span").attr("id", id)
                        run.first().before(wrapper)
                        run.forEach(wrapper::appendChild)
                        blocks += Block(id, text)
                    }
                    run.clear()
                }
                for (child in parent.childNodes().toList()) {
                    if (child is Element && (child.tag().isBlock || child.tagName() in setOf("br", "img") ||
                            child.selectFirst("br,img,pre") != null)) {
                        flush()
                        if (child.tagName() !in setOf("pre", "code", "img", "br", "hr")) visit(child)
                    } else if (child is TextNode || child is Element) {
                        run += child
                    }
                }
                flush()
            }
            visit(doc.body())
            title.trim().takeIf { it.isNotEmpty() }?.let { blocks.add(0, Block(TITLE_ID, it)) }
            return BilingualArticle(doc.body().html(), blocks)
        }

        fun fromText(text: String): BilingualArticle {
            val body = Element("div")
            text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.forEach { body.appendElement("p").text(it) }
            return fromHtml(body.html())
        }
    }
}
