package com.example.reviva.presentation.main

import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.reviva.R

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MIN_SPLASH_DURATION_MS: Long = 1400L
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        val splashScreen = installSplashScreen()
        val startTime = SystemClock.elapsedRealtime()

        splashScreen.setKeepOnScreenCondition {
            SystemClock.elapsedRealtime() - startTime < MIN_SPLASH_DURATION_MS
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
