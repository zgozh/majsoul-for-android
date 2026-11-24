package com.zheyou.majsoul

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.annotation.Keep
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.FloatBuffer

@Keep
internal fun interface MortalScorer {
    fun score(observation: FloatArray, legal: BooleanArray): FloatArray
}

@Keep
internal object MortalRules {
    init {
        System.loadLibrary("mortal_android")
    }

    external fun react(request: String, scorer: MortalScorer): String
}

internal class LocalMortal(private val context: Context) : AutoCloseable {
    private var session: OrtSession? = null

    fun react(request: JSONObject): JSONObject =
        JSONObject(MortalRules.react(request.toString(), MortalScorer(::score)))

    private fun score(observation: FloatArray, legal: BooleanArray): FloatArray {
        val environment = OrtEnvironment.getEnvironment()
        val active = session ?: OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            val model = context.modelFile("mortal-s42/mortal-s42.onnx")
            environment.createSession(model.path, options)
        }.also { session = it }
        val flags = ByteArray(legal.size) { if (legal[it]) 1 else 0 }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(observation), longArrayOf(1, 1012, 34)).use { obs ->
            OnnxTensor.createTensor(environment, ByteBuffer.wrap(flags), longArrayOf(1, 46), OnnxJavaType.BOOL).use { mask ->
                active.run(mapOf("observation" to obs, "legal" to mask)).use { outputs ->
                    @Suppress("UNCHECKED_CAST")
                    return (outputs[0].value as Array<FloatArray>)[0]
                }
            }
        }
    }

    override fun close() {
        session?.close()
        session = null
    }
}
