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
        super.onViewCreated(view, savedInstanceState)

        val pager = view.findViewById<ViewPager2>(R.id.viewPager)
        val dots = view.findViewById<TabLayout>(R.id.dots)
        val skip = view.findViewById<View>(R.id.skipBtn)
        val next = view.findViewById<View>(R.id.nextBtn)

        pager.adapter = OnboardingPagerAdapter(this)
        pager.setPageTransformer(FadeSlideTransformer())

        TabLayoutMediator(dots, pager) { _, _ -> }.attach()

        // Change button text and skip visibility
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == 2) {
                    (next as? com.google.android.material.button.MaterialButton)
                        ?.setText(R.string.get_started)
                    skip.visibility = View.INVISIBLE
                } else {
                    (next as? com.google.android.material.button.MaterialButton)
                        ?.setText(R.string.next)
                    skip.visibility = View.VISIBLE
                }
            }
        })

        skip.setOnClickListener {
            if (isAdded) {
                findNavController().navigate(R.id.action_onboarding_to_permissions)
            }
        }

        next.setOnClickListener {
            if (pager.currentItem < 2) {
                pager.currentItem++
            } else {
                if (isAdded) {
                    findNavController().navigate(R.id.action_onboarding_to_permissions)
                }
            }
        }
    }
}
