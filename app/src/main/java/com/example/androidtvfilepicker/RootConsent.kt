package com.example.androidtvfilepicker

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast

/** Dialogs that ask the user for consent before the app uses root. */
object RootConsent {

    /** Asks the user to turn on root support. Calls [onEnabled] if root access works. */
    fun askEnable(activity: Activity, onEnabled: () -> Unit) {
        show(
            activity,
            R.string.root_consent_title,
            activity.getString(R.string.root_consent_message),
            R.string.root_consent_enable
        ) { enable(activity, onEnabled) }
    }

    /** Asks the user to share protected files with the calling app. */
    fun askShare(activity: Activity, callerLabel: String, onAllowed: () -> Unit) {
        show(
            activity,
            R.string.root_share_title,
            activity.getString(R.string.root_share_message, callerLabel),
            R.string.root_share_allow,
            onAllowed
        )
    }

    private fun show(
        activity: Activity,
        title: Int,
        message: String,
        positive: Int,
        onPositive: () -> Unit
    ) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onPositive() }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        // The safe choice has focus at the start.
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
        dialog.show()
    }

    private fun enable(activity: Activity, onEnabled: () -> Unit) {
        Thread {
            // The root manager can show a request to the user here.
            val available = RootShell.isRootAvailable()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (available) {
                    RootAccess.setEnabled(activity, true)
                    Toast.makeText(activity, R.string.root_enabled_toast, Toast.LENGTH_SHORT).show()
                    onEnabled()
                } else {
                    Toast.makeText(activity, R.string.root_denied, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
