package com.huanchengfly.tieba.post.ui.page.forum.threadlist

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.huanchengfly.tieba.post.BuildConfig
import com.huanchengfly.tieba.post.arch.GlobalEvent
import com.huanchengfly.tieba.post.arch.onGlobalEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Debug-only, bounded local traces. No titles, account credentials or response bodies are recorded. */
internal object ForumScrollDiagnostics {
    private const val TAG = "ForumScrollTrace"
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024L
    private val sessions = AtomicLong()
    private data class Entry(val directory: File, val time: Long, val message: String)
    private val entries = Channel<Entry>(256, BufferOverflow.DROP_OLDEST)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
            for (entry in entries) {
                runCatching {
                    entry.directory.mkdirs()
                    val file = File(entry.directory, "forum-scroll.log")
                    if (file.length() >= MAX_FILE_BYTES) {
                        val previous = File(entry.directory, "forum-scroll.previous.log")
                        previous.delete()
                        if (!file.renameTo(previous)) file.writeText("")
                    }
                    file.appendText("${dateFormat.format(Date(entry.time))} ${entry.message}\n")
                }.onFailure { Log.w(TAG, "Unable to write scroll diagnostic log", it) }
            }
        }
    }

    fun nextSession(): Long = sessions.incrementAndGet()

    fun record(context: Context, message: String) {
        if (!BuildConfig.DEBUG) return
        val line = "v=${BuildConfig.VERSION_NAME} pid=${Process.myPid()} " +
                "elapsed=${SystemClock.elapsedRealtime()} $message"
        Log.i(TAG, line)
        entries.trySend(
            Entry(File(context.applicationContext.filesDir, "diagnostics"), System.currentTimeMillis(), line)
        )
    }
}

private data class ScrollObservation(
    val index: Int,
    val key: Any?,
    val layoutCount: Int,
    val scrolling: Boolean,
    val dataCount: Int,
    val hasRule: Boolean,
    val page: Int,
    val refreshing: Boolean,
    val loadingMore: Boolean,
)

@Composable
internal fun rememberForumScrollTrace(
    forumId: Long,
    isGood: Boolean,
    viewModel: ForumThreadListViewModel,
    listState: LazyListState,
    uiState: ForumThreadListUiState,
): (String) -> Unit {
    if (!BuildConfig.DEBUG) return {}

    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestUiState = rememberUpdatedState(uiState)
    val viewModelId = System.identityHashCode(viewModel)
    val session = remember(forumId, isGood, viewModelId, listState) {
        ForumScrollDiagnostics.nextSession()
    }
    val restoredPosition = remember(session) {
        "entryIndex=${listState.firstVisibleItemIndex} entryOffset=${listState.firstVisibleItemScrollOffset}"
    }
    val trace = remember(session) {
        { event: String ->
            val state = latestUiState.value
            ForumScrollDiagnostics.record(
                context,
                "session=$session forum=$forumId tab=${if (isGood) "good" else "latest"} " +
                        "vm=$viewModelId list=${System.identityHashCode(listState)} event=$event " +
                        "index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset} " +
                        "key=${listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key} " +
                        "layout=${listState.layoutInfo.totalItemsCount} data=${state.threadList.size} " +
                        "vmData=${viewModel.uiState.value.threadList.size} rule=${!state.forumRuleTitle.isNullOrEmpty()} " +
                        "page=${state.currentPage} scrolling=${listState.isScrollInProgress} " +
                        "refreshing=${state.isRefreshing} loadingMore=${state.isLoadingMore}"
            )
        }
    }

    DisposableEffect(session, lifecycleOwner) {
        trace("attach $restoredPosition lifecycle=${lifecycleOwner.lifecycle.currentState}")
        val observer = LifecycleEventObserver { _, event -> trace("lifecycle_$event") }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            trace("detach")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    onGlobalEvent<GlobalEvent.ScrollToTop> { trace("back_to_top source=global_event") }

    LaunchedEffect(session) {
        var previous: ScrollObservation? = null
        snapshotFlow {
            val state = latestUiState.value
            ScrollObservation(
                listState.firstVisibleItemIndex,
                listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key,
                listState.layoutInfo.totalItemsCount,
                listState.isScrollInProgress,
                state.threadList.size,
                !state.forumRuleTitle.isNullOrEmpty(),
                state.currentPage,
                state.isRefreshing,
                state.isLoadingMore,
            )
        }.collect { current ->
            val before = previous
            if (before != null && before.index > 0 && current.index == 0) {
                trace("top_transition fromIndex=${before.index} fromKey=${before.key}")
            } else {
                trace("position")
            }
            previous = current
        }
    }
    return trace
}
