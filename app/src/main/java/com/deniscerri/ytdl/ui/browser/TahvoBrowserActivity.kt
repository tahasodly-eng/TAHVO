package com.deniscerri.ytdl.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * TAHVO in-app browser.
 * The user opens any page; the browser notes the video files the page actually requests
 * (MP4, WEBM, HLS .m3u8, DASH .mpd…) and the <video> elements in it. The download button lists
 * them so the user can pick which to download, or hands the whole page to the yt-dlp engine
 * (needed for sites such as YouTube or TikTok whose streams are not plain files).
 * Nothing is detected that the page did not load itself; protected (DRM) streams cannot be saved.
 */
class TahvoBrowserActivity : BaseActivity() {

    data class Found(
        val url: String,
        val kind: String,
        val pageUrl: String,
        val pageTitle: String,
        val poster: String,
        val order: Long,
    )

    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var startPanel: View
    private lateinit var fullscreen: FrameLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    /** keyed by the media URL without its query string, so repeated range requests count once */
    private val found = ConcurrentHashMap<String, Found>()
    private var counter = 0L
    @Volatile private var currentPage = ""
    @Volatile private var currentTitle = ""
    private var userAgent = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_tahvo_browser)

        val root = findViewById<View>(R.id.browser_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }

        webView = findViewById(R.id.browser_webview)
        address = findViewById(R.id.browser_address)
        progress = findViewById(R.id.browser_progress)
        fab = findViewById(R.id.browser_fab)
        startPanel = findViewById(R.id.browser_start)
        fullscreen = findViewById(R.id.browser_fullscreen)

        findViewById<ImageButton>(R.id.browser_close).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.browser_reload).setOnClickListener {
            if (currentPage.isNotEmpty()) webView.reload() else go(address.text.toString())
        }
        address.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                go(address.text.toString()); true
            } else false
        }
        fab.setOnClickListener { showVideosSheet() }

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        val start = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (start.isNotBlank()) go(start) else {
            address.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
        }
        userAgent = webView.settings.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                // app links such as intent:// or tiktok:// — stay inside the browser; use the web fallback if the page gave one
                val fallback = runCatching {
                    if (scheme == "intent") Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME)
                        .getStringExtra("browser_fallback_url") else null
                }.getOrNull()
                if (!fallback.isNullOrBlank() && fallback.startsWith("http")) view.loadUrl(fallback)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (stripQuery(url) != stripQuery(currentPage)) {
                    found.clear()
                    updateFab()
                }
                currentPage = url
                currentTitle = ""
                if (!address.hasFocus()) address.setText(url)
                startPanel.isVisible = false
                fab.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                currentPage = url
                currentTitle = view.title.orEmpty()
                if (!address.hasFocus()) address.setText(url)
                scanDom()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                mediaKind(u)?.let { kind -> addFound(u, kind, "") }
                return null
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.isVisible = newProgress in 1..99
                progress.setProgressCompat(newProgress, true)
                // single-page sites (TikTok, X…) add videos without a new page load
                if (newProgress == 100) scanDom()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                currentTitle = title.orEmpty()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                fullscreen.isVisible = true
                fab.isVisible = false
            }

            override fun onHideCustomView() = hideCustomView()
        }

        // a link the page serves as a file download (Content-Disposition) is offered in the same list
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val kind = mediaKind(name) ?: if (mimeType?.startsWith("video/") == true || mimeType?.startsWith("audio/") == true) {
                name.substringAfterLast('.', "FILE").uppercase().take(4)
            } else null
            if (kind != null) {
                addFound(url, kind, "")
                showVideosSheet()
            } else {
                Toast.makeText(this, R.string.tahvo_browser_not_video, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.isVisible = false
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        fab.isVisible = currentPage.isNotEmpty()
    }

    private fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            !text.contains(' ') && Patterns.WEB_URL.matcher(text).matches() -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
        }
        address.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        startPanel.isVisible = false
        webView.loadUrl(url)
    }

    /** <video>/<source> elements and og:video tags currently in the page (blob: players are covered by the request watcher). */
    private fun scanDom() {
        val js = """
            (function(){
              var out=[];
              function add(u,p){ if(u && /^https?:/i.test(u)) out.push([u,p||'']); }
              document.querySelectorAll('video').forEach(function(v){
                add(v.currentSrc||v.src, v.poster);
                v.querySelectorAll('source').forEach(function(s){ add(s.src, v.poster); });
              });
              document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]').forEach(function(m){ add(m.content,''); });
              return JSON.stringify(out);
            })();
        """.trimIndent()
        runCatching {
            webView.evaluateJavascript(js) { raw ->
                runCatching {
                    val arr = JSONArray(JSONArray("[$raw]").getString(0))
                    for (i in 0 until arr.length()) {
                        val pair = arr.getJSONArray(i)
                        val u = pair.getString(0)
                        addFound(u, mediaKind(u) ?: "VIDEO", pair.optString(1))
                    }
                }
            }
        }
    }

    private fun addFound(url: String, kind: String, poster: String) {
        if (url.length > 4000) return
        val key = stripQuery(url)
        val existing = found[key]
        if (existing != null) {
            if (existing.poster.isEmpty() && poster.isNotEmpty()) found[key] = existing.copy(poster = poster)
            return
        }
        if (found.size >= MAX_ITEMS) return
        found[key] = Found(url, kind, currentPage, currentTitle, poster, counter++)
        runOnUiThread { updateFab() }
    }

    private fun updateFab() {
        val n = found.size
        fab.text = if (n == 0) getString(R.string.tahvo_browser_download) else getString(R.string.tahvo_browser_videos_count, n)
        if (n > 0) fab.extend()
    }

    private fun showVideosSheet() {
        val dialog = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_tahvo_page_videos, null)
        dialog.setContentView(v)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        val items = found.values.sortedBy { it.order }
        val title = currentTitle.ifBlank { Uri.parse(currentPage).host.orEmpty() }
        v.findViewById<TextView>(R.id.pv_page_title).text = title.ifBlank { getString(R.string.tahvo_browser_title) }

        val analyze = v.findViewById<MaterialCardView>(R.id.pv_analyze)
        analyze.isVisible = currentPage.startsWith("http")
        analyze.setOnClickListener {
            dialog.dismiss()
            analyzePage(currentPage)
        }

        val list = v.findViewById<LinearLayout>(R.id.pv_list)
        val download = v.findViewById<MaterialButton>(R.id.pv_download)
        val selectAll = v.findViewById<MaterialButton>(R.id.pv_select_all)
        v.findViewById<TextView>(R.id.pv_found_title).text = getString(R.string.tahvo_browser_found_title, items.size)
        v.findViewById<TextView>(R.id.pv_empty).isVisible = items.isEmpty()
        selectAll.isVisible = items.size > 1
        download.isVisible = items.isNotEmpty()

        val checks = mutableListOf<CheckBox>()
        fun refresh() {
            val n = checks.count { it.isChecked }
            download.isEnabled = n > 0
            download.text = getString(R.string.tahvo_browser_download_selected, n)
            selectAll.text = getString(if (n == checks.size) R.string.tahvo_browser_select_none else R.string.tahvo_browser_select_all)
        }
        val inflater = LayoutInflater.from(this)
        items.forEach { f ->
            val row = inflater.inflate(R.layout.item_tahvo_page_video, list, false)
            val check = row.findViewById<CheckBox>(R.id.pvi_check)
            row.findViewById<TextView>(R.id.pvi_kind).text = f.kind
            row.findViewById<TextView>(R.id.pvi_name).text = displayName(f.url)
            row.findViewById<TextView>(R.id.pvi_host).text = Uri.parse(f.url).host.orEmpty()
            // a single item is pre-selected; with several the user chooses
            check.isChecked = items.size == 1
            row.setOnClickListener { check.isChecked = !check.isChecked; refresh() }
            row.contentDescription = "${f.kind} ${displayName(f.url)}"
            checks += check
            list.addView(row)
        }
        // keep the list from pushing the button off small screens
        if (items.size > 5) {
            v.findViewById<NestedScrollView>(R.id.pv_scroll).layoutParams.height = (330 * resources.displayMetrics.density).toInt()
        }
        selectAll.setOnClickListener {
            val all = checks.all { it.isChecked }
            checks.forEach { it.isChecked = !all }
            refresh()
        }
        download.setOnClickListener {
            val chosen = items.filterIndexed { i, _ -> checks[i].isChecked }
            if (chosen.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            queue(chosen)
        }
        refresh()
        dialog.show()
    }

    /** Sends the page link through the normal TAHVO analysis (yt-dlp), which shows every video it can extract to choose from. */
    private fun analyzePage(url: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra("destination", "Analyze")
                .putExtra("url", url)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun queue(chosen: List<Found>) {
        val vm = ViewModelProvider(this)[DownloadViewModel::class.java]
        val cookies = CookieManager.getInstance()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val downloads = chosen.mapIndexed { i, f ->
                        val host = Uri.parse(f.pageUrl.ifBlank { f.url }).host.orEmpty()
                        val base = f.pageTitle.ifBlank { displayName(f.url).substringBeforeLast('.') }.ifBlank { host }
                        val name = if (chosen.size > 1) "$base (${i + 1})" else base
                        val result = vm.createEmptyResultItem(f.url).copy(
                            title = name, author = host, website = host, thumb = f.poster
                        )
                        val type = if (f.kind in AUDIO_KINDS) DownloadType.audio else DownloadType.video
                        val item = vm.createDownloadItemFromResult(result = result, givenType = type)
                        // the file server usually checks where the request comes from; send what the browser sent
                        val args = buildString {
                            if (f.pageUrl.isNotBlank()) append("--referer ").append(quote(f.pageUrl)).append(' ')
                            append("--user-agent ").append(quote(userAgent)).append(' ')
                            cookies.getCookie(f.url)?.takeIf { it.isNotBlank() }?.let {
                                append("--add-header ").append(quote("Cookie:$it")).append(' ')
                            }
                        }
                        item.extraCommands = (item.extraCommands + " " + args).trim()
                        item.customFileNameTemplate = safeFileName(name)
                        item
                    }
                    vm.queueDownloads(downloads)
                    downloads.size
                }.getOrElse { -1 }
            }
            if (ok > 0) {
                Toast.makeText(this@TahvoBrowserActivity, getString(R.string.tahvo_browser_queued, ok), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@TahvoBrowserActivity, R.string.tahvo_browser_queue_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val MAX_ITEMS = 60
        private val AUDIO_KINDS = setOf("MP3", "M4A", "AAC", "OGG", "OPUS")

        private val MEDIA_EXT = Regex("\\.(mp4|m4v|webm|mov|mkv|3gp|flv|m3u8|mpd|mp3|m4a|aac|ogg|opus)$", RegexOption.IGNORE_CASE)
        // pieces of a stream rather than a whole video
        private val SEGMENT = Regex("(^|[/_-])(seg|segment|frag|fragment|chunk|init|part)[-_]?\\d*\\.(mp4|m4v|m4a|webm)$", RegexOption.IGNORE_CASE)

        fun open(context: Context, url: String?) {
            context.startActivity(
                Intent(context, TahvoBrowserActivity::class.java)
                    .putExtra(EXTRA_URL, url.orEmpty())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /** Returns a short label for a URL that points at a media file, or null. */
        fun mediaKind(url: String): String? {
            val path = runCatching { Uri.parse(url).path }.getOrNull() ?: url.substringBefore('?')
            // YouTube's googlevideo pieces are useless on their own; such sites go through "analyze page"
            if (url.contains("googlevideo.com") || url.contains("/videoplayback")) return null
            val m = MEDIA_EXT.find(path) ?: return null
            if (SEGMENT.containsMatchIn(path)) return null
            return when (val ext = m.groupValues[1].lowercase()) {
                "m3u8" -> "HLS"
                "mpd" -> "DASH"
                else -> ext.uppercase()
            }
        }

        fun stripQuery(url: String) = url.substringBefore('#').substringBefore('?')

        fun displayName(url: String): String {
            val seg = Uri.parse(url).lastPathSegment.orEmpty()
            return Uri.decode(seg).ifBlank { url }
        }

        /** quotes a value for a yt-dlp config file */
        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

        /** a literal output template: no yt-dlp fields, no characters a file system rejects */
        fun safeFileName(name: String): String {
            val clean = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
                .replace("%", "%%").replace(Regex("\\s+"), " ").trim().take(90)
            return clean.ifBlank { "TAHVO video" }
        }
    }
}
