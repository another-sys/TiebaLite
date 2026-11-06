package com.huanchengfly.tieba.post

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.huanchengfly.tieba.post.arch.GlobalEvent
import com.huanchengfly.tieba.post.arch.GlobalEventFlow
import com.huanchengfly.tieba.post.arch.emitGlobalEventSuspend
import com.huanchengfly.tieba.post.ui.common.theme.compose.TiebaLiteTheme
import com.huanchengfly.tieba.post.ui.page.reply.ReplyPageContent
import com.huanchengfly.tieba.post.ui.page.reply.ReplyViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubPostReplyComposerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun replyingToAnotherSubPost_canSelectAndSendAnImageWithoutText() {
        // MainActivity normally initializes the legacy pixel conversion helpers.
        App.ScreenInfo.DENSITY = App.INSTANCE.resources.displayMetrics.density
        val viewModels = ViewModelStore()
        val model = ReplyViewModel()
        viewModels.put("reply", model)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // Fulfil the picker contract locally; no upload or forum post is performed.
        val image = Uri.parse("file:///android_asset/composer-test.png")
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            GlobalEventFlow.filterIsInstance<GlobalEvent.StartSelectImages>().collect {
                emitGlobalEventSuspend(GlobalEvent.SelectedImages(it.id, listOf(image)))
            }
        }
        try {
            compose.setContent {
                TiebaLiteTheme {
                    ReplyPageContent(
                        viewModel = model, onBack = {}, forumId = 1L, forumName = "Test",
                        threadId = 2L, postId = 3L, subPostId = 4L,
                        replyUserId = 5L, replyUserName = "User", replyUserPortrait = "portrait",
                        tbs = "unused", isDialog = true,
                    )
                }
            }
            compose.onNodeWithContentDescription(App.INSTANCE.getString(R.string.insert_photo))
                .assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription(App.INSTANCE.getString(R.string.desc_add_image))
                .assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { model.uiState.value.selectedImageList.isNotEmpty() }
            assertEquals(listOf(image.toString()), model.uiState.value.selectedImageList)
            compose.onNodeWithContentDescription(App.INSTANCE.getString(R.string.send_reply)).assertIsEnabled()
        } finally {
            scope.cancel()
            viewModels.clear()
        }
    }
}
