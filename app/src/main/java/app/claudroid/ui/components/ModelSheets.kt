package app.claudroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudroid.data.AppSettings
import app.claudroid.data.ModelTier
import app.claudroid.ui.theme.AppIcons
import app.claudroid.ui.theme.AppType
import app.claudroid.ui.theme.Radii
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

/**
 * Model switcher shared by home and chat. This used to be a bottom sheet; a dropdown
 * anchored under the model pill matches the top-bar control that opens it and keeps
 * the transcript visible. Selecting a tier only changes [AppSettings.activeTier]; the
 * concrete model stays editable in settings so the same picker works for Anthropic
 * aliases and OpenAI-compatible proxies.
 */
@Composable
fun ModelDropdownMenu(
    expanded: Boolean,
    settings: AppSettings,
    offset: DpOffset,
    onSelectTier: (ModelTier?) -> Unit,
    onSelectAuto: () -> Unit,
    onManageTiers: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    FloatingMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        width = ModelMenuWidth,
        offset = offset,
    ) {
        Text(
            "选择模型",
            style = AppType.captionMedium.copy(letterSpacing = 0.4.sp),
            color = colors.stone,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
        )
        val active = settings.resolvedActiveTier
        ModelMenuItem(
            title = "默认 / 路由组",
            subtitle = settings.model.ifBlank { "未设置" },
            selected = active == null && !settings.usesAutoModel,
        ) {
            onSelectTier(null)
            onDismiss()
        }
        ModelMenuItem(
            title = "CLI 默认（不指定模型）",
            subtitle = "不传 --model，由 Claude Code 用自身默认模型",
            selected = settings.usesAutoModel,
        ) {
            onSelectAuto()
            onDismiss()
        }
        ModelTier.entries.forEach { tier ->
            ModelMenuItem(
                title = tier.label,
                subtitle = tierMappingLabel(settings, tier),
                selected = active == tier,
            ) {
                onSelectTier(tier)
                onDismiss()
            }
        }
        ClaudeDivider(Modifier.padding(horizontal = 12.dp))
        DropdownMenuItem(
            text = {
                Text(
                    "管理模型档位",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accentInk,
                )
            },
            leadingIcon = {
                Icon(
                    AppIcons.Layers,
                    contentDescription = null,
                    tint = colors.accentInk,
                    modifier = Modifier.size(17.dp),
                )
            },
            onClick = {
                onDismiss()
                onManageTiers()
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ModelMenuItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) colors.accentInk else colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = AppType.caption,
                    color = colors.stone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    AppIcons.Check,
                    contentDescription = "已选择",
                    tint = colors.accentInk,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else null,
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = colors.ink,
            trailingIconColor = colors.accentInk,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    )
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
                val shape = Radii.smShape
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressFeedback(shape = shape)
                        .clip(shape)
                        .background(
                            if (selected) MaterialTheme.appColors.surface1
                            else MaterialTheme.appColors.paper
                        )
                        .border(
                            1.dp,
                            if (selected) MaterialTheme.appColors.ink
                            else MaterialTheme.appColors.sand,
                            shape,
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
                                .pressFeedback(shape = Radii.xsShape)
                                .clip(Radii.xsShape)
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
            .clip(Radii.smShape)
            .background(MaterialTheme.appColors.surface1)
            .border(1.dp, MaterialTheme.appColors.sand, Radii.smShape)
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
