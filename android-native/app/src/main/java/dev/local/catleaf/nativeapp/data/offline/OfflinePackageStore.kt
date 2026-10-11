package dev.local.catleaf.nativeapp.data.offline

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import dev.local.catleaf.nativeapp.data.article.ArticleIdentity
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.article.SearchQuery
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.OfflinePackageEntity
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** A single device-only package; no URI or file path is accepted from article HTML. */
class OfflinePackageStore(
    context: Context,
    private val db: CatLeafDatabase = CatLeafDatabase.get(context),
    private val fetch: PublicFetch = PublicFetch(),
) {
    private val root = File(context.noBackupFilesDir, "offline-packages")

    data class Resource(val file: File, val mime: String)
    data class Snapshot(val record: OfflinePackageEntity, val bodyHtml: String, val resources: Map<String, Resource>) {
        val coverUrl: String? get() = record.coverId?.let { "https://appassets.androidplatform.net/offline/$it" }
    }

    suspend fun save(scopeKey: String, localId: String, includeImages: Boolean): Result<OfflinePackageEntity> =
        withContext(Dispatchers.IO) {
            if (!singleTask.tryLock()) return@withContext Result.failure(IllegalStateException("已有一篇正在保存，请稍后重试"))
            try {
                runCatching { saveLocked(scopeKey, localId, includeImages) }
            } finally {
                singleTask.unlock()
            }
        }

    private suspend fun saveLocked(scopeKey: String, localId: String, includeImages: Boolean): OfflinePackageEntity {
        val article = db.articles().get(localId)?.takeIf { it.instanceKey == scopeKey }
            ?: error("文章不在当前使用范围")
        val content = db.articles().getContent(localId) ?: error("当前没有可保存的正文")
        val cover = content.thumbnail?.takeIf { EndpointPolicy.requirePublicFetchTarget(it).isSuccess }
        val sanitized = ArticleSanitizer.sanitize(content.content, article.link, listOf(article.title), cover)
        if (sanitized.isEmpty || sanitized.textLength == 0) error("当前没有可保存的正文，请先获取全文")
        val old = db.offline().get(localId, scopeKey)
        val generation = UUID.randomUUID().toString()
        val target = generationDir(scopeKey, localId, generation)
        val plainText = SearchQuery.bodyText(sanitized.html)
        val body = Jsoup.parseBodyFragment(sanitized.html)
        body.outputSettings().prettyPrint(false) // Preserve the displayed text and translation fingerprint.
        val urls = (body.select("img[src]").map { it.attr("src") } + listOfNotNull(cover)).distinct()
        val selected = if (includeImages) urls.take(MAX_IMAGES) else emptyList()
        val ids = urls.associateWith(::sha)
        body.select("img[src]").forEach { img ->
            img.attr("src", "https://appassets.androidplatform.net/offline/${ids[img.attr("src")] ?: "missing"}")
        }
        val frozenBody = body.body().html().toByteArray(Charsets.UTF_8)
        if (frozenBody.size > MAX_ARTICLE_BYTES) error("正文超过单篇 20 MiB 上限")
        check(db.offline().totalBytes() + frozenBody.size <= MAX_TOTAL_BYTES) { "离线保存空间已达 300 MiB 上限" }
        val manifest = JSONArray()
        if (includeImages) urls.forEach { manifest.put(JSONObject().put("url", it).put("id", ids.getValue(it))) }
        val pending = (old ?: OfflinePackageEntity(
            localId = localId, scopeKey = scopeKey, title = article.title, feedTitle = article.feedTitle,
            generation = "", status = "saving", selectedImages = includeImages,
            imageTotal = if (includeImages) urls.size else 0, imageSaved = 0, bytes = 0,
            bodyHash = ArticleIdentity.contentFingerprint(frozenBody.decodeToString()),
            searchBodyText = plainText, imageManifest = manifest.toString(),
            coverId = if (includeImages) cover?.let(ids::get) else null,
            updatedAt = System.currentTimeMillis(),
        )).copy(pendingGeneration = generation, status = "saving", updatedAt = System.currentTimeMillis())
        db.offline().upsert(pending)
        try {
            target.mkdirs()
            check(target.isDirectory) { "无法创建离线目录" }
            writeAtomic(File(target, "body.html"), frozenBody)
            var used = frozenBody.size.toLong()
            var failures = if (includeImages) (urls.size - selected.size) else 0
            var savedCount = 0
            if (old == null) db.offline().upsert(pending.copy(
                generation = generation, pendingGeneration = null, bytes = used,
            ))
            for ((index, url) in selected.withIndex()) {
                currentCoroutineContext().ensureActive()
                val response = fetch.fetch(url, PublicFetch.MAX_IMAGE_BYTES, article.link).getOrNull()
                currentCoroutineContext().ensureActive()
                val mime = response?.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (response == null || mime == null || mime !in IMAGE_TYPES || !validImage(mime, response.bytes) ||
                    used + response.bytes.size > MAX_ARTICLE_BYTES ||
                    db.offline().totalBytes() + (if (old == null) 0 else used) + response.bytes.size > MAX_TOTAL_BYTES) {
                    failures++
                } else {
                    val id = ids.getValue(url)
                    writeAtomic(File(target, id), response.bytes)
                    used += response.bytes.size
                    savedCount++
                    manifest.getJSONObject(index).put("mime", mime)
                    if (old == null) db.offline().upsert(pending.copy(
                        generation = generation, pendingGeneration = null,
                        imageSaved = savedCount, bytes = used, imageManifest = manifest.toString(),
                    ))
                }
            }
            check(used <= MAX_ARTICLE_BYTES) { "单篇离线包超过 20 MiB 上限" }
            if (old != null && failures > 0) error("更新未完成，旧离线副本已保留；可稍后重试")
            val result = OfflinePackageEntity(
                localId = localId, scopeKey = scopeKey, title = article.title, feedTitle = article.feedTitle,
                generation = generation, status = when {
                    !includeImages -> "text"
                    failures > 0 -> "partial"
                    else -> "complete"
                }, selectedImages = includeImages, imageTotal = if (includeImages) urls.size else 0,
                imageSaved = savedCount, bytes = used,
                bodyHash = ArticleIdentity.contentFingerprint(frozenBody.decodeToString()), searchBodyText = plainText,
                imageManifest = manifest.toString(), coverId = if (includeImages) cover?.let(ids::get) else null,
                updatedAt = System.currentTimeMillis(),
            )
            db.offline().upsert(result)
            if (old != null && old.generation.isNotBlank() && old.generation != generation) {
                generationDir(scopeKey, localId, old.generation).deleteRecursively()
            }
            return result
        } catch (error: Throwable) {
            // A successful old generation remains usable when a refresh fails.
            withContext(NonCancellable) {
                if (old != null) db.offline().upsert(old)
                else if (File(target, "body.html").isFile) {
                    val progress = db.offline().get(localId, scopeKey) ?: pending
                    db.offline().upsert(progress.copy(
                        generation = generation, pendingGeneration = null, status = "interrupted",
                        bytes = progress.bytes.coerceAtLeast(File(target, "body.html").length()),
                    ))
                } else db.offline().delete(localId, scopeKey)
            }
            if (old != null || !File(target, "body.html").isFile) target.deleteRecursively()
            throw error
        }
    }

    suspend fun retryMissing(scopeKey: String, localId: String): Result<OfflinePackageEntity> =
        withContext(Dispatchers.IO) {
            if (!singleTask.tryLock()) return@withContext Result.failure(IllegalStateException("已有一篇正在保存，请稍后重试"))
            try {
                runCatching {
                    val article = db.articles().get(localId)?.takeIf { it.instanceKey == scopeKey }
                        ?: error("文章不在当前使用范围")
                    var record = db.offline().get(localId, scopeKey) ?: error("离线副本不存在")
                    check(record.selectedImages) { "这篇只保存了正文" }
                    val folder = generationDir(scopeKey, localId, record.generation)
                    check(File(folder, "body.html").isFile) { "离线正文文件已丢失" }
                    val manifest = JSONArray(record.imageManifest)
                    record = record.copy(status = "saving")
                    db.offline().upsert(record)
                    try {
                        for (i in 0 until minOf(manifest.length(), MAX_IMAGES)) {
                            currentCoroutineContext().ensureActive()
                            val item = manifest.getJSONObject(i)
                            if (item.has("mime") && File(folder, item.getString("id")).isFile) continue
                            val fetched = fetch.fetch(item.getString("url"), PublicFetch.MAX_IMAGE_BYTES, article.link).getOrNull() ?: continue
                            currentCoroutineContext().ensureActive()
                            val mime = fetched.contentType?.substringBefore(';')?.trim()?.lowercase() ?: continue
                            if (mime !in IMAGE_TYPES || !validImage(mime, fetched.bytes) ||
                                record.bytes + fetched.bytes.size > MAX_ARTICLE_BYTES ||
                                db.offline().totalBytes() + fetched.bytes.size > MAX_TOTAL_BYTES) continue
                            writeAtomic(File(folder, item.getString("id")), fetched.bytes)
                            item.put("mime", mime)
                            record = record.copy(
                                imageSaved = record.imageSaved + 1, bytes = record.bytes + fetched.bytes.size,
                                imageManifest = manifest.toString(), updatedAt = System.currentTimeMillis(),
                            )
                            db.offline().upsert(record)
                        }
                        record = record.copy(status = if (record.imageSaved == record.imageTotal) "complete" else "partial")
                        db.offline().upsert(record)
                        record
                    } catch (error: Throwable) {
                        withContext(NonCancellable) { db.offline().upsert(record.copy(status = "interrupted")) }
                        throw error
                    }
                }
            } finally {
                singleTask.unlock()
            }
        }

    suspend fun load(scopeKey: String, localId: String, forceRepair: Boolean = false): Snapshot? = withContext(Dispatchers.IO) {
        val record = db.offline().get(localId, scopeKey) ?: return@withContext null
        val mayRepair = forceRepair || !singleTask.isLocked
        if (!mayRepair && record.generation.isBlank()) return@withContext null
        val active = if (record.pendingGeneration != null && mayRepair) {
            val repaired = if (record.generation.isNotBlank()) {
                record.copy(
                    pendingGeneration = null,
                    status = when {
                        !record.selectedImages -> "text"
                        record.imageSaved == record.imageTotal -> "complete"
                        else -> "partial"
                    },
                )
            } else {
                val pendingBody = File(generationDir(scopeKey, localId, record.pendingGeneration), "body.html")
                if (!pendingBody.isFile) {
                    db.offline().delete(localId, scopeKey)
                    return@withContext null
                }
                record.copy(generation = record.pendingGeneration, pendingGeneration = null,
                    status = "interrupted", bytes = pendingBody.length())
            }
            db.offline().upsert(repaired)
            repaired
        } else if (record.status == "saving" && mayRepair) {
            record.copy(status = "interrupted").also { db.offline().upsert(it) }
        } else record
        val folder = generationDir(scopeKey, localId, active.generation)
        val body = File(folder, "body.html").takeIf { it.isFile } ?: run {
            db.offline().delete(localId, scopeKey)
            return@withContext null
        }
        val resources = mutableMapOf<String, Resource>()
        val manifest = JSONArray(active.imageManifest)
        val knownFiles = mutableSetOf("body.html")
        for (i in 0 until manifest.length()) {
            val item = manifest.getJSONObject(i)
            val id = item.getString("id")
            if (!id.matches(Regex("[0-9a-f]{64}"))) continue
            val file = File(folder, id)
            val mime = item.optString("mime")
            if (file.isFile && mime in IMAGE_TYPES) {
                resources[id] = Resource(file, mime)
                knownFiles += id
            }
        }
        if (mayRepair && active.status == "interrupted") {
            folder.listFiles()?.filter { it.name !in knownFiles }?.forEach { it.delete() }
        }
        val checked = if (resources.size < active.imageSaved) {
            active.copy(status = "partial", imageSaved = resources.size).also { db.offline().upsert(it) }
        } else active
        Snapshot(checked, body.readText(), resources)
    }

    suspend fun remove(scopeKey: String, localId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!singleTask.tryLock()) return@withContext Result.failure(IllegalStateException("保存任务进行中，请先取消"))
        try {
            runCatching {
                val record = db.offline().get(localId, scopeKey) ?: return@runCatching
                db.withTransaction {
                    val article = db.articles().get(localId)
                    val normalText = ArticleSanitizer.sanitize(db.articles().getContent(localId)?.content, article?.link).plainText
                    if (article?.isStarred == true && normalText.isBlank()) {
                        val text = record.searchBodyText
                        check(text.isNotBlank()) { "收藏正文尚未保留，不能移除离线包" }
                        val safeText = org.jsoup.nodes.Entities.escape(text)
                        db.articles().upsertContent(ArticleContentEntity(
                            localId = localId, content = "<p>$safeText</p>", summary = null, thumbnail = null,
                            contentSource = "rss", contentHash = record.bodyHash,
                            searchText = text.take(500), searchBodyText = text, lastAccessedAt = System.currentTimeMillis(),
                        ))
                    }
                    check(db.offline().delete(localId, scopeKey) > 0) { "离线记录移除失败" }
                }
                articleDir(scopeKey, localId).deleteRecursively()
            }
        } finally {
            singleTask.unlock()
        }
    }

    /** On cold start, reconcile interrupted rows and files before reporting any package complete. */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        if (!singleTask.tryLock()) return@withContext
        try {
            db.offline().all().forEach { load(it.scopeKey, it.localId, forceRepair = true) }
            val valid = db.offline().all().associate { articleDir(it.scopeKey, it.localId).name to it.generation }
            root.listFiles()?.forEach { articleFolder ->
                val generation = valid[articleFolder.name]
                if (generation == null) articleFolder.deleteRecursively()
                else articleFolder.listFiles()?.forEach { if (it.name != generation) it.deleteRecursively() }
            }
        } finally {
            singleTask.unlock()
        }
    }

    private fun articleDir(scopeKey: String, localId: String) = File(root, sha("$scopeKey\n$localId"))
    private fun generationDir(scopeKey: String, localId: String, generation: String) =
        File(articleDir(scopeKey, localId), generation)

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
        check(temp.renameTo(file)) { "离线文件写入失败" }
    }

    private fun validImage(mime: String, bytes: ByteArray): Boolean {
        val signature = when (mime) {
            "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
            "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            "image/gif" -> bytes.size >= 6 && String(bytes, 0, 6) in setOf("GIF87a", "GIF89a")
            "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP"
            else -> false
        }
        if (!signature) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private val singleTask = Mutex()
        suspend fun clearForDeviceReset(context: Context) = withContext(Dispatchers.IO) {
            singleTask.withLock {
                val dir = File(context.noBackupFilesDir, "offline-packages")
                check(!dir.exists() || dir.deleteRecursively()) { "离线文件未能全部删除" }
            }
        }
        const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
        const val MAX_ARTICLE_BYTES = 20L * 1024 * 1024
        const val MAX_IMAGES = 50
        private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    }
}
