package app.floatphone.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Float 小手机安卓壳：全屏 WebView 直接加载线上站点。
 * 网页每次部署即时生效，本壳只负责原生能力（推送长连接、文件上下行、外链）。
 *
 * v2（2026-11-25）改动：
 *  - 修复顶部不能全屏：setDecorFitsSystemWindows 改为 false，状态栏/导航栏透明，
 *    窗口背景设为黑色，刘海屏 Android 11+ 用 ALWAYS 铺满
 *  - 补键盘处理：decorFits=false 后 adjustResize 失效，改用 IME insets 给 WebView 加底部内边距
 *  - 文件选择器：改用 FileChooserParams.parseResult，支持多选
 *
 * v3（2026-11-25）改动：
 *  - 修复打字时键盘盖住输入框：v2 给 WebView 加 padding 没生效（WebView 不会因 padding 缩小视口），
 *    改成外面包一层 FrameLayout，键盘弹出时调 WebView 的 bottomMargin，页面视口真正变矮
 *  - 键盘高度双保险：同时读 IME insets 和 getWindowVisibleDisplayFrame，取较大值，
 *    兼容隐藏系统栏后 insets 不上报键盘的机型
 */
class MainActivity : AppCompatActivity() {

    companion object {
        val SITE_URL: String = BuildConfig.SITE_URL
        const val VERSION = "1.0.0"
        /** 来电接听等场景的站内深链（必须以 SITE_URL 开头，否则忽略） */
        const val EXTRA_OPEN_URL = "open_url"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 键盘高度（两个来源取较大值）
    private var imeFromInsets = 0
    private var imeFromLayout = 0

    private fun applyKeyboardInset() {
        val h = maxOf(imeFromInsets, imeFromLayout)
        val lp = webView.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != h) {
            lp.bottomMargin = h
            webView.layoutParams = lp
        }
    }

    private fun setupKeyboardInset(container: FrameLayout) {
        // 来源 1：系统上报的 IME insets
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            imeFromInsets =
                if (insets.isVisible(WindowInsetsCompat.Type.ime()))
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                else 0
            applyKeyboardInset()
            insets
        }
        // 来源 2：窗口可见区域（沉浸式隐藏系统栏后，部分机型不上报 IME insets，靠这个兜底）
        container.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            container.getWindowVisibleDisplayFrame(r)
            val screenH = container.rootView.height
            val diff = screenH - r.bottom
            imeFromLayout = if (screenH > 0 && diff > screenH * 0.15f) diff else 0
            applyKeyboardInset()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        // parseResult 同时处理单选、多选（clipData）和取消（返回 null）
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback.onReceiveValue(uris ?: emptyArray())
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) PushService.start(this)
    }

    // 网页侧 getUserMedia（通话按住说话、语音条录音、视频通话摄像头）触发的
    // WebView 权限请求：先要系统运行时权限，拿到后再转授给页面。
    // 不实现 onPermissionRequest 时 WebView 会静默拒绝，页面永远拿不到麦克风。
    private var pendingWebPermissionRequest: android.webkit.PermissionRequest? = null

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val request = pendingWebPermissionRequest ?: return@registerForActivityResult
        pendingWebPermissionRequest = null
        val granted = request.resources.filter { resource ->
            webResourcePermissions(resource).all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    private fun webResourcePermissions(resource: String): List<String> = when (resource) {
        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
        else -> emptyList()
    }

    /** 沉浸式全屏：隐藏状态栏和底部导航条，从边缘滑动可临时唤出。 */
    private fun applyImmersive() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // —— 全屏关键：内容铺到系统栏下面，不再给状态栏留位置 ——
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // 窗口底色设黑，避免任何露出的缝隙显示成灰/白条
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        // 刘海/挖孔屏区域也铺满
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= 30)
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        // 音量键默认调媒体流：WebView 里的语音条/TTS 都走媒体流播放，
        // 不设的话短音频没在播时按键调的是铃声，用户感觉"音量键无效、声音巨大"
        volumeControlStream = AudioManager.STREAM_MUSIC

        // 外面包一层容器：键盘弹出时调整 WebView 的 bottomMargin，让网页视口真正变矮，
        // 底部的聊天输入框才会被顶到键盘上方
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val container = FrameLayout(this)
        container.setBackgroundColor(Color.BLACK)
        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(container)
        setupKeyboardInset(container)
        ViewCompat.requestApplyInsets(container)

        applyImmersive()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            userAgentString = "$userAgentString FloatShell/$VERSION"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.addJavascriptInterface(ShellBridge(), "AndroidShell")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                // 站内导航留在壳里；http(s) 外链和自定义协议（shortcuts:// 等）交给系统
                if (scheme == "http" || scheme == "https") {
                    if (url.host == Uri.parse(SITE_URL).host) return false
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url)); true
                    }.getOrDefault(true)
                }
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, url)); true
                }.getOrDefault(true)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val supported = request.resources.filter { webResourcePermissions(it).isNotEmpty() }
                if (supported.isEmpty()) { request.deny(); return }
                val missing = supported.flatMap { webResourcePermissions(it) }
                    .distinct()
                    .filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) { request.grant(supported.toTypedArray()); return }
                if (pendingWebPermissionRequest != null) { request.deny(); return }
                pendingWebPermissionRequest = request
                webPermissionLauncher.launch(missing.toTypedArray())
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                filePathCallback?.onReceiveValue(emptyArray())
                filePathCallback = callback
                return runCatching {
                    fileChooserLauncher.launch(params.createIntent()); true
                }.getOrElse {
                    filePathCallback = null; false
                }
            }
        }

        // 备份导出等下载：交给系统下载管理器，落到公共下载目录
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    // blob/data 由页面内 JS 触发的 a[download] 处理；提示用户等待
                    Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show()
                    return@DownloadListener
                }
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("User-Agent", userAgent)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType),
                    )
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "已开始下载到「下载」目录", Toast.LENGTH_SHORT).show()
            }
        })

        // 返回手势：小手机是单页应用，页面切换没有浏览器历史（canGoBack 恒为 false）。
        // 1) 先找最上层可见的「取消/关闭/Close/Cancel」按钮（弹窗）；
        // 2) 再找页面左上角的返回箭头；
        // 3) 都没有（已在桌面）才退出到手机桌面。
        val backJs = """
            (function(){
              var labels = ['取消','关闭','Close','Cancel','×','✕','✖'];
              var ariaOk = ['close','cancel','关闭','取消','返回','back'];
              function onTop(el){
                var r = el.getBoundingClientRect();
                if (r.width < 8 || r.height < 8) return false;
                if (r.bottom <= 0 || r.right <= 0 || r.top >= window.innerHeight || r.left >= window.innerWidth) return false;
                var cx = Math.min(Math.max(r.left + r.width / 2, 0), window.innerWidth - 1);
                var cy = Math.min(Math.max(r.top + r.height / 2, 0), window.innerHeight - 1);
                var t = document.elementFromPoint(cx, cy);
                return !!t && (t === el || el.contains(t));
              }
              var all = document.querySelectorAll('button,[role="button"],a,div,span');
              for (var i = all.length - 1; i >= 0; i--) {
                var el = all[i];
                var txt = (el.children.length === 0 || el.tagName === 'BUTTON') ? (el.textContent || '').trim() : '';
                var al = (el.getAttribute('aria-label') || '').trim().toLowerCase();
                if (labels.indexOf(txt) >= 0 || ariaOk.indexOf(al) >= 0) {
                  if (onTop(el)) { el.click(); return 'clicked'; }
                }
              }
              function isClickable(n){
                var t = n.tagName ? n.tagName.toLowerCase() : '';
                if (t === 'button' || t === 'a' || t === 'svg' || t === 'img') return true;
                if (n.getAttribute && n.getAttribute('role') === 'button') return true;
                if (n.querySelector && n.querySelector('svg,img')) return true;
                return window.getComputedStyle(n).cursor === 'pointer';
              }
              for (var y = 16; y <= 160; y += 8) {
                for (var x = 16; x <= 64; x += 8) {
                  var n = document.elementFromPoint(x, y);
                  for (var d = 0; n && d < 6; d++, n = n.parentElement) {
                    var r2 = n.getBoundingClientRect();
                    if (r2.width < 16 || r2.width > 100 || r2.height > 100) continue;
                    if (!isClickable(n)) continue;
                    n.click();
                    return 'clicked';
                  }
                }
              }
              return 'none';
            })()
        """.trimIndent()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(backJs) { result ->
                    if (result == "\"clicked\"") return@evaluateJavascript
                    if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
                }
            }
        })

        // 冷启动带深链（如来电接听）直接加载目标；否则加载首页
        webView.loadUrl(consumeOpenUrl(intent) ?: SITE_URL)
        ensurePushService()
    }

    /** singleTask：App 已在运行时（如全屏来电页接听）通过 onNewIntent 送达深链 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = consumeOpenUrl(intent) ?: return
        // SPA 已加载：loadUrl 到同页 hash 只触发 hashchange，不会整页重载
        webView.loadUrl(target)
    }

    private fun consumeOpenUrl(intent: Intent?): String? {
        val target = intent?.getStringExtra(EXTRA_OPEN_URL) ?: return null
        intent.removeExtra(EXTRA_OPEN_URL)
        return target.takeIf { it.startsWith(SITE_URL) }
    }

    private fun ensurePushService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PushService.start(this)
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }

    /** 暴露给网页的原生桥（网页侧可用 window.AndroidShell 特性检测壳环境）。 */
    inner class ShellBridge {
        @JavascriptInterface
        fun getVersion(): String = VERSION

        /** 打开本应用的系统设置页（引导用户关电池限制、开自启动）。 */
        @JavascriptInterface
        fun openAppSettings() {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** 请求忽略电池优化（保活关键一步）。 */
        @SuppressLint("BatteryLife")
        @JavascriptInterface
        fun requestIgnoreBatteryOptimization() {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
