package com.fearmikey.camera.ui.camera

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.PersistableBundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.fearmikey.camera.domain.model.CodePayload

/** Presentation and system intents for each [CodePayload] type. Nothing leaves the device unless the user picks an action. */
object CodeActions {

    fun title(payload: CodePayload): String = when (payload) {
        is CodePayload.Url -> "Link"
        is CodePayload.Wifi -> "Wi-Fi network"
        is CodePayload.Contact -> "Contact"
        is CodePayload.Event -> "Event"
        is CodePayload.Email -> "Email"
        is CodePayload.Sms -> "Text message"
        is CodePayload.Phone -> "Phone number"
        is CodePayload.Geo -> "Location"
        is CodePayload.Product -> "Product code"
        is CodePayload.Text -> "Text"
    }

    /** One-line summary for chips and sheet headers. */
    fun summary(payload: CodePayload): String = when (payload) {
        is CodePayload.Url -> payload.host.ifEmpty { payload.url }
        is CodePayload.Wifi -> payload.ssid
        is CodePayload.Contact -> payload.name ?: payload.organization ?: payload.phones.firstOrNull() ?: payload.emails.firstOrNull().orEmpty()
        is CodePayload.Event -> payload.summary ?: "Untitled event"
        is CodePayload.Email -> payload.to
        is CodePayload.Sms -> payload.number
        is CodePayload.Phone -> payload.number
        is CodePayload.Geo -> payload.query ?: "%.5f, %.5f".format(java.util.Locale.US, payload.latitude, payload.longitude)
        is CodePayload.Product -> payload.gtin
        is CodePayload.Text -> payload.raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    fun icon(payload: CodePayload): ImageVector = when (payload) {
        is CodePayload.Url -> Icons.Default.Link
        is CodePayload.Wifi -> Icons.Default.Wifi
        is CodePayload.Contact -> Icons.Default.Person
        is CodePayload.Event -> Icons.Default.Event
        is CodePayload.Email -> Icons.Default.Email
        is CodePayload.Sms -> Icons.Default.Sms
        is CodePayload.Phone -> Icons.Default.Call
        is CodePayload.Geo -> Icons.Default.Place
        is CodePayload.Product -> Icons.Default.ShoppingCart
        is CodePayload.Text -> Icons.Default.Description
    }

    fun primaryLabel(payload: CodePayload): String = when (payload) {
        is CodePayload.Url -> "Open link"
        is CodePayload.Wifi -> if (canSuggestWifi(payload)) "Join network" else "Open Wi-Fi settings"
        is CodePayload.Contact -> "Add contact"
        is CodePayload.Event -> "Add to calendar"
        is CodePayload.Email -> "Write email"
        is CodePayload.Sms -> "Write message"
        is CodePayload.Phone -> "Call"
        is CodePayload.Geo -> "Open map"
        is CodePayload.Product -> "Search web"
        is CodePayload.Text -> "Copy text"
    }

    fun runPrimary(context: Context, payload: CodePayload) {
        val handled = when (payload) {
            is CodePayload.Url -> context.launch(
                Intent(Intent.ACTION_VIEW, Uri.parse(payload.url)).addCategory(Intent.CATEGORY_BROWSABLE)
            )
            is CodePayload.Wifi -> { joinWifi(context, payload); true }
            is CodePayload.Contact -> context.launch(contactIntent(payload))
            is CodePayload.Event -> context.launch(eventIntent(payload))
            is CodePayload.Email -> context.launch(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                    putExtra(Intent.EXTRA_EMAIL, payload.to.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
                    payload.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    payload.body?.let { putExtra(Intent.EXTRA_TEXT, it) }
                }
            )
            is CodePayload.Sms -> context.launch(
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", payload.number, null)).apply {
                    payload.body?.let { putExtra("sms_body", it) }
                }
            )
            // Dial (not call) so the user always confirms the number.
            is CodePayload.Phone -> context.launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", payload.number, null)))
            is CodePayload.Geo -> {
                val coords = "${payload.latitude},${payload.longitude}"
                val q = Uri.encode(payload.query ?: coords)
                context.launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$q")))
            }
            is CodePayload.Product -> webSearch(context, payload.gtin)
            is CodePayload.Text -> { copy(context, payload.raw); true }
        }
        if (!handled) {
            copy(context, payload.raw, showToast = false)
            Toast.makeText(context, "No app can open this. Copied instead.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Copies [text]. Android 13+ shows its own clipboard confirmation, so the toast is only for older versions. */
    fun copy(context: Context, text: String, label: String = "Scanned code", sensitive: Boolean = false, showToast: Boolean = true) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            // ClipDescription.EXTRA_IS_SENSITIVE (API 33): hides the preview in the system clipboard UI.
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        clipboard.setPrimaryClip(clip)
        if (showToast && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.launch(Intent.createChooser(send, null))
    }

    fun webSearch(context: Context, query: String): Boolean =
        context.launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))

    // ---- Wi-Fi ----

    private fun canSuggestWifi(wifi: CodePayload.Wifi): Boolean = when (wifi.security) {
        CodePayload.WifiSecurity.OPEN -> true
        CodePayload.WifiSecurity.WPA, CodePayload.WifiSecurity.SAE -> wifi.password != null
        CodePayload.WifiSecurity.WEP, CodePayload.WifiSecurity.ENTERPRISE -> false
    }

    /**
     * Opens the system "Add network" dialog (Settings.ACTION_WIFI_ADD_NETWORKS), which needs no
     * Wi-Fi permissions. WEP/enterprise networks (unsupported by suggestions) fall back to copying
     * the password and opening the Wi-Fi panel.
     */
    private fun joinWifi(context: Context, wifi: CodePayload.Wifi) {
        val suggestion = if (canSuggestWifi(wifi)) runCatching {
            WifiNetworkSuggestion.Builder().setSsid(wifi.ssid).apply {
                when (wifi.security) {
                    CodePayload.WifiSecurity.WPA -> setWpa2Passphrase(wifi.password!!)
                    CodePayload.WifiSecurity.SAE -> setWpa3Passphrase(wifi.password!!)
                    else -> Unit
                }
                if (wifi.hidden) setIsHiddenSsid(true)
            }.build()
        }.getOrNull() else null // invalid passphrase lengths throw IllegalArgumentException

        if (suggestion != null && context.launch(
                Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                    .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
            )
        ) return

        wifi.password?.let { copy(context, it, label = "Wi-Fi password", sensitive = true, showToast = false) }
        if (!context.launch(Intent(Settings.Panel.ACTION_WIFI))) context.launch(Intent(Settings.ACTION_WIFI_SETTINGS))
        if (wifi.password != null) {
            Toast.makeText(context, "Password copied. Choose \"${wifi.ssid}\" and paste it.", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Contacts / calendar ----

    private fun contactIntent(c: CodePayload.Contact) = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        c.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
        c.organization?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        c.title?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
        c.address?.let { putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
        c.note?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
        listOf(
            ContactsContract.Intents.Insert.PHONE,
            ContactsContract.Intents.Insert.SECONDARY_PHONE,
            ContactsContract.Intents.Insert.TERTIARY_PHONE,
        ).zip(c.phones).forEach { (key, value) -> putExtra(key, value) }
        listOf(
            ContactsContract.Intents.Insert.EMAIL,
            ContactsContract.Intents.Insert.SECONDARY_EMAIL,
            ContactsContract.Intents.Insert.TERTIARY_EMAIL,
        ).zip(c.emails).forEach { (key, value) -> putExtra(key, value) }
        c.url?.let { url ->
            val website = ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Website.URL, url)
            }
            putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, arrayListOf(website))
        }
    }

    private fun eventIntent(e: CodePayload.Event) = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
        e.summary?.let { putExtra(CalendarContract.Events.TITLE, it) }
        e.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        e.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
        e.startMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        e.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        if (e.allDay) putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
    }

    private fun Context.launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
