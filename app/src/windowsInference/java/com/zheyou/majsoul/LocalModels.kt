package com.zheyou.majsoul

import android.content.Context
import org.json.JSONObject

internal class LocalModels(@Suppress("UNUSED_PARAMETER") context: Context) : AutoCloseable {
    fun react(@Suppress("UNUSED_PARAMETER") request: JSONObject): JSONObject =
        error("Local models were excluded from this build")

    override fun close() = Unit
}
