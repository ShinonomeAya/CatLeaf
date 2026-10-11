package dev.local.catleaf.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import dev.local.catleaf.nativeapp.data.offline.OfflinePackageStore
import kotlinx.coroutines.launch
import dev.local.catleaf.nativeapp.ui.CatLeafApp
import dev.local.catleaf.nativeapp.ui.settings.SettingsViewModel
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import dev.local.catleaf.nativeapp.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 使用系统启动机制；没有额外的网页启动屏或人为延时
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { runCatching { OfflinePackageStore(this@MainActivity).reconcile() } }
        setContent {
            // 主题跟设置走（P07 阅读分组）：设置页改选即时重着色，SYSTEM 才跟系统
            val settingsVm: SettingsViewModel = viewModel()
            val mode by settingsVm.themeMode.collectAsStateWithLifecycle()
            CatLeafTheme(darkTheme = resolveDarkTheme(mode, isSystemInDarkTheme())) {
                // 根容器给出背景与**默认文字色**：Compose 默认内容色是黑色，
                // 深色主题下不铺这一层会让标题变成黑底黑字（实测过）。
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    CatLeafApp()
                }
            }
        }
    }
}
