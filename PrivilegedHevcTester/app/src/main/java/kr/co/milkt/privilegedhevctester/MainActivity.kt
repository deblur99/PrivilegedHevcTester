package kr.co.milkt.privilegedhevctester

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import kr.co.milkt.privilegedhevctester.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Buttons: environment setup (host-bridge) → load HEVC HTML.
 *
 * Version *switch* needs Mac host-bridge (adb install). Same-version smoke does not.
 * After a provider switch the process must restart to load the new WebView native lib —
 * the app relaunches itself (no manual force-stop).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var webView: WebView? = null
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureWebView()
        binding.btnWebView91.setOnClickListener { startTest(WEBVIEW_91, skipSetup = false) }
        binding.btnWebView134.setOnClickListener { startTest(WEBVIEW_134, skipSetup = false) }
        setProgressVisible(false)
        applyCodecFromIntent(intent)

        maybeStartFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyCodecFromIntent(intent)
        maybeStartFromIntent(intent)
    }

    private fun applyCodecFromIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_CODEC)?.trim()?.lowercase()) {
            CODEC_H264.token -> binding.radioH264.isChecked = true
            CODEC_HEVC.token -> binding.radioHevc.isChecked = true
        }
    }

    private fun selectedCodec(): VideoCodec {
        return if (binding.radioH264.isChecked) CODEC_H264 else CODEC_HEVC
    }

    private fun maybeStartFromIntent(intent: Intent?) {
        val skip = intent?.getBooleanExtra(EXTRA_SKIP_SETUP, false) == true
        when (intent?.getStringExtra(EXTRA_RUN)?.trim()) {
            "91" -> startTest(WEBVIEW_91, skipSetup = skip)
            "134" -> startTest(WEBVIEW_134, skipSetup = skip)
        }
    }

    override fun onDestroy() {
        destroyWebView()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startTest(profile: WebViewProfile, skipSetup: Boolean) {
        if (running) return
        running = true
        val codec = selectedCodec()
        setButtonsEnabled(false)
        setProgressVisible(true, "테스트 중… ${profile.label} / ${codec.label}")
        appendLog("=== ${profile.label} / ${codec.label} ===")

        worker.execute {
            try {
                runProfile(profile, skipSetup, codec)
                runOnUiThread { setProgressVisible(false) }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                runOnUiThread { setProgressVisible(false) }
            } catch (e: Exception) {
                runOnUiThread { setProgressVisible(false) }
                appendLog("ERROR: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                runOnUiThread {
                    setProgressVisible(false)
                    running = false
                    setButtonsEnabled(true)
                    appendLog("=== done ===")
                }
            }
        }
    }

    private fun runProfile(profile: WebViewProfile, skipSetup: Boolean, codec: VideoCodec) {
        logCurrentWebViewPackage()

        val current = WebView.getCurrentWebViewPackage()?.versionName.orEmpty()
        val alreadyOk = current.contains(profile.versionMarker)

        if (skipSetup || alreadyOk) {
            if (alreadyOk && !skipSetup) {
                appendLog("Already on ${profile.versionMarker}* — skip host-bridge, load HTML")
            } else {
                appendLog("skipSetup=true (host already installed WebView)")
            }
        } else {
            waitForHostBridge(profile, current)
            // New WebView APK is installed, but this process still has the old provider
            // loaded. Relaunch so the next start binds the new engine — no manual force-stop.
            appendLog("Provider switched → process relaunch (load new WebView)")
            setProgressVisible(true, "WebView 교체 완료 — 앱 재시작 중…")
            relaunchProcessForNewProvider(profile, codec)
            return
        }

        val pkg = WebView.getCurrentWebViewPackage()
        val version = pkg?.versionName ?: "(unknown)"
        appendLog("After setup: ${pkg?.packageName} versionName=$version")
        if (!version.contains(profile.versionMarker)) {
            throw IllegalStateException(
                "Expected version containing \"${profile.versionMarker}\", got \"$version\""
            )
        }
        appendLog("Version OK (${profile.versionMarker})")

        val html = stageSampleFiles()
        val qSrc = URLEncoder.encode(codec.fileName, StandardCharsets.UTF_8.name())
        val qLabel = URLEncoder.encode(codec.label, StandardCharsets.UTF_8.name())
        val url = "file://${html.absolutePath}?src=$qSrc&label=$qLabel"
        appendLog("Codec=${codec.label} video=${codec.fileName}")
        runOnUiThreadBlocking {
            replaceWebView()
            configureAndLoadWebView(url)
        }
        Thread.sleep(8000L)
        appendLog("HTML load window finished")
    }

    /** Schedule same test with skipSetup, then kill process so WebView provider reloads. */
    private fun relaunchProcessForNewProvider(profile: WebViewProfile, codec: VideoCodec) {
        runOnUiThread {
            val relaunch = Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_RUN, profile.cmdToken)
                putExtra(EXTRA_SKIP_SETUP, true)
                putExtra(EXTRA_CODEC, codec.token)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(relaunch)
            finishAffinity()
            Process.killProcess(Process.myPid())
        }
    }

    private fun waitForHostBridge(profile: WebViewProfile, current: String) {
        val dir = getExternalFilesDir(null)
            ?: throw IllegalStateException("getExternalFilesDir null")
        val cmdFile = File(dir, "cmd")
        val doneFile = File(dir, "cmd.done")
        val errFile = File(dir, "cmd.err")
        val statusFile = File(dir, "cmd.status")
        doneFile.delete()
        errFile.delete()
        statusFile.delete()
        cmdFile.writeText(profile.cmdToken)
        appendLog(
            "Need WebView ${profile.versionMarker}* (now $current). " +
                "Wrote cmd=${profile.cmdToken}. Mac에서 host-bridge 실행 필요."
        )
        setProgressVisible(true, "host-bridge 연결 대기… WebView ${profile.cmdToken}")

        val deadline = System.currentTimeMillis() + 180_000L
        var lastStatus = ""
        var lastLogBeat = 0L
        while (System.currentTimeMillis() < deadline) {
            if (doneFile.isFile) break
            val live = if (statusFile.isFile) statusFile.readText().trim() else ""
            if (live.isNotEmpty() && live != lastStatus) {
                lastStatus = live
                appendLog("host-bridge: $live")
                setProgressVisible(true, live)
            } else if (live.isEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastLogBeat > 5_000L) {
                    val left = (deadline - now) / 1000
                    appendLog("host-bridge 미응답… (${left}s) — ./tools/webview-hevc-test/start-host-bridge.sh")
                    setProgressVisible(true, "host-bridge 미응답… ${left}s")
                    lastLogBeat = now
                }
            }
            Thread.sleep(300L)
        }
        if (!doneFile.isFile) {
            cmdFile.delete()
            throw IllegalStateException(
                "host-bridge timeout. On Mac:\n" +
                    "./tools/webview-hevc-test/start-host-bridge.sh\n" +
                    "Or: ./tools/webview-hevc-test/run-test.sh ${profile.cmdToken}"
            )
        }
        val status = doneFile.readText().trim()
        val detail = if (errFile.isFile) errFile.readText().trim() else ""
        appendLog("host-bridge status=$status\n$detail")
        if (status != "ok") {
            throw IllegalStateException("host-bridge setup failed: $status")
        }
        setProgressVisible(true, "WebView ${profile.cmdToken} 설치 완료")
        Thread.sleep(400L)
    }

    private fun stageSampleFiles(): File {
        val outDir = File(filesDir, "video-sample").apply { mkdirs() }
        for (name in listOf(ASSET_HTML, ASSET_HEVC, ASSET_H264)) {
            val out = File(outDir, name)
            // Always refresh HTML; copy videos if missing or empty
            if (name != ASSET_HTML && out.exists() && out.length() > 0L) continue
            assets.open(name).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            out.setReadable(true, false)
            appendLog("Staged $name → ${out.absolutePath} (${out.length()} bytes)")
        }
        val html = File(outDir, ASSET_HTML)
        if (!html.isFile || html.length() == 0L) {
            throw IllegalStateException("missing staged HTML: ${html.absolutePath}")
        }
        for (name in listOf(ASSET_HEVC, ASSET_H264)) {
            val f = File(outDir, name)
            if (!f.isFile || f.length() == 0L) {
                throw IllegalStateException("missing staged video: ${f.absolutePath}")
            }
        }
        appendLog("Sample ready: ${html.absolutePath}")
        return html
    }

    private fun logCurrentWebViewPackage() {
        val pkg = WebView.getCurrentWebViewPackage()
        if (pkg == null) {
            appendLog("getCurrentWebViewPackage(): null")
            return
        }
        appendLog(
            "Current WebView: ${pkg.packageName} " +
                "versionName=${pkg.versionName} versionCode=${formatVersionCode(pkg)}"
        )
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return replaceWebView()
    }

    private fun replaceWebView(): WebView {
        destroyWebView()
        val wv = WebView(this)
        binding.webViewContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        webView = wv
        return wv
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        try {
            wv.stopLoading()
            wv.webChromeClient = null
            wv.webViewClient = WebViewClient()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        } catch (_: Exception) {
        }
        webView = null
        binding.webViewContainer.removeAllViews()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureAndLoadWebView(url: String) {
        val wv = ensureWebView()
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = true
        wv.settings.allowContentAccess = true
        @Suppress("DEPRECATION")
        wv.settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        wv.settings.allowUniversalAccessFromFileURLs = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.domStorageEnabled = true
        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                appendLog("WebView error code=$errorCode desc=$description url=$failingUrl")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                appendLog("onPageFinished: $url")
                view?.evaluateJavascript(
                    """
                    (function(){
                      var v=document.getElementById('v');
                      if(!v) return 'no-video';
                      return 'hvc1='+JSON.stringify(v.canPlayType('video/mp4; codecs=\"hvc1\"'))
                        +' hev1='+JSON.stringify(v.canPlayType('video/mp4; codecs=\"hev1\"'))
                        +' err='+(v.error?v.error.code:'null')
                        +' readyState='+v.readyState
                        +' src='+v.currentSrc;
                    })();
                    """.trimIndent(),
                ) { result -> appendLog("js: $result") }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                appendLog("console: ${consoleMessage?.message()}")
                return true
            }
        }
        wv.loadUrl(url)
        appendLog("Loading $url")
    }

    private fun runOnUiThreadBlocking(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
    }

    private fun formatVersionCode(pkg: PackageInfo): Long {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
    }

    private fun appendLog(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            val existing = binding.logText.text?.toString().orEmpty()
            val prefix = if (existing.isEmpty()) "" else "$existing\n"
            binding.logText.text = prefix + line
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnWebView91.isEnabled = enabled
        binding.btnWebView134.isEnabled = enabled
        binding.radioHevc.isEnabled = enabled
        binding.radioH264.isEnabled = enabled
    }

    private fun setProgressVisible(visible: Boolean, label: String? = null) {
        runOnUiThread {
            if (label != null) {
                binding.progressLabel.text = label
            }
            binding.progressRow.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private data class WebViewProfile(
        val label: String,
        val versionMarker: String,
        val cmdToken: String,
    )

    private data class VideoCodec(
        val token: String,
        val label: String,
        val fileName: String,
    )

    companion object {
        private const val TAG = "HevcTester"
        private const val EXTRA_RUN = "run"
        private const val EXTRA_SKIP_SETUP = "skipSetup"
        private const val EXTRA_CODEC = "codec"
        private const val ASSET_HTML = "video-sample.html"
        private const val ASSET_HEVC = "hevc-lesson.mp4"
        private const val ASSET_H264 = "problem_h264.mp4"

        private val WEBVIEW_91 = WebViewProfile("WebView 91", "91.", "91")
        private val WEBVIEW_134 = WebViewProfile("WebView 134", "134.", "134")
        private val CODEC_HEVC = VideoCodec("hevc", "HEVC", ASSET_HEVC)
        private val CODEC_H264 = VideoCodec("h264", "H.264", ASSET_H264)
    }
}
