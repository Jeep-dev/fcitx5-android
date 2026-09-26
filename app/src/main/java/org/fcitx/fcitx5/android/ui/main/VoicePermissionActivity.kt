/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.toast

/**
 * Input method service cannot request runtime permissions by itself,
 * so voice input launches this transparent activity to ask for microphone access.
 */
class VoicePermissionActivity : ComponentActivity() {

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                toast(R.string.voice_input_permission_granted)
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                // "don't ask again" was chosen, system won't show the dialog anymore
                toast(R.string.voice_input_permission_denied)
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
