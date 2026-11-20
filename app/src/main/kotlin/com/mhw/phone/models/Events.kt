package com.mhw.phone.models

sealed class Events {
    data object RefreshCallLog : Events()

    class CameraUnavailable
}
