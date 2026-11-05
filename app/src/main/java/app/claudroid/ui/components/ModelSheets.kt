package app.claudroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.data.AppSettings
import app.claudroid.data.ModelTier
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.appColors

private @Composable
fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 34.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.appColors.surface2)
        )
    }
}

private @Composable
fun SheetTitle(title: String, description: String?) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.appColors.ink,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (description != null) {
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.appColors.stone,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private @Composable
fun ModelChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.appColors.accentInk else MaterialTheme.appColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.stone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (selected) {
            Icon(
                AppIcons.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.appColors.accentInk,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Composer model switcher shared by home and chat. Selecting a tier only changes
 * [AppSettings.activeTier]; the concrete model stays editable in settings so the same
 * picker works for Anthropic aliases and OpenAI-compatible proxies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    settings: AppSettings,
    onSelectTier: (ModelTier?) -> Unit,
    onManageTiers: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.appColors.paper,
        scrimColor = MaterialTheme.appColors.ink.copy(alpha = 0.32f),
        dragHandle = { SheetHandle() },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
        ) {
            SheetTitle(
                "选择模型",
                "档位交给 Claude Code 路由；具体模型可在设置里按档位映射。",
            )
            Spacer(Modifier.height(8.dp))
            val active = settings.resolvedActiveTier
            ModelChoiceRow(
                title = "默认模型",
                subtitle = settings.model.ifBlank { "未设置" },
                selected = active == null,
            ) {
                onSelectTier(null)
                onDismiss()
            }
            ClaudeDivider(Modifier.padding(horizontal = 12.dp))
            ModelTier.entries.forEach { tier ->
                ModelChoiceRow(
                    title = tier.label,
                    subtitle = tierMappingLabel(settings, tier),
                    selected = active == tier,
                ) {
                    onSelectTier(tier)
                    onDismiss()
                }
            }
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "管理模型档位",
                onClick = {
                    onManageTiers()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Searchable model list reused by the default/small model rows and the four tier
 * mappings. Blank writes are meaningful for tiers (official default) and small model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelListSheet(
    title: String,
    description: String?,
    models: List<String>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    emptyOption: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var manual by remember(title, current) { mutableStateOf(current) }
    val filtered = remember(models, query) {
        if (query.isBlank()) models else models.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.appColors.paper,
        scrimColor = MaterialTheme.appColors.ink.copy(alpha = 0.32f),
        dragHandle = { SheetHandle() },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            SheetTitle(title, description)
            Spacer(Modifier.height(10.dp))

            if (emptyOption != null) {
                val selected = current.isBlank()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MaterialTheme.appColors.orangeSoft
                            else MaterialTheme.appColors.paper
                        )
                        .border(
                            1.dp,
                            if (selected) MaterialTheme.appColors.orange
                            else MaterialTheme.appColors.sand,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable {
                            onSelect("")
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        emptyOption,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) MaterialTheme.appColors.accentInk else MaterialTheme.appColors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            AppIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.appColors.accentInk,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            SheetSearch(query) { query = it }
            Spacer(Modifier.height(6.dp))

            if (filtered.isEmpty()) {
                Text(
                    if (models.isEmpty()) {
                        "还没有拉取模型列表。可以先回设置页「拉取模型列表」，也可以在下方手动输入。"
                    } else {
                        "没有匹配「${query.trim()}」的模型。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.stone,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 228.dp)) {
                    items(filtered, key = { it }) { model ->
                        val selected = model.trim() == current.trim()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onSelect(model)
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                model,
                                style = AppType.code.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                                color = if (selected) MaterialTheme.appColors.accentInk
                                else MaterialTheme.appColors.ink,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (selected) {
                                Icon(
                                    AppIcons.Check,
                                    null,
                                    tint = MaterialTheme.appColors.accentInk,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }

            ClaudeDivider(Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            ClaudeField(
                label = "手动输入",
                value = manual,
                placeholder = "例如 claude-sonnet-5-5 或 auto/hy4free",
                onChange = { manual = it },
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "使用此模型",
                onClick = {
                    onSelect(manual.trim())
                    onDismiss()
                },
                enabled = manual.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SheetSearch(value: String, onValueChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.appColors.surface1)
            .border(1.dp, MaterialTheme.appColors.sand, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Search,
            null,
            tint = MaterialTheme.appColors.stone,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "搜索模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.stone,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.appColors.ink),
                cursorBrush = SolidColor(MaterialTheme.appColors.orange),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                AppIcons.Close,
                "清空",
                tint = MaterialTheme.appColors.stone,
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") }
                    .padding(4.dp),
            )
        }
    }
}
