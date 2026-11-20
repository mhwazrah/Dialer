package com.mhw.phone.helpers

interface CameraTorchListener {
    fun onTorchEnabled(isEnabled:Boolean)

    fun onTorchUnavailable()
}
