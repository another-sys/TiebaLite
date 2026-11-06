package com.huanchengfly.tieba.post

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.huanchengfly.tieba.post.api.models.ForumHeatBean
import com.huanchengfly.tieba.post.ui.page.search.forum.ForumHeatLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForumHeatTest {
    @Test
    fun missingHeatIsNotConfusedWithZero() {
        val gson = Gson()
        assertNull(gson.fromJson("""{"forum":{}}""", ForumHeatBean::class.java).forum?.hotNum)
        assertEquals(0L, gson.fromJson("""{"forum":{"hot_num":0}}""", ForumHeatBean::class.java).forum?.hotNum)
        assertEquals(7231L, gson.fromJson("""{"forum":{"hot_num":"7231"}}""", ForumHeatBean::class.java).forum?.hotNum)
    }

    @Test
    fun returningToResultsUsesCacheAndExpiredValuesRefresh() = runBlocking {
        var time = 0L
        var calls = 0
        val loader = ForumHeatLoader(fetch = { calls++; 0L }, now = { time })
        assertEquals(0L, loader.load("android"))
        assertEquals(0L, loader.load("android"))
        assertEquals(1, calls)
        time = 300_001
        loader.load("android")
        assertEquals(2, calls)
    }

    @Test
    fun failuresAreBrieflyCachedAndCancellationDoesNotPoisonCache() = runBlocking {
        var time = 0L
        var calls = 0
        val loader = ForumHeatLoader(fetch = { calls++; error("offline") }, now = { time })
        assertNull(loader.load("android"))
        assertNull(loader.load("android"))
        assertEquals(1, calls)
        time = 15_001
        assertNull(loader.load("android"))
        assertEquals(2, calls)

        val entered = CompletableDeferred<Unit>()
        var cancelFirst = true
        val cancellable = ForumHeatLoader(fetch = {
            if (cancelFirst) {
                entered.complete(Unit)
                delay(Long.MAX_VALUE)
            }
            42L
        })
        val job = launch { cancellable.load("android") }
        entered.await()
        job.cancelAndJoin()
        cancelFirst = false
        assertEquals(42L, cancellable.load("android"))
    }

    @Test
    fun scrollingCannotStartUnlimitedConcurrentRequests() = runBlocking {
        var active = 0
        var peak = 0
        val loader = ForumHeatLoader(fetch = {
            active++
            peak = maxOf(peak, active)
            delay(20)
            active--
            100L
        })
        (1..10).map { async { loader.load("forum$it") } }.awaitAll()
        assertEquals(3, peak)
        assertEquals(0, active)
    }
}
