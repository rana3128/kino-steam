package com.vpsin.kino.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: androidx.datastore.core.DataStore<Preferences> by
    preferencesDataStore(name = "tv_client")

/** The last server the user chose, so the app reconnects without asking again. */
class ServerStore(private val context: Context) {

    private val hostKey = stringPreferencesKey("server_host")
    private val portKey = stringPreferencesKey("server_port")
    private val nameKey = stringPreferencesKey("server_name")

    val saved = context.dataStore.data.map { prefs ->
        val host = prefs[hostKey]
        val port = prefs[portKey]?.toIntOrNull()
        if (host.isNullOrBlank() || port == null) null
        else ServerRef(name = prefs[nameKey] ?: host, host = host, port = port)
    }

    suspend fun save(server: ServerRef) {
        context.dataStore.edit { prefs ->
            prefs[hostKey] = server.host
            prefs[portKey] = server.port.toString()
            prefs[nameKey] = server.name
        }
    }

    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(hostKey)
            prefs.remove(portKey)
            prefs.remove(nameKey)
        }
    }
}

/**
 * Playback positions, keyed by the server's opaque file id.
 *
 * Positions are only meaningful when they are neither at the very start nor
 * effectively at the end, so [shouldOffer] encodes that rule in one place.
 */
class ResumeStore(private val context: Context) {

    private fun key(fileId: String) = longPreferencesKey("resume_$fileId")
    private fun durationKey(fileId: String) = longPreferencesKey("duration_$fileId")

    suspend fun get(fileId: String): Long =
        context.dataStore.data.first()[key(fileId)] ?: 0L

    suspend fun duration(fileId: String): Long =
        context.dataStore.data.first()[durationKey(fileId)] ?: 0L

    /** Progress 0f..1f for the browse cards, or null when there is nothing saved. */
    fun progressFlow(fileId: String) = context.dataStore.data.map { prefs ->
        val position = prefs[key(fileId)] ?: return@map null
        val total = prefs[durationKey(fileId)] ?: return@map null
        if (total <= 0) null else (position.toFloat() / total).coerceIn(0f, 1f)
    }

    val allProgress = context.dataStore.data.map { prefs ->
        prefs.asMap().keys
            .map { it.name }
            .filter { it.startsWith("resume_") }
            .mapNotNull { name ->
                val id = name.removePrefix("resume_")
                val position = prefs[longPreferencesKey(name)] ?: return@mapNotNull null
                val total = prefs[longPreferencesKey("duration_$id")] ?: return@mapNotNull null
                if (total <= 0) null else id to (position.toFloat() / total).coerceIn(0f, 1f)
            }
            .toMap()
    }

    suspend fun save(fileId: String, positionMs: Long, durationMs: Long) {
        // Near the start or near the end there is nothing worth resuming.
        if (!shouldOffer(positionMs, durationMs)) {
            clear(fileId)
            return
        }
        context.dataStore.edit { prefs ->
            prefs[key(fileId)] = positionMs
            prefs[durationKey(fileId)] = durationMs
        }
    }

    suspend fun clear(fileId: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(key(fileId))
            prefs.remove(durationKey(fileId))
        }
    }

    companion object {
        private const val MIN_POSITION_MS = 30_000L
        private const val END_MARGIN_MS = 60_000L

        /** More than 30s in, and not inside the final minute. */
        fun shouldOffer(positionMs: Long, durationMs: Long): Boolean {
            if (positionMs < MIN_POSITION_MS) return false
            if (durationMs <= 0) return true // duration unknown yet; keep it
            return positionMs < durationMs - END_MARGIN_MS
        }
    }
}
