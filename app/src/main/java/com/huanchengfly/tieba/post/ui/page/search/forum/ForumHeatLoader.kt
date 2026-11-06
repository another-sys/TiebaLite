package com.huanchengfly.tieba.post.ui.page.search.forum

import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Shared by the visible search rows; failures must never hide the search results. */
internal class ForumHeatLoader(
    private val fetch: suspend (String) -> Long?,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class Entry(val value: Long?, val expiresAt: Long)

    private val cache = LruCache<String, Entry>(64)
    private val requests = Semaphore(3)

    suspend fun load(forumName: String): Long? {
        if (forumName.isBlank()) return null
        cache.get(forumName)?.takeIf { it.expiresAt > now() }?.let { return it.value }
        return requests.withPermit {
            cache.get(forumName)?.takeIf { it.expiresAt > now() }?.let { return@withPermit it.value }
            val value = try {
                withTimeoutOrNull(8_000) { fetch(forumName) }?.takeIf { it >= 0 }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            // Briefly cache failures to avoid retry storms while scrolling.
            val ttl = if (value != null) 300_000L else 15_000L
            cache.put(forumName, Entry(value, now() + ttl))
            value
        }
    }
}
