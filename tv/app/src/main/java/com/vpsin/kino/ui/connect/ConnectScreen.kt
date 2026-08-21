package com.vpsin.kino.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpsin.kino.ui.common.TvButton
import com.vpsin.kino.ui.common.pointerClick
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vpsin.kino.data.ApiClient
import com.vpsin.kino.data.Discovery
import com.vpsin.kino.data.ServerRef
import com.vpsin.kino.data.ServerStore
import com.vpsin.kino.ui.theme.Accent
import com.vpsin.kino.ui.theme.Danger
import com.vpsin.kino.ui.theme.Line
import com.vpsin.kino.ui.theme.Surface1
import com.vpsin.kino.ui.theme.Surface2
import com.vpsin.kino.ui.theme.TextDim
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(onConnected: (ServerRef) -> Unit) {
    val context = LocalContext.current
    val store = remember { ServerStore(context) }
    val scope = rememberCoroutineScope()

    val found = remember { mutableStateListOf<ServerRef>() }
    var searching by remember { mutableStateOf(true) }
    var manual by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var searchAttempt by remember { mutableStateOf(0) }

    // Restarting on searchAttempt cancels the previous discovery flow, which
    // closes its socket — important, since the port can only be bound once.
    LaunchedEffect(searchAttempt) {
        found.clear()
        searching = true
        Discovery.discover().collect { server ->
            if (found.none { it.label == server.label }) found.add(server)
            searching = false // keep listening, but stop showing the spinner
        }
    }

    fun connect(server: ServerRef) {
        if (connecting) return
        connecting = true
        error = null
        scope.launch {
            try {
                // Confirm it really is our server before committing to it.
                val info = ApiClient.probe(server.host, server.port)
                val resolved = server.copy(name = info.name.ifBlank { server.name })
                store.save(resolved)
                onConnected(resolved)
            } catch (e: Exception) {
                error = "Could not reach ${server.label}. ${e.message ?: "Check the address and that Kino Server is running."}"
            } finally {
                connecting = false
            }
        }
    }

    Box(Modifier.fillMaxSize().padding(48.dp)) {
        Column(Modifier.fillMaxWidth(0.62f)) {
            Text(
                text = "Find your computer",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (manual) {
                    "Enter the address shown in Kino Server on your computer."
                } else {
                    "Make sure Kino Server is running on your computer and both devices are on the same Wi-Fi."
                },
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(24.dp))

            error?.let { message ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Danger.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .border(1.dp, Danger.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(14.dp)
                ) {
                    Text(message, color = Danger, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
            }

            if (manual) {
                ManualEntry(
                    enabled = !connecting,
                    onSubmit = { host, port -> connect(ServerRef(host, host, port)) },
                    onCancel = { manual = false; error = null },
                )
            } else {
                DiscoveredList(
                    servers = found,
                    searching = searching,
                    enabled = !connecting,
                    onPick = ::connect,
                    onManual = { manual = true; error = null },
                    onRetry = { searchAttempt++ },
                )
            }
        }
    }
}

@Composable
private fun DiscoveredList(
    servers: List<ServerRef>,
    searching: Boolean,
    enabled: Boolean,
    onPick: (ServerRef) -> Unit,
    onManual: () -> Unit,
    onRetry: () -> Unit,
) {
    val firstItem = remember { FocusRequester() }

    // Move focus onto the first server the moment one appears, so the remote's
    // OK button does the obvious thing without any D-pad travel.
    LaunchedEffect(servers.firstOrNull()?.label) {
        if (servers.isNotEmpty()) runCatching { firstItem.requestFocus() }
    }

    if (servers.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Surface1, RoundedCornerShape(10.dp))
                .padding(20.dp)
        ) {
            Text(
                text = if (searching) "Searching your network…" else "No servers found yet — still listening.",
                color = TextDim,
            )
        }
    } else {
        LazyColumn(
            Modifier.fillMaxWidth().height(260.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(servers, key = { it.label }) { server ->
                val isFirst = server.label == servers.first().label
                ListItem(
                    selected = false,
                    onClick = { onPick(server) },
                    enabled = enabled,
                    leadingContent = {
                        Icon(Icons.Filled.Computer, contentDescription = null)
                    },
                    headlineContent = {
                        Text(server.name, fontWeight = FontWeight.Medium)
                    },
                    supportingContent = {
                        Text(server.label, color = TextDim, fontSize = 13.sp)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerClick(enabled) { onPick(server) }
                        .then(if (isFirst) Modifier.focusRequester(firstItem) else Modifier),
                )
            }
        }
    }

    Spacer(Modifier.height(20.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton(onClick = onRetry, enabled = enabled) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Search again")
        }
        TvButton(onClick = onManual, enabled = enabled) {
            Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Enter address manually")
        }
    }
}

@Composable
private fun ManualEntry(
    enabled: Boolean,
    onSubmit: (host: String, port: Int) -> Unit,
    onCancel: () -> Unit,
) {
    var host by remember { mutableStateOf("192.168.1.") }
    var port by remember { mutableStateOf("8080") }
    val hostFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { hostFocus.requestFocus() } }

    val submit = {
        val p = port.trim().toIntOrNull()
        if (host.isNotBlank() && p != null && p in 1..65535) onSubmit(host.trim(), p)
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(
                label = "Address",
                value = host,
                onValueChange = { host = it.filter { c -> c.isDigit() || c == '.' } },
                keyboardType = KeyboardType.Decimal,
                onDone = submit,
                modifier = Modifier.width(240.dp).focusRequester(hostFocus),
            )
            Field(
                label = "Port",
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                keyboardType = KeyboardType.Number,
                onDone = submit,
                modifier = Modifier.width(120.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(onClick = submit, enabled = enabled) {
                Text(if (enabled) "Connect" else "Connecting…")
            }
            TvButton(onClick = onCancel, enabled = enabled) {
                Text("Back to search")
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }

    Column(modifier) {
        Text(label, color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface2, RoundedCornerShape(8.dp))
                // On a TV the focus ring is the only cursor the user has.
                .border(2.dp, if (focused) Accent else Line, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .onFocusChanged { focused = it.isFocused }
                .focusable(),
        )
    }
}
