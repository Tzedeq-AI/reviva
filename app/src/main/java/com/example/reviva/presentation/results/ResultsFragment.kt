package com.example.reviva.presentation.results

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.example.reviva.presentation.camera.state.ScanFlowViewModel
import com.example.reviva.R


class ResultsFragment : Fragment() {
    private val scanViewModel: ScanFlowViewModel by navGraphViewModels(R.id.app_nav_graph)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.frag_results, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scanViewModel.setStatus("Results Ready")

        view.findViewById<View>(R.id.actionBtn).setOnClickListener {
            findNavController().navigate(R.id.action_results_to_save)
        }
    }
}
