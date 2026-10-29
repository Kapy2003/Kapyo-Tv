package com.famelack.tv.ui.settings

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.famelack.tv.R
import com.famelack.tv.data.model.Country
import com.famelack.tv.data.storage.PreferencesManager

class SettingsDialog(
    context: Context,
    private val prefs: PreferencesManager,
    private val onCountryChanged: (Country) -> Unit,
    private val onRefreshRequested: () -> Unit,
    private val onCacheCleared: () -> Unit,
    private val onCheckUpdateRequested: () -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_settings)

        findViewById<TextView>(R.id.tv_current_country_val).text =
            "${prefs.selectedCountryName} (${prefs.selectedCountryCode.uppercase()})"

        findViewById<TextView>(R.id.tv_current_resolution_val).text = prefs.maxResolution

        findViewById<Button>(R.id.btn_change_country).setOnClickListener {
            showCountryPicker()
        }

        findViewById<Button>(R.id.btn_change_resolution).setOnClickListener {
            showResolutionPicker()
        }

        findViewById<Button>(R.id.btn_refresh_playlist).setOnClickListener {
            onRefreshRequested()
            dismiss()
        }

        findViewById<Button>(R.id.btn_clear_cache).setOnClickListener {
            onCacheCleared()
            Toast.makeText(context, "Cache cleared successfully", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            dismiss()
            onCheckUpdateRequested()
        }

        findViewById<Button>(R.id.btn_about).setOnClickListener {
            showAboutDialog()
        }

        findViewById<Button>(R.id.btn_report_broken).setOnClickListener {
            reportBrokenChannel()
        }

        findViewById<Button>(R.id.btn_close_settings).setOnClickListener {
            dismiss()
        }
    }

    private fun showCountryPicker() {
        val countries = Country.getCountryList(context)
        val items = countries.map { "${it.name} (${it.channelCount} channels)" }.toTypedArray()
        val currentIdx = countries.indexOfFirst { it.code.equals(prefs.selectedCountryCode, ignoreCase = true) }
            .coerceAtLeast(0)

        AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.change_country)
            .setSingleChoiceItems(items, currentIdx) { dialog, which ->
                val selected = countries[which]
                prefs.selectedCountryCode = selected.code
                prefs.selectedCountryName = selected.name
                findViewById<TextView>(R.id.tv_current_country_val)?.text =
                    "${selected.name} (${selected.code.uppercase()})"
                onCountryChanged(selected)
                dialog.dismiss()
                this.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResolutionPicker() {
        val options = arrayOf("480p (Fastest, low bandwidth)", "720p (Recommended for 512MB RAM)", "1080p (High RAM use)")
        val resValues = arrayOf("480p", "720p", "1080p")
        val currentIdx = resValues.indexOf(prefs.maxResolution).coerceAtLeast(1)

        AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.max_resolution)
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                prefs.maxResolution = resValues[which]
                findViewById<TextView>(R.id.tv_current_resolution_val)?.text = prefs.maxResolution
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAboutDialog() {
        val verName = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName ?: "1.1.0"
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.1.0"
            }
        } catch (e: Exception) {
            "1.1.0"
        }

        AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.about_title)
            .setMessage(
                "Kapyo Tv v$verName\n\n" +
                "Engineered for ultra-resource-constrained Android TV devices (512 MB RAM, Android Go Edition).\n\n" +
                "Data Source: iptv-org/iptv (Public Domain)\n\n" +
                "Disclaimer:\n" +
                "Kapyo Tv only indexes publicly available broadcast streams and does not host, re-stream, capture, or modify any media. " +
                "All stream content and trademarks remain property of their respective broadcasters."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun reportBrokenChannel() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:support@kapyotv.app")
            putExtra(Intent.EXTRA_SUBJECT, "Report Broken IPTV Stream [${prefs.selectedCountryCode.uppercase()}]")
            putExtra(
                Intent.EXTRA_TEXT,
                "Please describe the broken channel name, stream URL, and error message.\n\n" +
                "Country: ${prefs.selectedCountryName} (${prefs.selectedCountryCode})\n"
            )
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No email client found on TV", Toast.LENGTH_SHORT).show()
        }
    }
}
