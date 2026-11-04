package app.claudroid.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.AppContainer
import app.claudroid.BuildConfig
import app.claudroid.core.Logx
import app.claudroid.core.Paths
import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import app.claudroid.data.EngineMode
import app.claudroid.data.PermissionMode
import app.claudroid.data.ProviderPreset
import app.claudroid.data.Providers
import app.claudroid.data.AppRelease
import app.claudroid.data.ReleaseCheck
import app.claudroid.data.ReleaseStatus
import app.claudroid.engine.EngineManager
import app.claudroid.engine.openai.ModelCatalog
import app.claudroid.ui.components.Motion
import app.claudroid.ui.components.SectionLabel
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.Spacing
import app.claudroid.ui.theme.appColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    var testing by remember { mutableStateOf(false) }
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModels by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(Unit) { checkRelease() }
    LaunchedEffect(engineState.installedVersion) {
        usage = if (engineState.installedVersion == null) null else container.engine.storageUsage()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            AppIcons.Back,
                            "返回",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
        ) {
            Spacer(Modifier.height(6.dp))

            // ---------------- 接口 ----------------
            Section("接口", "Anthropic 与 OpenAI 兼容端点都支持；OpenAI 由 App 内置代理转译") {
                Label("服务商预设")
                PresetChips(settings) { preset ->
                    // Drop focus so the fields below adopt the preset values immediately.
                    focusManager.clearFocus()
                    scope.launch { container.settings.edit { Providers.apply(it, preset) } }
                }
                Hint("点选后自动填好 Base URL、鉴权方式和模型，API Key 保持不变。")
                Label("接口协议")
                ChipRow(
                    options = ApiFlavor.entries.toList(),
                    selected = settings.apiFlavor,
                    label = { it.label },
                    onSelect = { v -> scope.launch { container.settings.edit { it.copy(apiFlavor = v) } } },
                )
                Hint(settings.apiFlavor.hint)
                Field(
                    label = "Base URL",
                    value = settings.baseUrl,
                    placeholder = if (settings.apiFlavor == ApiFlavor.OPENAI) "https://api.openai.com/v1"
                    else "https://your-gateway（不要带 /v1）",
                    onChange = { v -> scope.launch { container.settings.edit { it.copy(baseUrl = v) } } },
                )
                Field(
                    label = "API Key",
                    value = settings.apiKey,
                    placeholder = "sk-...",
                    secret = true,
                    onChange = { v -> scope.launch { container.settings.edit { it.copy(apiKey = v) } } },
                )
                Label("Key 的传递方式")
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
                Field(
                    label = "模型",
                    value = settings.model,
                    placeholder = "点下面「拉取模型列表」自动获取，或手动填写",
                    onChange = { v -> scope.launch { container.settings.edit { it.copy(model = v) } } },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GhostPill(if (loadingModels) "拉取中…" else "拉取模型列表", !loadingModels) {
                        loadingModels = true
                        modelError = null
                        scope.launch {
                            runCatching { ModelCatalog.fetch(settings) }
                                .onSuccess { result ->
                                    if (result.models.isEmpty()) {
                                        modelError = "接口返回为空：" + result.endpoint
                                    } else {
                                        modelList = result.models
                                        showModels = true
                                    }
                                }
                                .onFailure { modelError = it.message ?: "拉取失败" }
                            loadingModels = false
                        }
                    }
                    modelError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Field(
                    label = "小模型（可选）",
                    value = settings.smallModel,
                    placeholder = "留空则沿用主模型",
                    onChange = { v -> scope.launch { container.settings.edit { it.copy(smallModel = v) } } },
                )
                Hint("Base URL 只填到网关根路径：CLI 会自动追加 /v1/messages；带了 /v1 会被自动去掉。")
                Hint("模型名会同时写进 ANTHROPIC_MODEL 与 --model。")
            }

            // ---------------- 引擎 ----------------
            Section("Claude Code 引擎", "内置官方二进制，从 npm registry 跟随官方升级") {
                KeyValue("已安装", engineState.installedVersion ?: "未安装")
                KeyValue("官方最新", engineState.latestVersion ?: "未检查")
                KeyValue("CPU 架构", engineState.arch ?: "未知")
                KeyValue("安装位置", engineState.jailPath ?: "尚未安装")
                KeyValue("运行方式", engineState.engineLabel(settings.engineMode))
                KeyValue(
                    "PRoot 组件",
                    if (engineState.prootAvailable) "已打包（无需 root）" else "缺失（请安装正式 APK）",
                )
                KeyValue(
                    "Root 授权",
                    when {
                        !engineState.rootGranted -> "未授权"
                        engineState.chrootAvailable -> "已授权，chroot 可用"
                        else -> "已授权，chroot 不可用"
                    },
                )
                usage?.let {
                    KeyValue(
                        "占用空间",
                        "${formatBytes(it.totalBytes)}（引擎 ${formatBytes(it.jailBytes)} / " +
                            "工作区 ${formatBytes(it.workspaceBytes)} / 会话 ${formatBytes(it.sessionBytes)}）",
                    )
                }
                Label("引擎模式")
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
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { engineState.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.appColors.accentInk,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${engineState.stage} ${engineState.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                engineState.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryPill(
                        text = if (engineState.installedVersion == null) "安装 CLI" else "升级 / 重装",
                        enabled = engineState.phase != EngineManager.Phase.Working,
                    ) { scope.launch { container.engine.install(force = true) } }
                    GhostPill("检查更新", engineState.phase != EngineManager.Phase.Working) {
                        scope.launch { container.engine.checkForUpdate(quiet = false) }
                    }
                    GhostPill("自检", !testing) {
                        testing = true
                        scope.launch {
                            container.engine.selfTest()
                            testing = false
                        }
                    }
                    GhostPill(
                        "卸载",
                        engineState.installedVersion != null && engineState.phase != EngineManager.Phase.Working,
                    ) { showUninstall = true }
                }
                engineState.selfTest?.let { MonoBlock(it) }
                TextButton(
                    onClick = { showLogs = !showLogs },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        if (showLogs) "收起引擎日志" else "查看引擎日志（${logs.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.appColors.accentInk,
                    )
                }
                if (showLogs) {
                    MonoBlock(logs.takeLast(80).joinToString("\n").ifBlank { "暂无日志" })
                }
            }

            // ---------------- 权限 ----------------
            Section("Root 与权限", "免 root 使用 PRoot；有 root 时改用 chroot，可直接操作 /data 与 Magisk 模块") {
                SettingRow(
                    title = "Root 授权",
                    subtitle = when {
                        engineState.rootGranted && engineState.chrootAvailable -> "root 已授权，chroot 可用"
                        engineState.rootGranted -> "root 已授权，chroot 不可用（加载器直启）"
                        else -> "未获得 root；不影响 PRoot 模式运行"
                    },
                ) {
                    GhostPill(if (engineState.rootGranted) "重新检查" else "申请授权", true) {
                        rootReport = "检查中…"
                        scope.launch {
                            container.engine.probeRoot()
                            rootReport = container.engine.rootProbeReport()
                        }
                    }
                }
                Hint(
                    "Android 10 起，targetSdk≥29 的应用不能执行自己数据目录里的文件（W^X）。" +
                        "本应用按 Termux / dsh-Go 的方式使用 targetSdk 28，并通过打包进 APK 的 PRoot 运行，" +
                        "因此大多数设备不需要 root。"
                )
                if (engineState.rootGranted) {
                    Hint("Root 模式会把 /system、/data、/sdcard 挂载进 jail；只在需要修改系统时使用。")
                }
                rootReport?.let { MonoBlock(it) }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Label("权限模式（--permission-mode）")
                ChipRow(
                    options = PermissionMode.entries.toList(),
                    selected = settings.permissionMode,
                    label = { it.label },
                    onSelect = { v -> scope.launch { container.settings.edit { it.copy(permissionMode = v) } } },
                )
                Hint(settings.permissionMode.hint)
            }

            // ---------------- 高级 ----------------
            Section("高级", null) {
                SettingRow(
                    title = "启动时自动检查更新",
                    subtitle = "每次打开 App 查询 npm registry",
                ) {
                    Switch(
                        checked = settings.autoUpdate,
                        onCheckedChange = { on ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch { container.settings.edit { it.copy(autoUpdate = on) } }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.appColors.accentInk,
                        ),
                    )
                }
                Field(
                    label = "追加系统提示词",
                    value = settings.extraSystemPrompt,
                    placeholder = "例如：这台机器已 root，优先用 /data/adb 下的路径",
                    singleLine = false,
                    onChange = { v -> scope.launch { container.settings.edit { it.copy(extraSystemPrompt = v) } } },
                )
                Field(
                    label = "额外环境变量（每行 KEY=VALUE）",
                    value = settings.extraEnv,
                    placeholder = "ANTHROPIC_CUSTOM_HEADERS=X-Foo: bar",
                    singleLine = false,
                    onChange = { v -> scope.launch { container.settings.edit { it.copy(extraEnv = v) } } },
                )
                Field(
                    label = "最大轮数（0 = 默认）",
                    value = if (settings.maxTurns == 0) "" else settings.maxTurns.toString(),
                    placeholder = "0",
                    keyboardType = KeyboardType.Number,
                    onChange = { v ->
                        val n = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                        scope.launch { container.settings.edit { it.copy(maxTurns = n) } }
                    },
                )
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "主题",
                    subtitle = listOf("跟随系统", "浅色", "深色").getOrElse(settings.themeMode) { "跟随系统" },
                ) {
                    TextButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            container.settings.edit { it.copy(themeMode = (it.themeMode + 1) % 3) }
                        }
                    }) {
                        Text("切换", color = MaterialTheme.appColors.accentInk)
                    }
                }
            }

            Section("关于", null) {
                KeyValue("应用版本", "Claudroid ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                KeyValue("后端", "Claude Code CLI（官方 npm 发布物）")
                val latest = release
                val status = latest?.let { ReleaseCheck.status(BuildConfig.VERSION_NAME, it.tag) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "最新发布",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when {
                                latest != null -> "v${latest.tag} · " + when (status) {
                                    ReleaseStatus.UP_TO_DATE -> "已是最新"
                                    ReleaseStatus.UPDATE_AVAILABLE -> "有新版本可下载"
                                    ReleaseStatus.DEV_BUILD -> "当前是本地构建"
                                    null -> ""
                                }
                                checkingRelease -> "正在检查…"
                                else -> "检查失败"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status == ReleaseStatus.UPDATE_AVAILABLE) {
                                MaterialTheme.appColors.accentInk
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    when {
                        latest != null -> TextButton(onClick = { openUrl(appContext, latest.url) }) {
                            Text(
                                if (status == ReleaseStatus.UPDATE_AVAILABLE) "去下载" else "打开发布页",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.appColors.accentInk,
                            )
                        }
                        checkingRelease -> CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 1.6.dp,
                            color = MaterialTheme.appColors.accentInk,
                        )
                        else -> TextButton(onClick = { scope.launch { checkRelease() } }) {
                            Text(
                                "重试",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.appColors.accentInk,
                            )
                        }
                    }
                }
                releaseError?.let {
                    Text(
                        "无法连接 GitHub：$it。网络受限时可忽略此检查，直接手动打开项目 Release 页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                SettingRow(
                    title = "工作区文件",
                    subtitle = Paths.workspace(appContext).absolutePath,
                ) {
                    GhostPill("打开", true) { onOpenWorkspace() }
                }
                Hint("工作区在 jail 内映射为 /work；Claude 生成的文件可以直接在应用里预览、分享或交给其他应用打开。")
            }
            Spacer(Modifier.height(40.dp))
        }

        if (showModels) {
            AlertDialog(
                onDismissRequest = { showModels = false },
                title = { Text("选择模型（${modelList.size}）", style = MaterialTheme.typography.titleMedium) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(modelList, key = { it }) { model ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch { container.settings.edit { it.copy(model = model) } }
                                        showModels = false
                                    }
                                    .padding(vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    model,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (model == settings.model) MaterialTheme.appColors.accentInk
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (model == settings.model) {
                                    Text("✓", color = MaterialTheme.appColors.accentInk)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showModels = false }) { Text("关闭") }
                },
                containerColor = MaterialTheme.colorScheme.surface,
            )
        }

        if (showUninstall) {
            AlertDialog(
                onDismissRequest = { showUninstall = false },
                title = { Text("卸载内置引擎") },
                text = {
                    Text(
                        "将删除 jail 里的 Claude Code 与运行环境（约 300MB）。" +
                            "会话记录默认保留；选择「全部删除」会同时清空工作区和会话。"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showUninstall = false
                            scope.launch {
                                container.engine.uninstall(deleteWorkspace = false)
                                usage = null
                            }
                        }
                    ) { Text("仅卸载引擎") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showUninstall = false
                            scope.launch {
                                container.engine.uninstall(deleteWorkspace = true)
                                usage = null
                            }
                        }
                    ) { Text("全部删除", color = MaterialTheme.colorScheme.error) }
                },
                containerColor = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Section(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(16.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.appColors.card)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        SectionLabel(title)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.faintText,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.appColors.faintText,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.appColors.faintText,
            modifier = Modifier.width(76.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    secret: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var revealed by remember { mutableStateOf(false) }
    // Keep the editing buffer local. Binding BasicTextField straight to the persisted
    // value makes every keystroke round-trip through DataStore; while that write lags,
    // Compose can render a stale value and drop or reorder fast input.
    var text by remember { mutableStateOf(value) }
    var committed by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.appColors.accentInk.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(Motion.FAST),
        label = "field-border",
    )
    LaunchedEffect(value, focused) {
        // While focused, every flow emission is an echo of our own keystrokes and may
        // still lag behind; adopting it would undo characters. External edits (provider
        // presets) clear focus first, so they land here once the field is idle again.
        if (!focused && value != committed) {
            text = value
            committed = value
        }
    }
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.appColors.faintText,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        committed = it
                        onChange(it)
                    },
                    singleLine = singleLine,
                    maxLines = if (singleLine) 1 else 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.appColors.accentInk),
                    visualTransformation = if (secret && !revealed) PasswordVisualTransformation()
                    else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (secret) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (revealed) "隐藏" else "显示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.appColors.accentInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { revealed = !revealed }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            Pill(text = label(option), selected = option == selected) { onSelect(option) }
        }
    }
}

@Composable
private fun PresetChips(settings: AppSettings, onApply: (ProviderPreset) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Providers.all.forEach { preset ->
            val active = settings.apiFlavor == preset.flavor &&
                settings.baseUrl.trimEnd('/') == preset.baseUrl.trimEnd('/')
            Pill(text = preset.label, selected = active) { onApply(preset) }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.appColors.accentInk
        else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseOut),
        label = "pill-bg",
    )
    val border by animateColorAsState(
        targetValue = if (selected) MaterialTheme.appColors.accentInk
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseOut),
        label = "pill-border",
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EaseOut),
        label = "pill-fg",
    )
    Box(
        Modifier
            .clip(CircleShape)
            .background(background)
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
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

@Composable
private fun PrimaryPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.appColors.accentInk,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun GhostPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.appColors.faintText,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MonoBlock(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(11.dp)
    ) {
        Text(
            text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
