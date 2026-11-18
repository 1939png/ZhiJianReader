package com.qin.paperreader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qin.paperreader.data.prefs.ReaderSettings
import com.qin.paperreader.reader.ReaderViewModel

/** 阅读页入口：从 ReaderViewModel 取状态，包装核心面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val tick by vm.fontsTick.collectAsStateWithLifecycle()
    SettingsSheetCore(
        settings = s,
        fontsTick = tick,
        onUpdate = vm::updateSettings,
        onImportFont = vm::importFont,
        onDismiss = onDismiss,
    )
}

/**
 * 阅读设置核心面板（书架与阅读页共用）：
 * 版式（字号/行距/段距/边距/缩进）、字体（内置+导入 TTF/OTF）、主题（预设+自定义色+纸纹）、
 * 翻页动画、辅助（常亮/音量键）。阅读页打开时页面本身即实时预览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheetCore(
    settings: ReaderSettings,
    fontsTick: Int,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onImportFont: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportFont(uri)
    }
    val fonts = remember(fontsTick, settings.fontKey) { FontManager.options(context) }
    val s = settings
    fun set(block: (ReaderSettings) -> ReaderSettings) = onUpdate(block)

    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---------- 预览样张 ----------
            val (bgL, textL) = Themes.resolveBgText(s)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(bgL))
                    .padding(14.dp),
            ) {
                Text(
                    "设置即所见（阅读页实时生效）",
                    fontSize = 12.sp,
                    color = Color(Themes.accentFor(bgL.toInt(), textL.toInt())),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "永和九年，岁在癸丑，暮春之初，会于会稽山阴之兰亭，修禊事也。群贤毕至，少长咸集。",
                    fontSize = s.fontSizeSp.sp,
                    lineHeight = (s.fontSizeSp * s.lineSpacingMult).sp,
                    color = Color(textL),
                )
            }
            SectionSpacer()

            // ---------- 版式 ----------
            SectionLabel("版式")
            LabeledSlider(
                label = "字体大小",
                value = s.fontSizeSp,
                valueText = "%.0f sp".format(s.fontSizeSp),
                range = 12f..36f,
                steps = 23,
            ) { v -> set { it.copy(fontSizeSp = v) } }
            LabeledSlider(
                label = "行距",
                value = s.lineSpacingMult,
                valueText = "%.2f×".format(s.lineSpacingMult),
                range = 1.0f..2.0f,
                steps = 19,
            ) { v -> set { it.copy(lineSpacingMult = v) } }
            LabeledSlider(
                label = "段距",
                value = s.paraSpacingDp,
                valueText = "%.0f dp".format(s.paraSpacingDp),
                range = 0f..24f,
                steps = 23,
            ) { v -> set { it.copy(paraSpacingDp = v) } }
            LabeledSlider(
                label = "左右边距",
                value = s.marginHDp,
                valueText = "%.0f dp".format(s.marginHDp),
                range = 8f..48f,
                steps = 39,
            ) { v -> set { it.copy(marginHDp = v) } }
            LabeledSlider(
                label = "上下边距",
                value = s.marginVDp,
                valueText = "%.0f dp".format(s.marginVDp),
                range = 8f..48f,
                steps = 39,
            ) { v -> set { it.copy(marginVDp = v) } }
            LabeledSlider(
                label = "首行缩进",
                value = s.indentChars.toFloat(),
                valueText = "${s.indentChars} 字符",
                range = 0f..2f,
                steps = 1,
            ) { v -> set { it.copy(indentChars = v.toInt()) } }
            SectionSpacer()

            // ---------- 字体 ----------
            SectionLabel("字体")
            ChipSelector(
                options = fonts.map { it.key to it.label },
                selected = s.fontKey,
            ) { key -> set { it.copy(fontKey = key) } }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                fontLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "application/x-font-ttf", "*/*"))
            }) { Text("从本地导入 TTF / OTF 字体…") }
            SectionSpacer()

            // ---------- 主题 ----------
            SectionLabel("主题")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Themes.presets.forEach { p ->
                    val selected = s.themeId == p.id
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(p.bg))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color(p.text).copy(alpha = 0.35f),
                                    shape = CircleShape,
                                )
                                .clickable { set { it.copy(themeId = p.id) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("文", fontSize = 13.sp, color = Color(p.text))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(p.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(s.customBg))
                            .border(
                                width = if (s.themeId == "custom") 2.dp else 1.dp,
                                color = if (s.themeId == "custom") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = CircleShape,
                            )
                            .clickable { set { it.copy(themeId = "custom") } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("自", fontSize = 13.sp, color = Color(s.customText))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("自定义", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (s.themeId == "custom") {
                Spacer(Modifier.height(8.dp))
                Text("背景色", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ColorSliders(s.customBg) { v -> set { it.copy(customBg = v) } }
                Spacer(Modifier.height(4.dp))
                Text("文字色", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ColorSliders(s.customText) { v -> set { it.copy(customText = v) } }
            }
            Spacer(Modifier.height(4.dp))
            SwitchRow("纸纹（纸张质感噪点）", s.paperTexture) { v ->
                set { it.copy(paperTexture = v) }
            }
            SectionSpacer()

            // ---------- 翻页动画 ----------
            SectionLabel("翻页动画")
            ChipSelector(
                options = com.qin.paperreader.reader.AnimMode.entries.map { it.id to it.label },
                selected = s.animMode,
            ) { id -> set { it.copy(animMode = id) } }
            SectionSpacer()

            // ---------- 辅助 ----------
            SectionLabel("辅助")
            SwitchRow("屏幕常亮", s.keepScreenOn) { v ->
                set { it.copy(keepScreenOn = v) }
            }
            SwitchRow("音量键翻页（音量下 = 下一页）", s.volumeTurn) { v ->
                set { it.copy(volumeTurn = v) }
            }
            SectionSpacer()
            SectionSpacer()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SectionSpacer() {
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ColorSliders(argb: Long, onChange: (Long) -> Unit) {
    val c = argb.toInt()
    var r by remember(argb) { mutableStateOf(((c shr 16 and 0xFF)).toFloat()) }
    var g by remember(argb) { mutableStateOf(((c shr 8 and 0xFF)).toFloat()) }
    var b by remember(argb) { mutableStateOf(((c and 0xFF)).toFloat()) }
    Column {
        LabeledSlider("红", r, "%.0f".format(r), 0f..255f, steps = 254) { v -> r = v; emit(r, g, b, onChange) }
        LabeledSlider("绿", g, "%.0f".format(g), 0f..255f, steps = 254) { v -> g = v; emit(r, g, b, onChange) }
        LabeledSlider("蓝", b, "%.0f".format(b), 0f..255f, steps = 254) { v -> b = v; emit(r, g, b, onChange) }
    }
}

private fun emit(r: Float, g: Float, b: Float, onChange: (Long) -> Unit) {
    val argb = (0xFFL shl 24) or (r.toInt().toLong() shl 16) or (g.toInt().toLong() shl 8) or b.toInt().toLong()
    onChange(argb)
}
