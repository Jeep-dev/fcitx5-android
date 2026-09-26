/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.VoicePermissionActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.startActivity
import org.mechdancer.dependency.manager.must
import timber.log.Timber
import java.util.Locale

/**
 * Records voice and transcribes it with an OpenAI-compatible cloud API,
 * then commits the result to the editor.
 */
class VoiceInputWindow : InputWindow.ExtendedInputWindow<VoiceInputWindow>(),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private val prefs = AppPrefs.getInstance().voiceInput

    private val buttonRipple by ThemeManager.prefs.keyRippleEffect
    private val buttonBorder by ThemeManager.prefs.keyBorder
    private val buttonRadius by ThemeManager.prefs.textEditingButtonRadius

    private enum class State { NeedPermission, NotConfigured, Recording, Transcribing, Error }

    private var state = State.Recording

    private var recorder: VoiceRecorder? = null
    private var job: Job? = null

    private val ui by lazy {
        VoiceInputUi(context, theme, buttonRipple, buttonBorder, buttonRadius.toFloat()).apply {
            secondaryButton.setOnClickListener { onSecondaryClick() }
            primaryButton.setOnClickListener { onPrimaryClick() }
            micIcon.setOnClickListener { if (state == State.Recording) recorder?.stop() }
            setOnHiddenListener { cancel() }
        }
    }

    override val title by lazy {
        context.getString(R.string.voice_input)
    }

    override fun onCreateView() = ui.root

    override fun onAttached() {
        start()
    }

    override fun onDetached() {
        cancel()
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        // editor changed, don't commit into another text field
        backToKeyboard()
    }

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun isConfigured() = prefs.apiUrl.getValue().isNotBlank() && prefs.model.getValue().isNotBlank()

    private fun start() {
        cancel()
        when {
            !isConfigured() -> showState(State.NotConfigured)
            !hasPermission() -> showState(State.NeedPermission)
            else -> startRecording()
        }
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun startRecording() {
        val rec = VoiceRecorder(prefs.maxDuration.getValue() * 1000L)
        recorder = rec
        showState(State.Recording)
        job = service.lifecycleScope.launch {
            val ticker = launch {
                while (isActive) {
                    updateRecordingProgress(rec)
                    delay(100L)
                }
            }
            try {
                val wav = rec.record()
                ticker.cancel()
                if (rec.elapsedMs < VoiceRecorder.MIN_DURATION_MS) {
                    showError(context.getString(R.string.voice_input_too_short))
                    return@launch
                }
                showState(State.Transcribing)
                val client = TranscriptionClient(
                    prefs.apiUrl.getValue(),
                    prefs.apiKey.getValue(),
                    prefs.model.getValue(),
                    prefs.language.getValue(),
                    prefs.prompt.getValue()
                )
                val text = client.transcribe(wav)
                if (!isActive) return@launch
                if (text.isEmpty()) {
                    showError(context.getString(R.string.voice_input_empty_result))
                    return@launch
                }
                service.commitText(text)
                backToKeyboard()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Voice input failed")
                showError(e.message ?: e.javaClass.simpleName)
            } finally {
                ticker.cancel()
            }
        }
    }

    private fun updateRecordingProgress(rec: VoiceRecorder) {
        ui.levelBar.progress = (rec.level * 100).toInt()
        ui.detailText.text = String.format(
            Locale.ROOT, "%s / %s",
            formatDuration(rec.elapsedMs), formatDuration(prefs.maxDuration.getValue() * 1000L)
        )
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun cancel() {
        job?.cancel()
        job = null
        recorder?.stop()
        recorder = null
    }

    private fun backToKeyboard() {
        cancel()
        if (windowManager.isAttached(this)) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    private fun showError(message: String) {
        showState(State.Error)
        ui.detailText.text = message
    }

    private fun showState(newState: State) {
        state = newState
        ui.levelBar.progress = 0
        ui.levelBar.visibility = if (newState == State.Recording) View.VISIBLE else View.INVISIBLE
        ui.detailText.text = ""
        when (newState) {
            State.NeedPermission -> {
                ui.statusText.setText(R.string.voice_input_need_permission)
                ui.setButtons(android.R.string.cancel, R.string.voice_input_grant)
            }
            State.NotConfigured -> {
                ui.statusText.setText(R.string.voice_input_not_configured)
                ui.setButtons(android.R.string.cancel, R.string.voice_input_open_settings)
            }
            State.Recording -> {
                ui.statusText.setText(R.string.voice_input_listening)
                ui.setButtons(android.R.string.cancel, R.string.voice_input_done)
            }
            State.Transcribing -> {
                ui.statusText.setText(R.string.voice_input_transcribing)
                ui.setButtons(android.R.string.cancel, null)
            }
            State.Error -> {
                ui.statusText.setText(R.string.voice_input_failed)
                ui.setButtons(android.R.string.cancel, R.string.voice_input_retry)
            }
        }
    }

    private fun onSecondaryClick() {
        // every state uses "cancel" as secondary action
        backToKeyboard()
    }

    private fun onPrimaryClick() {
        when (state) {
            State.NeedPermission -> {
                context.startActivity<VoicePermissionActivity> {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                backToKeyboard()
            }
            State.NotConfigured -> {
                AppUtil.launchMainToVoiceInput(context)
                backToKeyboard()
            }
            State.Recording -> recorder?.stop()
            State.Transcribing -> {}
            State.Error -> start()
        }
    }
}
