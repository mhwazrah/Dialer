package com.mhw.phone.extensions

import com.goodwy.commons.models.contacts.Contact

/** The number a call to this contact goes to without asking: the primary one or the only one; "" when the user picks. */
fun Contact.primaryNumber(): String =
    (phoneNumbers.firstOrNull { it.isPrimary } ?: phoneNumbers.singleOrNull())?.value.orEmpty()
