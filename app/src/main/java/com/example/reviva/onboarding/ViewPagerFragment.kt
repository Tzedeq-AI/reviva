package com.example.reviva.onboarding

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.example.reviva.R
import com.example.reviva.onboarding.screens.FirstScreen
import com.example.reviva.onboarding.screens.SecondScreen
import com.example.reviva.onboarding.screens.ThirdScreen

class ViewPagerFragment : Fragment(R.layout.fragment_view_pager) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val fragmentList = listOf(
            FirstScreen(),
            SecondScreen(),
            ThirdScreen()
        )

        val viewPager = view.findViewById<ViewPager2>(R.id.viewPager)

        viewPager.adapter = ViewPagerAdapter(this, fragmentList)
    }
}
