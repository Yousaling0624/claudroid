package app.claudroid.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.AppContainer
import app.claudroid.BuildConfig
import app.claudroid.core.Logx
import app.claudroid.core.Paths
import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppRelease
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import app.claudroid.data.EffortLevel
import app.claudroid.data.EngineMode
import app.claudroid.data.ModelTier
import app.claudroid.data.PermissionMode
import app.claudroid.data.Providers
import app.claudroid.data.ReleaseCheck
import app.claudroid.data.ReleaseStatus
import app.claudroid.engine.EngineManager
import app.claudroid.engine.openai.ModelCatalog
import app.claudroid.ui.components.ClaudeDivider
import app.claudroid.ui.components.ClaudeField
import app.claudroid.ui.components.ClaudeMark
import app.claudroid.ui.components.CodeCard
import app.claudroid.ui.components.GhostButton
import app.claudroid.ui.components.ModelListSheet
import app.claudroid.ui.components.Motion
import app.claudroid.ui.components.PillChip
import app.claudroid.ui.components.PrimaryButton
import app.claudroid.ui.components.SettingsCategoryCard
import app.claudroid.ui.components.SettingsGlassDialog
import app.claudroid.ui.components.SettingsOptionRow
import app.claudroid.ui.components.SettingsPageHeader
import app.claudroid.ui.components.SettingsRow
import app.claudroid.ui.components.SettingsSection
import app.claudroid.ui.components.SettingsTopBar
import app.claudroid.ui.components.WarmSwitch
import app.claudroid.ui.components.activeTierSummary
import app.claudroid.ui.components.hazeSource
import app.claudroid.ui.components.rememberFrostedState
import app.claudroid.ui.components.tierMappingLabel
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.LocalReduceMotion
import app.claudroid.ui.theme.Radii
import app.claudroid.ui.theme.SettingsType
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.launch

private enum class ModelTarget { DEFAULT, SMALL }

/**
 * Three-level settings IA. The root is a short list of icon+summary categories; each
 * detail page owns one scrollable column of the existing controls; a handful of
 * genuinely nested choices (permission mode, engine logs, extra environment) get a
 * third page instead of an inline expander. Keeping the state (async operations,
 * sheets, dialogs) in this one composable means a turn or model fetch is never
 * cancelled by navigating between categories, and BackHandler follows the expected
 * order: modal -> sub-page -> detail -> root -> leave settings.
 */
private enum class Page(val title: String, val icon: ImageVector) {
    ROOT("设置", AppIcons.Settings),
    API("接口与模型", AppIcons.Plug),
    TIERS("模型档位", AppIcons.Layers),
    ENGINE("引擎与权限", AppIcons.Shield),
    LABS("功能与实验", AppIcons.Flask),
    APPEARANCE("外观", AppIcons.Palette),
    WORKSPACE("工作区", AppIcons.Folder),
    ADVANCED("高级", AppIcons.Sliders),
    ABOUT("关于", AppIcons.Info),
}

/** The three pages that earn a real third level instead of an inline control. */
private enum class SubPage(val parent: Page) {
    PERMISSIONS(Page.ENGINE),
    ENGINE_LOGS(Page.ABOUT),
    EXTRA_ENV(Page.ADVANCED),
}

/** Root row metadata: icon + one-line summary of what lives behind it. */
private data class Category(val page: Page, val summary: String)

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val settings by container.settings.flow.collectAsState(initial = AppSettings())
    val engineState by container.engine.state.collectAsState()
    val logs by Logx.lines.collectAsState()
    val reduceMotion = LocalReduceMotion.current
    val frostedState = rememberFrostedState()
    // AnimatedContent disposes the outgoing page, so a page-local rememberScrollState
    // would forget where the user was. Keep one state per destination for the life of
    // the settings screen; returning from a third-level page lands back on its row.
    val scrollStates = remember { mutableMapOf<String, ScrollState>() }

    // Saved as enum names so rotation (or process restore) keeps the open detail or
    // sub-page; an unknown name falls back to the parent/root. Page-local UI state
    // below is deliberately not saved — leaving a page should discard its output.
    var pageName by rememberSaveable { mutableStateOf(Page.ROOT.name) }
    val page = runCatching { Page.valueOf(pageName) }.getOrDefault(Page.ROOT)
    var subPageName by rememberSaveable { mutableStateOf<String?>(null) }
    val subPage = subPageName?.let { runCatching { SubPage.valueOf(it) }.getOrNull() }
    val currentPage = subPage?.parent ?: page

    var testingEngine by remember { mutableStateOf(false) }
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelTarget by remember { mutableStateOf<ModelTarget?>(null) }
    var tierSheet by remember { mutableStateOf<ModelTier?>(null) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var testingConnection by remember { mutableStateOf(false) }
    var connectionMessage by remember { mutableStateOf<String?>(null) }
    var connectionOk by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<EngineManager.StorageUsage?>(null) }
    var showUninstall by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var releaseError by remember { mutableStateOf<String?>(null) }
    var checkingRelease by remember { mutableStateOf(true) }

    suspend fun checkRelease() {
        checkingRelease = true
        ReleaseCheck.fetch()
            .onSuccess {
                release = it
                releaseError = null
            }
            .onFailure { releaseError = it.message ?: "网络不可用" }
        checkingRelease = false
    }

    fun fetchModels() {
        loadingModels = true
        modelError = null
        scope.launch {
            runCatching { ModelCatalog.fetch(settings) }
                .onSuccess { result ->
                    if (result.models.isEmpty()) {
                        modelError = "接口返回为空：${result.endpoint}"
                    } else {
                        modelList = result.models
                        modelTarget = ModelTarget.DEFAULT
                    }
                }
                .onFailure {
                    modelError = "拉取失败：${it.message ?: "网络不可用"}。检查 Base URL、Key 与鉴权方式后重试。"
                }
            loadingModels = false
        }
    }

    fun testConnection() {
        testingConnection = true
        connectionMessage = "正在连接…"
        connectionOk = false
        scope.launch {
            runCatching { ModelCatalog.fetch(settings) }
                .onSuccess { result ->
                    if (result.models.isEmpty()) {
                        connectionOk = false
                        connectionMessage = "已连接，但模型列表为空：${result.endpoint}。请确认端点与协议是否匹配。"
                    } else {
                        connectionOk = true
                        connectionMessage = "连接成功 · ${result.models.size} 个可用模型"
                    }
                }
                .onFailure {
                    connectionOk = false
                    connectionMessage =
                        "连接失败：${it.message ?: "网络不可用"}。检查 Base URL、API Key 与鉴权方式后重试。"
                }
            testingConnection = false
        }
    }

    fun setActiveTier(tier: ModelTier?) {
        scope.launch { container.settings.edit { it.copy(activeTier = tier?.name ?: "") } }
    }

    fun selectAutoModel() {
        scope.launch { container.settings.edit { it.copy(activeTier = AppSettings.AUTO_MODEL) } }
    }

    fun openPage(target: Page) {
        subPageName = null
        pageName = target.name
    }

    fun openSub(target: SubPage) {
        subPageName = target.name
    }

    fun goBack() {
        when {
            subPage != null -> subPageName = null
            currentPage != Page.ROOT -> pageName = Page.ROOT.name
            else -> onBack()
        }
    }

    LaunchedEffect(Unit) { checkRelease() }
    LaunchedEffect(engineState.installedVersion) {
        usage = if (engineState.installedVersion == null) null else container.engine.storageUsage()
    }

    // One handler covers sub-page -> detail -> root; root back is left to MainActivity's
    // route handler. Sheets/dialogs register their own handler later and therefore win.
    BackHandler(enabled = subPage != null || currentPage != Page.ROOT) {
        if (subPage != null) subPageName = null else pageName = Page.ROOT.name
    }

    val destination = subPage?.let { "sub:${it.name}" } ?: currentPage.name
    val backLabel = when {
        subPage != null -> "返回${currentPage.title}"
        currentPage != Page.ROOT -> "返回设置"
        else -> "返回"
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(frostedState)
                .background(MaterialTheme.appColors.canvas)
        ) {
            AnimatedContent(
                targetState = destination,
                transitionSpec = {
                    fun level(value: String) = when {
                        value.startsWith("sub:") -> 2
                        value == Page.ROOT.name -> 0
                        else -> 1
                    }
                    val forward = level(targetState) >= level(initialState)
                    if (reduceMotion) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else if (forward) {
                        (fadeIn(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) +
                            slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) { it / 20 })
                            .togetherWith(
                                fadeOut(tween(Motion.FAST)) +
                                    slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) { -it / 40 }
                            )
                    } else {
                        (fadeIn(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) +
                            slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) { -it / 20 })
                            .togetherWith(
                                fadeOut(tween(Motion.FAST)) +
                                    slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EaseChrome)) { it / 40 }
                            )
                    }
                },
                label = "settings-page",
            ) { key ->
                val targetSub = key
                    .takeIf { it.startsWith("sub:") }
                    ?.removePrefix("sub:")
                    ?.let { runCatching { SubPage.valueOf(it) }.getOrNull() }
                val targetPage = targetSub?.parent
                    ?: runCatching { Page.valueOf(key) }.getOrDefault(Page.ROOT)
                // A single scroll container per page, keyed so each page keeps its own
                // scroll position and the activity line never fights the route transition.
                val scrollState = remember(key) {
                    scrollStates.getOrPut(key) { ScrollState(0) }
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .verticalScroll(scrollState)
                        .navigationBarsPadding()
                        .padding(horizontal = 18.dp)
                ) {
                    // The floating bar is 46dp + 8dp vertical padding below the status
                    // bar; 76dp lets the first heading start just under it while the
                    // page still scrolls behind the glass.
                    Spacer(Modifier.height(76.dp))
                    when {
                        targetSub == SubPage.PERMISSIONS -> PermissionsPage(settings, scope, container)
                        targetSub == SubPage.ENGINE_LOGS -> EngineLogsPage(logs)
                        targetSub == SubPage.EXTRA_ENV -> EnvPage(settings, scope, container)
                        targetPage == Page.ROOT -> RootPage(
                            settings = settings,
                            engineState = engineState,
                            onOpen = { openPage(it) },
                        )
                        targetPage == Page.API -> ApiPage(
                            settings = settings,
                            scope = scope,
                            container = container,
                            focusManager = focusManager,
                            loadingModels = loadingModels,
                            testingConnection = testingConnection,
                            modelError = modelError,
                            connectionMessage = connectionMessage,
                            connectionOk = connectionOk,
                            modelList = modelList,
                            onFetchModels = ::fetchModels,
                            onTestConnection = ::testConnection,
                            onPickSmall = { modelTarget = ModelTarget.SMALL },
                        )
                        targetPage == Page.TIERS -> TierPage(
                            settings = settings,
                            onSetTier = ::setActiveTier,
                            onSelectAuto = ::selectAutoModel,
                            onOpenTier = { tier -> tierSheet = tier },
                        )
                        targetPage == Page.ENGINE -> EnginePage(
                            container = container,
                            settings = settings,
                            engineState = engineState,
                            scope = scope,
                            testingEngine = testingEngine,
                            onTestingChange = { testingEngine = it },
                            onUninstall = { showUninstall = true },
                            onOpenPermissions = { openSub(SubPage.PERMISSIONS) },
                        )
                        targetPage == Page.LABS -> LabsPage(
                            settings = settings,
                            scope = scope,
                            container = container,
                        )
                        targetPage == Page.APPEARANCE -> AppearancePage(
                            settings = settings,
                            haptic = haptic,
                            scope = scope,
                            container = container,
                        )
                        targetPage == Page.WORKSPACE -> WorkspacePage(
                            appContext = appContext,
                            usage = usage,
                            onOpenWorkspace = onOpenWorkspace,
                            onUninstall = { showUninstall = true },
                        )
                        targetPage == Page.ADVANCED -> AdvancedPage(
                            settings = settings,
                            scope = scope,
                            container = container,
                            onOpenEnv = { openSub(SubPage.EXTRA_ENV) },
                        )
                        else -> AboutPage(
                            appContext = appContext,
                            logs = logs,
                            release = release,
                            releaseError = releaseError,
                            checkingRelease = checkingRelease,
                            testingEngine = testingEngine,
                            scope = scope,
                            container = container,
                            onCheckRelease = { scope.launch { checkRelease() } },
                            onTestingChange = { testingEngine = it },
                            onOpenLogs = { openSub(SubPage.ENGINE_LOGS) },
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }

        SettingsTopBar(
            backLabel = backLabel,
            onBack = { goBack() },
            frostedState = frostedState,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // -------------------------------------------- sheets & dialogs (screen-level)
        modelTarget?.let { target ->
            val isDefault = target == ModelTarget.DEFAULT
            ModelListSheet(
                title = if (isDefault) "默认模型" else "小模型（可选）",
                description = if (isDefault) {
                    "未选择档位时使用；档位映射留空时也会回退到这里。"
                } else {
                    "留空时使用 Haiku 档位映射，再回退默认模型。"
                },
                models = modelList,
                current = if (isDefault) settings.model else settings.smallModel,
                emptyOption = if (isDefault) null else "留空（跟随 Haiku / 默认模型）",
                onSelect = { value ->
                    scope.launch {
                        container.settings.edit {
                            if (isDefault) it.copy(model = value) else it.copy(smallModel = value)
                        }
                    }
                },
                onDismiss = { modelTarget = null },
                frostedState = frostedState,
            )
        }

        tierSheet?.let { tier ->
            ModelListSheet(
                title = "${tier.label} 档位",
                description = "留空则使用官方默认/跟随默认模型。Anthropic 兼容网关会写入 " +
                    "ANTHROPIC_DEFAULT_${tier.name}_MODEL；OpenAI 兼容由内置代理按档位转发。",
                models = modelList,
                current = settings.tierModel(tier),
                emptyOption = "使用官方默认",
                onSelect = { value ->
                    scope.launch { container.settings.edit { it.withTierModel(tier, value) } }
                },
                onDismiss = { tierSheet = null },
                frostedState = frostedState,
            )
        }

        if (showUninstall) {
            SettingsGlassDialog(
                title = "卸载内置引擎",
                message = "将删除 jail 里的 Claude Code 与运行环境（约 300MB）。会话记录默认保留；" +
                    "选择「全部删除」会同时清空工作区和会话。",
                onDismiss = { showUninstall = false },
                frostedState = frostedState,
            ) {
                PrimaryButton(
                    "仅卸载引擎",
                    {
                        showUninstall = false
                        scope.launch {
                            container.engine.uninstall(deleteWorkspace = false)
                            usage = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    "全部删除（含工作区与会话）",
                    {
                        showUninstall = false
                        scope.launch {
                            container.engine.uninstall(deleteWorkspace = true)
                            usage = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    destructive = true,
                )
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    "取消",
                    { showUninstall = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- root
@Composable
private fun RootPage(
    settings: AppSettings,
    engineState: EngineManager.State,
    onOpen: (Page) -> Unit,
) {
    val categories = listOf(
        Category(Page.API, "协议、Base URL、Key、默认与小模型"),
        Category(Page.TIERS, activeTierSummary(settings)),
        Category(Page.ENGINE, "安装、运行方式、Root 与权限模式"),
        Category(Page.LABS, "Effort、子代理模型与实验开关"),
        Category(Page.APPEARANCE, "浅色 / 深色 / 跟随系统"),
        Category(Page.WORKSPACE, "/work 预览、存储与清理"),
        Category(Page.ADVANCED, "自动更新、系统提示词、环境变量、轮数"),
        Category(Page.ABOUT, "版本、发布、引擎日志与自检"),
    )
    val colors = MaterialTheme.appColors

    SettingsPageHeader(
        title = "设置",
        intro = "接口、模型与引擎都在这台设备上；改动即时保存。",
    )
    ConfigHero(settings, engineState)
    Text(
        "全部设置",
        style = SettingsType.eyebrow,
        color = colors.stone,
        modifier = Modifier.padding(start = 4.dp, top = 28.dp, bottom = 12.dp),
    )
    categories.forEachIndexed { index, category ->
        SettingsCategoryCard(
            title = category.page.title,
            summary = category.summary,
            icon = category.page.icon,
            onClick = { onOpen(category.page) },
        )
        if (index != categories.lastIndex) Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ConfigHero(settings: AppSettings, engineState: EngineManager.State) {
    val colors = MaterialTheme.appColors
    val model = when {
        settings.usesAutoModel -> "CLI 默认（不指定模型）"
        settings.resolvedActiveTier != null -> activeTierSummary(settings)
        else -> settings.model.ifBlank { "未设置模型" }
    }
    val meta = listOf(
        settings.apiFlavor.label,
        settings.permissionMode.label,
        themeLabel(settings.themeMode),
        engineState.engineLabel(settings.engineMode),
    ).joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(Radii.xlShape)
            .background(colors.paper)
            .border(1.dp, colors.sand, Radii.xlShape)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("当前配置", style = SettingsType.eyebrow, color = colors.stone)
                Spacer(Modifier.height(6.dp))
                Text(
                    model,
                    style = SettingsType.hero,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(colors.orangeSoft)
                    .border(1.dp, colors.sand.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ClaudeMark(size = 22.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            meta,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
            color = colors.stone,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun themeLabel(mode: Int): String = when (mode) {
    1 -> "浅色"
    2 -> "深色"
    else -> "跟随系统"
}

// ---------------------------------------------------------------- API
@Composable
private fun ApiPage(
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    container: AppContainer,
    focusManager: androidx.compose.ui.focus.FocusManager,
    loadingModels: Boolean,
    testingConnection: Boolean,
    modelError: String?,
    connectionMessage: String?,
    connectionOk: Boolean,
    modelList: List<String>,
    onFetchModels: () -> Unit,
    onTestConnection: () -> Unit,
    onPickSmall: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    SettingsPageHeader(
        title = "接口与模型",
        intro = "Anthropic 与 OpenAI 兼容端点都支持；OpenAI 由内置代理转译。",
    )

    SettingsSection(
        "服务商预设",
        "点选后自动填好 Base URL、鉴权方式和默认模型；API Key 保持不变。",
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Providers.all.forEach { preset ->
                val active = settings.apiFlavor == preset.flavor &&
                    settings.baseUrl.trimEnd('/') == preset.baseUrl.trimEnd('/')
                PillChip(preset.label, active) {
                    focusManager.clearFocus()
                    scope.launch { container.settings.edit { Providers.apply(it, preset) } }
                }
            }
        }
        Hint("档位映射与小模型会清空。")
    }

    SettingsSection(
        "连接",
        "Base URL 只填到网关根路径：CLI 会自动追加 /v1/messages，带了 /v1 会被自动去掉。",
    ) {
        GroupLabel("接口协议")
        ChipRow(
            options = ApiFlavor.entries.toList(),
            selected = settings.apiFlavor,
            label = { it.label },
            onSelect = { v -> scope.launch { container.settings.edit { it.copy(apiFlavor = v) } } },
        )
        Hint(settings.apiFlavor.hint)
        ClaudeField(
            label = "Base URL",
            value = settings.baseUrl,
            placeholder = if (settings.apiFlavor == ApiFlavor.OPENAI) "https://api.openai.com/v1"
            else "https://your-gateway（不要带 /v1）",
            onChange = { v -> scope.launch { container.settings.edit { it.copy(baseUrl = v) } } },
        )
        ClaudeField(
            label = "API Key",
            value = settings.apiKey,
            placeholder = "sk-...",
            secret = true,
            onChange = { v -> scope.launch { container.settings.edit { it.copy(apiKey = v) } } },
        )
        GroupLabel("Key 的传递方式")
        ChipRow(
            options = AuthStyle.entries.toList(),
            selected = settings.authStyle,
            label = { it.label },
            onSelect = { v -> scope.launch { container.settings.edit { it.copy(authStyle = v) } } },
        )
        Hint(
            when (settings.authStyle) {
                AuthStyle.BEARER -> "发送 Authorization: Bearer <key>（多数自建网关用这个）"
                AuthStyle.API_KEY -> "发送 x-api-key: <key>（Anthropic 官方风格）"
                AuthStyle.BOTH -> "两种头都发；若网关报重复鉴权请只选一种"
            }
        )
    }

    SettingsSection(
        "模型",
        "默认模型会原样传给 --model；想让 CLI 用自身默认模型，请到「模型档位」切换。",
    ) {
        ClaudeField(
            label = "默认模型",
            value = settings.model,
            placeholder = "未选档位时原样传给 --model，如 auto/dfmodel[1m]",
            onChange = { v -> scope.launch { container.settings.edit { it.copy(model = v) } } },
        )
        Hint(
            "Magpie 式自动路由：把路由组 ID（例如 auto/dfmodel[1m]）直接填进这里，" +
                "并在「模型档位」保持「默认 / 路由组」、不要选具体档位；App 不会自动混调模型。"
        )
        Hint("想让 CLI 用自身默认模型，请到「模型档位」选「CLI 默认（不指定模型）」：它不传 --model 和 ANTHROPIC_MODEL。")
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GhostButton(
                text = if (loadingModels) "拉取中…" else "拉取模型列表",
                onClick = onFetchModels,
                enabled = !loadingModels,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                text = if (testingConnection) "连接中…" else "测试连接",
                onClick = onTestConnection,
                enabled = !loadingModels && !testingConnection,
                modifier = Modifier.weight(1f),
            )
        }
        modelError?.let { Note(it, colors.error, colors.error) }
        connectionMessage?.let {
            Note(
                it,
                if (connectionOk) colors.success else colors.error,
                if (connectionOk) colors.success else colors.error,
            )
        }
        if (modelList.isNotEmpty()) {
            Hint("已拉取 ${modelList.size} 个模型；可点上方按钮重新拉取并选择。")
        }

        ClaudeField(
            label = "小模型（可选）",
            value = settings.smallModel,
            placeholder = "留空时使用 Haiku 档位映射，再回退默认模型",
            onChange = { v -> scope.launch { container.settings.edit { it.copy(smallModel = v) } } },
        )
        if (modelList.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp)) {
                GhostButton("从列表选择", onPickSmall)
            }
        }
        Hint("小模型留空时，Haiku 映射会作为后台 small fast 模型使用。")
    }
}

// ---------------------------------------------------------------- tiers
@Composable
private fun TierPage(
    settings: AppSettings,
    onSetTier: (ModelTier?) -> Unit,
    onSelectAuto: () -> Unit,
    onOpenTier: (ModelTier) -> Unit,
) {
    val colors = MaterialTheme.appColors
    SettingsPageHeader(
        title = "模型档位",
        intro = "像 Magpie 一样先选档位，再由网关把档位映射到具体模型。",
    )
    SettingsSection(
        "当前档位",
        "「默认 / 路由组」会把「接口与模型」里填写的模型 ID 原样传给 --model。",
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip("默认 / 路由组", settings.resolvedActiveTier == null && !settings.usesAutoModel) {
                onSetTier(null)
            }
            // App-level "Auto": omit --model so the official CLI default applies.
            PillChip("CLI 默认（不指定模型）", settings.usesAutoModel) { onSelectAuto() }
            ModelTier.entries.forEach { tier ->
                PillChip(tier.label, settings.resolvedActiveTier == tier) { onSetTier(tier) }
            }
        }
        Hint(
            if (settings.usesAutoModel) "CLI 默认 · 不传 --model / ANTHROPIC_MODEL，由 Claude Code 使用自身默认模型"
            else activeTierSummary(settings)
        )
        Note(
            "「CLI 默认（不指定模型）」完全不传 --model 与 ANTHROPIC_MODEL，App 不参与选择，" +
                "也不做自动路由或端侧混调。",
            colors.graphite,
            colors.surface1,
        )
    }

    SettingsSection(
        "档位映射",
        "留空 = 官方默认/跟随默认模型。Anthropic 兼容网关会写入 ANTHROPIC_DEFAULT_*_MODEL。",
    ) {
        ModelTier.entries.forEachIndexed { index, tier ->
            SettingsRow(
                title = "${tier.label} · ${tier.alias}",
                subtitle = tierMappingLabel(settings, tier),
                onClick = { onOpenTier(tier) },
            )
            if (index != ModelTier.entries.lastIndex) {
                ClaudeDivider()
            }
        }
        Hint("OpenAI 兼容由内置代理按档位转发。")
        Hint("小模型留空时，Haiku 映射同时用于后台 small fast 任务。")
    }
}

// ---------------------------------------------------------------- engine & permissions
@Composable
private fun EnginePage(
    container: AppContainer,
    settings: AppSettings,
    engineState: EngineManager.State,
    scope: kotlinx.coroutines.CoroutineScope,
    testingEngine: Boolean,
    onTestingChange: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    // Root probe output belongs to this page only; leaving disposes it instead of
    // leaking a stale report into the next visit.
    var rootReport by remember { mutableStateOf<String?>(null) }
    SettingsPageHeader(
        title = "引擎与权限",
        intro = "内置官方 CLI；PRoot 免 root，有 root 时可用 chroot。",
    )

    SettingsSection("Claude Code 引擎", "内置官方二进制，从 npm registry 跟随官方升级。") {
        InfoRow("已安装", engineState.installedVersion ?: "未安装")
        InfoRow("官方最新", engineState.latestVersion ?: "未检查")
        InfoRow("CPU 架构", engineState.arch ?: "未知")
        InfoRow("安装位置", engineState.jailPath ?: "尚未安装")
        InfoRow("运行方式", engineState.engineLabel(settings.engineMode))
        InfoRow(
            "PRoot 组件",
            if (engineState.prootAvailable) "已打包（无需 root）" else "缺失（请安装正式 APK）",
        )
        InfoRow(
            "Root 授权",
            when {
                !engineState.rootGranted -> "未授权"
                engineState.chrootAvailable -> "已授权，chroot 可用"
                else -> "已授权，chroot 不可用"
            },
        )

        GroupLabel("引擎模式")
        ChipRow(
            options = EngineMode.entries.toList(),
            selected = settings.engineMode,
            label = { it.label },
            onSelect = { mode ->
                scope.launch {
                    container.settings.edit { it.copy(engineMode = mode) }
                    if (mode != EngineMode.PROOT) container.engine.probeRoot()
                }
            },
        )
        Hint(settings.engineMode.hint)
        Hint("切换模式后需要点「升级 / 重装」，引擎才会装到对应位置。")

        if (engineState.phase == EngineManager.Phase.Working) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(colors.surface2)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(engineState.fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(colors.orange)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${engineState.stage} ${engineState.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.graphite,
            )
        }
        engineState.error?.let { Note(it, colors.error, colors.error) }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = if (engineState.installedVersion == null) "安装 CLI" else "升级 / 重装",
                onClick = { scope.launch { container.engine.install(force = true) } },
                enabled = engineState.phase != EngineManager.Phase.Working,
            )
            GhostButton(
                "检查更新",
                { scope.launch { container.engine.checkForUpdate(quiet = false) } },
                enabled = engineState.phase != EngineManager.Phase.Working,
            )
            GhostButton(
                if (testingEngine) "自检中…" else "自检",
                {
                    onTestingChange(true)
                    scope.launch {
                        container.engine.selfTest()
                        onTestingChange(false)
                    }
                },
                enabled = !testingEngine,
            )
            GhostButton(
                "卸载",
                onUninstall,
                enabled = engineState.installedVersion != null &&
                    engineState.phase != EngineManager.Phase.Working,
            )
        }
        engineState.selfTest?.let { CodeCard(it, label = "SELFTEST", maxHeight = 240.dp) }
    }

    SettingsSection("Root 与权限", "免 root 使用 PRoot；有 root 时改用 chroot。") {
        SettingsRow(
            title = "Root 授权",
            subtitle = when {
                engineState.rootGranted && engineState.chrootAvailable -> "root 已授权，chroot 可用"
                engineState.rootGranted -> "root 已授权，chroot 不可用（加载器直启）"
                else -> "未获得 root；不影响 PRoot 模式运行"
            },
            trailing = {
                GhostButton(
                    if (engineState.rootGranted) "重新检查" else "申请授权",
                    {
                        rootReport = "检查中…"
                        scope.launch {
                            container.engine.probeRoot()
                            rootReport = container.engine.rootProbeReport()
                        }
                    }
                )
            },
        )
        rootReport?.let { CodeCard(it, label = "ROOT PROBE", maxHeight = 200.dp) }
        Note(
            "Root 模式会把 /system、/data、/sdcard 挂载进 jail；只在需要修改系统时使用。" +
                "Android 10+ 普通应用不能执行自身目录里的文件，本应用使用 targetSdk 28 + PRoot，" +
                "因此大多数设备无需 root。",
            colors.error,
            colors.error,
        )
        ClaudeDivider(Modifier.padding(top = 4.dp))
        SettingsRow(
            title = "权限模式",
            subtitle = "${settings.permissionMode.label} · 点击选择 CLI 的工具权限",
            onClick = onOpenPermissions,
        )
    }
}

// ---------------------------------------------------------------- labs / experiments
@Composable
private fun LabsPage(
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    container: AppContainer,
) {
    val colors = MaterialTheme.appColors
    SettingsPageHeader(
        title = "功能与实验",
        intro = "对应 Claude Code 的实验特性；部分在 headless 模式下受限。",
    )
    SettingsSection("实验开关", "写入对应的 CLAUDE_CODE_* 环境变量。") {
        LabSwitch(
            title = "Agent teams（实验）",
            subtitle = "多代理协作。注意：headless -p 下队友无法真正启动，开关仅写入环境变量，效果因版本而异。",
            checked = settings.agentTeams,
            onChange = { on -> scope.launch { container.settings.edit { it.copy(agentTeams = on) } } },
        )
        ClaudeDivider()
        LabSwitch(
            title = "Workflows",
            subtitle = "允许模型编排多步工作流。",
            checked = settings.workflows,
            onChange = { on -> scope.launch { container.settings.edit { it.copy(workflows = on) } } },
        )
        ClaudeDivider()
        LabSwitch(
            title = "Auto memory",
            subtitle = "让模型维护跨会话记忆文件。",
            checked = settings.autoMemory,
            onChange = { on -> scope.launch { container.settings.edit { it.copy(autoMemory = on) } } },
        )
        ClaudeDivider()
        LabSwitch(
            title = "Background tasks",
            subtitle = "允许后台任务继续运行。",
            checked = settings.backgroundTasks,
            onChange = { on -> scope.launch { container.settings.edit { it.copy(backgroundTasks = on) } } },
        )
        ClaudeDivider()
        LabSwitch(
            title = "Extended thinking",
            subtitle = "在支持的模型上启用扩展思考。",
            checked = settings.extendedThinking,
            onChange = { on -> scope.launch { container.settings.edit { it.copy(extendedThinking = on) } } },
        )
        // Be honest about the single-process headless launcher: teammates cannot spawn.
        Note(
            "Agent teams 依赖交互式会话：本 App 每个 turn 都以 headless -p 启动 CLI，" +
                "队友进程无法真正拉起。这个开关只是实验性标记，不代表队友已可用。",
            colors.accentInk,
            colors.orange,
        )
    }

    SettingsSection("推理强度", "AUTO 时不传该参数，沿用官方默认。") {
        GroupLabel("Effort")
        ChipRow(
            options = EffortLevel.entries.toList(),
            selected = settings.effort,
            label = { it.label },
            onSelect = { v -> scope.launch { container.settings.edit { it.copy(effort = v) } } },
        )
        Hint(settings.effort.hint)
    }

    SettingsSection("子代理与分类器模型", "留空 = 继承主模型 / 官方默认。") {
        ClaudeField(
            label = "子代理模型（subagentModel）",
            value = settings.subagentModel,
            placeholder = "留空 = 继承主模型，例如 claude-haiku-4-5",
            onChange = { v -> scope.launch { container.settings.edit { it.copy(subagentModel = v) } } },
        )
        Hint("写入 CLAUDE_CODE_SUBAGENT_MODEL；仅用于子代理 / 队友，不是主模型。")
        ClaudeField(
            label = "Auto 权限分类器模型（autoModeModel）",
            value = settings.autoModeModel,
            placeholder = "留空 = 使用官方默认分类器",
            onChange = { v -> scope.launch { container.settings.edit { it.copy(autoModeModel = v) } } },
        )
        Hint(
            "写入 CLAUDE_CODE_AUTO_MODE_MODEL；仅供官方 auto 权限模式审查分类器使用，不是对话主模型。" +
                "网关需要支持该分类器模型，否则 auto 模式可能退回只读。"
        )
        Note(
            "以上开关与模型会写入对应的 CLAUDE_CODE_* 环境变量（如 " +
                "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS、CLAUDE_CODE_DISABLE_*）；" +
                "「高级 → 额外环境变量」里写同名 KEY 会覆盖这里的设置。",
            colors.stone,
            colors.surface1,
        )
    }
}

@Composable
private fun LabSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        trailing = { WarmSwitch(checked, onChange) },
    )
}

// ---------------------------------------------------------------- appearance
@Composable
private fun AppearancePage(
    settings: AppSettings,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    scope: kotlinx.coroutines.CoroutineScope,
    container: AppContainer,
) {
    SettingsPageHeader(
        title = "外观",
        intro = "整页跟随系统或固定浅色/深色；没有纯黑和蓝灰。",
    )
    SettingsSection("主题", "切换后立即生效，不会重建正在进行的对话。") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("跟随系统", "浅色", "深色").forEachIndexed { index, label ->
                PillChip(label, settings.themeMode == index) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    scope.launch { container.settings.edit { it.copy(themeMode = index) } }
                }
            }
        }
        Hint("当前：${themeLabel(settings.themeMode)}。")
    }
}

// ---------------------------------------------------------------- workspace
@Composable
private fun WorkspacePage(
    appContext: android.content.Context,
    usage: EngineManager.StorageUsage?,
    onOpenWorkspace: () -> Unit,
    onUninstall: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    SettingsPageHeader(
        title = "工作区",
        intro = "Claude 在 jail 内把这里映射为 /work。",
    )
    SettingsSection("存储与清理", "工作区里的文件可以直接预览、分享或交给其他应用打开。") {
        SettingsRow(
            title = "打开工作区",
            subtitle = Paths.workspace(appContext).absolutePath,
            onClick = onOpenWorkspace,
        )
        ClaudeDivider()
        SettingsRow(
            title = "存储占用",
            subtitle = usage?.let {
                "工作区 ${formatBytes(it.workspaceBytes)} · 会话 ${formatBytes(it.sessionBytes)}"
            } ?: "安装引擎后可查看",
            trailing = usage?.let {
                {
                    Text(
                        formatBytes(it.totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.stone,
                    )
                }
            },
        )
        ClaudeDivider()
        SettingsRow(
            title = "清理 / 卸载",
            subtitle = "可仅卸载引擎，或连同工作区与会话一起删除",
            onClick = onUninstall,
        )
    }
}

// ---------------------------------------------------------------- advanced
@Composable
private fun AdvancedPage(
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    container: AppContainer,
    onOpenEnv: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    SettingsPageHeader(
        title = "高级",
        intro = "一般不需要改动；排障时可用。",
    )
    SettingsSection("更新", "每次打开 App 查询 npm registry。") {
        SettingsRow(
            title = "启动时自动检查更新",
            subtitle = "关闭后仍可在「引擎与权限」手动检查",
            trailing = {
                WarmSwitch(settings.autoUpdate) { on ->
                    scope.launch { container.settings.edit { it.copy(autoUpdate = on) } }
                }
            },
        )
    }
    SettingsSection("系统提示词", "追加到官方系统提示词末尾，下一轮对话生效。") {
        ClaudeField(
            label = "追加系统提示词",
            value = settings.extraSystemPrompt,
            placeholder = "例如：这台机器已 root，优先用 /data/adb 下的路径",
            singleLine = false,
            onChange = { v -> scope.launch { container.settings.edit { it.copy(extraSystemPrompt = v) } } },
        )
    }
    SettingsSection("环境变量", "每行 KEY=VALUE，写入 CLI 进程环境。") {
        val lines = settings.extraEnv.lines().count { it.isNotBlank() }
        SettingsRow(
            title = "额外环境变量",
            subtitle = if (lines == 0) "未设置" else "已设置 $lines 条 · 点击编辑",
            onClick = onOpenEnv,
        )
        Hint("「功能与实验」里的开关会写入对应的 CLAUDE_CODE_* 环境变量；在这里写同名 KEY 可覆盖它们。")
    }
    SettingsSection("运行限制", "0 = 使用 Claude Code 默认值。") {
        ClaudeField(
            label = "最大轮数（0 = 默认）",
            value = if (settings.maxTurns == 0) "" else settings.maxTurns.toString(),
            placeholder = "0",
            keyboardType = KeyboardType.Number,
            onChange = { v ->
                val n = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                scope.launch { container.settings.edit { it.copy(maxTurns = n) } }
            },
        )
    }
}

// ---------------------------------------------------------------- about
@Composable
private fun AboutPage(
    appContext: android.content.Context,
    logs: List<String>,
    release: AppRelease?,
    releaseError: String?,
    checkingRelease: Boolean,
    testingEngine: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    container: AppContainer,
    onCheckRelease: () -> Unit,
    onTestingChange: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    // The last self-test output stays page-local: leaving 关于 discards it rather
    // than leaking a stale report into the next visit.
    var aboutSelfTest by remember { mutableStateOf<String?>(null) }
    SettingsPageHeader(
        title = "关于",
        intro = "版本、发布与诊断信息。",
    )
    SettingsSection("版本与发布", null) {
        InfoRow("应用版本", "Claudroid ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
        InfoRow("后端", "Claude Code CLI（官方 npm 发布物）")
        ClaudeDivider()
        val latest = release
        val status = latest?.let { ReleaseCheck.status(BuildConfig.VERSION_NAME, it.tag) }
        when {
            latest != null -> SettingsRow(
                title = "最新发布",
                subtitle = "v${latest.tag} · " + when (status) {
                    ReleaseStatus.UP_TO_DATE -> "已是最新"
                    ReleaseStatus.UPDATE_AVAILABLE -> "有新版本可下载"
                    ReleaseStatus.DEV_BUILD -> "当前是本地构建"
                    null -> ""
                },
                onClick = { openUrl(appContext, latest.url) },
                trailing = {
                    Text(
                        if (status == ReleaseStatus.UPDATE_AVAILABLE) "下载" else "发布页",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accentInk,
                    )
                },
            )
            checkingRelease -> SettingsRow(
                title = "最新发布",
                subtitle = "正在检查…",
                trailing = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.6.dp,
                        color = colors.orange,
                    )
                },
            )
            else -> SettingsRow(
                title = "检查发布版本",
                subtitle = "网络不可用时可以重试，或直接打开项目 Release 页",
                onClick = onCheckRelease,
            )
        }
        releaseError?.let {
            Note(
                "无法连接 GitHub：$it。可以重试，或手动打开项目的 Release 页。",
                colors.stone,
                colors.surface1,
            )
        }
    }

    SettingsSection("诊断", "安装、启动与工具调用的原始输出。") {
        SettingsRow(
            title = "引擎日志（${logs.size}）",
            subtitle = "完整日志、复制与排查",
            onClick = onOpenLogs,
        )
        ClaudeDivider()
        SettingsRow(
            title = "运行自检",
            subtitle = if (testingEngine) "正在检查 CLI、架构与路径…" else "检查 CLI、PRoot/chroot 与工作区",
            onClick = {
                if (!testingEngine) {
                    onTestingChange(true)
                    scope.launch {
                        val result = container.engine.selfTest()
                        aboutSelfTest = result
                        onTestingChange(false)
                    }
                }
            },
            trailing = if (testingEngine) {
                {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.6.dp,
                        color = colors.orange,
                    )
                }
            } else null,
        )
        aboutSelfTest?.let { CodeCard(it, label = "SELFTEST", maxHeight = 220.dp) }
    }
}

// ---------------------------------------------------------------- third level
@Composable
private fun PermissionsPage(
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    container: AppContainer,
) {
    val colors = MaterialTheme.appColors
    SettingsPageHeader(
        title = "权限模式",
        intro = "--permission-mode 决定 Claude 能直接执行哪些工具；修改后下一轮对话生效。",
    )
    SettingsSection("模式", "没有交互式宿主时，需要批准的工具调用会被直接拒绝。") {
        PermissionMode.entries.forEachIndexed { index, mode ->
            SettingsOptionRow(
                title = mode.label,
                description = mode.hint,
                selected = settings.permissionMode == mode,
                onClick = {
                    scope.launch { container.settings.edit { it.copy(permissionMode = mode) } }
                },
            )
            if (index != PermissionMode.entries.lastIndex) {
                ClaudeDivider(Modifier.padding(horizontal = 8.dp))
            }
        }
    }
    SettingsSection("关于「自动」", "官方 Auto 权限会用分类器审查每次敏感操作。") {
        Note(
            "OpenAI 兼容代理与多数第三方网关没有分类器，CLI 会退回只读或拒绝敏感操作。" +
                "可在「功能与实验 → Auto 权限分类器模型」指定模型；遇到工具被拒时请改用" +
                "「自动改文件」或「完全放行」。",
            colors.graphite,
            colors.surface1,
        )
    }
}

@Composable
private fun EngineLogsPage(logs: List<String>) {
    val text = remember(logs) {
        logs.joinToString("\n").ifBlank { "暂无日志" }
    }
    SettingsPageHeader(
        title = "引擎日志",
        intro = "安装、启动与工具调用的原始输出，保留最近 600 行。",
    )
    SettingsSection("输出", "点右上角图标复制全部日志。") {
        CodeCard(text, label = "ENGINE LOG", maxHeight = 460.dp)
    }
}

@Composable
private fun EnvPage(
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    container: AppContainer,
) {
    val colors = MaterialTheme.appColors
    SettingsPageHeader(
        title = "环境变量",
        intro = "每行 KEY=VALUE，写入 CLI 进程环境；修改即时保存，下一轮对话生效。",
    )
    SettingsSection("额外环境变量", "示例：CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1") {
        ClaudeField(
            label = "KEY=VALUE（每行一条）",
            value = settings.extraEnv,
            placeholder = "ANTHROPIC_CUSTOM_HEADERS=X-Foo: bar",
            singleLine = false,
            onChange = { v -> scope.launch { container.settings.edit { it.copy(extraEnv = v) } } },
        )
        Hint("不要在这里重复填写 API Key；Key 由「接口与模型」单独管理。")
    }
    SettingsSection("优先级", null) {
        Note(
            "这里写的同名 KEY 会覆盖「功能与实验」自动写入的 CLAUDE_CODE_* 变量。" +
                "排障时可临时关闭某个实验开关，而不必改动上面的选项。",
            colors.stone,
            colors.surface1,
        )
    }
}

// ---------------------------------------------------------------- shared chrome
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            PillChip(label(option), option == selected) { onSelect(option) }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = SettingsType.eyebrow,
        color = MaterialTheme.appColors.stone,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
        color = MaterialTheme.appColors.stone,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Quiet key/value line; deliberately not a [SettingsRow] so values stay secondary. */
@Composable
private fun InfoRow(key: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.appColors.graphite,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.appColors.stone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 210.dp),
        )
    }
}

/** Small warm note; tone is used for border/text, with a matching 7% fill. */
@Composable
private fun Note(text: String, tone: Color, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(Radii.smShape)
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, tone.copy(alpha = 0.28f), Radii.smShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = tone,
        )
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1L shl 30 -> "%.1f GB".format(value.toDouble() / (1L shl 30))
    value >= 1L shl 20 -> "%.0f MB".format(value.toDouble() / (1L shl 20))
    value >= 1L shl 10 -> "%.0f KB".format(value.toDouble() / (1L shl 10))
    else -> "$value B"
}
