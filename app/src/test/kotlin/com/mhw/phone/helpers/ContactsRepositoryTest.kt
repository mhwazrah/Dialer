package com.mhw.phone.helpers

import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.contacts.Address
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.contacts.Email
import com.goodwy.commons.models.contacts.IM
import com.goodwy.commons.models.contacts.Organization
import com.mhw.phone.helpers.ContactsRepository.ListSearchKeys
import com.mhw.phone.helpers.ContactsRepository.SearchKeys
import com.mhw.phone.helpers.ContactsRepository.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ContactsRepositoryTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadLanguages() {
            DialpadT9.readFromJson(File("src/main/res/raw/t9languages.json").readText())
        }
    }

    private fun contact(
        id: Int = 1,
        first: String = "",
        surname: String = "",
        nickname: String = "",
        numbers: List<PhoneNumber> = emptyList(),
        company: String = "",
        job: String = "",
        notes: String = "",
        addresses: List<String> = emptyList(),
        emails: List<String> = emptyList(),
        ims: List<String> = emptyList(),
        websites: List<String> = emptyList()
    ) = Contact(
        id = id, prefix = "", firstName = first, middleName = "", surname = surname, suffix = "", nickname = nickname,
        photoUri = "", phoneNumbers = ArrayList(numbers),
        emails = ArrayList(emails.map { Email(it, 0, "") }),
        addresses = ArrayList(addresses.map { Address(it, 0, "", "", "", "", "", "", "", "") }),
        events = ArrayList(), source = "", starred = 0, contactId = id, thumbnailUri = "", photo = null, notes = notes,
        groups = ArrayList(), organization = Organization(company, job), websites = ArrayList(websites),
        relations = ArrayList(), IMs = ArrayList(ims.map { IM(it, 0, "") }), mimetype = "", ringtone = ""
    )

    private fun phone(value: String, normalized: String = "") = PhoneNumber(value, 0, "", normalized, false)

    // ---- Snapshot.find

    @Test
    fun findBlankIsNull() {
        val snapshot = Snapshot(listOf(contact(numbers = listOf(phone("0501234567")))), mapOf("0501234567" to contact()), emptyList())
        assertNull(snapshot.find(""))
        assertNull(snapshot.find("   "))
    }

    @Test
    fun findUsesIndexHitFirst() {
        val indexed = contact(id = 1, first = "Indexed", numbers = listOf(phone("0501234567")))
        val other = contact(id = 2, first = "Other", numbers = listOf(phone("0501234567")))
        val snapshot = Snapshot(listOf(other, indexed), mapOf("0501234567" to indexed), emptyList())
        assertSame(indexed, snapshot.find("0501234567"))
    }

    @Test
    fun findNormalizesFormattingBeforeIndexLookup() {
        val indexed = contact(first = "Indexed", numbers = listOf(phone("0501234567")))
        val snapshot = Snapshot(emptyList(), mapOf("0501234567" to indexed), emptyList())
        assertSame(indexed, snapshot.find("050 123-4567"))
    }

    @Test
    fun findFallsBackToCommonsMatchingWhenIndexMisses() {
        val national = contact(first = "Ali", numbers = listOf(phone("07911123456")))
        val snapshot = Snapshot(listOf(national), emptyMap(), emptyList())
        // commons only knows a few country prefixes (+44, +31, ...): a dialled +44... is matched against a stored 0...
        assertSame(national, snapshot.find("+447911123456"))
    }

    // A Saudi contact saved as 05... is indexed by the provider's E.164 form, so a +966 call matches it
    @Test
    fun findMatchesSaudiInternationalCallToNationalContactThroughTheE164Index() {
        val saved = contact(numbers = listOf(phone("0501234567", normalized = "+966501234567")))
        val snapshot = Snapshot(listOf(saved), mapOf("+966501234567" to saved), emptyList())
        assertSame(saved, snapshot.find("+966 50 123 4567"))
        assertSame(saved, snapshot.find("+966501234567"))
    }

    @Test
    fun findReturnsNullWhenNothingMatches() {
        val snapshot = Snapshot(listOf(contact(numbers = listOf(phone("0501234567")))), emptyMap(), emptyList())
        assertNull(snapshot.find("0559999999"))
    }

    @Test
    fun listSearchKeysIsCachedForSnapshotContactsAndComputedForOthers() {
        val inSnapshot = contact(nickname = "Ábc")
        val snapshot = Snapshot(listOf(inSnapshot), emptyMap(), emptyList())
        snapshot.buildListSearchKeys()
        assertSame(snapshot.listSearchKeys(inSnapshot), snapshot.listSearchKeys(inSnapshot))
        val outside = contact(id = 9, nickname = "Éfg")
        assertEquals("Efg", snapshot.listSearchKeys(outside).nickname)
    }

    // ---- ListSearchKeys

    @Test
    fun listSearchKeysFoldAccentsAndArabic() {
        val keys = ListSearchKeys.of(
            contact(
                nickname = "José", notes = "Café", company = "Müller", job = "أستاذ",
                addresses = listOf("Rue Édith", "شارع الأمير")
            )
        )
        assertEquals("Jose", keys.nickname)
        assertEquals("Cafe", keys.notes)
        assertEquals("Muller", keys.company)
        assertEquals("استاذ", keys.jobPosition)
        assertEquals(listOf("Rue Edith", "شارع الامير"), keys.addresses)
    }

    private fun matches(contact: Contact, query: String, normalize: Boolean) =
        ListSearchKeys.of(contact).matchesSecondaryFields(contact, query, normalize)

    private val accented
        get() = contact(
            nickname = "José", notes = "Café", company = "Müller", job = "Ingénieur",
            addresses = listOf("Rue Édith")
        )

    @Test
    fun normalizedModeMatchesAccentStrippedFields() {
        for (q in listOf("jose", "cafe", "muller", "ingenieur", "edith")) assertTrue(q, matches(accented, q, true))
        assertFalse(matches(accented, "xyz", true))
    }

    @Test
    fun rawModeMatchesStoredTextOnly() {
        for (q in listOf("josé", "café", "müller", "ingénieur", "édith")) assertTrue(q, matches(accented, q, false))
        for (q in listOf("jose", "cafe", "muller", "ingenieur", "edith")) assertFalse(q, matches(accented, q, false))
    }

    @Test
    fun arabicFoldingAppliesInNormalizedModeOnly() {
        val c = contact(nickname = "أحمد") // hamza-alef ahmad
        assertTrue(matches(c, "احمد", true))
        assertFalse(matches(c, "احمد", false))
        assertTrue(matches(c, "أحمد", false))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(matches(contact(company = "ACME Corp"), "acme", true))
        assertTrue(matches(contact(company = "ACME Corp"), "acme", false))
    }

    @Test
    fun eachFieldMatchesOnItsOwn() {
        assertTrue(matches(contact(nickname = "zork"), "zor", true))
        assertTrue(matches(contact(addresses = listOf("12 Elm Street")), "elm", true))
        assertTrue(matches(contact(emails = listOf("a@b.com")), "a@b", true))
        assertTrue(matches(contact(ims = listOf("skype:ali")), "skype", true))
        assertTrue(matches(contact(notes = "vip client"), "vip", true))
        assertTrue(matches(contact(company = "Initech"), "tech", true))
        assertTrue(matches(contact(job = "Manager"), "manag", true))
        assertTrue(matches(contact(websites = listOf("https://example.org")), "example", true))
    }

    @Test
    fun emailsImsAndWebsitesAreMatchedAsStoredEvenWhenNormalizing() {
        val c = contact(emails = listOf("josé@x.com"), ims = listOf("josé"), websites = listOf("https://josé.com"))
        assertFalse(matches(c, "jose", true))
        assertTrue(matches(c, "josé", true))
    }

    @Test
    fun emptyContactMatchesNothing() {
        assertFalse(matches(contact(), "a", true))
        assertFalse(matches(contact(), "a", false))
    }

    // ---- SearchKeys

    @Test
    fun searchKeysCoverNameNicknameAndCompany() {
        val keys = SearchKeys.of(contact(first = "Ahmed", nickname = "Bob", company = "Acme"), LOCALE_EN)
        assertTrue("24633" in keys.matchKeys)
        assertTrue("262" in keys.matchKeys)
        assertTrue("2263" in keys.matchKeys)
    }

    @Test
    fun searchKeysDropEmptyAndDedupe() {
        // no nickname, no company: only the name keys; name and display name are identical so they collapse
        val keys = SearchKeys.of(contact(first = "Ahmed"), LOCALE_EN)
        assertEquals(listOf("24633"), keys.matchKeys)
    }

    @Test
    fun searchKeysAddDigitsOnlyFormWhenNameHasSeparators() {
        val keys = SearchKeys.of(contact(first = "John", surname = "Smith"), LOCALE_EN)
        assertTrue("564676484" in keys.matchKeys)
        assertEquals("564676484", keys.displayNameDigits)
        assertTrue(keys.matchKeys.any { it.contains(' ') })
        assertEquals(keys.matchKeys.size, keys.matchKeys.toSet().size)
    }

    @Test
    fun searchKeysOfEmptyContactAreEmpty() {
        val keys = SearchKeys.of(contact(), LOCALE_EN)
        assertTrue(keys.matchKeys.isEmpty())
        assertEquals("", keys.displayNameDigits)
    }

    @Test
    fun searchKeysUseSecondaryLanguage() {
        val keys = SearchKeys.of(contact(first = "محمد"), "ar")
        assertTrue("8685" in keys.matchKeys)
        assertEquals("8685", keys.displayNameDigits)
    }

    @Test
    fun snapshotSearchKeysAreBuiltOncePerLanguage() {
        val c = contact(first = "Ahmed")
        val snapshot = Snapshot(listOf(c), emptyMap(), emptyList())
        val first = snapshot.searchKeys(LOCALE_EN)
        assertSame(first, snapshot.searchKeys(LOCALE_EN))
        assertEquals(listOf("24633"), first.getValue(c).matchKeys)
    }
}
