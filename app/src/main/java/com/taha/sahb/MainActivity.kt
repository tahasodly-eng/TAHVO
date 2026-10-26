package com.taha.sahb

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val i = Intent(this, RingActivity::class.java)
                .setAction(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) i.putExtra(Intent.EXTRA_STREAM, uris[0])
            else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // keep content clear of the status and navigation bars
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, bottom = bars.bottom + (8 * resources.displayMetrics.density).toInt(), left = bars.left, right = bars.right)
            insets
        }
        findViewById<MaterialButton>(R.id.pick).setOnClickListener { picker.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.share_hint).setOnClickListener { showHow() }
        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.m_how -> showHow()
                R.id.m_lang -> pickLanguage()
                R.id.m_about -> showAbout()
            }
            true
        }
    }

    private fun showHow() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.menu_how).setMessage(R.string.how_body)
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.menu_about)
            .setIcon(R.drawable.logo_tile)
            .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.ok, null).show()
    }

    /** Device language by default; a manual choice is stored by AndroidX and applied on every Android version. */
    private fun pickLanguage() {
        val tags = arrayOf("", "ar", "en")
        val labels = arrayOf(getString(R.string.lang_system), getString(R.string.lang_ar), getString(R.string.lang_en))
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val checked = tags.indexOfFirst { it.isNotEmpty() && current.startsWith(it) }.let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(this).setTitle(R.string.menu_language)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                AppCompatDelegate.setApplicationLocales(
                    if (tags[which].isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tags[which])
                )
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
