package com.example.reviva

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        // Install Android 12+ splash before anything else
        val splashScreen = installSplashScreen()

        // Optional: subtle fade-out when splash exits
        splashScreen.setOnExitAnimationListener { splashView ->
            splashView.view.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    splashView.remove()
                }
                .start()
        }

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}
