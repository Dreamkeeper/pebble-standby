package org.cryomonitor.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class EmergencyNumberTest {
    @Test
    fun `country defaults`() {
        assertEquals("112", EmergencyNumber.forCountry("de"))
        assertEquals("112", EmergencyNumber.forCountry("RU"))
        assertEquals("911", EmergencyNumber.forCountry("us"))
        assertEquals("999", EmergencyNumber.forCountry("GB"))
        assertEquals("000", EmergencyNumber.forCountry("AU"))
        assertEquals("112", EmergencyNumber.forCountry(null))
        assertEquals("112", EmergencyNumber.forCountry(""))
    }

    @Test
    fun `override wins, blank override does not`() {
        assertEquals("103", EmergencyNumber.resolve("103", "RU"))
        assertEquals("911", EmergencyNumber.resolve("  ", "US"))
        assertEquals("112", EmergencyNumber.resolve(null, "FR"))
    }
}
