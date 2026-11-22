package com.mhw.phone.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import com.mhw.phone.extensions.startCallWithConfirmationCheck

/**
 * Invisible stop between a widget row and the call, so a widget call goes through the same SIM choice and call
 * confirmation as a tap in the app. Ends once the call screen covers it or a dialog is dismissed.
 */
class WidgetCallActivity : SimpleActivity() {
    companion object {
        private const val EXTRA_NUMBER = "widget_number"
        private const val EXTRA_NAME = "widget_name"
        private const val EXTRA_PHOTO = "widget_photo"

        // The photo travels along: the contact list may not be loaded when the app starts from a widget
        fun fillIn(number: String, name: String, photoUri: String) =
            Intent().putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_NAME, name).putExtra(EXTRA_PHOTO, photoUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        useDynamicTheme = false // keep the manifest's see-through theme: the library would swap in the opaque app theme
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(EXTRA_NUMBER)
        if (number.isNullOrEmpty() || savedInstanceState != null) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        startCallWithConfirmationCheck(number, name, photoUri = intent.getStringExtra(EXTRA_PHOTO), onCancel = ::finish)
    }

    // Invisible: no app backdrop or theme colour behind the dialog, the home screen stays in view
    override fun onResume() {
        super.onResume()
        findViewById<View>(android.R.id.content).background = null
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onStop() {
        super.onStop()
        finish()
    }
}
