package dev.local.catleaf.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.article.ArticleFilter
import dev.local.catleaf.nativeapp.ui.feeds.FeedManagerScreen
import dev.local.catleaf.nativeapp.ui.feeds.FeedManagerStart
import dev.local.catleaf.nativeapp.ui.feeds.FeedManagerViewModel
import dev.local.catleaf.nativeapp.ui.feeds.OpmlViewModel
import dev.local.catleaf.nativeapp.ui.discovery.DiscoveryScreen
import dev.local.catleaf.nativeapp.ui.discovery.DiscoveryViewModel
import dev.local.catleaf.nativeapp.ui.instance.InstanceSetupScreen
import dev.local.catleaf.nativeapp.ui.onboarding.OnboardingScreen
import dev.local.catleaf.nativeapp.ui.search.SearchScreen
import dev.local.catleaf.nativeapp.ui.search.SearchViewModel
import dev.local.catleaf.nativeapp.ui.digest.DigestScreen
import dev.local.catleaf.nativeapp.ui.digest.DigestViewModel
import dev.local.catleaf.nativeapp.ui.favorites.FavoritesScreen
import dev.local.catleaf.nativeapp.ui.favorites.FavoritesViewModel
import dev.local.catleaf.nativeapp.ui.settings.AiSettingsScreen
import dev.local.catleaf.nativeapp.ui.settings.AiSettingsViewModel
import dev.local.catleaf.nativeapp.ui.settings.AboutScreen
import dev.local.catleaf.nativeapp.ui.settings.ReadingPreferencesScreen
import dev.local.catleaf.nativeapp.ui.settings.SettingsScreen
import dev.local.catleaf.nativeapp.ui.settings.UsageScreen
import dev.local.catleaf.nativeapp.ui.settings.UsageViewModel
import dev.local.catleaf.nativeapp.ui.settings.SettingsViewModel
import dev.local.catleaf.nativeapp.ui.settings.StateBackupViewModel
import dev.local.catleaf.nativeapp.ui.settings.StorageScreen
import dev.local.catleaf.nativeapp.ui.settings.StorageViewModel
import dev.local.catleaf.nativeapp.ui.reading.ReadingScreen
import dev.local.catleaf.nativeapp.ui.reading.ReadingViewModel
import dev.local.catleaf.nativeapp.ui.reader.ReaderScreen
import dev.local.catleaf.nativeapp.ui.reader.ReaderViewModel
import dev.local.catleaf.nativeapp.ui.instance.InstanceSetupViewModel
import dev.local.catleaf.nativeapp.ui.instance.InstanceIntent

/**
 * 根导航骨架：阅读 / 收藏 / 设置三个入口。
 * 尚未配置实例时先进入 P01 连接页（原型 05）；配置完成后进入根页。
 * 业务页面在 T08–T20 按各自任务实现，这里不预建空壳页面。
 *
 * 图标使用 docs/assets/ui 交付的 Lucide SVG 转换结果（见 ui-manifest.json），
 * 不引入 Material 默认图标库或动态色。
 */
private enum class RootTab(
    val labelRes: Int,
    val iconRes: Int,
    val selectedIconRes: Int = 0,
) {
    Reading(R.string.tab_reading, R.drawable.ic_nav_reading),
    Favorites(R.string.tab_favorites, R.drawable.ic_nav_favorites, R.drawable.ic_nav_favorites_active),
    Settings(R.string.tab_settings, R.drawable.ic_nav_settings),
}

@Composable
private fun RootTab.icon(selected: Boolean): Painter =
    painterResource(if (selected && selectedIconRes != 0) selectedIconRes else iconRes)

private enum class RootGate { Checking, NeedsInstance, Ready, Onboarding }

@Composable
fun CatLeafApp() {
    val setupViewModel: InstanceSetupViewModel = viewModel()
    // 与 MainActivity 同一个（Activity 作用域）：主题即时预览读它，重置信号也从它发
    val settingsViewModel: SettingsViewModel = viewModel()

    var gate by remember { mutableStateOf(RootGate.Checking) }
    // 只读判定（不发网络）：本机范围直接进根页（ADR 003 默认本机）；
    // 实例范围要求实例行还在（密钥暂不可读仍可读缓存，修复提示在页面内），指针悬空才回 P01 修复
    LaunchedEffect(setupViewModel) {
        gate = when {
            !setupViewModel.isScopeReady() -> RootGate.NeedsInstance
            setupViewModel.onboardingPending() -> RootGate.Onboarding
            else -> RootGate.Ready
        }
    }
    // 重置此设备数据后重新判定：设置已清空 → 全新用户 → 本机范围（不强制回 P01）
    val resetTick by settingsViewModel.resetTick.collectAsState()
    LaunchedEffect(resetTick) {
        if (resetTick > 0) {
            gate = when {
                !setupViewModel.isScopeReady() -> RootGate.NeedsInstance
                setupViewModel.onboardingPending() -> RootGate.Onboarding
                else -> RootGate.Ready
            }
        }
    }

    when (gate) {
        RootGate.Checking -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }

        RootGate.NeedsInstance -> {
            // 连接成功后重新判定，进入根页
            val state by setupViewModel.state.collectAsState()
            LaunchedEffect(state.connectedOrigin) {
                if (state.connectedOrigin != null) gate = RootGate.Ready
            }
            InstanceSetupScreen(setupViewModel)
        }

        RootGate.Onboarding -> OnboardingGate(onDone = { gate = RootGate.Ready })

        RootGate.Ready -> {
            // 二级页面整屏替换根页，不重复底部导航（设计规范 §6）
            var showFeedManager by rememberSaveable { mutableStateOf(false) }
            // 阅读空态「手动添加 / 导入 OPML」直接进入对应流程，结束即回阅读页（UIR03）
            var feedManagerStart by rememberSaveable { mutableStateOf<FeedManagerStart?>(null) }
            // 入口选择放在根导航这一层：详情页整屏替换根页，回来后仍停在原入口
            var selectedTab by rememberSaveable { mutableStateOf(RootTab.Reading) }
            var showAiSettings by rememberSaveable { mutableStateOf(false) }
            var showStorage by rememberSaveable { mutableStateOf(false) }
            var openOfflineManager by rememberSaveable { mutableStateOf(false) }
            var returnToOfflineManager by rememberSaveable { mutableStateOf(false) }
            var showAbout by rememberSaveable { mutableStateOf(false) }
            // 阅读偏好独立页（T22 F13）：设置根页与阅读器 Aa 共用；从阅读器进入时
            // openArticleId 保持不变，返回自动落回同一篇并按锚点恢复正文位置
            var showReadingPrefs by rememberSaveable { mutableStateOf(false) }
            // 实例分组（P07）：换密钥或连接/切换实例复用 P01 流程，用独立 VM 与首配互不干扰
            var showInstanceConnect by rememberSaveable { mutableStateOf(false) }
            var connectIntent by rememberSaveable { mutableStateOf(InstanceIntent.Connect.name) }
            var connectSession by rememberSaveable { mutableStateOf(0) }
            // 发现订阅源（L04）：入口在订阅管理与阅读空态；预填的目录条目交给管理页表单
            var showDiscovery by rememberSaveable { mutableStateOf(false) }
            // 发现页叠在管理列表之上打开（返回回管理列表）；从发现页进入的添加/OPML 流程结束后回发现页
            var discoveryOverManager by rememberSaveable { mutableStateOf(false) }
            var returnToDiscovery by rememberSaveable { mutableStateOf(false) }
            var discoverySession by rememberSaveable { mutableStateOf(0) }
            // 使用方式（L05）：本机默认/实例可选/复制到本机
            var showUsage by rememberSaveable { mutableStateOf(false) }
            // 文章搜索（Q01）：阅读页头部进入，返回锚由详情页机制接管
            var showSearch by rememberSaveable { mutableStateOf(false) }
            val searchVm: SearchViewModel = viewModel()
            val searchListState = rememberLazyListState()
            LaunchedEffect(showSearch) { if (showSearch) searchListState.scrollToItem(0) }
            var pendingPrefill by rememberSaveable { mutableStateOf<String?>(null) }
            // 详情页只记 localId：正文与状态都从本地读，旋转/进程恢复后重新载入同一篇
            var openArticleId by rememberSaveable { mutableStateOf<String?>(null) }
            // 日摘要（P05）：源固定为进入时的当前源；date 为 null 时摘要页引导先选日期
            var digestFeedId by rememberSaveable { mutableStateOf<String?>(null) }
            var digestDate by rememberSaveable { mutableStateOf<String?>(null) }
            val articleId = openArticleId
            when {
                showDiscovery -> {
                    val discoveryVm: DiscoveryViewModel = viewModel()
                    // enterSession 按 token 幂等，重组不重置条件
                    discoveryVm.enterSession(discoverySession)
                    // 从发现页进入单一流程：结束（提交/取消/选择器取消）后回到发现页原搜索与滚位
                    val startFromDiscovery: (FeedManagerStart) -> Unit = { start ->
                        feedManagerStart = start
                        returnToDiscovery = true
                        showDiscovery = false
                        showFeedManager = true
                    }
                    DiscoveryScreen(
                        viewModel = discoveryVm,
                        onBack = { showDiscovery = false },
                        onPrefillAdd = { entry ->
                            // url|名称|建议分组：打开预填表单（复用共同新增与验证）
                            pendingPrefill = "${entry.feedUrl}|${entry.name}|"
                            startFromDiscovery(FeedManagerStart.Prefilled)
                        },
                        onManualAdd = { pendingPrefill = null; startFromDiscovery(FeedManagerStart.AddForm) },
                        onImportOpml = { pendingPrefill = null; startFromDiscovery(FeedManagerStart.OpmlPicker) },
                    )
                }

                showSearch && articleId == null && !showStorage -> {
                    SearchScreen(
                        viewModel = searchVm,
                        onBack = { showSearch = false },
                        onOpenArticle = { openArticleId = it },
                        listState = searchListState,
                    )
                }

                showFeedManager -> FeedManagerHost(
                    prefill = pendingPrefill,
                    onPrefillConsumed = { pendingPrefill = null },
                    startAction = feedManagerStart,
                    onBack = {
                        feedManagerStart = null
                        if (returnToDiscovery) {
                            returnToDiscovery = false
                            showFeedManager = discoveryOverManager
                            showDiscovery = true
                        } else {
                            showFeedManager = false
                        }
                    },
                    onOpenDiscovery = { discoveryOverManager = true; discoverySession++; showDiscovery = true },
                    // 添加结果「继续阅读」：不论从哪进来都落到阅读页（原型 34）
                    onContinueReading = {
                        feedManagerStart = null
                        returnToDiscovery = false
                        discoveryOverManager = false
                        showFeedManager = false
                        selectedTab = RootTab.Reading
                    },
                )

                showAiSettings -> {
                    val aiVm: AiSettingsViewModel = viewModel()
                    AiSettingsScreen(viewModel = aiVm, onBack = { showAiSettings = false })
                }

                showStorage -> {
                    val storageVm: StorageViewModel = viewModel()
                    val stateVm: StateBackupViewModel = viewModel()
                    StorageScreen(
                        viewModel = storageVm,
                        stateVm = stateVm,
                        onBack = { showStorage = false; openOfflineManager = false },
                        startOfflineManager = openOfflineManager,
                        onOpenOfflineArticle = { localId ->
                            returnToOfflineManager = true
                            openArticleId = localId
                            showStorage = false
                        },
                    )
                }

                showAbout -> AboutScreen(onBack = { showAbout = false })

                showReadingPrefs -> ReadingPreferencesScreen(
                    viewModel = settingsViewModel,
                    onBack = { showReadingPrefs = false },
                )

                showInstanceConnect -> {
                    val connectVm: InstanceSetupViewModel = viewModel(key = "settingsConnect")
                    // UIR10：按进入意图打开（本机→连接 30 / 实例→管理 17），每次进入换 session，
                    // 不带上一回的「已连接」结果；已存密钥不回显
                    val intent = InstanceIntent.valueOf(connectIntent)
                    // open 按 session 幂等：同一次进入的重组/旋转不清空已填内容
                    connectVm.open(intent, connectSession)
                    // 仅连接成功才返回使用方式页（connect() 成功时已切到实例）；取消/返回不改使用方式
                    val connectState by connectVm.state.collectAsState()
                    LaunchedEffect(connectState.connectedOrigin, connectState.intent) {
                        if (connectState.connectedOrigin != null && connectState.intent == intent) showInstanceConnect = false
                    }
                    InstanceSetupScreen(connectVm, showBack = true, onBack = { showInstanceConnect = false })
                }

                showUsage -> {
                    // 从连接/管理页回来时本页重新进入组合，load() 重新读取范围
                    val usageVm: UsageViewModel = viewModel()
                    UsageScreen(
                        viewModel = usageVm,
                        onBack = { showUsage = false },
                        onConnectInstance = {
                            connectIntent = InstanceIntent.Connect.name
                            connectSession++
                            showInstanceConnect = true
                        },
                        onManageInstance = {
                            connectIntent = InstanceIntent.Manage.name
                            connectSession++
                            showInstanceConnect = true
                        },
                    )
                }

                articleId != null -> {
                    val readerVm: ReaderViewModel = viewModel()
                    ReaderScreen(
                        localId = articleId,
                        viewModel = readerVm,
                        onBack = {
                            openArticleId = null
                            if (returnToOfflineManager) {
                                openOfflineManager = true
                                showStorage = true
                                returnToOfflineManager = false
                            }
                        },
                        onOpenPreferences = { showReadingPrefs = true },
                        onOpenStorage = { openOfflineManager = true; showStorage = true },
                    )
                }

                digestFeedId != null -> {
                    val digestVm: DigestViewModel = viewModel()
                    val feed = digestFeedId
                    DigestScreen(
                        feedId = feed.orEmpty(),
                        seedDate = digestDate,
                        viewModel = digestVm,
                        onBack = { digestFeedId = null },
                        onOpenArticle = { openArticleId = it },
                        // AI 设置分支排在摘要之前：返回时仍回到本摘要页
                        onOpenAiSettings = { showAiSettings = true },
                    )
                }

                else -> RootScaffold(
                    selected = selectedTab,
                    onSelectTab = { selectedTab = it },
                    settingsVm = settingsViewModel,
                    onOpenReadingPrefs = { showReadingPrefs = true },
                    onOpenFeedManager = { feedManagerStart = null; showFeedManager = true },
                    onOpenManualAdd = { feedManagerStart = FeedManagerStart.AddForm; showFeedManager = true },
                    onOpenOpmlImport = { feedManagerStart = FeedManagerStart.OpmlPicker; showFeedManager = true },
                    onOpenAiSettings = { showAiSettings = true },
                    onOpenStorage = { openOfflineManager = false; showStorage = true },
                    onOpenAbout = { showAbout = true },
                    onOpenArticle = { openArticleId = it },
                    onOpenDiscovery = {
                        pendingPrefill = null
                        feedManagerStart = null
                        showFeedManager = false
                        discoveryOverManager = false
                        discoverySession++
                        showDiscovery = true
                    },
                    onOpenDigest = { feedId, date ->
                        digestFeedId = feedId ?: DigestViewModel.ALL_FEEDS
                        digestDate = date
                    },
                    onOpenUsage = { showUsage = true },
                    onOpenSearch = { feedId, dateScope, unreadOnly, starredOnly ->
                        searchVm.open(feedId, dateScope, unreadOnly, starredOnly)
                        showSearch = true
                    },
                )
            }
        }
    }
}

/**
 * 引导门（原型 23–24，UIR01/02）：发现、手动添加、导入 OPML 都在引导内整屏打开，
 * 返回/取消回到引导原步骤；只有跳过或「完成并阅读」才进入根页（页面说明 §10）。
 */
private enum class OnboardingSub { None, Discovery, Manual, Opml, Prefilled }

@Composable
private fun OnboardingGate(onDone: () -> Unit) {
    var sub by rememberSaveable { mutableStateOf(OnboardingSub.None) }
    var prefill by rememberSaveable { mutableStateOf<String?>(null) }
    // 从发现页进入的手动添加/OPML：结束后回发现页，而不是回引导
    var backToDiscovery by rememberSaveable { mutableStateOf(false) }
    when (sub) {
        OnboardingSub.None -> OnboardingScreen(
            onDone = onDone,
            onOpenDiscovery = { sub = OnboardingSub.Discovery },
            onManualAdd = { backToDiscovery = false; sub = OnboardingSub.Manual },
            onImportOpml = { backToDiscovery = false; sub = OnboardingSub.Opml },
        )
        OnboardingSub.Discovery -> {
            val discoveryVm: DiscoveryViewModel = viewModel()
            // 引导内固定一个会话：往返添加流程保留条件；进入根页后第一次打开发现页（token 0）重新开始
            discoveryVm.enterSession(-1)
            DiscoveryScreen(
                viewModel = discoveryVm,
                onBack = { sub = OnboardingSub.None },
                onPrefillAdd = { entry ->
                    prefill = "${entry.feedUrl}|${entry.name}|"
                    sub = OnboardingSub.Prefilled
                },
                onManualAdd = { backToDiscovery = true; sub = OnboardingSub.Manual },
                onImportOpml = { backToDiscovery = true; sub = OnboardingSub.Opml },
            )
        }
        OnboardingSub.Manual, OnboardingSub.Opml, OnboardingSub.Prefilled -> FeedManagerHost(
            prefill = prefill,
            onPrefillConsumed = { prefill = null },
            startAction = when (sub) {
                OnboardingSub.Manual -> FeedManagerStart.AddForm
                OnboardingSub.Opml -> FeedManagerStart.OpmlPicker
                else -> FeedManagerStart.Prefilled
            },
            // 预填表单或发现页头部入口：结束后回发现页继续挑选，否则回引导
            onBack = {
                sub = if (sub == OnboardingSub.Prefilled || backToDiscovery) OnboardingSub.Discovery else OnboardingSub.None
                backToDiscovery = false
            },
            onOpenDiscovery = null,
        )
    }
}

/** 订阅管理宿主：接好 OPML 依赖与发现页预填（url|名称|分组），根页与引导共用 */
@Composable
private fun FeedManagerHost(
    prefill: String?,
    onPrefillConsumed: () -> Unit,
    startAction: FeedManagerStart?,
    onBack: () -> Unit,
    onOpenDiscovery: (() -> Unit)?,
    onContinueReading: (() -> Unit)? = null,
) {
    val vm: FeedManagerViewModel = viewModel()
    val opmlVm: OpmlViewModel = viewModel()
    LaunchedEffect(vm) {
        // 用同一个已连接实例的 store/importer，OPML 才能复用实例 client 与本地镜像
        vm.opmlDependencies()?.let { (registry, importer) -> opmlVm.attach(registry, importer) }
        prefill?.let {
            val parts = it.split("|")
            vm.openPrefilledAddForm(
                url = parts.getOrElse(0) { "" },
                title = parts.getOrElse(1) { "" },
                category = parts.getOrElse(2) { "" },
            )
            onPrefillConsumed()
        }
    }
    FeedManagerScreen(
        viewModel = vm,
        onBack = onBack,
        opmlViewModel = opmlVm,
        onOpenDiscovery = onOpenDiscovery,
        startAction = startAction,
        onContinueReading = onContinueReading,
    )
}

@Composable
private fun RootScaffold(
    selected: RootTab,
    onSelectTab: (RootTab) -> Unit,
    settingsVm: SettingsViewModel,
    onOpenReadingPrefs: () -> Unit,
    onOpenFeedManager: () -> Unit,
    onOpenManualAdd: () -> Unit,
    onOpenOpmlImport: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenSearch: (String?, ArticleFilter.DateScope, Boolean, Boolean) -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenArticle: (String) -> Unit,
    onOpenDigest: (feedId: String?, date: String?) -> Unit,
    onOpenDiscovery: () -> Unit,
) {
    // The 72dp base grows with the label line; system navigation insets stay separate.
    val navigationBarHeight = with(LocalDensity.current) {
        (52.dp + MaterialTheme.typography.labelMedium.lineHeight.toDp()).coerceAtLeast(72.dp)
    }
    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(NavigationBarDefaults.windowInsets).height(navigationBarHeight),
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                RootTab.entries.forEach { tab ->
                    val selectedTab = selected == tab
                    val label = stringResource(tab.labelRes)
                    NavigationBarItem(
                        selected = selectedTab,
                        onClick = { onSelectTab(tab) },
                        icon = {
                            Icon(
                                painter = tab.icon(selectedTab),
                                contentDescription = label,
                            )
                        },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        // 每个入口自己填满内容区：阅读页与收藏页整屏（各自带列表/筛选），设置页是入口列表。
        // 这里不再统一加内边距与居中排列——否则会双重内边距、内容被居中，
        // 而且根页品牌标题会和页面自己的标题重复。
        when (selected) {
            RootTab.Reading -> Box(Modifier.fillMaxSize().padding(innerPadding)) {
                val readingVm: ReadingViewModel = viewModel()
                ReadingScreen(
                    readingVm,
                    onOpenArticle = onOpenArticle,
                    onOpenDigest = onOpenDigest,
                    // T22 F04：订阅面板的「管理订阅」沿现有回调进入管理页
                    onOpenManageSubscriptions = onOpenFeedManager,
                    onOpenDiscovery = onOpenDiscovery,
                    onOpenManualAdd = onOpenManualAdd,
                    onOpenOpmlImport = onOpenOpmlImport,
                    onOpenSearch = { feedId, dateScope, unreadOnly ->
                        onOpenSearch(feedId, dateScope, unreadOnly, false)
                    },
                )
            }

            RootTab.Favorites -> Box(Modifier.fillMaxSize().padding(innerPadding)) {
                val favoritesVm: FavoritesViewModel = viewModel()
                FavoritesScreen(
                    favoritesVm,
                    onOpenArticle = onOpenArticle,
                    onOpenSearch = { onOpenSearch(null, ArticleFilter.DateScope.All, false, true) },
                    // 原型 06：收藏空态回阅读
                    onGoReading = { onSelectTab(RootTab.Reading) },
                )
            }

            RootTab.Settings -> Box(Modifier.fillMaxSize().padding(innerPadding)) {
                SettingsScreen(
                    viewModel = settingsVm,
                    onOpenReadingPrefs = onOpenReadingPrefs,
                    onOpenFeedManager = onOpenFeedManager,
                    onOpenAiSettings = onOpenAiSettings,
                    onOpenUsage = onOpenUsage,
                    onOpenStorage = onOpenStorage,
                    onOpenAbout = onOpenAbout,
                )
            }
        }
    }
}
