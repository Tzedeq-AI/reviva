package com.example.reviva.presentation.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.reviva.R

class PermissionsFragment : Fragment() {

    private var hasAskedOnce = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val galleryGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
            } else {
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
            }

            if (cameraGranted && galleryGranted) {
                // ✅ Navigate if granted
                findNavController().navigate(R.id.action_permissions_to_home)
            } else {
                // ❌ Denied
                hasAskedOnce = true
                Toast.makeText(requireContext(), "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.frag_permissions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.actionBtn).setOnClickListener {
            checkAndRequestPermissions()
        }
    }

    private fun checkAndRequestPermissions() {
        val cameraPermission = Manifest.permission.CAMERA
        val galleryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val permissions = arrayOf(cameraPermission, galleryPermission)

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            findNavController().navigate(R.id.action_permissions_to_home)
        } else {
            val showRationale = permissions.any { shouldShowRequestPermissionRationale(it) }

            if (showRationale) {
                // User denied once → show rationale
                AlertDialog.Builder(requireContext())
                    .setTitle("Permissions Required")
                    .setMessage("We need Camera and Gallery access to continue. Please grant them.")
                    .setPositiveButton("OK") { _, _ ->
                        requestPermissionLauncher.launch(permissions)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else if (hasAskedOnce) {
                // Permanent denial → guide to Settings
                AlertDialog.Builder(requireContext())
                    .setTitle("Permissions Permanently Denied")
                    .setMessage("Please enable permissions in Settings to continue.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", requireContext().packageName, null)
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                // First time asking → just request
                requestPermissionLauncher.launch(permissions)
            }
        }
    }
}
