package com.nodeterm.android.ui

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * Speeds up IME show/hide. The Compose `SoftwareKeyboardController` schedules the request onto the
 * UI thread, so the keyboard can lag a frame or two behind the tap. Calling the platform
 * [InputMethodManager] synchronously pops and dismisses the keyboard as fast as the system allows —
 * with no synthetic frame delay.
 */
internal object FastKeyboard {

    /**
     * Instantly request the soft keyboard for [view] (the current window). The focused editable
     * field (the hidden IME carrier) is already focused before this is called.
     */
    fun show(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Instantly dismiss the soft keyboard for the window [view] belongs to (no wrap-up animation). */
    fun hide(view: View) {
        val token = view.windowToken ?: return
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }
}
