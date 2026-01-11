package com.example.reviva.presentation.main

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.reviva.R

class MainActivity : AppCompatActivity() {

    companion object {
        // Minimum time to keep the splash screen visible so the brand is seen
        // and the transition does not appear as a flicker on fast devices.
        private const val MIN_SPLASH_DURATION_MS: Long = 1400L
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        val splashScreen = installSplashScreen()
        val startTime = SystemClock.elapsedRealtime()

        // Keep splash visible for at least MIN_SPLASH_DURATION_MS on all supported Android versions
        splashScreen.setKeepOnScreenCondition {
            SystemClock.elapsedRealtime() - startTime < MIN_SPLASH_DURATION_MS
        }

        // Fade out only on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashView ->
                splashView.view.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction {
                        splashView.remove()
                    }
                    .start()
            }
        }

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)


    }
}