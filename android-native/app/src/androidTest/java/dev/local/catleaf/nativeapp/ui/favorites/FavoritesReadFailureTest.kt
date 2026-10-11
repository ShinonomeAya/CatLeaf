package dev.local.catleaf.nativeapp.ui.favorites

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoritesReadFailureTest {
    @get:Rule val rule = createComposeRule()

    @Test fun database_failure_is_an_error_and_never_an_empty_collection() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CatLeafDatabase::class.java).build()
        // The fault is confined to this empty in-memory database, never the user's store.
        db.openHelper.writableDatabase.execSQL("ALTER TABLE feeds RENAME TO rf_unreadable_feeds")
        val vm = FavoritesViewModel(app, db)
        rule.setContent { CatLeafTheme { FavoritesScreen(vm, onOpenArticle = {}) } }
        rule.waitUntil(10000) { vm.state.value.error != null }
        rule.onNodeWithText("收藏读取失败，请重试").assertExists()
        rule.onNodeWithText("还没有收藏").assertDoesNotExist()
        rule.onNodeWithText("重试").assertExists()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(app.getExternalFilesDir(null), "rf-favorites-error.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        db.close()
    }
}
