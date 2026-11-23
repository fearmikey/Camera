package com.fearmikey.camera.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Structured meaning of a scanned code's text. Pure Kotlin (no Android types) so it can be
 * unit-tested on the JVM; UI actions for each type live in ui/camera/CodeActions.kt.
 */
sealed interface CodePayload {
    /** The original decoded text. */
    val raw: String

    data class Url(override val raw: String, val url: String) : CodePayload {
        val isSecure: Boolean get() = url.startsWith("https://", ignoreCase = true)

        private val authority: String
            get() = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')

        /** user:pass@host links are a common phishing trick: the real destination is after the '@'. */
        val hasUserInfo: Boolean get() = '@' in authority

        val host: String
            get() {
                val hostPort = authority.substringAfterLast('@')
                return if (hostPort.startsWith('[')) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
            }
    }

    enum class WifiSecurity(val label: String) {
        OPEN("Open"), WEP("WEP"), WPA("WPA/WPA2"), SAE("WPA3"), ENTERPRISE("Enterprise")
    }

    data class Wifi(
        override val raw: String,
        val ssid: String,
        val password: String?,
        val security: WifiSecurity,
        val hidden: Boolean,
    ) : CodePayload

    data class Contact(
        override val raw: String,
        val name: String?,
        val organization: String?,
        val title: String?,
        val phones: List<String>,
        val emails: List<String>,
        val address: String?,
        val url: String?,
        val note: String?,
    ) : CodePayload

    /** Times are epoch millis; all-day events start at UTC midnight (as CalendarContract expects). */
    data class Event(
        override val raw: String,
        val summary: String?,
        val startMillis: Long?,
        val endMillis: Long?,
        val allDay: Boolean,
        val location: String?,
        val description: String?,
    ) : CodePayload

    data class Email(override val raw: String, val to: String, val subject: String?, val body: String?) : CodePayload

    data class Sms(override val raw: String, val number: String, val body: String?) : CodePayload

    data class Phone(override val raw: String, val number: String) : CodePayload

    data class Geo(override val raw: String, val latitude: Double, val longitude: Double, val query: String?) : CodePayload

    data class Product(override val raw: String, val gtin: String, val format: CodeFormat) : CodePayload

    data class Text(override val raw: String) : CodePayload

    companion object {
        fun parse(text: String, format: CodeFormat = CodeFormat.QR_CODE): CodePayload {
            val t = text.trim()
            if (format != CodeFormat.QR_CODE) {
                return if (format.isProduct && t.isNotEmpty() && t.all { it.isDigit() }) Product(text, t, format) else Text(text)
            }
            val lower = t.lowercase(Locale.ROOT)
            val parsed = runCatching {
                when {
                    lower.startsWith("http://") || lower.startsWith("https://") -> Url(text, t)
                    lower.startsWith("www.") && t.none { it.isWhitespace() } -> Url(text, "https://$t")
                    lower.startsWith("wifi:") -> parseWifi(text, t.substring(5))
                    lower.startsWith("mecard:") -> parseMecard(text, t.substring(7))
                    lower.startsWith("begin:vcard") -> parseVcard(text, t)
                    lower.startsWith("begin:vevent") || lower.startsWith("begin:vcalendar") -> parseEvent(text, t)
                    lower.startsWith("mailto:") -> parseMailto(text, t.substring(7))
                    lower.startsWith("matmsg:") -> parseMatmsg(text, t.substring(7))
                    lower.startsWith("smsto:") || lower.startsWith("mmsto:") -> parseSmsTo(text, t.substring(6))
                    lower.startsWith("sms:") -> parseSmsUri(text, t.substring(4))
                    lower.startsWith("tel:") -> percentDecode(t.substring(4)).takeIf { it.isNotBlank() }?.let { Phone(text, it) }
                    lower.startsWith("geo:") -> parseGeo(text, t.substring(4))
                    else -> null
                }
            }.getOrNull()
            return parsed ?: Text(text)
        }

        // ---- MECARD-style "KEY:value;KEY:value;;" (WIFI, MECARD, MATMSG) ----

        /** Splits on unescaped ';' and returns (KEY, unescaped value) pairs in order. */
        internal fun mecardFields(body: String): List<Pair<String, String>> {
            val parts = splitUnescaped(body, ';')
            return parts.mapNotNull { part ->
                val idx = part.indexOf(':')
                if (idx <= 0) null else part.substring(0, idx).trim().uppercase(Locale.ROOT) to unescapeBackslash(part.substring(idx + 1))
            }
        }

        private fun parseWifi(raw: String, body: String): Wifi? {
            val fields = mecardFields(body).toMap()
            val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
            val password = fields["P"]?.takeIf { it.isNotEmpty() }
            val type = fields["T"].orEmpty().uppercase(Locale.ROOT)
            val security = when {
                "EAP" in type || fields.containsKey("E") -> WifiSecurity.ENTERPRISE
                type == "WEP" -> WifiSecurity.WEP
                type == "SAE" || type == "WPA3" -> WifiSecurity.SAE
                type == "NOPASS" || type.isEmpty() -> if (password == null) WifiSecurity.OPEN else WifiSecurity.WPA
                else -> WifiSecurity.WPA
            }
            return Wifi(raw, ssid, password, security, fields["H"].equals("true", ignoreCase = true))
        }

        private fun parseMecard(raw: String, body: String): Contact? {
            val fields = mecardFields(body)
            fun all(key: String) = fields.filter { it.first == key }.map { it.second.trim() }.filter { it.isNotEmpty() }
            fun first(key: String) = all(key).firstOrNull()
            // MECARD names are "Last,First".
            val name = first("N")?.let { n ->
                val parts = n.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) (parts.drop(1) + parts[0]).joinToString(" ") else parts.firstOrNull()
            } ?: first("NICKNAME")
            val contact = Contact(
                raw = raw,
                name = name,
                organization = first("ORG"),
                title = first("TITLE"),
                phones = all("TEL"),
                emails = all("EMAIL"),
                address = first("ADR")?.replace(",,", ",")?.trim(',', ' '),
                url = first("URL"),
                note = first("NOTE"),
            )
            return contact.takeIf { it.name != null || it.phones.isNotEmpty() || it.emails.isNotEmpty() }
        }

        private fun parseMatmsg(raw: String, body: String): Email? {
            val fields = mecardFields(body).toMap()
            val to = fields["TO"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Email(raw, to, fields["SUB"]?.takeIf { it.isNotEmpty() }, fields["BODY"]?.takeIf { it.isNotEmpty() })
        }

        // ---- vCard / iCalendar content lines ----

        internal data class ContentLine(val name: String, val params: Map<String, String>, val value: String)

        /** Unfolds RFC 5545/6350 continuation lines and splits "NAME;PARAM=x:value". */
        internal fun contentLines(text: String): List<ContentLine> {
            val unfolded = mutableListOf<String>()
            for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                if ((line.startsWith(' ') || line.startsWith('\t')) && unfolded.isNotEmpty()) {
                    unfolded[unfolded.lastIndex] = unfolded.last() + line.substring(1)
                } else if (line.isNotBlank()) {
                    unfolded += line
                }
            }
            return unfolded.mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val left = line.substring(0, idx).split(';')
                // Strip vCard group prefixes such as "item1.TEL".
                val name = left[0].substringAfterLast('.').trim().uppercase(Locale.ROOT)
                val params = left.drop(1).associate { p ->
                    val eq = p.indexOf('=')
                    if (eq < 0) p.trim().uppercase(Locale.ROOT) to "" else
                        p.substring(0, eq).trim().uppercase(Locale.ROOT) to p.substring(eq + 1).trim().trim('"')
                }
                var value = line.substring(idx + 1)
                if (params["ENCODING"].equals("QUOTED-PRINTABLE", ignoreCase = true)) value = decodeQuotedPrintable(value)
                ContentLine(name, params, value)
            }
        }

        private fun parseVcard(raw: String, text: String): Contact? {
            val lines = contentLines(text)
            fun values(key: String) = lines.filter { it.name == key }
            fun single(key: String) = values(key).firstOrNull()?.let { unescapeText(it.value).trim() }?.takeIf { it.isNotEmpty() }
            fun structured(key: String) = values(key).firstOrNull()?.let { line ->
                splitUnescaped(line.value, ';').map { unescapeText(it).trim() }
            }

            val name = single("FN") ?: structured("N")?.let { n ->
                // N = family;given;additional;prefix;suffix
                listOfNotNull(n.getOrNull(3), n.getOrNull(1), n.getOrNull(2), n.getOrNull(0), n.getOrNull(4))
                    .filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
            }
            val contact = Contact(
                raw = raw,
                name = name,
                organization = structured("ORG")?.filter { it.isNotEmpty() }?.joinToString(" ")?.takeIf { it.isNotEmpty() },
                title = single("TITLE"),
                phones = values("TEL").map { unescapeText(it.value).trim().removePrefix("tel:") }.filter { it.isNotEmpty() },
                emails = values("EMAIL").map { unescapeText(it.value).trim() }.filter { it.isNotEmpty() },
                address = structured("ADR")?.filter { it.isNotEmpty() }?.joinToString(", ")?.takeIf { it.isNotEmpty() },
                url = single("URL"),
                note = single("NOTE"),
            )
            return contact.takeIf { it.name != null || it.phones.isNotEmpty() || it.emails.isNotEmpty() }
        }

        private fun parseEvent(raw: String, text: String): Event? {
            val all = contentLines(text)
            // Only look inside the first VEVENT when it is wrapped in a VCALENDAR.
            val start = all.indexOfFirst { it.name == "BEGIN" && it.value.trim().equals("VEVENT", true) }
            val end = all.indexOfFirst { it.name == "END" && it.value.trim().equals("VEVENT", true) }
            val lines = if (start >= 0) all.subList(start + 1, if (end > start) end else all.size) else all
            fun line(key: String) = lines.firstOrNull { it.name == key }
            fun single(key: String) = line(key)?.let { unescapeText(it.value).trim() }?.takeIf { it.isNotEmpty() }

            val dtStart = line("DTSTART")
            val allDay = dtStart != null && (dtStart.params["VALUE"].equals("DATE", true) || dtStart.value.trim().length == 8)
            val startMillis = dtStart?.let { parseIcalTime(it) }
            var endMillis = line("DTEND")?.let { parseIcalTime(it) }
            if (allDay && startMillis != null && endMillis == null) endMillis = startMillis + 24L * 60 * 60 * 1000
            val summary = single("SUMMARY")
            if (summary == null && startMillis == null) return null
            return Event(raw, summary, startMillis, endMillis, allDay, single("LOCATION"), single("DESCRIPTION"))
        }

        private val ICAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)
        private val ICAL_DATE_TIME_NO_SECONDS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm", Locale.ROOT)

        internal fun parseIcalTime(line: ContentLine): Long? {
            val v = line.value.trim()
            return runCatching {
                if (v.length == 8) {
                    LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                } else {
                    val utc = v.endsWith("Z", ignoreCase = true)
                    val body = v.removeSuffix("Z").removeSuffix("z")
                    val local = runCatching { LocalDateTime.parse(body, ICAL_DATE_TIME) }
                        .getOrElse { LocalDateTime.parse(body, ICAL_DATE_TIME_NO_SECONDS) }
                    val zone = when {
                        utc -> ZoneOffset.UTC
                        line.params["TZID"] != null -> runCatching { ZoneId.of(line.params.getValue("TZID")) }.getOrDefault(ZoneId.systemDefault())
                        else -> ZoneId.systemDefault()
                    }
                    local.atZone(zone).toInstant().toEpochMilli()
                }
            }.getOrNull()
        }

        // ---- URI schemes ----

        private fun queryParams(query: String): Map<String, String> =
            query.split('&').mapNotNull { kv ->
                val eq = kv.indexOf('=')
                if (eq <= 0) null else kv.substring(0, eq).lowercase(Locale.ROOT) to percentDecode(kv.substring(eq + 1))
            }.toMap()

        private fun parseMailto(raw: String, rest: String): Email? {
            val params = queryParams(rest.substringAfter('?', ""))
            val to = listOf(percentDecode(rest.substringBefore('?')), params["to"].orEmpty())
                .filter { it.isNotBlank() }.joinToString(",")
            if (to.isBlank()) return null
            return Email(raw, to, params["subject"]?.takeIf { it.isNotEmpty() }, params["body"]?.takeIf { it.isNotEmpty() })
        }

        /** "SMSTO:number:body" */
        private fun parseSmsTo(raw: String, rest: String): Sms? {
            val number = rest.substringBefore(':').trim()
            if (number.isEmpty()) return null
            return Sms(raw, number, rest.substringAfter(':', "").takeIf { it.isNotEmpty() })
        }

        /** "sms:number?body=..." (RFC 5724), also the non-standard "sms:number:body". */
        private fun parseSmsUri(raw: String, rest: String): Sms? {
            if ('?' !in rest && ':' in rest) return parseSmsTo(raw, rest)
            val number = percentDecode(rest.substringBefore('?')).trimEnd(';').trim()
            if (number.isEmpty()) return null
            return Sms(raw, number, queryParams(rest.substringAfter('?', ""))["body"]?.takeIf { it.isNotEmpty() })
        }

        private fun parseGeo(raw: String, rest: String): Geo? {
            val coords = rest.substringBefore('?').substringBefore(';').split(',')
            val lat = coords.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
            val lng = coords.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            return Geo(raw, lat, lng, queryParams(rest.substringAfter('?', ""))["q"]?.takeIf { it.isNotBlank() })
        }

        // ---- escaping helpers ----

        private fun splitUnescaped(s: String, separator: Char): List<String> {
            val parts = mutableListOf<String>()
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(c).append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == separator) {
                    parts += sb.toString()
                    sb.clear()
                } else {
                    sb.append(c)
                }
                i++
            }
            if (sb.isNotEmpty()) parts += sb.toString()
            return parts
        }

        /** MECARD/WIFI: a backslash makes the next character literal. */
        private fun unescapeBackslash(s: String): String {
            if ('\\' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1]); i += 2
                } else {
                    sb.append(s[i]); i++
                }
            }
            return sb.toString()
        }

        /** vCard/iCalendar TEXT: \n or \N is a newline, other escapes are literal. */
        private fun unescapeText(s: String): String {
            if ('\\' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    val n = s[i + 1]
                    sb.append(if (n == 'n' || n == 'N') '\n' else n)
                    i += 2
                } else {
                    sb.append(s[i]); i++
                }
            }
            return sb.toString()
        }

        /** Decodes %XX (UTF-8) sequences; '+' stays literal; malformed escapes are kept as-is. */
        internal fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 <= s.lastIndex) {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex); i += 3; continue
                    }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }

        private fun decodeQuotedPrintable(s: String): String {
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '=' && i + 2 <= s.lastIndex) {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex); i += 3; continue
                    }
                }
                if (c == '=' && i == s.lastIndex) break // soft line break
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
