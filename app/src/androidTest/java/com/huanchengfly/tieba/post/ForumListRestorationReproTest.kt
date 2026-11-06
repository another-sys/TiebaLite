package com.huanchengfly.tieba.post

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.ui.common.theme.compose.TiebaLiteTheme
import com.huanchengfly.tieba.post.ui.models.ThreadItemData
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.forum.threadlist.ForumThreadListPage
import com.huanchengfly.tieba.post.ui.page.forum.threadlist.ForumThreadListPartialChange
import com.huanchengfly.tieba.post.ui.page.forum.threadlist.ForumThreadListUiIntent
import com.huanchengfly.tieba.post.ui.page.forum.threadlist.ForumThreadListUiState
import com.huanchengfly.tieba.post.ui.page.forum.threadlist.ForumThreadListViewModel
import com.huanchengfly.tieba.post.ui.widgets.compose.LocalSnackbarHostState
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.ramcosta.composedestinations.utils.toDestinationsNavigator
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Characterizes a possible return-navigation race without changing production timing.
 * Only the test blocks the item projection, forcing the header to arrive first.
 * Passing the reproduction test proves the mechanism, not its occurrence in normal use.
 */
@RunWith(AndroidJUnit4::class)
class ForumListRestorationReproTest {
    @get:Rule
    val compose = createComposeRule()

    private val itemGate = CountDownLatch(1)
    private val viewModels = ViewModelStore()
    private lateinit var listState: LazyListState

    private class Snapshot(
        val ruleTitle: String?,
        private val itemGate: CountDownLatch,
    ) : UiState {
        val itemIds: List<Int>
            get() {
                check(itemGate.await(20, TimeUnit.SECONDS)) { "Test item gate timed out" }
                return (0 until 80).toList()
            }
    }

    @After
    fun releaseItemProjection() {
        itemGate.countDown()
        viewModels.clear()
    }

    private fun showIndependentProjections(ruleTitle: String?) {
        val source = MutableStateFlow(Snapshot(ruleTitle, itemGate))
        compose.setContent {
            val title by source.collectPartialAsState(Snapshot::ruleTitle, initial = null)
            val items by source.collectPartialAsState(Snapshot::itemIds, initial = emptyList())
            listState = rememberLazyListState(30, 12)
            MyLazyColumn(state = listState, modifier = Modifier.size(300.dp, 480.dp)) {
                if (title != null) {
                    item(key = "ForumRule") {
                        Box(Modifier.fillMaxWidth().height(48.dp))
                    }
                }
                itemsIndexed(items, key = { index, id -> "${index}_$id" }) { _, _ ->
                    Box(Modifier.fillMaxWidth().height(48.dp))
                }
            }
        }
    }

    @Test
    fun headerBeforeItems_reproducesPositionReset() {
        showIndependentProjections("Forum rule")
        compose.waitUntil(5_000) { listState.layoutInfo.totalItemsCount == 1 }
        compose.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            itemGate.countDown()
        }
        compose.waitUntil(5_000) { listState.layoutInfo.totalItemsCount == 81 }
        compose.runOnIdle {
            Log.i("ForumRestoreRepro", "Header-first: restored index 30 became ${listState.firstVisibleItemIndex}")
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun emptyListBeforeItems_preservesRestoredPosition() {
        showIndependentProjections(null)
        compose.runOnIdle {
            assertEquals(0, listState.layoutInfo.totalItemsCount)
            assertEquals(30, listState.firstVisibleItemIndex)
            itemGate.countDown()
        }
        compose.waitUntil(5_000) { listState.layoutInfo.totalItemsCount == 80 }
        compose.runOnIdle {
            Log.i("ForumRestoreRepro", "No header: restored index ${listState.firstVisibleItemIndex}, offset ${listState.firstVisibleItemScrollOffset}")
            assertEquals(30, listState.firstVisibleItemIndex)
            assertEquals(12, listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun coherentSnapshot_preservesRestoredPositionWithHeader() {
        itemGate.countDown()
        val source = MutableStateFlow(Snapshot("Forum rule", itemGate))
        compose.setContent {
            val snapshot by source.collectAsState()
            val items = snapshot.itemIds
            listState = rememberLazyListState(30, 12)
            MyLazyColumn(state = listState, modifier = Modifier.size(300.dp, 480.dp)) {
                if (snapshot.ruleTitle != null) {
                    item(key = "ForumRule") {
                        Box(Modifier.fillMaxWidth().height(48.dp))
                    }
                }
                itemsIndexed(items, key = { index, id -> "${index}_$id" }) { _, _ ->
                    Box(Modifier.fillMaxWidth().height(48.dp))
                }
            }
        }
        compose.waitUntil(5_000) { listState.layoutInfo.totalItemsCount == 81 }
        compose.runOnIdle {
            Log.i("ForumRestoreRepro", "Coherent snapshot: restored index ${listState.firstVisibleItemIndex}, offset ${listState.firstVisibleItemScrollOffset}")
            assertEquals(30, listState.firstVisibleItemIndex)
            assertEquals(12, listState.firstVisibleItemScrollOffset)
        }
    }

    private class CachedForumViewModel : ForumThreadListViewModel() {
        override fun createInitialState() = ForumThreadListUiState(
            forumRuleTitle = "Forum rule",
            threadList = (1L..80L).map { id ->
                ThreadItemData(
                    ThreadInfo(id = id, title = "Thread $id", isTop = 1).wrapImmutable(),
                    blocked = false,
                    hidden = false,
                )
            }.toImmutableList(),
            currentPage = 3,
            hasMore = false,
        )

        override fun createPartialChangeProducer() =
            object : PartialChangeProducer<ForumThreadListUiIntent, ForumThreadListPartialChange, ForumThreadListUiState> {
                override fun toPartialChangeFlow(intentFlow: Flow<ForumThreadListUiIntent>): Flow<ForumThreadListPartialChange> =
                    emptyFlow()
            }
    }

    @Test
    fun productionPage_reenteringWithCachedData_preservesPosition() {
        val viewModel = CachedForumViewModel().apply { initialized = true }
        viewModels.put("cached-forum", viewModel)
        var showForum by mutableStateOf(true)
        compose.setContent {
            val stateHolder = rememberSaveableStateHolder()
            val navigator = rememberNavController().toDestinationsNavigator()
            val snackbar = remember { SnackbarHostState() }
            TiebaLiteTheme {
                CompositionLocalProvider(
                    LocalNavigator provides navigator,
                    LocalSnackbarHostState provides snackbar,
                ) {
                    if (showForum) {
                        stateHolder.SaveableStateProvider("forum") {
                            listState = rememberLazyListState(30, 12)
                            Box(Modifier.size(300.dp, 480.dp)) {
                                ForumThreadListPage(
                                    forumId = 1L,
                                    forumName = "Restoration test",
                                    viewModel = viewModel,
                                    lazyListState = listState,
                                )
                            }
                        }
                    }
                }
            }
        }
        repeat(4) { attempt ->
            compose.waitUntil(5_000) { listState.layoutInfo.totalItemsCount == 81 }
            compose.runOnIdle {
                assertEquals("Return $attempt", 30, listState.firstVisibleItemIndex)
                assertEquals("Return $attempt", 12, listState.firstVisibleItemScrollOffset)
            }
            if (attempt < 3) {
                val previousState = listState
                compose.runOnIdle { showForum = false }
                compose.waitForIdle()
                compose.runOnIdle { showForum = true }
                compose.waitUntil(5_000) { listState !== previousState }
            }
        }
        Log.i("ForumRestoreRepro", "Production page: initial entry and 3 restored entries kept index 30, offset 12")
    }
}
