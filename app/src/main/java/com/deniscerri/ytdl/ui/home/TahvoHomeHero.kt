package com.deniscerri.ytdl.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.HistoryItem
import com.deniscerri.ytdl.util.Extensions.loadThumbnail
import com.deniscerri.ytdl.util.FileUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * TAHVO home screen block shown when there are no results on screen.
 * It only drives existing app features: the analyze button hands the link to the
 * normal extraction pipeline, and recent downloads are read from the history database.
 */
class TahvoHomeHero(
    private val root: View,
    private val lifecycleOwner: LifecycleOwner,
    private val onAnalyze: (String) -> Unit,
    private val onKeywordSearch: (String) -> Unit,
    private val onViewAllDownloads: () -> Unit,
) {
    private val context: Context = root.context
    private val urlLayout: TextInputLayout = root.findViewById(R.id.tahvo_url_layout)
    private val urlInput: TextInputEditText = root.findViewById(R.id.tahvo_url_input)
    private val recentList: LinearLayout = root.findViewById(R.id.tahvo_recent_list)
    private val recentEmpty: TextView = root.findViewById(R.id.tahvo_recent_empty)

    private data class Platform(val badge: String, val name: String, val url: String)

    // Shortcuts open the platform so the user can copy a video link; they make no compatibility promise beyond what yt-dlp supports.
    private val platforms = listOf(
        Platform("T", "TikTok", "https://www.tiktok.com/"),
        Platform("Y", "YouTube", "https://m.youtube.com/"),
        Platform("I", "Instagram", "https://www.instagram.com/"),
        Platform("F", "Facebook", "https://m.facebook.com/watch/"),
        Platform("X", "X", "https://x.com/"),
    )

    fun bind() {
        root.findViewById<MaterialButton>(R.id.tahvo_paste_btn).setOnClickListener { pasteFromClipboard() }
        root.findViewById<MaterialButton>(R.id.tahvo_analyze_btn).setOnClickListener { analyze() }
        root.findViewById<MaterialButton>(R.id.tahvo_keyword_search_btn).setOnClickListener {
            onKeywordSearch(urlInput.text?.toString().orEmpty().trim())
        }
        root.findViewById<MaterialButton>(R.id.tahvo_view_all_btn).setOnClickListener { onViewAllDownloads() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                analyze(); true
            } else false
        }
        urlInput.setOnFocusChangeListener { _, _ -> urlLayout.error = null }
        buildPlatforms()
        observeRecentDownloads()
    }

    fun setVisible(visible: Boolean) {
        root.isVisible = visible
    }

    private fun analyze() {
        val text = urlInput.text?.toString().orEmpty().trim()
        when {
            text.isEmpty() -> urlLayout.error = context.getString(R.string.tahvo_empty_url)
            !isValidLink(text) -> urlLayout.error = context.getString(R.string.tahvo_invalid_url)
            else -> {
                urlLayout.error = null
                onAnalyze(normalize(text))
            }
        }
    }

    private fun isValidLink(text: String): Boolean {
        val candidate = normalize(text)
        if (candidate.contains(' ')) return false
        return Patterns.WEB_URL.matcher(candidate).matches() &&
            (candidate.startsWith("http://") || candidate.startsWith("https://"))
    }

    /** Accept links pasted without a scheme, e.g. "vt.tiktok.com/abc". */
    private fun normalize(text: String): String {
        val t = text.trim()
        return if (!t.startsWith("http://") && !t.startsWith("https://") && Patterns.WEB_URL.matcher(t).matches()) "https://$t" else t
    }

    private fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(context, R.string.tahvo_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        // keep only the first link if the clipboard holds a share text such as "Watch this! https://..."
        val link = Regex("https?://\\S+").find(text)?.value ?: text
        urlInput.setText(link)
        urlInput.setSelection(urlInput.length())
        urlLayout.error = null
    }

    private fun buildPlatforms() {
        val grid = root.findViewById<GridLayout>(R.id.tahvo_platforms_grid)
        grid.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val items = platforms + Platform("+", context.getString(R.string.tahvo_more_sites), "https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md")
        items.forEach { p ->
            val v = inflater.inflate(R.layout.tahvo_platform_item, grid, false)
            v.findViewById<TextView>(R.id.tahvo_platform_badge).text = p.badge
            v.findViewById<TextView>(R.id.tahvo_platform_name).text = p.name
            v.contentDescription = p.name
            v.setOnClickListener { openUrl(p.url) }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(v, params)
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun observeRecentDownloads() {
        val dao = DBManager.getInstance(context).historyDao
        lifecycleOwner.lifecycleScope.launch {
            dao.getAllHistory()
                .map { list -> list.sortedByDescending { it.time }.take(3) }
                .collectLatest { renderRecent(it) }
        }
    }

    private fun renderRecent(items: List<HistoryItem>) {
        recentList.removeAllViews()
        recentEmpty.isVisible = items.isEmpty()
        val inflater = LayoutInflater.from(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val hideThumb = prefs.getStringSet("hide_thumbnails", emptySet())!!.contains("downloads")
        items.forEach { item ->
            val v = inflater.inflate(R.layout.tahvo_recent_item, recentList, false)
            v.findViewById<TextView>(R.id.tahvo_recent_title).text = item.title.ifBlank { item.url }
            val size = if (item.filesize > 1) FileUtil.convertFileSize(item.filesize) else null
            val quality = item.format.format_note.takeIf { it.isNotBlank() }
            v.findViewById<TextView>(R.id.tahvo_recent_meta).text =
                listOfNotNull(quality, size, item.website.takeIf { it.isNotBlank() }).joinToString(" · ")
            v.findViewById<ImageView>(R.id.tahvo_recent_type).setImageResource(
                when (item.type) {
                    DownloadType.audio -> R.drawable.ic_music
                    DownloadType.video -> R.drawable.ic_video
                    else -> R.drawable.ic_terminal
                }
            )
            v.findViewById<ImageView>(R.id.tahvo_recent_thumb).loadThumbnail(hideThumb, item.thumb)
            v.setOnClickListener {
                val path = item.downloadPath.firstOrNull { FileUtil.exists(it) }
                if (path == null) {
                    Toast.makeText(context, R.string.tahvo_file_missing, Toast.LENGTH_SHORT).show()
                } else {
                    FileUtil.openFileIntent(context, path)
                }
            }
            recentList.addView(v)
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
