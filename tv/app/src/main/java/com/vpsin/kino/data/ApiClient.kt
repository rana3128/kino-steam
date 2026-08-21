package com.vpsin.kino.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP client over HttpURLConnection. The API is three JSON endpoints and
 * some URL building, so a full HTTP stack would be more dependency than value —
 * ExoPlayer does the heavy streaming with its own data source.
 */
class ApiClient(private val baseUrl: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun info(timeoutMs: Int = 3000): ServerInfo =
        get("/api/info", timeoutMs).let { json.decodeFromString(it) }

    /** Pass null for the top level (the list of shared folders). */
    suspend fun browse(folderId: String?): BrowseResult {
        val path = if (folderId == null) "/api/browse"
        else "/api/browse?path=" + Uri.encode(folderId)
        return json.decodeFromString(get(path))
    }

    fun streamUrl(fileId: String): String = "$baseUrl/api/files/$fileId/stream"

    fun subtitleUrl(fileId: String, subtitleId: String): String =
        "$baseUrl/api/files/$fileId/subtitles/$subtitleId"

    private suspend fun get(path: String, timeoutMs: Int = 8000): String = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw ApiException("Server returned HTTP $code")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Validate a host:port the user typed, returning the server's own name. */
        suspend fun probe(host: String, port: Int): ServerInfo =
            ApiClient("http://$host:$port").info()
    }
}

class ApiException(message: String) : Exception(message)
