package cn.shaohsmoni.voxassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Setup UI: permissions, model download, GLM key, start/stop service.
 * Log pane shows engine state in real time.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btn: Button

    private val neededModels = listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2" to "asr.tar.bz2",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" to "silero_vad.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2" to "kws.tar.bz2",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this

        val pad = (16 * resources.displayMetrics.density).toInt()
        val keyEdit = EditText(this).apply {
            hint = "GLM API Key（Digiwin）"
            setText(GlmClient.loadConfig(ctx).apiKey)
        }
        log = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF9EE49E.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll = ScrollView(this).apply { addView(log) }
        btn = Button(this).apply { text = "启动语音助手" }
        btn.setOnClickListener {
            GlmClient.saveConfig(
                ctx,
                GlmClient.Config(
                    GlmClient.loadConfig(ctx).baseUrl,
                    keyEdit.text.toString().trim(),
                    GlmClient.loadConfig(ctx).model
                )
            )
            startVoice()
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(keyEdit)
            addView(btn)
            addView(scroll, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(layout)

        appendLog("Vox助手 v1.0 —— 全离线语音助手\n")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else {
            prepareAssets()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            prepareAssets()
        } else {
            appendLog("✗ 麦克风权限被拒绝，语音功能不可用\n")
        }
    }

    private fun prepareAssets() {
        val d = filesDir
        if (VoiceEngine(this).modelsReady()) {
            appendLog("✓ 模型已就绪，可直接启动\n")
            return
        }
        appendLog("首次运行：下载模型（约 55MB，仅此一次）...\n")
        Thread {
            try {
                // 1) ASR tarball
                val asrTar = File(d, "asr.tar.bz2")
                if (!asrTar.exists()) download(neededModels[0].first, asrTar)
                untarSelective(asrTar, d, listOf(
                    "encoder-epoch-99-avg-1.onnx",
                    "decoder-epoch-99-avg-1.onnx",
                    "joiner-epoch-99-avg-1.onnx",
                    "tokens.txt"))

                // 2) VAD
                downloadIfNeeded(neededModels[1].first, File(d, "silero_vad.onnx"))

                // 3) KWS tarball
                val kwsTar = File(d, "kws.tar.bz2")
                if (!kwsTar.exists()) download(neededModels[2].first, kwsTar)
                untarSelective(kwsTar, d, listOf(
                    "kws-encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    "kws-decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    "kws-joiner-epoch-12-avg-2-chunk-16-left-64.onnx",
                    "kws-tokens.txt"))

                // 4) keywords file (built-in)
                val kw = File(d, "kws-keywords.txt")
                if (!kw.exists()) kw.writeText(KEYWORDS)

                asrTar.delete(); kwsTar.delete()
                runOnUiThread { appendLog("✓ 模型全部就绪！点启动即可\n") }
            } catch (e: Exception) {
                runOnUiThread { appendLog("✗ 下载失败: ${e.message}\n") }
            }
        }.start()
    }

    private fun downloadIfNeeded(url: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        download(url, dest)
    }

    private fun download(url: String, dest: File) {
        appendLog("↓ ${dest.name} ...\n")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connect()
        val input = if (conn.responseCode in 300..399) {
            val loc = conn.getHeaderField("Location")
            (URL(loc).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }.inputStream
        } else conn.inputStream
        input.use { ins -> dest.outputStream().use { ins.copyTo(it, 1 shl 16) } }
        appendLog("  ✓ ${dest.name} (${dest.length() / 1048576}MB)\n")
    }

    /** extract selected files (flattened) from a .tar.bz2 */
    private fun untarSelective(tar: File, destDir: File, wanted: List<String>) {
        val proc = ProcessBuilder("tar", "xjf", tar.absolutePath, "-C", destDir.absolutePath,
            "--strip-components=2")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // verify all wanted files exist somewhere in destDir
        for (w in wanted) {
            val f = File(destDir, w)
            if (!f.exists()) {
                // search deeper
                val found = destDir.walkTopDown().firstOrNull { it.name == w }
                if (found != null && found.absolutePath != f.absolutePath) {
                    found.copyTo(f, overwrite = true)
                } else throw RuntimeException("解包缺少 $w\n$out")
            }
        }
    }

    private fun startVoice() {
        appendLog("▶ 启动引擎...\n")
        val intent = Intent(this, VoiceService::class.java)
        ContextCompat.startForegroundService(this, intent)
        btn.isEnabled = false
        btn.text = "运行中（通知栏可停止）"
    }

    fun appendLog(s: String) {
        runOnUiThread {
            log.append(s)
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    companion object {
        // 你好小爱 + few fallbacks; format: token sequence @score
        val KEYWORDS = """
            你 好 小 爱 @1.5
            小 爱 同 学 @1.5
        """.trimIndent()
    }
}
