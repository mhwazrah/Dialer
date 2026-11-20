package com.mhw.phone.dialogs

import android.app.Activity
import android.content.DialogInterface.BUTTON_POSITIVE
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.showKeyboard
import com.mhw.phone.R
import com.mhw.phone.databinding.DialogAddSpeedDialBinding
import com.mhw.phone.models.SpeedDial

class AddSpeedDialDialog(
    private val activity: Activity,
    private val speedDial: SpeedDial,
    private val callback: (name: String) -> Unit,
) {
    init {
        val binding = DialogAddSpeedDialBinding.inflate(activity.layoutInflater).apply {
            addSpeedDialEditText.apply {
                setText(speedDial.number)
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok, null)
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.speed_dial) { alertDialog ->
                    alertDialog.showKeyboard(binding.addSpeedDialEditText)
                    alertDialog.getButton(BUTTON_POSITIVE).apply {
                        setOnClickListener {
                            val newNumber = binding.addSpeedDialEditText.text.toString().trim()
                            callback(newNumber)
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }
}
