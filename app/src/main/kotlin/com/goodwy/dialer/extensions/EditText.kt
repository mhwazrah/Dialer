package com.goodwy.dialer.extensions

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.EditText

fun EditText.addCharacter(char: Char) {
    val keyCode = getCharKeyCode(char)
    if (keyCode == null) {
        text.insert(selectionStart.coerceAtLeast(0), char.toString())
        return
    }
    dispatchKeyEvent(getKeyEvent(keyCode))
    dispatchKeyEvent(getKeyEvent(keyCode, KeyEvent.ACTION_UP))
}

fun EditText.getKeyEvent(keyCode: Int, action: Int = KeyEvent.ACTION_DOWN): KeyEvent {
    val now = SystemClock.uptimeMillis()
    return KeyEvent(now, now, action, keyCode, 0)
}

private fun getCharKeyCode(char: Char) = when (char) {
    '0' -> KeyEvent.KEYCODE_0
    '1' -> KeyEvent.KEYCODE_1
    '2' -> KeyEvent.KEYCODE_2
    '3' -> KeyEvent.KEYCODE_3
    '4' -> KeyEvent.KEYCODE_4
    '5' -> KeyEvent.KEYCODE_5
    '6' -> KeyEvent.KEYCODE_6
    '7' -> KeyEvent.KEYCODE_7
    '8' -> KeyEvent.KEYCODE_8
    '9' -> KeyEvent.KEYCODE_9
    '*' -> KeyEvent.KEYCODE_STAR
    ',' -> KeyEvent.KEYCODE_NUMPAD_COMMA
    '+' -> KeyEvent.KEYCODE_PLUS
    ';' -> KeyEvent.KEYCODE_SEMICOLON
    '#' -> KeyEvent.KEYCODE_POUND
    else -> null
}

fun EditText.disableKeyboard() {
    showSoftInputOnFocus = false
}
