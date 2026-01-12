package com.example.reviva.presentation.onboarding

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.example.reviva.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class OnboardingHostFragment : Fragment(R.layout.fragment_onboarding_host) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val pager = view.findViewById<ViewPager2>(R.id.viewPager)
        val dots = view.findViewById<TabLayout>(R.id.dots)
        val skip = view.findViewById<View>(R.id.skipBtn)
        val next = view.findViewById<View>(R.id.nextBtn)

        pager.adapter = OnboardingPagerAdapter(this)

        TabLayoutMediator(dots, pager) { _, _ -> }.attach()

        pager.setPageTransformer(FadeSlideTransformer())

        skip.setOnClickListener {
            findNavController().navigate(R.id.action_onboarding_to_permissions)
        }

        next.setOnClickListener {
            if (pager.currentItem < 2) pager.currentItem++
            else findNavController().navigate(R.id.action_onboarding_to_permissions)
        }
    }
}
