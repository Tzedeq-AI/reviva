package com.example.reviva.presentation.onboarding

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
class FadeSlideTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, pos: Float) {
        page.alpha = (1 - abs(pos * 1.2f)).coerceIn(0f, 1f)
        page.translationX = -pos * page.width * 0.15f
    }
}
