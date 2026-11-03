package com.music.bitchord.data.home

import android.content.Context
import com.music.bitchord.data.model.HomeShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CachedHomeFeed(
    val shelves: List<HomeShelf>,
    val continuation: String?,
    val timestamp: Long = System.currentTimeMillis(),
)

object HomeFeedCache {
    private val memoryCache = ConcurrentHashMap<String, CachedHomeFeed>()
    private var baseDir: File? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun init(context: Context) {
        if (baseDir == null) {
            baseDir = File(context.applicationContext.filesDir, "home_cache").apply { mkdirs() }
        }
    }

    private fun cacheFile(identity: String?): File? {
        val dir = baseDir ?: return null
        val safeKey = (identity ?: "guest").replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(dir, "home_feed_$safeKey.json")
    }

    fun getMemory(identity: String?): CachedHomeFeed? = memoryCache[identity ?: "guest"]

    fun get(identity: String?): CachedHomeFeed? {
        val key = identity ?: "guest"
        memoryCache[key]?.let { return it }

        val file = cacheFile(identity) ?: return null
        if (!file.exists()) return null

        return runCatching {
            val text = file.readText()
            val parsed = json.decodeFromString<CachedHomeFeed>(text)
            if (parsed.shelves.isNotEmpty()) {
                memoryCache[key] = parsed
                parsed
            } else null
        }.getOrNull()
    }

    suspend fun put(identity: String?, shelves: List<HomeShelf>, continuation: String?) {
        if (shelves.isEmpty()) return
        val key = identity ?: "guest"
        val entry = CachedHomeFeed(shelves = shelves, continuation = continuation)
        memoryCache[key] = entry

        val file = cacheFile(identity) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val tempFile = File(file.parentFile, "${file.name}.tmp")
                tempFile.writeText(json.encodeToString(CachedHomeFeed.serializer(), entry))
                if (tempFile.exists()) {
                    tempFile.renameTo(file)
                }
            }
        }
    }

    fun clear(identity: String? = null) {
        if (identity == null) {
            memoryCache.clear()
            baseDir?.listFiles()?.forEach { it.delete() }
        } else {
            val key = identity.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            memoryCache.remove(key)
            cacheFile(identity)?.delete()
        }
    }
}
