package com.personal.autoclicker

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personal.autoclicker.data.AppSettings
import com.personal.autoclicker.data.PresetStore
import com.personal.autoclicker.databinding.ActivityMainBinding
import com.personal.autoclicker.model.Preset
import com.personal.autoclicker.service.AutoClickService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appSettings: AppSettings
    private lateinit var presetStore: PresetStore
    private lateinit var adapter: PresetAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettings(this)
        presetStore = PresetStore(this)

        adapter = PresetAdapter(
            onRename = { preset -> showRenameDialog(preset) },
            onDelete = { preset -> showDeleteDialog(preset) }
        )
        binding.rvPresets.layoutManager = LinearLayoutManager(this)
        binding.rvPresets.adapter = adapter

        binding.btnOverlayPermission.setOnClickListener { onOverlayPermissionClicked() }
        binding.btnAccessibilityPermission.setOnClickListener { onAccessibilityPermissionClicked() }
        binding.btnToggleOverlay.setOnClickListener { onToggleOverlayClicked() }

        binding.switchAntiDetection.isChecked = appSettings.antiDetection
        binding.switchAntiDetection.setOnCheckedChangeListener { _, isChecked ->
            appSettings.antiDetection = isChecked
        }
    }

    override fun onStart() {
        super.onStart()
        AutoClickService.onStateChanged = { refreshUi() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        refreshPresets()
    }

    override fun onStop() {
        AutoClickService.onStateChanged = null
        super.onStop()
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        if (accessibilityEnabled != 1) return false
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(this, AutoClickService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component != null && component == expected) return true
        }
        return false
    }

    private fun refreshUi() {
        val overlayGranted = hasOverlayPermission()
        val accessibilityGranted = isAccessibilityServiceEnabled()

        binding.btnOverlayPermission.setText(
            if (overlayGranted) R.string.btn_overlay_granted else R.string.btn_overlay_grant
        )
        binding.btnAccessibilityPermission.setText(
            if (accessibilityGranted) R.string.btn_accessibility_granted else R.string.btn_accessibility_grant
        )

        val allGranted = overlayGranted && accessibilityGranted
        binding.btnToggleOverlay.isEnabled = allGranted
        val overlayShowing = AutoClickService.instance?.isOverlayVisible == true
        binding.btnToggleOverlay.setText(
            when {
                !allGranted -> R.string.btn_toggle_locked
                overlayShowing -> R.string.btn_toggle_off
                else -> R.string.btn_toggle_on
            }
        )
    }

    private fun refreshPresets() {
        val presets = presetStore.getAll()
        adapter.submit(presets)
        binding.tvPresetsEmpty.visibility =
            if (presets.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun onOverlayPermissionClicked() {
        if (hasOverlayPermission()) {
            Toast.makeText(this, R.string.toast_overlay_granted, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_overlay_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun onAccessibilityPermissionClicked() {
        if (isAccessibilityServiceEnabled()) {
            Toast.makeText(this, R.string.toast_accessibility_granted, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.toast_accessibility_guide, Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_overlay_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun onToggleOverlayClicked() {
        if (!hasOverlayPermission() || !isAccessibilityServiceEnabled()) {
            refreshUi()
            return
        }
        val service = AutoClickService.instance
        if (service == null) {
            Toast.makeText(this, R.string.toast_service_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        service.toggleOverlay()
        refreshUi()
    }

    private fun showRenameDialog(preset: Preset) {
        val input = EditText(this).apply {
            setText(preset.name)
            setSelectAllOnFocus(true)
            setSingleLine(true)
            hint = getString(R.string.preset_rename_hint)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preset_rename_title)
            .setView(container)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val renamed = presetStore.rename(preset.name, input.text.toString())
                if (!renamed) {
                    Toast.makeText(this, R.string.toast_name_invalid, Toast.LENGTH_SHORT).show()
                }
                refreshPresets()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeleteDialog(preset: Preset) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preset_delete_title)
            .setMessage(getString(R.string.preset_delete_message, preset.name))
            .setPositiveButton(R.string.action_ok) { _, _ ->
                presetStore.delete(preset.name)
                refreshPresets()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
