package org.cryomonitor.companion

/**
 * Default emergency number by country (design D6; owner decision 1).
 * The wearer's override in Advanced settings wins when set. 112 is the
 * GSM default and works in most of the world, including as a redirect in
 * many countries that list another number.
 */
object EmergencyNumber {
    private val byCountry = mapOf(
        "US" to "911", "CA" to "911", "MX" to "911", "PH" to "911",
        "GB" to "999", "IE" to "999", "HK" to "999", "KE" to "999", "SG" to "995",
        "AU" to "000", "NZ" to "111",
        "JP" to "119", "KR" to "119", "CN" to "120", "TW" to "119",
        "IN" to "112", "BR" to "192", "AR" to "107", "CL" to "131",
        "ZA" to "10177", "IL" to "101",
    )

    /** @param countryIso two-letter code from the network, SIM or locale; any case. */
    fun forCountry(countryIso: String?): String {
        val cc = countryIso?.trim()?.uppercase().orEmpty()
        return byCountry[cc] ?: "112"
    }

    /** Resolve: override first, then the country default. */
    fun resolve(override: String?, countryIso: String?): String =
        override?.trim()?.takeIf { it.isNotEmpty() } ?: forCountry(countryIso)
}
