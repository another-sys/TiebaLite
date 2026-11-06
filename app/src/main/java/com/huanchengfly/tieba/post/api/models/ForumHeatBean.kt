package com.huanchengfly.tieba.post.api.models

import com.google.gson.annotations.SerializedName

data class ForumHeatBean(
    val forum: Forum? = null,
) {
    data class Forum(
        @SerializedName("hot_num") val hotNum: Long? = null,
    )
}
