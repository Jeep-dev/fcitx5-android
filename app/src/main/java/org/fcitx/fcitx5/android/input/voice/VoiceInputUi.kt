/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.editing.TextEditingButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.gravityCenterHorizontal
import splitties.views.imageResource
import splitties.views.setPaddingDp

class VoiceInputUi(
    override val ctx: Context,
    private val theme: Theme,
    private val ripple: Boolean,
    private val border: Boolean,
    private val radius: Float
) : Ui {

    private fun button(altStyle: Boolean) =
        TextEditingButton(ctx, theme, ripple, border, radius, altStyle).apply {
            // TextEditingButton.setText adds the TextView, call it only once
            setText(android.R.string.cancel)
        }

    val micIcon = imageView {
        imageResource = R.drawable.ic_baseline_keyboard_voice_24
        imageTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val statusText = textView {
        textSize = 18f
        gravity = gravityCenter
        setTextColor(theme.keyTextColor)
    }

    val detailText = textView {
        textSize = 14f
        gravity = gravityCenter
        maxLines = 4
        setTextColor(theme.altKeyTextColor)
    }

    val levelBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        isIndeterminate = false
        progressTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
        progressBackgroundTintList = ColorStateList.valueOf(theme.dividerColor)
    }

    val secondaryButton = button(altStyle = true)

    val primaryButton = button(altStyle = false)

    private val buttonRow = horizontalLayout {
        add(secondaryButton, lParams(0, matchParent) { weight = 1f })
        add(primaryButton, lParams(0, matchParent) { weight = 1f })
    }

    @SuppressLint("ViewConstructor")
    private inner class Root : FrameLayout(ctx) {
        var onHidden: (() -> Unit)? = null

        // InputView is not detached when the keyboard hides, watch window visibility instead
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != View.VISIBLE) onHidden?.invoke()
        }
    }

    private val content = verticalLayout {
        gravity = gravityCenterHorizontal
        setPaddingDp(16, 8, 16, 8)
        add(micIcon, lParams(dp(56), dp(56)) { topMargin = dp(8) })
        add(statusText, lParams(matchParent, wrapContent) { topMargin = dp(8) })
        add(detailText, lParams(matchParent, wrapContent) { topMargin = dp(4) })
        add(levelBar, lParams(dp(160), wrapContent) { topMargin = dp(8) })
        add(buttonRow, lParams(matchParent, 0) { weight = 1f; topMargin = dp(8) })
    }

    private val rootView = Root().apply {
        add(content, lParams(matchParent, matchParent))
    }

    override val root: View = rootView

    fun setOnHiddenListener(listener: () -> Unit) {
        rootView.onHidden = listener
    }

    fun setButtons(@StringRes secondary: Int?, @StringRes primary: Int?) {
        secondaryButton.visibility = if (secondary == null) View.GONE else View.VISIBLE
        primaryButton.visibility = if (primary == null) View.GONE else View.VISIBLE
        secondary?.let { secondaryButton.textView.setText(it) }
        primary?.let { primaryButton.textView.setText(it) }
    }
}
