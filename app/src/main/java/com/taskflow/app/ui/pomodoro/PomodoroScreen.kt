package com.taskflow.app.ui.pomodoro

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.ui.theme.GradientEnd
import com.taskflow.app.ui.theme.GradientStart
import kotlin.random.Random

/**
 * Immersive Pomodoro focus screen.
 *
 * - 沉浸式壁纸背景 (gradient / preset / custom upload) + 顶部励志名言
 * - 极简环形倒计时器 (MM:SS), 初始值为任务设定的 [PomodoroViewModel.UiState.totalSeconds]
 * - **进页自动倒计时 (Auto-Start)**: ViewModel 启动后立即开始专注
 * - **暂停限制弹窗 (Pause Limit Timer)**: 点暂停即弹模态倒计时, 到点自动继续
 * - 底部控制栏: 🖼️壁纸 / 🎵 背景音 / ▶⏸ 播放暂停 / ↺ 重置 / ☀️ 屏幕常亮
 * - 屏幕常亮 (FLAG_KEEP_SCREEN_ON) 通过 [DisposableEffect] 挂到当前 Window
 * - 三音源背景音乐 BottomSheet (本地 raw / 在线流媒体 / 📁 自定义导入)
 * - 倒计时完成时, ViewModel 自动写入 focus_history 表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PomodoroScreen(
    taskId: Long,
    onBack: () -> Unit
) {
    val viewModel: PomodoroViewModel = viewModel(factory = PomodoroViewModel.factory(taskId))
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // ====== Keep-screen-on (spec: 通过 DisposableEffect 对当前 Window 动态设置
    // FLAG_KEEP_SCREEN_ON, 关闭/退出页面时自动清除标记). ======
    DisposableEffect(state.keepScreenOn) {
        val activity = context.findActivity()
        val window = activity?.window
        if (state.keepScreenOn && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ====== Background music manager — owned by the screen, released on exit. ======
    val audioManager = remember { AudioPlayerManager(context) }
    DisposableEffect(Unit) {
        onDispose { audioManager.release() }
    }

    // ====== Local audio file import (spec: 📁 自定义导入, 读取手机本地 MP3). ======
    val audioImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistable read permission so playback survives process death.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = queryDisplayName(uri, context) ?: "自定义音频"
            audioManager.playImported(uri, name)
        }
    }

    // ====== Custom wallpaper image picker (spec: ➕ 自定义上传, 从相册选择). ======
    var customWallpaperUri by rememberSaveable { mutableStateOf<String?>(null) }
    val wallpaperPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            customWallpaperUri = uri.toString()
        }
    }

    var showAudioSheet by rememberSaveable { mutableStateOf(false) }
    val audioSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showWallpaperSheet by rememberSaveable { mutableStateOf(false) }
    val wallpaperSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var wallpaperCategory by rememberSaveable { mutableStateOf(WallpaperCategory.SCENERY) }

    // ====== Pick a motivational quote on entry (stable per session). ======
    val quote = remember {
        MOTIVATIONAL_QUOTES[Random.nextInt(MOTIVATIONAL_QUOTES.size)]
    }

    // ====== Auto-dismiss the completion toast after a few seconds. ======
    LaunchedEffect(state.completed) {
        if (state.completed) {
            kotlinx.coroutines.delay(3000L)
            viewModel.acknowledgeCompletion()
        }
    }

    // ====== Status-bar immersion fix (spec: 顶栏白块 Bug) ======
    // 壁纸层放在 Scaffold *背后* (fillMaxSize, 无 padding)，渐变/图片直接延伸到
    // 屏幕最顶端（status bar 之下），彻底消除顶部白色遮挡。Scaffold 自身保持透明，
    // TopAppBar 也透明，content 仅承载前景控件。
    Box(modifier = Modifier.fillMaxSize()) {
        // ====== Layer 1: immersive wallpaper background (full-bleed) ======
        // Custom uploaded image wins; otherwise the selected preset's gradient.
        if (customWallpaperUri != null) {
            Image(
                painter = rememberAsyncCoilPainterOrNull(customWallpaperUri)
                    ?: painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Dark overlay so white text stays legible over any photo.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC0F172A))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                wallpaperCategory.topColor,
                                wallpaperCategory.midColor,
                                wallpaperCategory.bottomColor
                            )
                        )
                    )
            )
            // Soft starry overlay (cheap — drawn as canvas dots).
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rng = Random(42)
                repeat(40) {
                    val x = rng.nextFloat() * size.width
                    val y = rng.nextFloat() * size.height * 0.6f
                    val r = rng.nextFloat() * 1.6f + 0.4f
                    drawCircle(
                        color = Color.White.copy(alpha = 0.18f + rng.nextFloat() * 0.3f),
                        radius = r,
                        center = androidx.compose.ui.geometry.Offset(x, y)
                    )
                }
            }
        }

        // ====== Layer 2: transparent Scaffold on top of the wallpaper ======
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.taskTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "番茄专注",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    },
                    actions = {
                        // 🖼️ 壁纸设置 (top-right entry — always reachable)
                        IconButton(onClick = { showWallpaperSheet = true }) {
                            Icon(
                                Icons.Outlined.Image,
                                contentDescription = "壁纸设置",
                                tint = Color.White
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            // ====== Layer 3: foreground content (no wallpaper here) ======
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(Modifier.height(8.dp))

                // ====== Motivational quote ======
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "“",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = quote.first,
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Text(
                        text = "— ${quote.second}",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.height(48.dp))

                // ====== Ring countdown timer ======
                RingCountdown(
                    totalSeconds = state.totalSeconds,
                    remainingSeconds = state.remainingSeconds,
                    isRunning = state.isRunning,
                    isCompleted = state.completed
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (state.completed) "🎉 本轮专注完成！已记录至统计"
                    else if (state.isPausing) "休息一下，倒计时后自动继续"
                    else if (state.isRunning) "专注中…" else "准备好了就开始吧",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "已完成 ${state.sessionsCompleted} 轮",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(Modifier.weight(1f))

                // ====== Audio now-playing indicator ======
                AnimatedVisibility(
                    visible = audioManager.currentTitle != null,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(200))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Outlined.GraphicEq,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = audioManager.currentTitle.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ====== Bottom control bar ======
                BottomControlBar(
                    isRunning = state.isRunning,
                    isCompleted = state.completed,
                    keepScreenOn = state.keepScreenOn,
                    onToggleRunning = viewModel::toggleRunning,
                    onReset = viewModel::reset,
                    onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
                    onOpenAudio = { showAudioSheet = true },
                    onOpenWallpaper = { showWallpaperSheet = true }
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ====== Pause-limit modal (spec: 点暂停立即弹窗显示暂停限制倒计时). ======
    if (state.isPausing) {
        PauseLimitDialog(
            pauseRemainingSeconds = state.pauseRemainingSeconds,
            onResume = viewModel::resumeFromPause
        )
    }

    // ====== Audio BottomSheet (本地离线 / 在线流媒体 / 📁 自定义导入) ======
    if (showAudioSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAudioSheet = false },
            sheetState = audioSheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            AudioPickerContent(
                manager = audioManager,
                onImportLocal = {
                    audioImportLauncher.launch(arrayOf("audio/*"))
                },
                onClose = { showAudioSheet = false }
            )
        }
    }

    // ====== Wallpaper BottomSheet (预置分类 + ➕ 自定义上传) ======
    if (showWallpaperSheet) {
        ModalBottomSheet(
            onDismissRequest = { showWallpaperSheet = false },
            sheetState = wallpaperSheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            WallpaperPickerContent(
                selectedCategory = wallpaperCategory,
                onSelectCategory = { wallpaperCategory = it },
                onUploadCustom = {
                    wallpaperPickerLauncher.launch(arrayOf("image/*"))
                },
                onClearCustom = { customWallpaperUri = null },
                hasCustomWallpaper = customWallpaperUri != null,
                onClose = { showWallpaperSheet = false }
            )
        }
    }
}

// ====== Ring countdown ======

@Composable
private fun RingCountdown(
    totalSeconds: Int,
    remainingSeconds: Int,
    isRunning: Boolean,
    isCompleted: Boolean
) {
    val progress = if (totalSeconds <= 0) 0f
    else (totalSeconds - remainingSeconds).toFloat() / totalSeconds.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(600),
        label = "ringProgress"
    )

    val displaySeconds = remainingSeconds.coerceAtLeast(0)
    val mm = (displaySeconds / 60).toString().padStart(2, '0')
    val ss = (displaySeconds % 60).toString().padStart(2, '0')

    // Subtle pulse while running — gives the ring a "living" feel.
    val infinite = rememberInfiniteTransition(label = "ringPulse")
    val pulseScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val ringScale = if (isRunning) pulseScale else 1f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(280.dp)
            .scale(ringScale)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = 14.dp.toPx()
            val diameter = size.minDimension - strokeW
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f
            )
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
            // Track
            drawArc(
                color = Color.White.copy(alpha = 0.12f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
            // Progress ring
            val ringColor = if (isCompleted) Color(0xFF6EE7C7) else GradientStart
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }

        // Inner glassy disc
        Box(
            modifier = Modifier
                .size(210.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$mm:$ss",
                    color = Color.White,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isCompleted) "完成" else if (isRunning) "专注中" else "待开始",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

// ====== Pause-limit modal ======

@Composable
private fun PauseLimitDialog(
    pauseRemainingSeconds: Int,
    onResume: () -> Unit
) {
    val mm = (pauseRemainingSeconds / 60).toString().padStart(2, '0')
    val ss = (pauseRemainingSeconds % 60).toString().padStart(2, '0')

    // Pulsing accent to draw the eye.
    val infinite = rememberInfiniteTransition(label = "pausePulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pausePulseAlpha"
    )

    AlertDialog(
        onDismissRequest = { /* enforce the limit — no dismiss-by-outside-tap */ },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Pause,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B).copy(alpha = pulseAlpha),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "暂停中",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF59E0B)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large countdown digits
                Text(
                    text = "$mm:$ss",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF59E0B).copy(alpha = pulseAlpha)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "以上是暂停限制时间，避免过长打断专注流程",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "倒计时归零后将自动继续专注",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(containerColor = GradientStart)
            ) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("继续专注", color = Color.White)
            }
        },
        dismissButton = {} // no dismiss button — enforce the limit
    )
}

// ====== Bottom control bar ======

@Composable
private fun BottomControlBar(
    isRunning: Boolean,
    isCompleted: Boolean,
    keepScreenOn: Boolean,
    onToggleRunning: () -> Unit,
    onReset: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenWallpaper: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 🖼️ 壁纸
        PillButton(
            icon = Icons.Outlined.Image,
            label = "壁纸",
            highlighted = false,
            onClick = onOpenWallpaper
        )

        // 🎵 背景音
        PillButton(
            icon = Icons.Outlined.MusicNote,
            label = "背景音",
            highlighted = false,
            onClick = onOpenAudio
        )

        // ▶/⏸ 主控按钮 (放大居中)
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(GradientStart, GradientEnd))
                ),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onToggleRunning, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = if (isRunning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isRunning) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // ↺ 重置
        PillButton(
            icon = Icons.Outlined.Refresh,
            label = "重置",
            highlighted = false,
            onClick = onReset
        )

        // ☀️ 屏幕常亮 toggle (compact pill)
        PillButton(
            icon = Icons.Outlined.WbSunny,
            label = if (keepScreenOn) "已常亮" else "常亮",
            highlighted = keepScreenOn,
            onClick = onToggleKeepScreenOn
        )
    }
}

@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (highlighted) GradientStart.copy(alpha = 0.3f)
        else Color.White.copy(alpha = 0.10f)
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// ====== Audio BottomSheet content ======

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioPickerContent(
    manager: AudioPlayerManager,
    onImportLocal: () -> Unit,
    onClose: () -> Unit
) {
    var selectedCategory by rememberSaveable { mutableStateOf(AudioCategory.NATURE) }
    val tracks = remember(selectedCategory) { AudioLibrary.byCategory(selectedCategory) }

    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = "背景音乐",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "💾 本地预置 · 🌐 在线流媒体 · 📁 自定义导入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(14.dp))

        // Preset category tabs: 自然音 / 氛围音乐 / 轻音乐
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioCategory.values().forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat.label) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ====== 📁 自定义导入 entry (always visible above the list) ======
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Upload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "📁 自定义导入",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "从手机本地选择 MP3 / M4A / WAV",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onImportLocal) {
                    Text("选择文件")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tracks, key = { it.title + it.source.hashCode() }) { track ->
                val isOnline = track.source is AudioSource.Online
                val isCurrent = manager.currentTitle == track.title && manager.isPlaying
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isOnline) Color(0xFF7950F2).copy(alpha = 0.15f)
                                    else Color(0xFF15D0AB).copy(alpha = 0.15f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isOnline) "🌐" else "💾",
                                fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isOnline) "在线流媒体" else "本地离线",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            if (isCurrent) {
                                manager.stop()
                            } else {
                                manager.play(track)
                            }
                        }) {
                            Icon(
                                imageVector = if (isCurrent) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (isCurrent) "停止" else "播放",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = if (manager.isPlaying) "正在播放：${manager.currentTitle.orEmpty()}" else "未播放",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                manager.stop()
                onClose()
            }) { Text("完成") }
        }
    }
}

// ====== Wallpaper BottomSheet content ======

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallpaperPickerContent(
    selectedCategory: WallpaperCategory,
    onSelectCategory: (WallpaperCategory) -> Unit,
    onUploadCustom: () -> Unit,
    onClearCustom: () -> Unit,
    hasCustomWallpaper: Boolean,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = "壁纸设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "预置风景 / 动物 / 极简 · ➕ 自定义上传",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(14.dp))

        // Preset category chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WallpaperCategory.values().forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = {
                        // Switching to a preset clears any custom upload.
                        onClearCustom()
                        onSelectCategory(cat)
                    },
                    label = { Text(cat.label) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ====== ➕ 自定义上传 entry ======
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = if (hasCustomWallpaper) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (hasCustomWallpaper) "✓ 已设置自定义壁纸" else "➕ 自定义上传",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (hasCustomWallpaper) "从相册重新选择 / 清除自定义"
                        else "从相册选择图片，自动高斯模糊遮罩",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onUploadCustom) {
                    Text(if (hasCustomWallpaper) "重新选择" else "选择图片")
                }
            }
        }

        if (hasCustomWallpaper) {
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClearCustom) { Text("清除自定义壁纸") }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Preset preview swatches
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(WallpaperCategory.values().toList()) { cat ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = if (selectedCategory == cat && !hasCustomWallpaper)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.verticalGradient(listOf(cat.topColor, cat.bottomColor))
                                )
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = cat.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = cat.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            onClearCustom()
                            onSelectCategory(cat)
                        }) { Text("应用") }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text("完成") }
        }
    }
}

// ====== Wallpaper presets ======

enum class WallpaperCategory(
    val label: String,
    val description: String,
    val topColor: Color,
    val midColor: Color,
    val bottomColor: Color
) {
    SCENERY(
        label = "风景",
        description = "深蓝渐变 · 星夜沉浸",
        topColor = Color(0xFF1E1B4B),
        midColor = Color(0xFF312E81),
        bottomColor = Color(0xFF0F172A)
    ),
    ANIMAL(
        label = "动物",
        description = "暖橙琥珀 · 温润陪伴",
        topColor = Color(0xFF7C2D12),
        midColor = Color(0xFF9A3412),
        bottomColor = Color(0xFF431407)
    ),
    MINIMAL(
        label = "极简/杂项",
        description = "薄荷灰绿 · 清爽专注",
        topColor = Color(0xFF064E3B),
        midColor = Color(0xFF065F46),
        bottomColor = Color(0xFF022C22)
    )
}

// ====== Helpers ======

/** Walk up the Context chain to find the hosting Activity (for Window flags). */
private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Resolve a display name for a content:// URI via OpenableColumns. Returns null
 * on any failure — callers fall back to a generic label.
 */
private fun queryDisplayName(uri: Uri, context: android.content.Context): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}

/**
 * Returns a Coil `rememberAsyncImagePainter` for the given URI. Coil is a hard
 * dependency of this project (see app/build.gradle.kts `libs.coil.compose`), so
 * we call it directly. If the URI is null, returns null so the caller falls
 * back to the preset gradient wallpaper.
 */
@Composable
private fun rememberAsyncCoilPainterOrNull(uri: String?): androidx.compose.ui.graphics.painter.Painter? {
    if (uri == null) return null
    return coil.compose.rememberAsyncImagePainter(model = uri)
}

/**
 * Motivational quotes shown at the top of the focus screen. Picked at random
 * once per entry (stable for the duration of the session).
 */
private val MOTIVATIONAL_QUOTES: List<Pair<String, String>> = listOf(
    "专注，是最高级的自由。" to "村上春树",
    "你今天偷的懒，是为明天挖的坑。" to "佚名",
    "把每一件简单的事做好就是不简单。" to "张瑞敏",
    "不积跬步，无以至千里。" to "荀子",
    "真正的努力，是水到渠成。" to "佚名",
    "完成比完美更重要。" to "Sheryl Sandberg",
    "专注当下，未来自来。" to "佚名",
    "心心在一艺，其艺必工。" to "《韩非子》"
)
