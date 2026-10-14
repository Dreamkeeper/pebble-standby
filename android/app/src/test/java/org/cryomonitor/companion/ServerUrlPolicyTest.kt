package org.cryomonitor.companion

import org.cryomonitor.companion.ServerUrlPolicy.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlPolicyTest {

    @Test
    fun `https is accepted anywhere`() {
        assertNull(ServerUrlPolicy.check("https://cm.example.org"))
        assertNull(ServerUrlPolicy.check("https://203.0.113.9:8443/"))
    }

    @Test
    fun `plain http only on the tailnet, the LAN and loopback`() {
        for (ok in listOf("http://100.77.30.115:8080", "http://100.64.0.1", "http://100.127.255.254",
                          "http://nas.tail1234.ts.net:8080", "http://192.168.1.20:8080",
                          "http://10.0.0.5", "http://172.16.4.4", "http://172.31.255.1",
                          "http://nas.local:8080", "http://nas.lan", "http://router.home.arpa",
                          "http://localhost:8080", "http://127.0.0.1:8080", "http://[fd7a:115c::1]:8080"))
            assertNull(ok, ServerUrlPolicy.check(ok))
        for (bad in listOf("http://cm.example.org", "http://203.0.113.9", "http://100.63.255.255",
                           "http://100.128.0.1", "http://172.32.0.1", "http://11.0.0.1",
                           "http://evil.ts.net.example.com"))
            assertEquals(bad, Problem.CLEARTEXT_NOT_PRIVATE, ServerUrlPolicy.check(bad))
    }

    @Test
    fun `other schemes and junk are rejected`() {
        assertEquals(Problem.NOT_HTTP, ServerUrlPolicy.check("ftp://x.example"))
        assertEquals(Problem.NOT_A_URL, ServerUrlPolicy.check("cm.example.org"))
        assertEquals(Problem.NOT_A_URL, ServerUrlPolicy.check("http://"))
        assertEquals(Problem.NOT_A_URL, ServerUrlPolicy.check(""))
    }
}
