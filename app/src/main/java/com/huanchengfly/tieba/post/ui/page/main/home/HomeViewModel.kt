package com.huanchengfly.tieba.post.ui.page.main.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.models.database.TopForum
import com.huanchengfly.tieba.post.ui.page.main.home.SortType.*
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.HistoryUtil
import com.huanchengfly.tieba.post.utils.appPreferences
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import org.litepal.LitePal

// 排序类型枚举
enum class SortType(internal val apiParam: Int) {
    HOT_NUM(apiParam = 2),     // 热度值排序
    FOLLOW(apiParam = 1),      // 关注时间排序
    LEVEL(apiParam = 3);       // 等级排序

    // 为每个排序类型提供排序函数
    internal fun <T : HomeUiState.Forum> sort(list: List<T>): List<T> =
        when (this) {
            HOT_NUM -> list.sortedByDescending { it.hotNum }
            else -> list.toList() // 其他排序类型由API处理
        }
}

@Stable
class HomeViewModel : BaseViewModel<HomeUiIntent, HomePartialChange, HomeUiState, HomeUiEvent>() {
    // 获取应用偏好设置实例
    val context = App.INSTANCE
    private val appPreferences = context.appPreferences

    // 从偏好设置中读取保存的排序类型，如果没有则使用默认值
    private val savedSortType: SortType
        get() = try {
            val value = appPreferences.homePageSortType
            if (!value.isNullOrEmpty()) {
                SortType.valueOf(value)
            } else {
                LEVEL
            }
        } catch (e: Exception) {
            LEVEL
        }

    override fun createInitialState(): HomeUiState = HomeUiState(sortType = savedSortType)

    // 添加一个变量来存储当前排序类型
    private val currentSortTypeFlow = MutableStateFlow(savedSortType)


    // 更新排序类型的方法
    fun updateSortType(sortType: SortType) {
        currentSortTypeFlow.value = sortType
        // 将排序类型保存到偏好设置中
        appPreferences.homePageSortType = sortType.name
    }

    // 获取当前排序类型的方法
    fun getCurrentSortType(): SortType {
        return currentSortTypeFlow.value
    }

    override fun createPartialChangeProducer(): PartialChangeProducer<HomeUiIntent, HomePartialChange, HomeUiState> =
        HomePartialChangeProducer(this)

    override fun dispatchEvent(partialChange: HomePartialChange): UiEvent? =
        when (partialChange) {
            is HomePartialChange.TopForums.Delete.Failure -> CommonUiEvent.Toast(partialChange.errorMessage)
            is HomePartialChange.TopForums.Add.Failure -> CommonUiEvent.Toast(partialChange.errorMessage)
            else -> null
        }

    class HomePartialChangeProducer(private val viewModel: HomeViewModel) :
        PartialChangeProducer<HomeUiIntent, HomePartialChange, HomeUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<HomeUiIntent>): Flow<HomePartialChange> {
            return merge(
                intentFlow.filterIsInstance<HomeUiIntent.Refresh>()
                    .flatMapConcat { produceRefreshPartialChangeFlow() },
                intentFlow.filterIsInstance<HomeUiIntent.RefreshHistory>()
                    .flatMapConcat { produceRefreshHistoryPartialChangeFlow() },
                intentFlow.filterIsInstance<HomeUiIntent.TopForums.Delete>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<HomeUiIntent.TopForums.Add>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<HomeUiIntent.Unfollow>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<HomeUiIntent.ToggleHistory>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                // 排序相关的流处理
                intentFlow.filterIsInstance<HomeUiIntent.ChangeSortType>()
                    .flatMapConcat { changeSortIntent ->
                        // 更新ViewModel中的排序类型
                        viewModel.updateSortType(changeSortIntent.sortType)
                        // 合并排序类型变更、刷新开始和刷新操作
                        flowOf(
                            HomePartialChange.ChangeSortType(changeSortIntent.sortType),
                            HomePartialChange.Refresh.Start
                        ).mergeWith(produceRefreshPartialChangeFlow())
                    }
            )
        }

        // 扩展函数：合并两个Flow
        private fun <T> Flow<T>.mergeWith(other: Flow<T>): Flow<T> = merge(this, other)

        @Suppress("USELESS_CAST")
        private fun produceRefreshPartialChangeFlow(): Flow<HomePartialChange.Refresh> =
            HistoryUtil.getFlow(HistoryUtil.TYPE_FORUM, 0)
                .flatMapConcat { historyForums ->
                    // 获取当前排序类型
                    val currentSortType = viewModel.getCurrentSortType()

                    // 直接使用枚举中定义的API参数
                    TiebaApi.getInstance().forumGuideNewFlow(currentSortType.apiParam)
                        .map { forumRecommend ->
                            val forums = forumRecommend.data_?.like_forum?.map {
                                HomeUiState.Forum(
                                    it.avatar,
                                    it.forum_id.toString(),
                                    it.forum_name,
                                    it.is_sign == 1,
                                    it.level_id.toString(),
                                    it.hot_num
                                )
                            } ?: emptyList()
                            Pair(forums, historyForums)
                        }
                }
                .map { (forums, historyForums) ->
                    val topForumsDB = LitePal.findAll(TopForum::class.java).map { it.forumId }
                    // 获取当前排序类型用于本地排序
                    val currentSortType = viewModel.getCurrentSortType()

                    // 使用枚举中定义的排序函数
                    val sortedForums = currentSortType.sort(forums)
                    val topForums = sortedForums.filter { topForumsDB.contains(it.forumId) }

                    HomePartialChange.Refresh.Success(
                        sortedForums,
                        topForums,
                        historyForums,
                        currentSortType
                    ) as HomePartialChange.Refresh
                }
                .onStart { emit(HomePartialChange.Refresh.Start) }
                .catch { emit(HomePartialChange.Refresh.Failure(it)) }

        @Suppress("USELESS_CAST")
        private fun produceRefreshHistoryPartialChangeFlow(): Flow<HomePartialChange.RefreshHistory> =
            HistoryUtil.getFlow(HistoryUtil.TYPE_FORUM, 0)
                .map { HomePartialChange.RefreshHistory.Success(it) as HomePartialChange.RefreshHistory }
                .catch { emit(HomePartialChange.RefreshHistory.Failure(it)) }

        private fun HomeUiIntent.TopForums.Delete.toPartialChangeFlow() =
            flow {
                val deletedRows = LitePal.deleteAll(TopForum::class.java, "forumId = ?", forumId)
                if (deletedRows > 0) {
                    emit(HomePartialChange.TopForums.Delete.Success(forumId))
                } else {
                    emit(HomePartialChange.TopForums.Delete.Failure("forum $forumId is not top!"))
                }
            }.flowOn(Dispatchers.IO)
                .catch { emit(HomePartialChange.TopForums.Delete.Failure(it.getErrorMessage())) }

        private fun HomeUiIntent.TopForums.Add.toPartialChangeFlow() =
            flow {
                val success = TopForum(forum.forumId).saveOrUpdate("forumId = ?", forum.forumId)
                if (success) {
                    emit(HomePartialChange.TopForums.Add.Success(forum))
                } else {
                    emit(HomePartialChange.TopForums.Add.Failure("未知错误"))
                }
            }.flowOn(Dispatchers.IO)
                .catch { emit(HomePartialChange.TopForums.Add.Failure(it.getErrorMessage())) }

        private fun HomeUiIntent.Unfollow.toPartialChangeFlow() =
            TiebaApi.getInstance()
                .unlikeForumFlow(forumId, forumName, AccountUtil.getLoginInfo()!!.tbs)
                .map<CommonResponse, HomePartialChange.Unfollow> {
                    HomePartialChange.Unfollow.Success(forumId)
                }
                .catch { emit(HomePartialChange.Unfollow.Failure(it.getErrorMessage())) }

        private fun HomeUiIntent.ToggleHistory.toPartialChangeFlow() =
            flowOf(HomePartialChange.ToggleHistory(!currentExpand))
    }
}

sealed interface HomeUiIntent : UiIntent {
    data object Refresh : HomeUiIntent

    data object RefreshHistory : HomeUiIntent

    data class Unfollow(val forumId: String, val forumName: String) : HomeUiIntent

    sealed interface TopForums : HomeUiIntent {
        data class Delete(val forumId: String) : TopForums

        data class Add(val forum: HomeUiState.Forum) : TopForums
    }

    data class ToggleHistory(val currentExpand: Boolean) : HomeUiIntent

    // 排序相关的 Intent
    data class ChangeSortType(val sortType: SortType) : HomeUiIntent
}

sealed interface HomePartialChange : PartialChange<HomeUiState> {
    sealed class Unfollow : HomePartialChange {
        override fun reduce(oldState: HomeUiState): HomeUiState =
            when (this) {
                is Success -> {
                    oldState.copy(
                        forums = oldState.forums.filterNot { it.forumId == forumId }
                            .toImmutableList(),
                        topForums = oldState.topForums.filterNot { it.forumId == forumId }
                            .toImmutableList(),
                    )
                }

                is Failure -> oldState
            }

        data class Success(val forumId: String) : Unfollow()

        data class Failure(val errorMessage: String) : Unfollow()
    }

    sealed class Refresh : HomePartialChange {
        override fun reduce(oldState: HomeUiState): HomeUiState =
            when (this) {
                is Success -> oldState.copy(
                    isLoading = false,
                    forums = forums.toImmutableList(),
                    topForums = topForums.toImmutableList(),
                    historyForums = historyForums.toImmutableList(),
                    error = null,
                    sortType = sortType
                )

                is Failure -> oldState.copy(isLoading = false, error = error)
                Start -> oldState.copy(isLoading = true)
            }

        data object Start : Refresh()

        data class Success(
            val forums: List<HomeUiState.Forum>,
            val topForums: List<HomeUiState.Forum>,
            val historyForums: List<History>,
            val sortType: SortType
        ) : Refresh()

        data class Failure(
            val error: Throwable,
        ) : Refresh()
    }

    sealed class RefreshHistory : HomePartialChange {
        override fun reduce(oldState: HomeUiState): HomeUiState =
            when (this) {
                is Success -> oldState.copy(
                    historyForums = historyForums.toImmutableList(),
                )

                else -> oldState
            }

        data class Success(
            val historyForums: List<History>,
        ) : RefreshHistory()

        data class Failure(
            val error: Throwable,
        ) : RefreshHistory()
    }

    sealed interface TopForums : HomePartialChange {
        sealed interface Delete : HomePartialChange {
            override fun reduce(oldState: HomeUiState): HomeUiState =
                when (this) {
                    is Success -> oldState.copy(topForums = oldState.topForums.filterNot { it.forumId == forumId }
                        .toImmutableList())

                    is Failure -> oldState
                }

            data class Success(val forumId: String) : Delete

            data class Failure(val errorMessage: String) : Delete
        }

        sealed interface Add : HomePartialChange {
            override fun reduce(oldState: HomeUiState): HomeUiState =
                when (this) {
                    is Success -> {
                        val topForumsId = oldState.topForums.map { it.forumId }.toMutableList()
                        topForumsId.add(forum.forumId)
                        oldState.copy(
                            topForums = oldState.forums.filter { topForumsId.contains(it.forumId) }
                                .toImmutableList()
                        )
                    }

                    is Failure -> oldState
                }

            data class Success(val forum: HomeUiState.Forum) : Add

            data class Failure(val errorMessage: String) : Add
        }
    }

    data class ToggleHistory(val expand: Boolean) : HomePartialChange {
        override fun reduce(oldState: HomeUiState): HomeUiState =
            oldState.copy(expandHistoryForum = expand)
    }

    // 排序类型变更的 PartialChange
    data class ChangeSortType(val sortType: SortType) : HomePartialChange {
        override fun reduce(oldState: HomeUiState): HomeUiState {
            return oldState.copy(
                sortType = sortType
            )
        }
    }
}

@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val forums: ImmutableList<Forum> = persistentListOf(),
    val topForums: ImmutableList<Forum> = persistentListOf(),
    val historyForums: ImmutableList<History> = persistentListOf(),
    val expandHistoryForum: Boolean = true,
    val error: Throwable? = null,
    val sortType: SortType = LEVEL, // 默认排序类型
) : UiState {
    @Immutable
    data class Forum(
        val avatar: String,
        val forumId: String,
        val forumName: String,
        val isSign: Boolean,
        val levelId: String,
        val hotNum: Int,
    )
}

sealed interface HomeUiEvent : UiEvent