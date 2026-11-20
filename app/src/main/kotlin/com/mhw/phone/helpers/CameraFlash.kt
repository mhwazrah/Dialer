package com.mhw.phone.helpers

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.helpers.isTiramisuPlus
import com.mhw.phone.models.Events
import org.greenrobot.eventbus.EventBus

internal class CameraFlash(
    context: Context,
    private var cameraTorchListener: CameraTorchListener? = null,
) {
    private val context: Context = context.applicationContext
    private val manager = this.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId: String? = null

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != this@CameraFlash.cameraId) return
            cameraTorchListener?.onTorchEnabled(enabled)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId != this@CameraFlash.cameraId) return
            cameraTorchListener?.onTorchUnavailable()
        }
    }

    init {
        try {
            // The first camera is not necessarily the one with a flash unit (e.g. front camera listed first)
            cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            this.context.showErrorToast(e)
        }
    }

    // Returns false (after one error toast) when the torch could not be switched, so callers can stop retrying.
    fun toggleFlashlight(enable: Boolean): Boolean {
        try {
            if (supportsBrightnessControl() && enable) {
                changeTorchBrightness(getCurrentBrightnessLevel())
            } else {
                manager.setTorchMode(cameraId!!, enable)
            }
            return true
        } catch (e: Exception) {
            context.showErrorToast(e)
            val mainRunnable = Runnable {
                EventBus.getDefault().post(Events.CameraUnavailable())
            }
            Handler(context.mainLooper).post(mainRunnable)
            return false
        }
    }

    fun changeTorchBrightness(level: Int) {
        if (isTiramisuPlus()) {
            manager.turnOnTorchWithStrengthLevel(cameraId!!, level)
        }
    }

    // Static per device; a failed lookup throws and is retried next time (lazy does not cache exceptions)
    private val maxBrightnessLevel: Int by lazy {
        if (isTiramisuPlus()) {
            val characteristics = manager.getCameraCharacteristics(cameraId!!)
            characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: MIN_BRIGHTNESS_LEVEL
        } else {
            MIN_BRIGHTNESS_LEVEL
        }
    }

    fun getMaximumBrightnessLevel(): Int = maxBrightnessLevel

    fun supportsBrightnessControl(): Boolean {
        val maxBrightnessLevel = getMaximumBrightnessLevel()
        return maxBrightnessLevel > MIN_BRIGHTNESS_LEVEL
    }

    fun getCurrentBrightnessLevel(): Int {
//        var brightnessLevel = context.config.brightnessLevel
//        if (brightnessLevel == DEFAULT_BRIGHTNESS_LEVEL) {
//            brightnessLevel = getMaximumBrightnessLevel()
//        }
//        return brightnessLevel
        return getMaximumBrightnessLevel()
    }

    fun initialize() {
        manager.registerTorchCallback(torchCallback, Handler(context.mainLooper))
    }

    fun unregisterListeners() {
        manager.unregisterTorchCallback(torchCallback)
    }

    fun release() {
        cameraTorchListener = null
    }
}
