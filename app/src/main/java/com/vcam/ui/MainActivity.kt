package com.vcam.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vcam.R
import com.vcam.databinding.ActivityMainBinding
import com.vcam.service.VCamService
import com.vcam.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setMediaUri(it, this) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> viewModel.initRoot() }

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* user returns */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupObservers()
        setupClicks()
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-check code every time app comes to foreground
        lifecycleScope.launch { checkCodeStillValid() }
    }

    // ── Code re-verification ──────────────────────────────────────────

    private suspend fun checkCodeStillValid() {
        val prefs = getSharedPreferences("vcam_gate", Context.MODE_PRIVATE)
        val stored = prefs.getString("access_code", null) ?: run {
            logout(); return
        }
        val content = CodeGateActivity.fetchAllCod() ?: return // offline → allow
        if (!CodeGateActivity.codeExistsIn(content, stored)) {
            withContext(Dispatchers.Main) { logout() }
        }
    }

    private fun logout() {
        getSharedPreferences("vcam_gate", Context.MODE_PRIVATE).edit()
            .remove("access_code").apply()
        stopVCamService()
        startActivity(Intent(this, CodeGateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    // ── Observers ─────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.mediaUri.observe(this) { uri ->
            if (uri != null) {
                binding.ivMediaPreview.setImageURI(uri)
                binding.ivMediaPreview.visibility   = View.VISIBLE
                binding.layoutPlaceholder.visibility = View.GONE
                binding.btnClearMedia.visibility     = View.VISIBLE
                binding.tvMediaBadge.visibility      = View.VISIBLE
                binding.tvMediaBadge.text =
                    if (viewModel.isVideo.value == true) "VIDEO" else "IMAGE"
                setStartEnabled(true)
            } else {
                binding.ivMediaPreview.visibility    = View.GONE
                binding.layoutPlaceholder.visibility = View.VISIBLE
                binding.btnClearMedia.visibility     = View.GONE
                binding.tvMediaBadge.visibility      = View.GONE
                setStartEnabled(false)
            }
        }

        viewModel.isServiceRunning.observe(this) { running ->
            if (running) {
                binding.btnStartStop.text       = getString(R.string.stop_vcam)
                binding.btnStartStop.setTextColor(0xFFFF3B3B.toInt())
                binding.tvHint.visibility       = View.VISIBLE
                binding.tvHint.text             = getString(R.string.injection_active)
            } else {
                binding.btnStartStop.text       = getString(R.string.start_vcam)
                binding.btnStartStop.setTextColor(0xFF000000.toInt())
                binding.tvHint.visibility       = View.GONE
            }
        }

        viewModel.rootStatus.observe(this) { ok ->
            binding.tvRootStatus.text = if (ok) "Rooted ✓" else "No Root ✗"
            binding.tvRootStatus.setTextColor(
                ContextCompat.getColor(this, if (ok) R.color.color_root_ok else R.color.color_root_fail)
            )
        }
    }

    // ── Click listeners ───────────────────────────────────────────────

    private fun setupClicks() {
        binding.btnPickImage.setOnClickListener  { pickMedia.launch("image/*") }
        binding.btnPickVideo.setOnClickListener  { pickMedia.launch("video/*") }
        binding.btnClearMedia.setOnClickListener { viewModel.clearMedia() }

        binding.btnStartStop.setOnClickListener {
            if (viewModel.isServiceRunning.value == true) {
                stopVCamService()
            } else {
                handleStart()
            }
        }
    }

    private fun setStartEnabled(enabled: Boolean) {
        binding.btnStartStop.alpha   = if (enabled) 1f else 0.4f
        binding.btnStartStop.isEnabled = enabled
    }

    // ── Start/stop ────────────────────────────────────────────────────

    private fun handleStart() {
        val mediaUri = viewModel.mediaUri.value ?: return
        checkOverlayThenStart(mediaUri)
    }

    private fun checkOverlayThenStart(mediaUri: Uri) {
        if (!Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this, R.style.Theme_VCam)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_msg)
                .setPositiveButton(R.string.grant) { _, _ ->
                    overlayPermLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                               Uri.parse("package:$packageName"))
                    )
                    doStartService(mediaUri)
                }
                .setNegativeButton(R.string.skip) { _, _ -> doStartService(mediaUri) }
                .show()
        } else {
            doStartService(mediaUri)
        }
    }

    private fun doStartService(mediaUri: Uri) {
        val intent = Intent(this, VCamService::class.java).apply {
            action = VCamService.ACTION_START
            putExtra(VCamService.EXTRA_MEDIA_URI, mediaUri.toString())
            putExtra(VCamService.EXTRA_IS_VIDEO, viewModel.isVideo.value == true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        viewModel.setServiceRunning(true)
    }

    private fun stopVCamService() {
        startService(Intent(this, VCamService::class.java).apply {
            action = VCamService.ACTION_STOP
        })
        viewModel.setServiceRunning(false)
    }

    // ── Permissions ───────────────────────────────────────────────────

    private fun requestPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else viewModel.initRoot()
    }
}
