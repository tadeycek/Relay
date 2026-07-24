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
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.text.input.KeyboardType
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
    val all: List<CountryDialCode> by lazy {
        val util = PhoneNumberUtil.getInstance()
        util.supportedRegions
            .mapNotNull { region ->
                val code = util.getCountryCodeForRegion(region)
                if (code <= 0) return@mapNotNull null
                val name = Locale("", region).displayCountry
                CountryDialCode(region, code, name.ifBlank { region })
            }
            .sortedBy { it.displayName }
    }

    fun forRegion(region: String): CountryDialCode? = all.find { it.region.equals(region, ignoreCase = true) }

    /** Best-guess starting region: the device's own locale, falling back to Slovenia, then the first entry. */
    fun deviceDefault(context: Context): CountryDialCode {
        val deviceRegion = context.resources.configuration.locales.get(0).country
        return forRegion(deviceRegion) ?: forRegion("SI") ?: all.first()
    }
}

/** Regional-indicator flag emoji built from a two-letter ISO region code (e.g. "SI" -> 🇸🇮). */
fun flagEmoji(region: String): String {
    if (region.length != 2) return ""
    val base = 0x1F1E6
    return region.uppercase().map { base + (it - 'A') }
        .filter { it in 0x1F1E6..0x1F1FF }
        .joinToString("") { String(Character.toChars(it)) }
}

/**
 * Two-part phone entry: a country/dial-code picker (searchable dropdown) next to a number field
 * that live-formats as you type via libphonenumber's AsYouTypeFormatter, so the grouping is
 * correct for whichever country is selected (not hardcoded to one country's pattern). Emits the
 * full E.164 number (e.g. "+38669123456") via [onE164Change] — the leading trunk "0" some people
 * are used to typing locally (e.g. Slovenian "069...") is deliberately stripped, since the country
 * code already replaces it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneNumberField(
    modifier: Modifier = Modifier,
    onE164Change: (String) -> Unit,
) {
    val context = LocalContext.current
    val phoneUtil = remember { PhoneNumberUtil.getInstance() }
    var selected by remember { mutableStateOf(CountryDialCodes.deviceDefault(context)) }
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var rawDigits by remember { mutableStateOf("") }
    var displayText by remember { mutableStateOf("") }

    fun reformatAndEmit(region: String, digits: String) {
        val formatter = phoneUtil.getAsYouTypeFormatter(region)
        var formatted = ""
        for (c in digits) formatted = formatter.inputDigit(c)
        displayText = formatted
        onE164Change(if (digits.isEmpty()) "" else "+${selected.dialCode}$digits")
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
            modifier = Modifier.width(110.dp),
        ) {
            OutlinedTextField(
                value = "${flagEmoji(selected.region)} +${selected.dialCode}",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Country", fontFamily = IbmPlexSans) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
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
                                "${flagEmoji(country.region)} ${country.displayName} (+${country.dialCode})",
                                fontFamily = IbmPlexSans,
                            )
                        },
                        onClick = {
                            selected = country
                            expanded = false
                            searchQuery = ""
                            reformatAndEmit(country.region, rawDigits)
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = displayText,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }.trimStart('0')
                rawDigits = digits
                reformatAndEmit(selected.region, digits)
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
