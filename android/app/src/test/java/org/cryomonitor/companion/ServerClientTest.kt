package org.cryomonitor.companion

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ServerClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ServerClient({ server.url("/").toString().trimEnd('/') },
                              { "unit-test-token" })
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- enrollment ----

    @Test
    fun `enroll success returns token and wearer id`() {
        server.enqueue(MockResponse().setBody(
            """{"token":"tok123","wearer_id":"alice"}"""))
        val r = client.enroll(server.url("/").toString(), "ABCD-EFGH")
        assertTrue(r is ServerClient.EnrollResult.Success)
        r as ServerClient.EnrollResult.Success
        assertEquals("tok123", r.token)
        assertEquals("alice", r.wearerId)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/enroll", recorded.path)
        assertEquals("ABCD-EFGH",
            JSONObject(recorded.body.readUtf8()).getString("code"))
    }

    @Test
    fun `enroll distinguishes burned code from malformed and rate limit`() {
        server.enqueue(MockResponse().setResponseCode(410))
        assertTrue(client.enroll(server.url("/").toString(), "AAAA-AAAA")
            is ServerClient.EnrollResult.CodeRejected)

        server.enqueue(MockResponse().setResponseCode(422))
        assertTrue(client.enroll(server.url("/").toString(), "nope")
            is ServerClient.EnrollResult.Malformed)

        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(client.enroll(server.url("/").toString(), "AAAA-AAAA")
            is ServerClient.EnrollResult.RateLimited)
    }

    @Test
    fun `enroll against dead server reports unreachable`() {
        val dead = server.url("/").toString()
        server.shutdown()
        assertTrue(client.enroll(dead, "AAAA-AAAA")
            is ServerClient.EnrollResult.Unreachable)
    }

    // ---- auth plumbing ----

    @Test
    fun `bearer header on every authenticated call and never in the url`() {
        server.enqueue(MockResponse().setBody("""{"contacts":[],"tiers":[]}"""))
        client.fetchContacts()
        val recorded = server.takeRequest()
        assertEquals("Bearer unit-test-token",
                     recorded.getHeader("Authorization"))
        assertTrue(!recorded.path!!.contains("token"))
    }

    // ---- contacts ----

    @Test
    fun `fetchContacts parses contacts and tiers`() {
        server.enqueue(MockResponse().setBody("""
            {"contacts":[{"id":"c1","name":"R1","tier_name":"primary",
                          "telegram_chat_id":"123456","ntfy_topic":null,
                          "email":null}],
             "tiers":[{"name":"primary","position":0,
                       "repeat_after_s":1800,"promote_after_s":600}]}"""))
        val p = client.fetchContacts()
        assertNotNull(p)
        assertEquals(1, p!!.contacts.size)
        assertEquals("R1", p.contacts[0].name)
        assertEquals("123456", p.contacts[0].telegramChatId)
        assertEquals(null, p.contacts[0].email)
        assertEquals(600, p.tiers[0].promoteAfterS)
    }

    @Test
    fun `saveContact maps field-level validation errors`() {
        server.enqueue(MockResponse().setResponseCode(422).setBody(
            """{"detail":{"fields":{"telegram_chat_id":
               "must be a numeric Telegram chat id"}}}"""))
        val r = client.saveContact(ServerClient.Contact(
            null, "X", "primary", "abc", null, null))
        assertTrue(r is ServerClient.SaveResult.FieldErrors)
        r as ServerClient.SaveResult.FieldErrors
        assertTrue("telegram_chat_id" in r.fields)
    }

    @Test
    fun `saveContact ok on 200`() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"contact_id":"c9"}"""))
        val r = client.saveContact(ServerClient.Contact(
            null, "R1", "primary", "123456", null, null))
        assertTrue(r is ServerClient.SaveResult.Ok)
    }

    // ---- heartbeat ack ----

    @Test
    fun `heartbeat surfaces degraded flag`() {
        server.enqueue(MockResponse().setBody(
            """{"state":"ok","server_time":1.0,"degraded":true}"""))
        val ack = client.heartbeat(80, null, null, false)
        assertNotNull(ack)
        assertTrue(ack!!.degraded)
        assertEquals("ok", ack.state)
    }

    @Test
    fun `status parses degraded and escalation count`() {
        server.enqueue(MockResponse().setBody(
            """{"phone":"ok","degraded":false,
                "active_escalations":{"e1":{},"e2":{}},"recent_events":[]}"""))
        val s = client.fetchStatus()
        assertEquals(false, s!!.degraded)
        assertEquals(2, s.activeEscalations)
    }

    // ---- diagnostics ----

    @Test
    fun `diagnostics upload posts the zip with days and request id, token in header`() {
        server.enqueue(MockResponse().setBody("""{"id":"d-1"}"""))
        val f = java.io.File.createTempFile("bundle", ".zip").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)); deleteOnExit() }
        val r = client.uploadDiagnostics(f, 7, "req-9")
        assertTrue(r is ServerClient.UploadResult.Ok)
        assertEquals("d-1", (r as ServerClient.UploadResult.Ok).id)
        val rec = server.takeRequest()
        assertEquals("/api/v1/diagnostics?days=7&request_id=req-9", rec.path)
        assertEquals("Bearer unit-test-token", rec.getHeader("Authorization"))
        assertEquals("application/zip", rec.getHeader("Content-Type"))
        assertEquals(7L, rec.bodySize)
    }

    @Test
    fun `diagnostics upload reports size and auth refusals distinctly`() {
        val f = java.io.File.createTempFile("bundle", ".zip").apply { writeText("x"); deleteOnExit() }
        server.enqueue(MockResponse().setResponseCode(413))
        val big = client.uploadDiagnostics(f, 7, null)
        assertTrue((big as ServerClient.UploadResult.Failed).why.contains("413"))
        server.enqueue(MockResponse().setResponseCode(401))
        val auth = client.uploadDiagnostics(f, 7, null)
        assertTrue((auth as ServerClient.UploadResult.Failed).why.contains("401"))
    }

    @Test
    fun `declining a request posts to its decline endpoint`() {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(client.declineDiagnostics("req-9"))
        assertEquals("/api/v1/diagnostics/requests/req-9/decline", server.takeRequest().path)
    }
}
