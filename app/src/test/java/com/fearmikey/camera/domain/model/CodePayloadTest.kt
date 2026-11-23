package com.fearmikey.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.ZoneId

class CodePayloadTest {

    private inline fun <reified T : CodePayload> parse(text: String, format: CodeFormat = CodeFormat.QR_CODE): T {
        val p = CodePayload.parse(text, format)
        assertTrue("Expected ${T::class.simpleName} but got $p", p is T)
        return p as T
    }

    @Test
    fun url() {
        val p = parse<CodePayload.Url>("https://example.com/path?q=1")
        assertEquals("example.com", p.host)
        assertTrue(p.isSecure)
        assertFalse(p.hasUserInfo)
    }

    @Test
    fun urlWithUserInfoExposesRealHost() {
        val p = parse<CodePayload.Url>("http://bank.example.com@evil.example.net:8080/login")
        assertTrue(p.hasUserInfo)
        assertFalse(p.isSecure)
        assertEquals("evil.example.net", p.host)
    }

    @Test
    fun bareWwwBecomesHttps() {
        assertEquals("https://www.example.org", parse<CodePayload.Url>("www.example.org").url)
    }

    @Test
    fun wifiWpaWithEscapes() {
        val p = parse<CodePayload.Wifi>("WIFI:T:WPA;S:My\\;Net;P:pa\\:ss\\\\word;H:true;;")
        assertEquals("My;Net", p.ssid)
        assertEquals("pa:ss\\word", p.password)
        assertEquals(CodePayload.WifiSecurity.WPA, p.security)
        assertTrue(p.hidden)
    }

    @Test
    fun wifiOpenAndFieldOrder() {
        val p = parse<CodePayload.Wifi>("WIFI:S:Guest;T:nopass;;")
        assertEquals("Guest", p.ssid)
        assertNull(p.password)
        assertEquals(CodePayload.WifiSecurity.OPEN, p.security)
    }

    @Test
    fun wifiSaeAndEnterprise() {
        assertEquals(CodePayload.WifiSecurity.SAE, parse<CodePayload.Wifi>("WIFI:T:SAE;S:x;P:12345678;;").security)
        assertEquals(CodePayload.WifiSecurity.ENTERPRISE, parse<CodePayload.Wifi>("WIFI:T:WPA2-EAP;S:corp;E:PEAP;;").security)
    }

    @Test
    fun wifiWithoutSsidIsText() {
        parse<CodePayload.Text>("WIFI:T:WPA;P:secret;;")
    }

    @Test
    fun mecard() {
        val p = parse<CodePayload.Contact>("MECARD:N:Doe,John;TEL:+15551234;TEL:+15559876;EMAIL:john@example.com;ORG:Acme;URL:https://acme.example;;")
        assertEquals("John Doe", p.name)
        assertEquals(listOf("+15551234", "+15559876"), p.phones)
        assertEquals(listOf("john@example.com"), p.emails)
        assertEquals("Acme", p.organization)
        assertEquals("https://acme.example", p.url)
    }

    @Test
    fun vcardWithFoldingGroupsAndStructuredFields() {
        val vcard = """
            BEGIN:VCARD
            VERSION:3.0
            N:Doe;Jane;;Dr.;
            item1.TEL;TYPE=CELL:+1 555 0100
            EMAIL;TYPE=WORK:jane@exa
             mple.com
            ORG:Example Corp;Research
            ADR;TYPE=WORK:;;1 Main St;Springfield;IL;62701;USA
            NOTE:Line one\nLine two\, ok
            END:VCARD
        """.trimIndent()
        val p = parse<CodePayload.Contact>(vcard)
        assertEquals("Dr. Jane Doe", p.name)
        assertEquals(listOf("+1 555 0100"), p.phones)
        assertEquals(listOf("jane@example.com"), p.emails)
        assertEquals("Example Corp Research", p.organization)
        assertEquals("1 Main St, Springfield, IL, 62701, USA", p.address)
        assertEquals("Line one\nLine two, ok", p.note)
    }

    @Test
    fun vcardPrefersFnAndDecodesQuotedPrintable() {
        val p = parse<CodePayload.Contact>("BEGIN:VCARD\r\nVERSION:2.1\r\nFN;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:J=C3=BCrgen\r\nEND:VCARD")
        assertEquals("Jürgen", p.name)
    }

    @Test
    fun eventUtc() {
        val p = parse<CodePayload.Event>(
            "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Launch\nDTSTART:20261204T120000Z\nDTEND:20261204T133000Z\nLOCATION:Room 1\nEND:VEVENT\nEND:VCALENDAR"
        )
        assertEquals("Launch", p.summary)
        assertEquals("Room 1", p.location)
        assertFalse(p.allDay)
        assertEquals(ZonedDateTime.of(2026, 12, 4, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), p.startMillis)
        assertEquals(ZonedDateTime.of(2026, 12, 4, 13, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), p.endMillis)
    }

    @Test
    fun eventWithTzid() {
        val p = parse<CodePayload.Event>("BEGIN:VEVENT\nSUMMARY:Call\nDTSTART;TZID=Europe/Berlin:20260701T090000\nEND:VEVENT")
        assertEquals(ZonedDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(), p.startMillis)
        assertNull(p.endMillis)
    }

    @Test
    fun allDayEventGetsOneDayEnd() {
        val p = parse<CodePayload.Event>("BEGIN:VEVENT\nSUMMARY:Holiday\nDTSTART;VALUE=DATE:20261225\nEND:VEVENT")
        val start = LocalDate.of(2026, 12, 25).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertTrue(p.allDay)
        assertEquals(start, p.startMillis)
        assertEquals(start + 86_400_000L, p.endMillis)
    }

    @Test
    fun mailtoWithParams() {
        val p = parse<CodePayload.Email>("mailto:a@example.com?subject=Hello%20there&body=1+1%3D2&cc=x@y.z")
        assertEquals("a@example.com", p.to)
        assertEquals("Hello there", p.subject)
        assertEquals("1+1=2", p.body) // '+' is literal in mailto
    }

    @Test
    fun matmsg() {
        val p = parse<CodePayload.Email>("MATMSG:TO:b@example.com;SUB:Hi;BODY:See you;;")
        assertEquals("b@example.com", p.to)
        assertEquals("Hi", p.subject)
        assertEquals("See you", p.body)
    }

    @Test
    fun smsVariants() {
        parse<CodePayload.Sms>("SMSTO:+15550100:Hello: world").let {
            assertEquals("+15550100", it.number)
            assertEquals("Hello: world", it.body)
        }
        parse<CodePayload.Sms>("sms:+15550100?body=Hi%20there").let {
            assertEquals("+15550100", it.number)
            assertEquals("Hi there", it.body)
        }
        parse<CodePayload.Sms>("sms:+15550100").let { assertNull(it.body) }
    }

    @Test
    fun telAndGeo() {
        assertEquals("+1 555 0100", parse<CodePayload.Phone>("tel:+1%20555%200100").number)
        parse<CodePayload.Geo>("geo:52.5200,13.4050?q=Berlin%20Mitte").let {
            assertEquals(52.52, it.latitude, 1e-9)
            assertEquals(13.405, it.longitude, 1e-9)
            assertEquals("Berlin Mitte", it.query)
        }
        parse<CodePayload.Text>("geo:200,0")
    }

    @Test
    fun productBarcodes() {
        val p = parse<CodePayload.Product>("4006381333931", CodeFormat.EAN_13)
        assertEquals("4006381333931", p.gtin)
        // Non-QR codes are never interpreted as links, even if the text looks like one.
        parse<CodePayload.Text>("https://example.com", CodeFormat.BARCODE)
    }

    @Test
    fun plainText() {
        parse<CodePayload.Text>("Just some text")
        parse<CodePayload.Text>("www.has space.com")
    }

    @Test
    fun formatMapping() {
        assertEquals(CodeFormat.EAN_13, CodeFormat.fromNative("EAN_13"))
        assertEquals(CodeFormat.UPC_A, CodeFormat.fromNative("UPC-A"))
        assertEquals(CodeFormat.QR_CODE, CodeFormat.fromNative("QR_CODE"))
        assertEquals(CodeFormat.BARCODE, CodeFormat.fromNative("UPC_EAN_EXTENSION"))
    }
}
