package com.huanchengfly.tieba.post.ui.page.reply

import com.huanchengfly.tieba.post.api.models.UploadPictureResultBean

internal fun buildReplyContent(
    text: String,
    subPostId: Long?,
    replyUserPortrait: String?,
    replyUserName: String?,
): String = if (subPostId != null && subPostId != 0L &&
    !replyUserPortrait.isNullOrBlank() && !replyUserName.isNullOrBlank()
) {
    "回复 #(reply, $replyUserPortrait, $replyUserName) :$text"
} else text

internal fun UploadPictureResultBean.toReplyImageMarkup(): String {
    val id = picId?.takeIf { it.isNotBlank() && it != "0" }
    val width = picInfo?.originPic?.width?.toIntOrNull()
    val height = picInfo?.originPic?.height?.toIntOrNull()
    require(id != null && width != null && width > 0 && height != null && height > 0) {
        "图片上传结果不完整，请重新上传"
    }
    return "#(pic,$id,$width,$height)"
}

internal fun appendReplyImages(content: String, images: List<UploadPictureResultBean>): String =
    (listOfNotNull(content.takeIf { it.isNotEmpty() }) + images.map { it.toReplyImageMarkup() })
        .joinToString("\n")
