package com.relay.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.IbmPlexSans
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import java.util.Locale

data class CountryDialCode(val region: String, val dialCode: Int, val displayName: String)

object CountryDialCodes {
    private val EU_REGIONS = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
        "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
        "PL", "PT", "RO", "SK", "SI", "ES", "SE",
    )

    val all: List<CountryDialCode> by lazy {
        val util = PhoneNumberUtil.getInstance()
        util.supportedRegions
            .filter { it in EU_REGIONS }
            .mapNotNull { region ->
                val code = util.getCountryCodeForRegion(region)
                if (code <= 0) return@mapNotNull null
                val name = Locale("", region).displayCountry
                CountryDialCode(region, code, name.ifBlank { region })
            }
            .sortedBy { it.displayName }
    }

    fun forRegion(region: String): CountryDialCode? = all.find { it.region.equals(region, ignoreCase = true) }

    /** Regional-indicator flag emoji built from a two-letter ISO region code (e.g. "SI" -> 🇸🇮). */
    fun flagEmoji(region: String): String {
        if (region.length != 2) return ""
        val base = 0x1F1E6
        return region.uppercase().map { base + (it - 'A') }
            .filter { it in 0x1F1E6..0x1F1FF }
            .joinToString("") { String(Character.toChars(it)) }
    }

    /** Best-guess starting region: the device's own locale, falling back to Slovenia, then the first entry. */
    fun deviceDefault(context: Context): CountryDialCode {
        val deviceRegion = context.resources.configuration.locales.get(0).country
        return forRegion(deviceRegion) ?: forRegion("SI") ?: all.first()
    }
}

/**
 * Renders raw digits grouped per the selected country's numbering plan (via AsYouTypeFormatter)
 * without ever changing the underlying stored text — only how it's displayed. This is the correct
 * Compose mechanism for "formatted display over a raw value" (the same approach used for e.g.
 * credit-card-number inputs): a plain onValueChange that hands back a *different, reshaped* string
 * every keystroke makes Compose's cursor-position heuristic guess wrong and lag behind, because it
 * can't tell where the cursor should land relative to a suddenly-different string. Keeping the real
 * TextField value as the untouched raw digits and only transforming what's *drawn*, with an explicit
 * offset map back to raw positions, keeps the cursor exactly where it should be.
 */
private class PhoneGroupingTransformation(
    private val phoneUtil: PhoneNumberUtil,
    private val region: String,
    private val dialCode: Int,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val formatter = phoneUtil.getAsYouTypeFormatter(region)
        var formatted = ""
        for (c in "+$dialCode$raw") formatted = formatter.inputDigit(c)
        val display = formatted.removePrefix("+$dialCode").trim()

        // Map raw digit offsets <-> display offsets by counting digits consumed — works for any
        // grouping pattern (2-3-3, 3-3-4, ...) since it doesn't assume where the spaces land.
        val rawToDisplay = IntArray(raw.length + 1)
        var digitsSeen = 0
        for (i in display.indices) {
            if (display[i].isDigit()) {
                digitsSeen++
                if (digitsSeen <= raw.length) rawToDisplay[digitsSeen] = i + 1
            }
        }
        for (j in digitsSeen + 1..raw.length) rawToDisplay[j] = display.length

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                rawToDisplay[offset.coerceIn(0, raw.length)]

            override fun transformedToOriginal(offset: Int): Int {
                val clamped = offset.coerceIn(0, display.length)
                var count = 0
                for (k in 0 until clamped) if (display[k].isDigit()) count++
                return count.coerceIn(0, raw.length)
            }
        }

        return TransformedText(AnnotatedString(display), offsetMapping)
    }
}

/**
 * Two-part phone entry: a country/dial-code picker (searchable dropdown) next to a number field
 * that live-formats as you type via libphonenumber's AsYouTypeFormatter, so the grouping is
 * correct for whichever country is selected (not hardcoded to one country's pattern). Emits the
 * full E.164 number (e.g. "+38669123456") via [onE164Change], built through libphonenumber's parse
 * so per-country trunk-prefix rules are correct (Slovenia drops a leading "0", Italy keeps it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneNumberField(
    modifier: Modifier = Modifier,
    initialE164: String = "",
    onE164Change: (String) -> Unit,
) {
    val context = LocalContext.current
    val phoneUtil = remember { PhoneNumberUtil.getInstance() }
    var selected by remember { mutableStateOf(CountryDialCodes.deviceDefault(context)) }
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var rawDigits by remember { mutableStateOf("") }

    fun emit(region: String, dialCode: Int, national: String) {
        if (national.isEmpty()) {
            onE164Change("")
            return
        }
        // Build E.164 via libphonenumber's parse rather than blind "+dialCode+digits" concatenation,
        // so per-country trunk-prefix rules are honored — notably Italy, which KEEPS its leading 0 in
        // E.164 (unlike Slovenia/Germany/etc. which drop it). Falls back to concatenation for
        // still-incomplete input that parse can't handle yet.
        val e164 = runCatching {
            val num = phoneUtil.parse(national, region)
            phoneUtil.format(num, PhoneNumberUtil.PhoneNumberFormat.E164)
        }.getOrElse { "+$dialCode$national" }
        onE164Change(e164)
    }

    // Pre-fill from an existing E.164 (e.g. "Edit profile"): set the country + national digits.
    androidx.compose.runtime.LaunchedEffect(initialE164) {
        if (initialE164.isNotBlank() && rawDigits.isEmpty()) {
            runCatching {
                val num = phoneUtil.parse(initialE164, null)
                phoneUtil.getRegionCodeForNumber(num)?.let { region ->
                    CountryDialCodes.forRegion(region)?.let { selected = it }
                }
                rawDigits = phoneUtil.getNationalSignificantNumber(num)
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent,
        unfocusedBorderColor = Border,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedContainerColor = Surface2,
        unfocusedContainerColor = Surface2,
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.width(132.dp),
        ) {
            OutlinedTextField(
                value = "${CountryDialCodes.flagEmoji(selected.region)} +${selected.dialCode}",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Code", fontFamily = IbmPlexSans) },
                colors = fieldColors,
                modifier = Modifier.menuAnchor(),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false; searchQuery = "" },
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search country", fontFamily = IbmPlexSans) },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.padding(8.dp),
                )
                val filtered = CountryDialCodes.all.filter {
                    searchQuery.isBlank() ||
                        it.displayName.contains(searchQuery, ignoreCase = true) ||
                        it.dialCode.toString().contains(searchQuery)
                }
                filtered.forEach { country ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${country.displayName} (+${country.dialCode})",
                                fontFamily = IbmPlexSans,
                            )
                        },
                        onClick = {
                            selected = country
                            expanded = false
                            searchQuery = ""
                            emit(country.region, country.dialCode, rawDigits)
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = rawDigits,
            onValueChange = { input ->
                // Cap by E.164's 15-digit total (minus this country's dial-code length) rather than a
                // flat 8 — 8 fits Slovenia but silently truncates longer plans (DE/FR/IT mobiles are
                // 9-11), which would corrupt the saved number. Don't strip leading zeros here — parse()
                // in emit() drops trunk prefixes where appropriate but keeps Italy's significant 0.
                val maxNational = (15 - selected.dialCode.toString().length).coerceAtLeast(4)
                val digits = input.filter { it.isDigit() }.take(maxNational)
                rawDigits = digits
                emit(selected.region, selected.dialCode, digits)
            },
            visualTransformation = remember(selected) {
                PhoneGroupingTransformation(phoneUtil, selected.region, selected.dialCode)
            },
            label = { Text("Phone number", fontFamily = IbmPlexSans) },
            placeholder = { Text("69 123 456", color = TextSecondary, fontFamily = IbmPlexSans) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = fieldColors,
            modifier = Modifier.weight(1f),
        )
    }
}
