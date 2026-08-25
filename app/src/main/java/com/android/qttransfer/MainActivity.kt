package com.android.qttransfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import com.android.qttransfer.receiver.ReceiverActivity
import com.android.qttransfer.sender.SenderActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.hub_send).setOnClickListener {
            startActivity(Intent(this, SenderActivity::class.java))
        }
        findViewById<Button>(R.id.hub_receive).setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
        }
        findViewById<Button>(R.id.hub_receive_hcc2d).setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java).putExtra(ReceiverActivity.EXTRA_HCC2D, true))
        }
        findViewById<TextView>(R.id.hub_license).apply {
            text = HtmlCompat.fromHtml(
                getString(R.string.footer_license_link),
                HtmlCompat.FROM_HTML_MODE_LEGACY,
            )
            movementMethod = LinkMovementMethod.getInstance()
        }
        findViewById<TextView>(R.id.hub_github).setOnClickListener {
            openGitHub("https://github.com/rmkrv")
        }
    }

    private fun openGitHub(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open GitHub.", Toast.LENGTH_SHORT).show()
        }
    }
}
