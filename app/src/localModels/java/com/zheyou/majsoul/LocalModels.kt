package com.zheyou.majsoul

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal class LocalModels(context: Context) : AutoCloseable {
    private val epsilon = LocalEpsilon(context)
    private val mortal = LocalMortal(context)

    fun react(request: JSONObject): JSONObject = when (request.getString("model")) {
        "epsilon-prism-3p" -> epsilon.react(request)
        "mortal-s42" -> mortal.react(request)
        else -> error("Unknown model")
    }

    override fun close() {
        try {
            epsilon.close()
        } finally {
            mortal.close()
        }
    }
}

private val modelFilesLock = Any()

internal fun Context.modelFile(asset: String): File = synchronized(modelFilesLock) {
    val target = File(codeCacheDir, asset)
    if (!target.isFile) {
        val file = AtomicFile(target)
        val output = file.startWrite()
        try {
            assets.open(asset).use { it.copyTo(output) }
            file.finishWrite(output)
        } catch (failure: IOException) {
            file.failWrite(output)
            throw failure
        }
    }
    target
}
