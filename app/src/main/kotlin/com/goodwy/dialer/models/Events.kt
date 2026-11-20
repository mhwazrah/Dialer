package com.goodwy.dialer.models

sealed class Events {
    data object RefreshCallLog : Events()

    class CameraUnavailable
}
