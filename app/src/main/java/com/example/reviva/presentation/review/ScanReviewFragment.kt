package com.example.reviva.presentation.review

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.example.reviva.presentation.camera.state.ScanFlowViewModel
import com.example.reviva.R


class ScanReviewFragment : Fragment() {
    private val scanViewModel: ScanFlowViewModel by navGraphViewModels(R.id.app_nav_graph)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.frag_scanreview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scanViewModel.setStatus("Reviewing")

        view.findViewById<View>(R.id.actionBtn).setOnClickListener {
            findNavController().navigate(R.id.action_review_to_processing)
        }
    }
}
