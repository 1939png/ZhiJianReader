package com.qin.paperreader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qin.paperreader.text.EncodingDetector
import java.text.Collator
import java.util.Locale

// ---------- 对话框 ----------

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    placeholder: String = "",
    confirmLabel: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 导入/重新指定编码：单选编码 + 实时预览。 */
@Composable
fun EncodingPickerDialog(
    title: String,
    displayName: String,
    sizeBytes: Long,
    detected: EncodingDetector.Id,
    previews: Map<EncodingDetector.Id, String>,
    confirmLabel: String = "导入",
    onConfirm: (EncodingDetector.Id) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(detected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(displayName, fontWeight = FontWeight.Medium)
                if (sizeBytes >= 0) {
                    Text(formatSize(sizeBytes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    EncodingDetector.Id.entries.forEach { id ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selected = id }
                                .padding(vertical = 2.dp),
                        ) {
                            RadioButton(selected = selected == id, onClick = { selected = id })
                            Text(id.label + if (id == detected) "（自动识别）" else "")
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "预览",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        previews[selected] ?: "",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "若预览出现乱码，请切换编码。重新指定编码后阅读进度会回到开头。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ---------- 书架封面 ----------

private val coverColors = listOf(
    0xFF8C6E4A, 0xFF5F7161, 0xFF6B5B7A, 0xFF7A5C50,
    0xFF4F6D7A, 0xFF8A6D3B, 0xFF5D6D5E, 0xFF7D6862,
    0xFF56657A, 0xFF7A6A54,
)

@Composable
fun BookCover(title: String, seed: String, modifier: Modifier = Modifier, coverFile: java.io.File? = null) {
    val idx = (seed.hashCode() and 0x7FFFFFFF) % coverColors.size
    val base = Color(coverColors[idx])
    val dark = Color(Themes.mix(base.toArgb(), 0xFF000000.toInt(), 0.25f))
    // 自定义封面：后台解码，失败时回退到生成的默认封面
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = coverFile?.path,
        key2 = coverFile?.lastModified(),
    ) {
        value = coverFile?.let { f ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Box(modifier.clip(RoundedCornerShape(6.dp))) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "自定义封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(listOf(base, dark))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.take(1).ifBlank { "书" },
                color = Color(0xFFF7F1E4),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------- 设置控件 ----------

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> ChipSelector(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (id, label) ->
            val isSel = id == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- 格式化 ----------

fun formatTimeAgo(ts: Long): String {
    if (ts <= 0) return "未读过"
    val now = System.currentTimeMillis()
    val diff = now - ts
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 2 * day -> "昨天"
        diff < 30 * day -> "${diff / day} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date(ts))
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024f)
    else -> "%.1fMB".format(bytes / 1024f / 1024f)
}

fun titleComparator(): Comparator<String> = Comparator { a, b ->
    Collator.getInstance(Locale.CHINA).compare(a, b)
}
