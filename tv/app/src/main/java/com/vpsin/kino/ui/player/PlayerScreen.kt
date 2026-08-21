package com.vpsin.kino.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vpsin.kino.ui.common.TvButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vpsin.kino.data.ApiClient
import com.vpsin.kino.data.Entry
import com.vpsin.kino.data.ResumeStore
import com.vpsin.kino.data.formatTime
import com.vpsin.kino.ui.theme.Accent
import com.vpsin.kino.ui.theme.Danger
import com.vpsin.kino.ui.theme.TextDim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 4_000L
private const val SAVE_INTERVAL_MS = 10_000L

/** What the overlay is currently showing. */
private enum class Overlay { None, Controls, Subtitles }

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    api: ApiClient,
    file: Entry,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resumeStore = remember { ResumeStore(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var overlay by remember { mutableStateOf(Overlay.Controls) }
    var lastInteraction by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }

    // null = still deciding; false = start from 0; true = seek to saved position
    var resumeDecision by remember { mutableStateOf<Boolean?>(null) }
    var savedPosition by remember { mutableLongStateOf(0L) }
    var askResume by remember { mutableStateOf(false) }

    // Chosen subtitle: null means off. Held here so the selector and the player agree.
    var activeSubtitle by remember { mutableStateOf<String?>(null) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            // Without a wake mode the CPU can sleep on a TV during long playback.
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
    }

    // Decide about resuming before the first frame, so we never show 0:00 and jump.
    LaunchedEffect(file.id) {
        val saved = resumeStore.get(file.id)
        val savedDuration = resumeStore.duration(file.id)
        if (ResumeStore.shouldOffer(saved, savedDuration)) {
            savedPosition = saved
            askResume = true
        } else {
            resumeDecision = false
        }
    }

    // Build the media item once the resume question is settled.
    LaunchedEffect(file.id, resumeDecision) {
        val startFrom = resumeDecision ?: return@LaunchedEffect

        val subtitleConfigs = file.subtitles.map { subtitle ->
            MediaItem.SubtitleConfiguration.Builder(
                android.net.Uri.parse(api.subtitleUrl(file.id, subtitle.id))
            )
                .setMimeType(mimeTypeFor(subtitle.ext))
                .setLanguage(subtitle.lang.takeIf { it != "und" })
                .setLabel(subtitle.label)
                // SELECTION_FLAG_DEFAULT would force subtitles on; leave them off
                // until the user asks, matching how most players behave.
                .build()
        }

        val item = MediaItem.Builder()
            .setUri(api.streamUrl(file.id))
            .setMediaId(file.id)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

        player.setMediaItem(item, if (startFrom) savedPosition else 0L)
        player.prepare()
        player.playWhenReady = true
        // Subtitles stay off until chosen from the selector.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    // Mirror player state into Compose.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY && duration <= 0) {
                    duration = player.duration.coerceAtLeast(0L)
                }
                if (state == Player.STATE_ENDED) {
                    // Finished: drop the resume entry rather than parking at the end.
                    scope.launch { resumeStore.clear(file.id) }
                    onExit()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                // A black screen tells the user nothing; name the actual failure.
                error = describe(e)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Poll position for the scrubber, and persist periodically so a hard power-off
    // still leaves a usable resume point.
    LaunchedEffect(player) {
        var sinceSave = 0L
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration
            if (d > 0) duration = d

            sinceSave += 500
            if (sinceSave >= SAVE_INTERVAL_MS && duration > 0) {
                sinceSave = 0
                resumeStore.save(file.id, position, duration)
            }
            delay(500)
        }
    }

    // Auto-hide the control bar after a period with no button presses. The subtitle
    // list is exempt: navigating it moves focus rather than calling touch(), so a
    // timer would yank it away mid-choice. BACK closes that one.
    LaunchedEffect(overlay, lastInteraction) {
        if (overlay != Overlay.Controls) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        overlay = Overlay.None
    }

    LaunchedEffect(seekFeedback) {
        if (seekFeedback == null) return@LaunchedEffect
        delay(900)
        seekFeedback = null
    }

    // Pause and save when the system backgrounds us (Home button, input switch).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    player.pause()
                    val p = player.currentPosition
                    val d = player.duration
                    if (d > 0) scope.launch { resumeStore.save(file.id, p, d) }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun touch() {
        lastInteraction = System.currentTimeMillis()
    }

    fun saveAndExit() {
        val p = player.currentPosition
        val d = player.duration
        scope.launch {
            if (d > 0) resumeStore.save(file.id, p, d)
            onExit()
        }
    }

    BackHandler {
        when (overlay) {
            Overlay.Subtitles -> { overlay = Overlay.Controls; touch() }
            Overlay.Controls -> overlay = Overlay.None
            Overlay.None -> saveAndExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

                // Whenever an overlay owns focus, decline every key. This root Box
                // is focusable and sits above those overlays in the bubble path, so
                // consuming arrows here would stop the focus system from ever moving
                // between the overlay's buttons — leaving them unreachable.
                if (askResume || error != null || overlay == Overlay.Subtitles) {
                    return@onKeyEvent false
                }

                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        if (player.isPlaying) player.pause() else player.play()
                        overlay = Overlay.Controls
                        touch()
                        true
                    }

                    Key.MediaPlay -> { player.play(); overlay = Overlay.Controls; touch(); true }
                    Key.MediaPause -> { player.pause(); overlay = Overlay.Controls; touch(); true }

                    Key.DirectionRight, Key.MediaFastForward, Key.MediaSkipForward -> {
                        val target = (player.currentPosition + SEEK_STEP_MS)
                            .coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)
                        player.seekTo(target)
                        position = target
                        seekFeedback = "+${SEEK_STEP_MS / 1000}s"
                        overlay = Overlay.Controls
                        touch()
                        true
                    }

                    Key.DirectionLeft, Key.MediaRewind, Key.MediaSkipBackward -> {
                        val target = (player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L)
                        player.seekTo(target)
                        position = target
                        seekFeedback = "-${SEEK_STEP_MS / 1000}s"
                        overlay = Overlay.Controls
                        touch()
                        true
                    }

                    Key.DirectionUp -> { overlay = Overlay.Controls; touch(); true }

                    Key.DirectionDown -> {
                        // Only worth opening if the file actually has subtitles.
                        overlay = if (file.subtitles.isEmpty()) Overlay.Controls else Overlay.Subtitles
                        touch()
                        true
                    }

                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // the overlay below replaces it
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            Text(
                "Loading…",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        seekFeedback?.let { label ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Text(label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        error?.let { message ->
            ErrorOverlay(message = message, onExit = onExit)
        }

        if (askResume) {
            ResumeDialog(
                position = savedPosition,
                onResume = { askResume = false; resumeDecision = true },
                onStartOver = {
                    askResume = false
                    resumeDecision = false
                    scope.launch { resumeStore.clear(file.id) }
                },
            )
        }

        if (overlay == Overlay.Controls && error == null && !askResume) {
            ControlBar(
                title = file.name,
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                hasSubtitles = file.subtitles.isNotEmpty(),
                activeSubtitleLabel = activeSubtitle,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (overlay == Overlay.Subtitles && error == null) {
            SubtitleSelector(
                subtitles = file.subtitles,
                active = activeSubtitle,
                onSelect = { subtitle ->
                    activeSubtitle = subtitle?.label
                    applySubtitle(player, subtitle?.lang, subtitle?.label, enable = subtitle != null)
                    overlay = Overlay.Controls
                    touch()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Turn text tracks on/off and pick one by language+label.
 *
 * Media3 exposes sideloaded subtitles as normal text tracks, so selection goes
 * through TrackSelectionParameters rather than any subtitle-specific API.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun applySubtitle(player: ExoPlayer, language: String?, label: String?, enable: Boolean) {
    if (!enable) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        return
    }

    val textGroup = player.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .firstOrNull { group ->
            (0 until group.length).any { i ->
                val format = group.getTrackFormat(i)
                (label != null && format.label == label) ||
                    (language != null && format.language == language)
            }
        }

    val builder = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)

    if (textGroup != null) {
        val index = (0 until textGroup.length).firstOrNull { i ->
            val format = textGroup.getTrackFormat(i)
            (label != null && format.label == label) || (language != null && format.language == language)
        } ?: 0
        builder.setOverrideForType(TrackSelectionOverride(textGroup.mediaTrackGroup, index))
    } else if (language != null) {
        // Tracks may not be published yet; a language preference still applies.
        builder.setPreferredTextLanguage(language)
    }

    player.trackSelectionParameters = builder.build()
}

private fun mimeTypeFor(ext: String): String = when (ext.lowercase()) {
    ".vtt" -> MimeTypes.TEXT_VTT
    ".ssa", ".ass" -> MimeTypes.TEXT_SSA
    else -> MimeTypes.APPLICATION_SUBRIP
}

/** Plain-language reason for a playback failure, since transcoding is not offered. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun describe(e: PlaybackException): String = when (e.errorCode) {
    // DECODING_FAILED covers the common "decoder exists but this profile
    // exceeds its capabilities" case (e.g. HEVC Main 10 on a decoder that
    // only does 8-bit), so name the actual codec rather than guessing.
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    -> "This TV cannot decode ${codecLabel(e)}. Since the server streams files " +
        "as-is without converting them, this one will not play here."

    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "Lost connection to the computer. Check that Kino Server is still running."

    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file is no longer on the computer."

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    -> "This file's container format is not supported."

    else -> e.message ?: "Playback failed (${e.errorCodeName})."
}

/**
 * Human-readable codec name for the track that failed, e.g. "10-bit HEVC video".
 * Falls back to a vague phrase when the format is unavailable.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun codecLabel(e: PlaybackException): String {
    val format = (e as? ExoPlaybackException)?.rendererFormat ?: return "this file's format"
    val codecs = format.codecs.orEmpty()

    val name = when (format.sampleMimeType) {
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_VC1 -> "VC-1"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        else -> format.sampleMimeType?.substringAfter('/') ?: "this file's"
    }

    // HEVC profile 2 (hvc1.2 / hev1.2) is Main 10 — by far the most common
    // reason a file that plays on a computer refuses to play on a TV.
    val depth = if (codecs.startsWith("hvc1.2") || codecs.startsWith("hev1.2")) "10-bit " else ""
    val kind = if (MimeTypes.isAudio(format.sampleMimeType)) " audio" else " video"

    return "$depth$name$kind"
}

@Composable
private fun ErrorOverlay(message: String, onExit: () -> Unit) {
    // Without this the only way off the error screen is the remote's BACK button:
    // nothing on the overlay ever takes focus, so OK does nothing.
    val exitFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { exitFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(0.6f),
        ) {
            Text("Can't play this video", color = Danger, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(24.dp))
            TvButton(onClick = onExit, modifier = Modifier.focusRequester(exitFocus)) {
                Text("Back to library")
            }
        }
    }
}

@Composable
private fun ResumeDialog(position: Long, onResume: () -> Unit, onStartOver: () -> Unit) {
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { resumeFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Resume playback?", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("You stopped at ${formatTime(position)}", color = TextDim, fontSize = 15.sp)
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(onClick = onResume, modifier = Modifier.focusRequester(resumeFocus)) {
                    Text("Resume")
                }
                TvButton(onClick = onStartOver) { Text("Start over") }
            }
        }
    }
}

@Composable
private fun ControlBar(
    title: String,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    hasSubtitles: Boolean,
    activeSubtitleLabel: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 40.dp, vertical = 22.dp)
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(12.dp))

        // Progress bar. Not focusable: seeking is left/right on the D-pad, which
        // works whether or not the bar is visible.
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(3.dp))
        ) {
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .background(Accent, RoundedCornerShape(3.dp))
            )
        }

        Spacer(Modifier.height(9.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatTime(position)} / ${formatTime(duration)}",
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(18.dp))
            Text(
                text = if (isPlaying) "Playing" else "Paused",
                color = TextDim,
                fontSize = 13.sp,
            )

            Spacer(Modifier.weight(1f))

            // Spell out the remote mapping — a TV has no discoverable UI affordances.
            Text(
                text = buildString {
                    append("OK play/pause   ◀ ▶ 10s")
                    if (hasSubtitles) {
                        append("   ▼ subtitles")
                        activeSubtitleLabel?.let { append(" ($it)") }
                    }
                    append("   BACK exit")
                },
                color = TextDim,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SubtitleSelector(
    subtitles: List<com.vpsin.kino.data.Subtitle>,
    active: String?,
    onSelect: (com.vpsin.kino.data.Subtitle?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(horizontal = 40.dp, vertical = 22.dp)
    ) {
        Text("Subtitles", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(
                onClick = { onSelect(null) },
                modifier = Modifier.focusRequester(firstFocus),
            ) {
                Text(if (active == null) "Off  ✓" else "Off")
            }

            subtitles.forEach { subtitle ->
                TvButton(onClick = { onSelect(subtitle) }) {
                    val name = if (subtitle.lang == "und") subtitle.label else "${subtitle.label} (${subtitle.lang})"
                    Text(if (active == subtitle.label) "$name  ✓" else name)
                }
            }
        }
    }
}
