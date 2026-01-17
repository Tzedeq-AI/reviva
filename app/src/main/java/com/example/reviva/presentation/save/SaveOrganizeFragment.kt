package com.example.reviva.presentation.save

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import com.example.reviva.presentation.camera.state.ScanFlowViewModel
import com.example.reviva.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SaveOrganizeFragment : Fragment() {

    private val scanViewModel: ScanFlowViewModel
            by hiltNavGraphViewModels(R.id.app_nav_graph)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.frag_saveorganize, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scanViewModel.reset()

        view.findViewById<View>(R.id.actionBtn).setOnClickListener {
            findNavController().navigate(R.id.action_save_to_home)
        }
    }
}
