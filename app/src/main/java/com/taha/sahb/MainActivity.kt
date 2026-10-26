package com.taha.sahb

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<MaterialButton>(R.id.pick).setOnClickListener {
            picker.launch(arrayOf("image/*", "video/*", "audio/*", "*/*"))
        }
    }
}
