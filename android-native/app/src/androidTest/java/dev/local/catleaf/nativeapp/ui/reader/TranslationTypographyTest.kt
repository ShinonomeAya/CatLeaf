package dev.local.catleaf.nativeapp.ui.reader

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.catleaf.nativeapp.data.ai.Translator
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.local.AiCacheEntity
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.local.catleaf.nativeapp.ManualProbe
import androidx.test.platform.app.InstrumentationRegistry

/**
 * F18（独立验收）：译文必须与原文共用用户字号与行距策略。
 * 用**隔离环境 + 本地假译文**验证排版——假数据只在一次性仪器命名空间 `f18-test` 下，
 * 凭据全部为显眼的假值，零网络、零付费调用、不混入真实实例数据。
 *
 * 断言方式：语义树拿不到精确 sp，但能拿渲染高度——同一段译文在字号 16 与 22 下，
 * 多行文本的高度比例必须明显大于 1（行为级证明「用户字号驱动译文排版」）。
 */
@RunWith(AndroidJUnit4::class)
class TranslationTypographyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val db get() = CatLeafDatabase.get(ctx)
    private val settings get() = SettingsStore(ctx)

    private val instanceKey = "f18-test"
    private val localId = "g:f18-test-article"
    private val baseUrl = "https://ai.invalid.test"
    private val model = "f18-fake-model"
    private val fakeKey = "NOT-A-REAL-KEY-f18-local-only"
    private val original = "The quick brown fox jumps over the lazy dog, and then keeps running for a while."
    private val translation = "敏捷的棕色狐狸跳过懒狗，然后继续跑了一段路。这一句在两种字号下都会被换行成多行文本，用来比较渲染高度。"
    private var previousSettings: SettingsStore.Snapshot? = null

    @Before
    fun seed() = runBlocking {
        previousSettings = settings.current()
        settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
        settings.setCurrentInstanceKey(instanceKey)
        // 隔离命名空间种子：文章 + 正文 + 假 AI 配置 + 完整译文缓存（键与 ReaderViewModel 的计算完全一致）
        db.articles().upsertArticle(
            ArticleEntity(
                localId = localId,
                instanceKey = instanceKey,
                feedId = "f18-feed",
                guid = "f18-article",
                link = "https://example.org/f18",
                title = "F18 译文排版测试",
                publishedAt = null,
                feedTitle = "本地测试源",
                feedUrl = "https://example.org/feed",
                stateUpdatedAt = 1,
            ),
        )
        db.articles().upsertContent(
            ArticleContentEntity(
                localId = localId,
                content = "<p>$original</p>",
                summary = null,
                thumbnail = null,
                contentSource = "rss",
                contentHash = dev.local.catleaf.nativeapp.data.article.ArticleIdentity
                    .contentFingerprint("<p>$original</p>"),
                lastAccessedAt = 1,
            ),
        )
        val plain = ArticleSanitizer.sanitize("<p>$original</p>", "https://example.org/f18").plainText.trim()
        db.aiCache().insert(
            AiCacheEntity(
                cacheKey = Translator.cacheKey(instanceKey, localId, Translator.hash(plain), baseUrl, model),
                instanceKey = instanceKey,
                task = Translator.TASK,
                endpoint = baseUrl,
                model = model,
                contentHash = Translator.hash(plain),
                language = Translator.TARGET_LANGUAGE,
                promptVersion = "v1", // Read-only legacy cache compatibility; bilingual fixtures use BilingualReaderProbeTest.
                result = translation,
                complete = true,
                createdAt = 1,
            ),
        )
        settings.setAiConfig(baseUrl, model)
        CredentialStore(ctx).put(CredentialStore.AI_KEY, fakeKey)
        settings.setBodyFontSize(16)
    }

    @After
    fun cleanup() = runBlocking {
        db.aiCache().clearInstance(instanceKey)
        db.articles().deleteContent(listOf(localId))
        previousSettings?.let { previous ->
            settings.setAiConfig(previous.aiBaseUrl, previous.aiModel)
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
            settings.setOnboardingDone(previous.onboardingDone)
            settings.setBodyFontSize(previous.bodyFontSizeSp)
        }
        runCatching { CredentialStore(ctx).delete(CredentialStore.AI_KEY) }
        Unit
    }

    @Test
    fun translated_text_grows_with_user_font_size() {
        // 直接构造 VM（AndroidViewModel 只要 Application），假配置下 open() 应命中本地译文缓存——零网络
        val readerVm = ReaderViewModel(ctx)
        composeRule.setContent {
            CatLeafTheme {
                ReaderScreen(
                    localId = localId,
                    viewModel = readerVm,
                    onBack = {},
                    onOpenPreferences = {},
                    onOpenStorage = {},
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) { readerVm.state.value.translation != null }
        readerVm.showTranslation()
        composeRule.waitForIdle()
        // 用未裁剪的布局高度：360dp/1.3 倍下页首块更高，译文伸出视口，boundsInRoot 会被窗口裁成同一高度（VR3 实测 193px=193px）
        val heightAt16 = composeRule.onNodeWithText(translation).fetchSemanticsNode().size.height.toFloat()

        runBlocking { settings.setBodyFontSize(22) }
        composeRule.waitUntil(timeoutMillis = 5_000) { readerVm.state.value.bodyFontSizeSp == 22 }
        composeRule.waitForIdle()
        val heightAt22 = composeRule.onNodeWithText(translation).fetchSemanticsNode().size.height.toFloat()

        // 字号 16→22：文本块（扣掉 vertical 8dp×2 的 padding≈48px）应显著变高；
        // 比例至少 1.2（理论 ~22/16=1.375）。行为级证明译文由用户字号驱动。
        val ratio = (heightAt22 - 48f) / (heightAt16 - 48f).coerceAtLeast(1f)
        assertTrue("译文未随用户字号变化：16→${heightAt16}px, 22→${heightAt22}px", ratio > 1.2f)
    }

    @Test @ManualProbe fun capture_translation_toolbar_without_ai_request() {
        val readerVm = ReaderViewModel(ctx)
        val dark = InstrumentationRegistry.getArguments().getString("theme") == "dark"
        composeRule.setContent {
            CatLeafTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    ReaderScreen(localId, readerVm, {}, {}, {})
                }
            }
        }
        composeRule.waitUntil(10_000) { readerVm.state.value.translation != null && !readerVm.state.value.loading }
        readerVm.showTranslation()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("重新翻译").assertIsDisplayed()
        Thread.sleep(400) // Wait for the window buffer after Compose becomes idle.
        java.io.File(ctx.getExternalFilesDir(null), "translation-toolbar.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
