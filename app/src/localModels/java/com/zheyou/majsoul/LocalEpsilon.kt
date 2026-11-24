package com.zheyou.majsoul

import android.content.Context
import com.zheyou.majsoul.epsilon.EpsilonMjai
import org.json.JSONObject
import java.io.File

internal class LocalEpsilon(private val context: Context) : AutoCloseable {
    private var session: EpsilonMjai.Session? = null

    // The activity runs model loading, replay and teardown on the same worker.
    fun react(request: JSONObject): JSONObject {
        val active = session ?: open().also { session = it }
        return JSONObject(active.react(request.toString()))
    }

    private fun open(): EpsilonMjai.Session {
        val assetDir = "epsilon-u3-v413"
        val modelDir = File(context.codeCacheDir, assetDir)
        for (name in listOf("policy.json", "m0/model.json", "m0/weights.bin")) {
            context.modelFile("$assetDir/$name")
        }
        System.setProperty("ai.djl.pytorch.num_threads", "2")
        return EpsilonMjai.Session(modelDir.path)
    }

    override fun close() {
        session?.close()
        session = null
    }

}
