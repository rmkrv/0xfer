package com.android.xfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import com.android.xfer.receiver.ReceiverActivity
import com.android.xfer.sender.SenderActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playLaunchAnimation()
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

    private fun playLaunchAnimation() {
        val splash = findViewById<View>(R.id.launch_splash)
        val halo = findViewById<View>(R.id.launch_halo)
        val logo = findViewById<View>(R.id.launch_logo)
        val brand = findViewById<View>(R.id.launch_brand)

        halo.alpha = 0f
        halo.scaleX = 0.82f
        halo.scaleY = 0.82f
        logo.alpha = 0f
        logo.scaleX = 0.72f
        logo.scaleY = 0.72f
        brand.alpha = 0f
        brand.translationY = 18f * resources.displayMetrics.density

        splash.post {
            halo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(720)
                .setInterpolator(DecelerateInterpolator())
                .start()
            animatePulse(findViewById(R.id.launch_pulse_one), 40L)
            animatePulse(findViewById(R.id.launch_pulse_two), 220L)
            logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(560)
                .setInterpolator(OvershootInterpolator(0.65f))
                .start()
            brand.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(190)
                .setDuration(420)
                .setInterpolator(DecelerateInterpolator())
                .start()

            splash.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    splash.animate()
                        .alpha(0f)
                        .setDuration(240)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { splash.visibility = View.GONE }
                        .start()
                }
            }, LAUNCH_SPLASH_DURATION_MS)
        }
    }

    private fun animatePulse(pulse: View, delay: Long) {
        pulse.alpha = 0f
        pulse.scaleX = 0.66f
        pulse.scaleY = 0.66f
        pulse.animate()
            .alpha(0.7f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delay)
            .setDuration(360)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                pulse.animate()
                    .alpha(0f)
                    .scaleX(1.32f)
                    .scaleY(1.32f)
                    .setDuration(520)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun openGitHub(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open GitHub.", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val LAUNCH_SPLASH_DURATION_MS = 1_150L
    }
}
