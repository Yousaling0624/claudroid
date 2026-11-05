package app.claudroid.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import app.claudroid.data.EngineMode
import app.claudroid.data.ModelTier
import app.claudroid.data.PermissionMode
import app.claudroid.data.ProviderPreset
import app.claudroid.data.Providers
import app.claudroid.data.ReleaseCheck
import app.claudroid.data.ReleaseStatus
import app.claudroid.engine.EngineManager
import app.claudroid.engine.openai.ModelCatalog
import app.claudroid.ui.components.ClaudeDialog
import app.claudroid.ui.components.ClaudeDivider
import app.claudroid.ui.components.ClaudeField
import app.claudroid.ui.components.CodeCard
import app.claudroid.ui.components.GhostButton
import app.claudroid.ui.components.ModelListSheet
import app.claudroid.ui.components.PillChip
import app.claudroid.ui.components.PrimaryButton
import app.claudroid.ui.components.SettingsRow
import app.claudroid.ui.components.SettingsSection
import app.claudroid.ui.components.WarmSwitch
import app.claudroid.ui.components.activeTierSummary
import app.claudroid.ui.components.tierMappingLabel
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.launch

private enum class ModelTarget { DEFAULT, SMALL }

/**
 * Full settings surface. Everything is a paper card on the warm canvas: the API
 * surface, model tiers, engine controls, root, appearance, workspace and about.
 * Tier mappings follow Magpie's shape — pick a tier, then map it to a concrete
 * model for the configured gateway.
 */
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

    var showLogs by remember { mutableStateOf(false) }
    var rootReport by remember { mutableStateOf<String?>(null) }
    var testingEngine by remember { mutableStateOf(false) }
    var aboutSelfTest by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(Unit) { checkRelease() }
    LaunchedEffect(engineState.installedVersion) {
        usage = if (engineState.installedVersion == null) null else container.engine.storageUsage()
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            // ------------------------------------------------ API surface
            SettingsSection(
                "接口与模型",
                "Anthropic 与 OpenAI 兼容端点都支持；OpenAI 由内置代理转译。"
            ) {
                GroupLabel("服务商预设")
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
                Hint("点选后自动填好 Base URL、鉴权方式和默认模型，API Key 保持不变；档位映射与小模型会清空。")

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

                ClaudeField(
                    label = "默认模型",
                    value = settings.model,
                    placeholder = "未选档位时使用，例如 claude-sonnet-5-5",
                    onChange = { v -> scope.launch { container.settings.edit { it.copy(model = v) } } },
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GhostButton(
                        text = if (loadingModels) "拉取中…" else "拉取模型列表",
                        onClick = ::fetchModels,
                        enabled = !loadingModels,
                    )
                    GhostButton(
                        text = if (testingConnection) "连接中…" else "测试连接",
                        onClick = ::testConnection,
                        enabled = !testingConnection,
                    )
                }
                modelError?.let { Note(it, MaterialTheme.appColors.error, MaterialTheme.appColors.error) }
                connectionMessage?.let {
                    Note(
                        it,
                        if (connectionOk) MaterialTheme.appColors.success else MaterialTheme.appColors.error,
                        if (connectionOk) MaterialTheme.appColors.success else MaterialTheme.appColors.error,
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
                    Row(Modifier.padding(top = 8.dp)) {
                        GhostButton("从列表选择", { modelTarget = ModelTarget.SMALL })
                    }
                }
                Hint("小模型留空时，Haiku 映射会作为后台 small fast 模型使用。")
                Hint("Base URL 只填到网关根路径：CLI 会自动追加 /v1/messages；带了 /v1 会被自动去掉。")
            }

            // ------------------------------------------------ tiers
            SettingsSection(
                "模型档位",
                "像 Magpie 一样先选档位，再由网关把档位映射到具体模型。"
            ) {
                GroupLabel("当前档位")
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillChip("默认", settings.resolvedActiveTier == null) { setActiveTier(null) }
                    ModelTier.entries.forEach { tier ->
                        PillChip(tier.label, settings.resolvedActiveTier == tier) { setActiveTier(tier) }
                    }
                }
                Hint(activeTierSummary(settings))

                Spacer(Modifier.height(4.dp))
                ClaudeDivider()
                ModelTier.entries.forEachIndexed { index, tier ->
                    SettingsRow(
                        title = "${tier.label} · ${tier.alias}",
                        subtitle = tierMappingLabel(settings, tier),
                        onClick = { tierSheet = tier },
                    )
                    if (index != ModelTier.entries.lastIndex) {
                        ClaudeDivider()
                    }
                }
                Hint(
                    "留空 = 官方默认/跟随默认模型。Anthropic 兼容网关（如 Magpie）会写入 " +
                        "ANTHROPIC_DEFAULT_OPUS_MODEL 等变量；OpenAI 兼容由内置代理按档位转发。"
                )
                Hint("小模型留空时，Haiku 映射同时用于后台 small fast 任务。")
            }

            // ------------------------------------------------ engine
            SettingsSection(
                "Claude Code 引擎",
                "内置官方二进制，从 npm registry 跟随官方升级。"
            ) {
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
                            .background(MaterialTheme.appColors.surface2)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(engineState.fraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(MaterialTheme.appColors.orange)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${engineState.stage} ${engineState.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.graphite,
                    )
                }
                engineState.error?.let { Note(it, MaterialTheme.appColors.error, MaterialTheme.appColors.error) }

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
                            testingEngine = true
                            scope.launch {
                                container.engine.selfTest()
                                testingEngine = false
                            }
                        },
                        enabled = !testingEngine,
                    )
                    GhostButton(
                        "卸载",
                        { showUninstall = true },
                        enabled = engineState.installedVersion != null &&
                            engineState.phase != EngineManager.Phase.Working,
                    )
                }
                engineState.selfTest?.let { CodeCard(it, label = "SELFTEST", maxHeight = 240.dp) }
            }

            // ------------------------------------------------ root
            SettingsSection(
                "Root 与权限",
                "免 root 使用 PRoot；有 root 时改用 chroot，可直接操作 /data 与 Magisk 模块。"
            ) {
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
                    MaterialTheme.appColors.error,
                    MaterialTheme.appColors.error,
                )

                Spacer(Modifier.height(4.dp))
                ClaudeDivider()
                GroupLabel("权限模式（--permission-mode）")
                ChipRow(
                    options = PermissionMode.entries.toList(),
                    selected = settings.permissionMode,
                    label = { it.label },
                    onSelect = { v -> scope.launch { container.settings.edit { it.copy(permissionMode = v) } } },
                )
                Hint(settings.permissionMode.hint)
            }

            // ------------------------------------------------ appearance
            SettingsSection("外观", "整页跟随系统或固定浅色/深色；没有纯黑和蓝灰。") {
                GroupLabel("主题")
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
                Hint("切换后立即生效，不会重建正在进行的对话。")
            }

            // ------------------------------------------------ workspace
            SettingsSection("工作区", "Claude 在 jail 内把这里映射为 /work。") {
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
                                color = MaterialTheme.appColors.stone,
                            )
                        }
                    },
                )
                ClaudeDivider()
                SettingsRow(
                    title = "清理 / 卸载",
                    subtitle = "可仅卸载引擎，或连同工作区与会话一起删除",
                    onClick = { showUninstall = true },
                )
                Hint("工作区里的文件可以直接预览、分享或交给其他应用打开。")
            }

            // ------------------------------------------------ advanced
            SettingsSection("高级", "一般不需要改动；排障时可用。") {
                SettingsRow(
                    title = "启动时自动检查更新",
                    subtitle = "每次打开 App 查询 npm registry",
                    trailing = {
                        WarmSwitch(settings.autoUpdate) { on ->
                            scope.launch { container.settings.edit { it.copy(autoUpdate = on) } }
                        }
                    },
                )
                ClaudeField(
                    label = "追加系统提示词",
                    value = settings.extraSystemPrompt,
                    placeholder = "例如：这台机器已 root，优先用 /data/adb 下的路径",
                    singleLine = false,
                    onChange = { v -> scope.launch { container.settings.edit { it.copy(extraSystemPrompt = v) } } },
                )
                ClaudeField(
                    label = "额外环境变量（每行 KEY=VALUE）",
                    value = settings.extraEnv,
                    placeholder = "ANTHROPIC_CUSTOM_HEADERS=X-Foo: bar",
                    singleLine = false,
                    onChange = { v -> scope.launch { container.settings.edit { it.copy(extraEnv = v) } } },
                )
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

            // ------------------------------------------------ about
            SettingsSection("关于", null) {
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
                                color = MaterialTheme.appColors.accentInk,
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
                                color = MaterialTheme.appColors.orange,
                            )
                        },
                    )
                    else -> SettingsRow(
                        title = "检查发布版本",
                        subtitle = "网络不可用时可以重试，或直接打开项目 Release 页",
                        onClick = { scope.launch { checkRelease() } },
                    )
                }
                releaseError?.let {
                    Note(
                        "无法连接 GitHub：$it。可以重试，或手动打开项目的 Release 页。",
                        MaterialTheme.appColors.stone,
                        MaterialTheme.appColors.surface1,
                    )
                }
                ClaudeDivider()
                SettingsRow(
                    title = "引擎日志（${logs.size}）",
                    subtitle = if (showLogs) "点此收起" else "安装、启动与工具调用的原始输出",
                    onClick = { showLogs = !showLogs },
                )
                if (showLogs) {
                    CodeCard(
                        logs.takeLast(120).joinToString("\n").ifBlank { "暂无日志" },
                        label = "ENGINE LOG",
                        maxHeight = 300.dp,
                    )
                }
                SettingsRow(
                    title = "运行自检",
                    subtitle = if (testingEngine) "正在检查 CLI、架构与路径…" else "检查 CLI、PRoot/chroot 与工作区",
                    onClick = {
                        if (!testingEngine) {
                            testingEngine = true
                            scope.launch {
                                aboutSelfTest = container.engine.selfTest()
                                testingEngine = false
                            }
                        }
                    },
                    trailing = if (testingEngine) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 1.6.dp,
                                color = MaterialTheme.appColors.orange,
                            )
                        }
                    } else null,
                )
                aboutSelfTest?.let { CodeCard(it, label = "SELFTEST", maxHeight = 220.dp) }
            }
            Spacer(Modifier.height(36.dp))
        }
    }

    // ------------------------------------------------ sheets & dialogs
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
                modelTarget = null
            },
            onDismiss = { modelTarget = null },
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
                tierSheet = null
            },
            onDismiss = { tierSheet = null },
        )
    }

    if (showUninstall) {
        ClaudeDialog(
            title = "卸载内置引擎",
            message = "将删除 jail 里的 Claude Code 与运行环境（约 300MB）。会话记录默认保留；" +
                "选择「全部删除」会同时清空工作区和会话。",
            onDismiss = { showUninstall = false },
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

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(
                AppIcons.Back,
                "返回",
                tint = MaterialTheme.appColors.graphite,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            "设置",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.appColors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(44.dp))
    }
}

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
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.appColors.stone,
        modifier = Modifier.padding(top = 14.dp, bottom = 7.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.appColors.stone,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun InfoRow(key: String, value: String) {
    SettingsRow(
        title = key,
        trailing = {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.graphite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 210.dp),
            )
        },
    )
}

/** Small warm note; tone is used for border/text, with a matching 7% fill. */
@Composable
private fun Note(text: String, tone: Color, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, tone.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
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
