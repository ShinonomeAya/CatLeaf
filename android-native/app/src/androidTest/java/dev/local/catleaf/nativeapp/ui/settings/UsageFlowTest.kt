package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.local.InstanceEntity
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.ui.instance.InstanceIntent
import dev.local.catleaf.nativeapp.ui.instance.InstanceSetupScreen
import dev.local.catleaf.nativeapp.ui.instance.InstanceSetupViewModel
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * UIR09/UIR10（原型 29–31、17）：使用方式页的范围语义。全部数据为隔离的假实例行，
 * 不联网、不碰真实实例；结束后恢复原 settings 并删除本测试写入的行。
 */
@RunWith(AndroidJUnit4::class)
class UsageFlowTest {
    @get:Rule val rule = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val suffix = UUID.randomUUID().toString().take(8)
    private val instance = "isolated-usage-$suffix"
    private val origin = "https://usage-$suffix.example.invalid"
    private var previous: SettingsStore.Snapshot? = null

    @Before fun seed() { runBlocking {
        previous = settings.current()
        db.instances().upsert(InstanceEntity(instance, origin, "隔离实例", lastConnectedAt = 1_700_000_000_000))
        listOf("a", "b").forEach { f ->
            db.feeds().upsert(FeedEntity(instance, "feed-$f-$suffix", "https://example.invalid/$f", "隔离源 $f", "", "ok", null))
        }
        db.articles().upsertArticle(article("1", "feed-a-$suffix", read = true, starred = false))
        db.articles().upsertArticle(article("2", "feed-b-$suffix", read = false, starred = true))
        // 已删源的孤立收藏：来源订阅不在实例订阅表里
        db.articles().upsertArticle(article("3", "gone-$suffix", read = false, starred = true))
    } }

    @After fun cleanup() { runBlocking {
        val w = db.openHelper.writableDatabase
        w.execSQL("DELETE FROM articles WHERE instanceKey = ?", arrayOf(instance))
        db.feeds().deleteAll(instance)
        w.execSQL("DELETE FROM instances WHERE instanceKey = ?", arrayOf(instance))
        previous?.let {
            settings.setCurrentInstanceKey(it.currentInstanceKey)
            settings.setActiveScopeKind(it.activeScopeKind)
        }
    } }

    private fun article(id: String, feedId: String, read: Boolean, starred: Boolean) = ArticleEntity(
        localId = "usage-$id-$suffix", instanceKey = instance, feedId = feedId, guid = "$id-$suffix",
        link = "https://example.invalid/$id", title = "隔离文章 $id", publishedAt = null,
        feedTitle = "隔离源", feedUrl = "https://example.invalid/feed", isRead = read, isStarred = starred,
        starredAt = if (starred) 1L else null, stateUpdatedAt = 1,
    )

    private suspend fun localCounts() =
        db.feeds().list(LOCAL_SCOPE_KEY).size to db.articles().listByInstance(LOCAL_SCOPE_KEY).size

    @Test fun local_scope_selects_local_card_and_instance_card_opens_connect() {
        runBlocking { settings.setActiveScopeKind(SCOPE_KIND_LOCAL) }
        var connectOpened = 0
        val vm = UsageViewModel(app)
        rule.setContent {
            CatLeafTheme { UsageScreen(vm, onBack = {}, onConnectInstance = { connectOpened++ }, onManageInstance = {}) }
        }
        rule.onNodeWithText("本机使用").assertIsDisplayed()
        rule.onNodeWithText("连接实例有什么用？").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("复制已下载内容到本机").assertDoesNotExist()
        rule.onNodeWithText("自有实例").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, connectOpened) }
    }

    @Test fun copy_preview_shows_frozen_counts_and_cancel_writes_nothing() {
        runBlocking {
            settings.setCurrentInstanceKey(instance)
            settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
        }
        val before = runBlocking { localCounts() }
        var backs = 0
        val vm = UsageViewModel(app)
        rule.setContent {
            CatLeafTheme { UsageScreen(vm, onBack = { backs++ }, onConnectInstance = {}, onManageInstance = {}) }
        }
        rule.waitUntil(5_000) { vm.state.value.instanceOrigin == origin }
        rule.onNodeWithText("复制已下载内容到本机").performScrollTo().performClick()
        rule.waitUntil(5_000) { vm.state.value.previewReady }

        rule.onNodeWithText("复制已下载内容，原实例保留").assertIsDisplayed()
        rule.onNodeWithContentDescription("订阅，来源实例 2，本机 ${before.first}").assertIsDisplayed()
        rule.onNodeWithContentDescription("收藏，来源实例 2，本机", substring = true).assertIsDisplayed()
        rule.onNodeWithText("同时带上未选订阅和已删源的收藏（1 条）").performScrollTo()

        // 全不选 → 确认不可用
        rule.onNodeWithText("隔离源 a").performScrollTo().performClick()
        rule.onNodeWithText("隔离源 b").performScrollTo().performClick()
        rule.onNodeWithText("确认复制").performScrollTo().assertIsNotEnabled()
        // 反选后孤立收藏计数跟随（b 的收藏也变为未选）
        rule.onNodeWithText("同时带上未选订阅和已删源的收藏（2 条）").performScrollTo()

        rule.onNodeWithText("取消").performScrollTo().performClick()
        rule.onNodeWithText("当前使用").assertIsDisplayed()
        // 系统返回回到上一页
        Espresso.pressBack()
        rule.runOnIdle { assertEquals(1, backs) }

        val after = runBlocking { localCounts() }
        assertEquals(before, after)
        assertEquals(SCOPE_KIND_INSTANCE, runBlocking { settings.current().activeScopeKind })
    }

    @Test fun system_back_in_preview_returns_to_usage_not_previous_page() {
        runBlocking {
            settings.setCurrentInstanceKey(instance)
            settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
        }
        var backs = 0
        val vm = UsageViewModel(app)
        rule.setContent {
            CatLeafTheme { UsageScreen(vm, onBack = { backs++ }, onConnectInstance = {}, onManageInstance = {}) }
        }
        rule.waitUntil(5_000) { vm.state.value.instanceOrigin == origin }
        rule.onNodeWithText("自有实例").assertIsSelected()
        rule.onNodeWithText("复制已下载内容到本机").performScrollTo().performClick()
        rule.onNodeWithText("复制已下载内容，原实例保留").assertIsDisplayed()
        Espresso.pressBack()
        rule.onNodeWithText("当前使用").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, backs) }
    }

    @Test fun connect_cancel_keeps_local_scope_and_manage_shows_current_instance() {
        runBlocking {
            settings.setCurrentInstanceKey(instance)
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        }
        var cancelled = 0
        val vm = InstanceSetupViewModel(app)
        vm.open(InstanceIntent.Connect, session = 1)
        rule.setContent { CatLeafTheme { InstanceSetupScreen(vm, showBack = true, onBack = { cancelled++ }) } }
        rule.onNodeWithText("连接自有实例").assertIsDisplayed()
        rule.onNodeWithText("连接并切换").performScrollTo()
        rule.onNodeWithText("取消").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, cancelled) }
        assertEquals(SCOPE_KIND_LOCAL, runBlocking { settings.current().activeScopeKind })

        // 同一 VM 换意图进入管理：状态卡显示当前 origin，同址时主按钮为「更新密钥」
        rule.runOnIdle { vm.open(InstanceIntent.Manage, session = 2) }
        rule.waitUntil(5_000) { vm.state.value.managingFrom == origin }
        rule.onNodeWithText("实例管理").assertIsDisplayed()
        rule.onNodeWithText("上次连接成功", substring = true).assertIsDisplayed()
        rule.onNodeWithText("更新密钥").performScrollTo()
        assertTrue(vm.state.value.secret.isEmpty())
    }
}
