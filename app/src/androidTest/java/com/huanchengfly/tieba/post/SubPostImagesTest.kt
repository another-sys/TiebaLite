package com.huanchengfly.tieba.post

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.huanchengfly.tieba.post.api.models.protos.PbContent
import com.huanchengfly.tieba.post.api.models.protos.SubPostList
import com.huanchengfly.tieba.post.api.models.protos.User
import com.huanchengfly.tieba.post.api.models.protos.contentRenders
import com.huanchengfly.tieba.post.api.models.protos.getContentText
import com.huanchengfly.tieba.post.api.models.protos.renders
import com.huanchengfly.tieba.post.ui.common.PicContentRender
import com.huanchengfly.tieba.post.ui.common.theme.compose.TiebaLiteTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubPostImagesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun missingSizeAndOrigin_fallBackToNumericSizeAndAvailableUrl() {
        val render = listOf(PbContent(type = 3, src = "https://example.com/a.jpg", width = 640, height = 480))
            .renders.single() as PicContentRender
        assertEquals(640, render.width)
        assertEquals(480, render.height)
        assertEquals(render.picUrl, render.originUrl)
        assertEquals("https://example.com/a.jpg", render.picUrl)
        val originalOnly = listOf(PbContent(type = 3, originSrc = "https://example.com/original.jpg", bsize = "560,525"))
            .renders.single() as PicContentRender
        assertEquals("https://example.com/original.jpg", originalOnly.picUrl)
        assertEquals(560, originalOnly.width)
        assertEquals(525, originalOnly.height)
    }

    @Test
    fun malformedImageSizes_areSafeAndDoNotDropContent() {
        listOf("", "640", "0,0", "bad,size", "-1,400").forEach { size ->
            val render = listOf(PbContent(type = 3, bsize = size)).renders.single() as PicContentRender
            assertEquals(1, render.width)
            assertEquals(1, render.height)
        }
        assertNotNull(SubPostList().getContentText())
        val reply = SubPostList(author = User(id = 1, name = "User", nameShow = "User"), content = listOf(
            PbContent(type = 0, text = " : "), PbContent(type = 0, text = "hello")))
        assertEquals("User : hello", reply.getContentText().text)
    }

    @Test
    fun subPostGallery_keepsImageOrderAndSkipsMissingUrls() {
        val subPost = SubPostList(id = 42L, content = listOf(
            PbContent(type = 0, text = "文字"),
            PbContent(type = 3, src = "https://example.com/a.jpg", bsize = "200,100"),
            PbContent(type = 3),
            PbContent(type = 20, src = "https://example.com/b.gif", bsize = "100,200"),
        ))
        val images = subPost.contentRenders.filterIsInstance<PicContentRender>()
        assertEquals(3, images.size)
        assertNull(images[1].photoViewData)
        assertEquals(0, images[0].photoViewData!!.index)
        assertEquals(1, images[2].photoViewData!!.index)
        val gallery = images[2].photoViewData!!
        assertNull(gallery.data)
        assertEquals(listOf("https://example.com/a.jpg", "https://example.com/b.gif"), gallery.picItems.map { it.originUrl })
        assertTrue(gallery.picItems.all { it.postId == 42L })
    }

    @Test
    fun thumbnailAndInvalidImage_renderWithoutCrashing() {
        val pictures = SubPostList(content = listOf(
            PbContent(type = 3, src = "file:///android_asset/test-image-not-present.png"),
            PbContent(type = 3),
        )).contentRenders.filterIsInstance<PicContentRender>()
        compose.setContent {
            TiebaLiteTheme {
                Column(Modifier.size(300.dp)) {
                    pictures[0].ThumbnailRender()
                    pictures[1].Render()
                }
            }
        }
        compose.onAllNodesWithContentDescription(App.INSTANCE.getString(R.string.desc_image))[0].assertIsDisplayed()
        compose.onNodeWithText(App.INSTANCE.getString(R.string.image_unavailable)).assertIsDisplayed()
    }
}
