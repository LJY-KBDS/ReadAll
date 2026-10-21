package com.readall.app.ui.reader

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val OverlayContainer = Color(0xCC1A1C1B)
private val OverlayContent = Color.White
private val PageTurnFill = Color.White.copy(alpha = 0.22f)
private val PageTurnFillDisabled = Color.White.copy(alpha = 0.10f)
private val PageTurnIcon = Color(0xB35C5C5C)

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPlayButton by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pageInputActive by remember { mutableStateOf(false) }
    val zoom = remember { ZoomState() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val liftSettingsToTop = showSettings && (pageInputActive || imeVisible)
    val settingsVerticalBias by animateFloatAsState(
        targetValue = if (liftSettingsToTop) -1f else 1f,
        animationSpec = tween(durationMillis = 280),
        label = "settingsPanelBias"
    )

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(showSettings) {
        if (!showSettings) {
            pageInputActive = false
            keyboardController?.hide()
        }
    }

    LaunchedEffect(showPlayButton, showSettings) {
        if (showPlayButton && !showSettings) {
            delay(3000)
            showPlayButton = false
        }
    }

    LaunchedEffect(zoom.scale) {
        delay(350)
        viewModel.setRenderZoom(zoom.scale)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111110))
    ) {
        when {
            state.error != null -> {
                Text(
                    text = state.error ?: "",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
            }
            state.pageBitmap != null -> {
                Image(
                    bitmap = state.pageBitmap!!.asImageBitmap(),
                    contentDescription = "PDF ${state.currentPage + 1}페이지",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { zoom.viewport = it }
                        .pointerInput(showSettings) {
                            detectTransformGestures { _, pan, zoomChange, _ ->
                                val newScale = (zoom.scale * zoomChange).coerceIn(1f, 5f)
                                zoom.offset = if (newScale <= 1f) {
                                    Offset.Zero
                                } else {
                                    clampZoomOffset(zoom.offset + pan, newScale, zoom.viewport)
                                }
                                zoom.scale = newScale
                            }
                        }
                        .pointerInput(showSettings) {
                            detectTapGestures(
                                onTap = {
                                    if (!showSettings) showPlayButton = !showPlayButton
                                },
                                onDoubleTap = { tap ->
                                    if (zoom.scale > 1.05f) {
                                        zoom.scale = 1f
                                        zoom.offset = Offset.Zero
                                    } else {
                                        zoom.scale = 2.4f
                                        val center = Offset(zoom.viewport.width / 2f, zoom.viewport.height / 2f)
                                        zoom.offset = clampZoomOffset(
                                            (center - tap) * (zoom.scale - 1f),
                                            zoom.scale,
                                            zoom.viewport
                                        )
                                    }
                                }
                            )
                        }
                        .graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                        }
                )
            }
            else -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        PageTurnZone(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight(0.36f)
                .width(76.dp),
            enabled = state.currentPage > 0 && state.error == null,
            onClick = {
                showPlayButton = false
                viewModel.previousPage()
            },
            content = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "이전 페이지",
                    tint = PageTurnIcon,
                    modifier = Modifier.size(36.dp)
                )
            }
        )

        PageTurnZone(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(0.36f)
                .width(76.dp),
            enabled = state.currentPage < state.pageCount - 1 && state.error == null,
            onClick = {
                showPlayButton = false
                viewModel.nextPage()
            },
            content = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "다음 페이지",
                    tint = PageTurnIcon,
                    modifier = Modifier.size(36.dp)
                )
            }
        )

        FilledIconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(12.dp)
                .align(Alignment.TopStart)
                .size(44.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = OverlayContainer,
                contentColor = OverlayContent
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
        }

        AnimatedVisibility(
            visible = showPlayButton && !showSettings && state.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            FilledIconButton(
                onClick = {
                    viewModel.togglePlay()
                    showPlayButton = false
                },
                modifier = Modifier.size(84.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "일시정지" else "재생",
                    modifier = Modifier.size(44.dp)
                )
            }
        }

        if (state.isPreparing && state.pageBitmap != null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(22.dp),
                strokeWidth = 2.dp,
                color = OverlayContent
            )
        }

        CompactStatusBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            currentPage = state.currentPage + 1,
            pageCount = state.pageCount.coerceAtLeast(1),
            speed = state.speed,
            zoomScale = zoom.scale,
            enabled = state.error == null,
            onClick = {
                showPlayButton = false
                showSettings = true
            }
        )

        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        keyboardController?.hide()
                        showSettings = false
                    }
            )
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier
                .align(BiasAlignment(0f, settingsVerticalBias))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = if (liftSettingsToTop) 8.dp else 0.dp)
        ) {
            ReaderSettingsCard(
                state = state,
                onDismiss = {
                    keyboardController?.hide()
                    showSettings = false
                },
                onPrevious = viewModel::previousPage,
                onNext = viewModel::nextPage,
                onGoToPage = viewModel::goToPageNumber,
                onSpeed = viewModel::setSpeed,
                onPageInputActiveChange = { pageInputActive = it }
            )
        }
    }
}

@Composable
private fun PageTurnZone(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) PageTurnFill else PageTurnFillDisabled),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun CompactStatusBar(
    modifier: Modifier,
    currentPage: Int,
    pageCount: Int,
    speed: Float,
    zoomScale: Float,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val speedLabel = "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"
    val zoomLabel = if (zoomScale > 1.02f) "   ·   ${(zoomScale * 100).toInt()}%" else ""
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(OverlayContainer)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "$currentPage / $pageCount   ·   $speedLabel$zoomLabel",
            color = OverlayContent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewport by mutableStateOf(IntSize.Zero)
}

private fun clampZoomOffset(offset: Offset, scale: Float, viewport: IntSize): Offset {
    if (scale <= 1f || viewport.width == 0 || viewport.height == 0) return Offset.Zero
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY)
    )
}

@Composable
private fun ReaderSettingsCard(
    state: ReaderUiState,
    onDismiss: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGoToPage: (Int) -> Boolean,
    onSpeed: (Float) -> Unit,
    onPageInputActiveChange: (Boolean) -> Unit
) {
    val currentDisplayPage = state.currentPage + 1
    val maxPage = state.pageCount.coerceAtLeast(1)
    var pageField by remember(currentDisplayPage) {
        mutableStateOf(
            TextFieldValue(
                text = currentDisplayPage.toString(),
                selection = TextRange(0, currentDisplayPage.toString().length)
            )
        )
    }
    var pageError by remember { mutableStateOf<String?>(null) }

    fun submitPage() {
        val page = pageField.text.trim().toIntOrNull()
        if (page == null) {
            pageError = "숫자를 입력하세요."
            return
        }
        if (page !in 1..maxPage) {
            pageError = "1부터 ${maxPage} 사이의 페이지를 입력하세요."
            return
        }
        if (!onGoToPage(page)) {
            pageError = "1부터 ${maxPage} 사이의 페이지를 입력하세요."
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("재생 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = "닫기", modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("페이지", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledIconButton(
                    onClick = onPrevious,
                    enabled = state.currentPage > 0,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 페이지")
                }
                OutlinedTextField(
                    value = pageField,
                    onValueChange = { next ->
                        if (next.text.length <= 6 && next.text.all { it.isDigit() }) {
                            pageField = next
                            pageError = null
                        }
                    },
                    singleLine = true,
                    isError = pageError != null,
                    supportingText = pageError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitPage() }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { onPageInputActiveChange(it.isFocused) }
                )
                FilledIconButton(
                    onClick = onNext,
                    enabled = state.currentPage < state.pageCount - 1,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 페이지")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { submitPage() }) { Text("이동") }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("속도", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${"%.2f".format(state.speed).trimEnd('0').trimEnd('.')}x",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = state.speed,
                onValueChange = onSpeed,
                valueRange = 0.5f..2.5f,
                steps = 7,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
            if (!state.statusMessage.isNullOrBlank()) {
                Text(
                    text = state.statusMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
