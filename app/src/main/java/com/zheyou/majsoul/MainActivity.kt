package com.zheyou.majsoul

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private data class Recommendation(val label: String, val request: JSONObject, val action: JSONObject)

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val inference = Executors.newSingleThreadExecutor()
    private val explanation = Executors.newSingleThreadExecutor()
    private val localModels by lazy { LocalModels(applicationContext) }
    private val promptPreferences by lazy { getSharedPreferences("prompt_position", MODE_PRIVATE) }
    private var pending: Future<*>? = null
    private var pendingExplanation: Future<*>? = null
    private var recommendation: Recommendation? = null
    private var explanationLines: List<String>? = null
    private val expanded: Boolean get() = promptPreferences.getBoolean(EXPLANATION_EXPANDED, false)
    private var revision = 0L
    private var bridgeSupported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val layout = FrameLayout(this)
        webView = WebView(this)
        layout.addView(webView, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 15f
            maxWidth = dp(180)
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(222, 24, 33, 38))
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        layout.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT))
        DraggablePrompt(layout, status, dp(12))
        setContentView(layout)
        WindowCompat.getInsetsController(window, layout).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setupWebView()
        webView.loadUrl(GAME_URL)
    }

    private fun setupWebView() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                return url.scheme != "https" || (request.isForMainFrame && url.host != GAME_HOST)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                clear(if (bridgeSupported) null else "请更新 Android System WebView")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) clear("网页加载失败 · 点击重试", retry = true)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                if (request.isForMainFrame) clear("网页加载失败 · 点击重试", retry = true)
            }
        }
        bridgeSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (!bridgeSupported) return

        val origins = setOf("https://$GAME_HOST")
        WebViewCompat.addWebMessageListener(webView, "ZheyouTraining", origins) { _, message, origin, main, _ ->
            if (main && origin == Uri.parse("https://$GAME_HOST")) {
                message.data?.let(::onGameMessage)
            }
        }
        val script = assets.open("training.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
    }

    private fun onGameMessage(data: String) {
        if (data.length > 200_000) return
        val message = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (message.optString("type")) {
            "idle", "watching", "gap" -> clear()
            "decision" -> {
                val body = message.optJSONObject("request") ?: return
                clear()
                val current = revision
                val remainingMs = message.optLong("remainingMs").coerceIn(0L, 120_000L)
                if (remainingMs > 0) status.postDelayed({
                    if (current == revision) clear()
                }, remainingMs)
                pending = inference.submit {
                    val result = runCatching { requestRecommendations(body) }
                    runOnUiThread {
                        if (current != revision) return@runOnUiThread
                        result.onSuccess { next ->
                            if (next != null) {
                                recommendation = next
                                status.setOnClickListener { toggleExplanation() }
                                refreshExplanation()
                            }
                        }.onFailure {
                            Log.w("ZheyouInference", "Recommendation failed", it)
                            showPrompt(if (BuildConfig.LOCAL_MODELS) "本机推理未就绪"
                                else "Windows 推理未就绪 · 检查 ADB 转发和模型状态")
                        }
                    }
                }
            }
        }
    }

    private fun clear(text: String? = null, retry: Boolean = false) {
        revision++
        pending?.cancel(true)
        pending = null
        pendingExplanation?.cancel(true)
        pendingExplanation = null
        recommendation = null
        explanationLines = null
        showPrompt(text, retry)
    }

    private fun showPrompt(text: String?, retry: Boolean = false) {
        status.maxWidth = dp(180)
        status.text = text
        status.contentDescription = null
        status.visibility = if (text == null) View.GONE else View.VISIBLE
        status.setOnClickListener(if (retry) View.OnClickListener { webView.loadUrl(GAME_URL) } else null)
        status.isClickable = retry
    }

    private fun toggleExplanation() {
        promptPreferences.edit().putBoolean(EXPLANATION_EXPANDED, !expanded).apply()
        refreshExplanation()
    }

    private fun refreshExplanation() {
        val active = recommendation ?: return
        renderRecommendation()
        if (!expanded || explanationLines != null || pendingExplanation != null) return
        val current = revision
        pendingExplanation = explanation.submit {
            val lines = runCatching { RecommendationDetails.explain(active.request, active.action) }
                .getOrElse { listOf("当前局面暂无可核对的解读") }
            runOnUiThread {
                if (current != revision) return@runOnUiThread
                pendingExplanation = null
                explanationLines = lines
                if (expanded) renderRecommendation()
            }
        }
    }

    private fun renderRecommendation() {
        val active = recommendation ?: return
        val showDetails = expanded
        val heading = active.label + if (showDetails) "  ▴" else "  ▾"
        status.maxWidth = dp(if (showDetails) 252 else 180)
        status.text = if (showDetails) {
            val body = explanationLines?.joinToString("\n") ?: "正在计算解读…"
            SpannableString("$heading\n$body").apply {
                setSpan(ForegroundColorSpan(Color.rgb(205, 216, 222)), heading.length + 1, length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else heading
        status.contentDescription = if (showDetails) null else "${active.label}，查看操作解读"
        status.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        clear()
        if (BuildConfig.LOCAL_MODELS) {
            inference.execute { localModels.close() }
        }
        inference.shutdown()
        explanation.shutdownNow()
        (webView.parent as? FrameLayout)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun requestRecommendations(body: JSONObject): Recommendation? {
        val start = SystemClock.elapsedRealtime()
        val reaction = if (BuildConfig.LOCAL_MODELS) localModels.react(body) else requestWindowsReaction(body)
        if (reaction == null) return null
        val elapsed = SystemClock.elapsedRealtime() - start
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val backend = if (BuildConfig.LOCAL_MODELS) body.optString("model") else "Windows"
            Log.d("ZheyouInference", "$backend recommendation received in $elapsed ms")
        }
        return actionLabel(reaction)?.let { Recommendation(it, body, reaction) }
    }

    private fun requestWindowsReaction(body: JSONObject): JSONObject? {
        val connection = URL("http://127.0.0.1:25034/v3/react")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 1000
            connection.readTimeout = 2500
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer zheyou-local-inference")
            connection.setRequestProperty("Content-Type", "application/json")
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Inference failed")
            val reply = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            return reply.optJSONObject("reaction")
        } finally {
            connection.disconnect()
        }
    }

    private fun actionLabel(reaction: JSONObject): String? {
        val tile = tileName(reaction.optString("pai"))
        return when (reaction.optString("type")) {
            "dahai" -> "切$tile"
            "reach" -> if (tile.isEmpty()) "立直" else "立直 · 切$tile"
            "chi" -> {
                val consumed = reaction.getJSONArray("consumed")
                val first = tileName(consumed.getString(0))
                val second = tileName(consumed.getString(1))
                "吃$tile（用$first、$second）"
            }
            "pon" -> "碰$tile"
            "ankan" -> {
                val consumed = reaction.optJSONArray("consumed")?.optString(0).orEmpty()
                "暗杠${tileName(consumed.removeSuffix("r"))}"
            }
            "kakan" -> "加杠$tile"
            "daiminkan" -> "大明杠$tile"
            "kita", "nukidora" -> "拔北"
            "hora" -> "和牌"
            "ryukyoku" -> "流局"
            "none" -> "跳过"
            else -> null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXPLANATION_EXPANDED = "explanation_expanded"
        private const val GAME_HOST = "game.maj-soul.com"
        private const val GAME_URL = "https://$GAME_HOST/1/"
    }
}

internal fun tileName(tile: String): String {
    val honor = when (tile) {
        "E" -> "东风"
        "S" -> "南风"
        "W" -> "西风"
        "N" -> "北风"
        "P" -> "白板"
        "F" -> "发财"
        "C" -> "红中"
        else -> null
    }
    if (honor != null) return honor
    if (tile.length !in 2..3) return tile
    val rank = tile[0] - '1'
    if (rank !in 0..8) return tile
    val suit = when (tile[1]) {
        'm' -> '万'
        'p' -> '筒'
        's' -> '索'
        else -> return tile
    }
    val red = if (tile.length == 3 && tile[2] == 'r') "红" else ""
    return "$red${"一二三四五六七八九"[rank]}$suit"
}
