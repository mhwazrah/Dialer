package com.mhw.phone.helpers

import android.net.Uri
import android.provider.ContactsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UriUtilsTest {
    private val a = Uri.parse("content://x/1")

    @Test
    fun areEqualHandlesNulls() {
        assertTrue(UriUtils.areEqual(null, null))
        assertFalse(UriUtils.areEqual(a, null))
        assertFalse(UriUtils.areEqual(null, a))
        assertTrue(UriUtils.areEqual(a, Uri.parse("content://x/1")))
        assertFalse(UriUtils.areEqual(a, Uri.parse("content://x/2")))
    }

    @Test
    fun parseAndStringify() {
        assertNull(UriUtils.parseUriOrNull(null))
        assertEquals(a, UriUtils.parseUriOrNull("content://x/1"))
        assertNull(UriUtils.uriToString(null))
        assertEquals("content://x/1", UriUtils.uriToString(a))
    }

    @Test
    fun nullForNonContactsUri() {
        val contacts = Uri.parse("content://${ContactsContract.AUTHORITY}/contacts/1")
        assertNull(UriUtils.nullForNonContactsUri(null))
        assertNull(UriUtils.nullForNonContactsUri(a))
        assertEquals(contacts, UriUtils.nullForNonContactsUri(contacts))
    }
}
