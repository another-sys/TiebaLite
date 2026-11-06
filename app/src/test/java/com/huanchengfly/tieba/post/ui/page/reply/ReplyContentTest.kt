package com.huanchengfly.tieba.post.ui.page.reply

import com.huanchengfly.tieba.post.api.models.PicInfo
import com.huanchengfly.tieba.post.api.models.PicInfoItem
import com.huanchengfly.tieba.post.api.models.UploadPictureResultBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReplyContentTest {
    private fun image(id: String? = "picture-id", width: String = "640", height: String = "480"):
            UploadPictureResultBean {
        val info = PicInfoItem(width, height, "jpg", "https://example.com/image.jpg")
        return UploadPictureResultBean("0", "", chunkNo = "1", picId = id,
            picInfo = PicInfo(info, info, info))
    }

    @Test
    fun imageOnlyReply_hasNoExtraTextOrLeadingNewline() {
        assertEquals("#(pic,picture-id,640,480)", appendReplyImages("", listOf(image())))
    }

    @Test
    fun replyingToSubPost_retainsMentionBeforeTextAndImages() {
        val text = buildReplyContent("看这张图", 123L, "portrait", "张三")
        assertEquals("回复 #(reply, portrait, 张三) :看这张图\n#(pic,a,640,480)\n#(pic,b,640,480)",
            appendReplyImages(text, listOf(image("a"), image("b"))))
    }

    @Test
    fun ordinaryReplyAndMissingUserMetadata_doNotInjectNullMention() {
        assertEquals("内容", buildReplyContent("内容", null, "portrait", "张三"))
        assertEquals("内容", buildReplyContent("内容", 123L, null, null))
        assertEquals("内容", appendReplyImages("内容", emptyList()))
    }

    @Test
    fun invalidUploadMetadata_stopsBeforePostingBrokenPictureMarkup() {
        listOf(image(null), image("0"), image(width = "0"), image(height = "-1"),
            image(width = "invalid")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { appendReplyImages("text", listOf(invalid)) }
        }
    }
}
