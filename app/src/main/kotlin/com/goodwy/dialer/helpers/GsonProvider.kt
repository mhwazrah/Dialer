package com.goodwy.dialer.helpers

import com.google.gson.Gson

/** The one process-wide Gson: instances cache reflective type adapters, so sharing avoids rebuilding them per call. */
val gson: Gson = Gson()
