package com.vpsin.kino.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import com.vpsin.kino.ui.common.TvButton
import com.vpsin.kino.ui.common.pointerClick
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vpsin.kino.data.ApiClient
import com.vpsin.kino.data.BrowseResult
import com.vpsin.kino.data.Entry
import com.vpsin.kino.data.ResumeStore
import com.vpsin.kino.data.ServerRef
import com.vpsin.kino.data.formatSize
import com.vpsin.kino.ui.theme.Accent
import com.vpsin.kino.ui.theme.Danger
import com.vpsin.kino.ui.theme.Line
import com.vpsin.kino.ui.theme.Surface1
import com.vpsin.kino.ui.theme.Surface2
import com.vpsin.kino.ui.theme.TextDim

@Composable
fun BrowseScreen(
    server: ServerRef,
    api: ApiClient,
    onPlay: (Entry) -> Unit,
    onChangeServer: () -> Unit,
) {
    val context = LocalContext.current
    val resumeStore = remember { ResumeStore(context) }
    val progress by resumeStore.allProgress.collectAsState(initial = emptyMap())

    // Explicit stack rather than the breadcrumb from the server: BACK must return
    // to exactly the folder the user came from, including scroll intent.
    var stack by remember { mutableStateOf(listOf<String?>(null)) }
    var result by remember { mutableStateOf<BrowseResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }

    val currentId = stack.last()

    LaunchedEffect(currentId, reloadToken) {
        loading = true
        error = null
        try {
            result = api.browse(currentId)
        } catch (e: Exception) {
            error = e.message ?: "Could not reach ${server.label}"
        } finally {
            loading = false
        }
    }

    // BACK walks up the folder tree; only at the root does it fall through and
    // let the system close the app.
    BackHandler(enabled = stack.size > 1) {
        stack = stack.dropLast(1)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Header(
            server = server,
            crumbs = result?.path.orEmpty().map { it.name },
            onChangeServer = onChangeServer,
            onRefresh = { reloadToken++ },
        )

        Spacer(Modifier.height(18.dp))

        when {
            error != null -> ErrorPanel(error!!, onRetry = { reloadToken++ })

            loading && result == null -> Text("Loading…", color = TextDim)

            result?.entries.isNullOrEmpty() -> Text(
                if (currentId == null) "No folders shared yet. Add one in Kino Server on your computer."
                else "No videos in this folder.",
                color = TextDim,
            )

            else -> Grid(
                entries = result!!.entries,
                progress = progress,
                onOpenFolder = { id -> stack = stack + id },
                onPlay = onPlay,
            )
        }
    }
}

@Composable
private fun Header(
    server: ServerRef,
    crumbs: List<String>,
    onChangeServer: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = crumbs.lastOrNull() ?: "Shared folders",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (crumbs.isEmpty()) server.name else (listOf(server.name) + crumbs).joinToString("  ›  "),
                color = TextDim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TvButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.width(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        TvButton(onClick = onChangeServer) { Text("Change server") }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Danger.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .border(1.dp, Danger.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text(message, color = Danger)
        }
        Spacer(Modifier.height(16.dp))
        TvButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun Grid(
    entries: List<Entry>,
    progress: Map<String, Float>,
    onOpenFolder: (String) -> Unit,
    onPlay: (Entry) -> Unit,
) {
    val state = rememberLazyGridState()
    val firstCard = remember { FocusRequester() }

    // Land focus on the first card whenever the folder changes, so OK works
    // immediately. Without this the remote's first press is wasted just
    // acquiring focus, and nothing on screen looks selected.
    LaunchedEffect(entries.firstOrNull()?.id) {
        if (entries.isNotEmpty()) runCatching { firstCard.requestFocus() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.id }) { entry ->
            EntryCard(
                entry = entry,
                progress = progress[entry.id],
                onClick = {
                    if (entry.isFolder) onOpenFolder(entry.id) else onPlay(entry)
                },
                modifier = if (entry.id == entries.first().id) {
                    Modifier.focusRequester(firstCard)
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun EntryCard(
    entry: Entry,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Surface1,
            focusedContainerColor = Surface2,
        ),
        // Scale plus a bright border: on a TV, focus state is the only cursor.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = Border(androidx.compose.foundation.BorderStroke(1.dp, Line)),
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Accent)),
        ),
        modifier = modifier.fillMaxWidth().pointerClick(onClick = onClick),
    ) {
        Column {
            // 16:9 art area. No thumbnails yet, so a large glyph carries the type.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(if (entry.isFolder) Surface2 else Color(0xFF11141B)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (entry.isFolder) Icons.Filled.Folder else Icons.Filled.Movie,
                    contentDescription = null,
                    tint = if (entry.isFolder) Accent.copy(alpha = 0.8f) else TextDim,
                    modifier = Modifier.width(44.dp),
                )

                if (entry.subtitles.isNotEmpty()) {
                    Icon(
                        Icons.Filled.ClosedCaption,
                        contentDescription = "Subtitles available",
                        tint = TextDim,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .width(18.dp),
                    )
                }

                // Resume indicator, so a part-watched file is obvious at a glance.
                if (progress != null && progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.55f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(4.dp)
                                .background(Accent)
                        )
                    }
                }
            }

            Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
                Text(
                    text = entry.name,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isFolder && entry.size > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text(formatSize(entry.size), color = TextDim, fontSize = 11.sp)
                }
            }
        }
    }
}
