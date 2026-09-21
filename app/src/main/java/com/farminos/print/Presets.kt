package com.farminos.print

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Presets travel as JSON rather than as serialised protobuf so that a user can paste one into an
 * issue and a maintainer can review it as a diff. Enums are written as names, not ordinals, so the
 * format survives renumbering in settings.proto.
 */
const val PRESETS_SCHEMA = 1

const val USER_PRESET_PREFIX = "user-"

class PresetFormatException(
    message: String,
) : Exception(message)

/**
 * The fields that identify a physical printer rather than describe a configuration. A preset never
 * carries them: applying one keeps whatever the printer already has.
 */
private fun PrinterSettings.Builder.keepIdentityOf(printer: PrinterSettings): PrinterSettings.Builder =
    this
        .setEnabled(printer.enabled)
        .setAddress(printer.address)
        .setName(printer.name)
        .setInterface(printer.`interface`)

/**
 * Overwrites every non-identity field. A preset that only set the fields it mentioned would leave
 * stale values behind and there would be no way to tell what the printer is really configured with,
 * so parsing fills omissions from DEFAULT_PRINTER_SETTINGS rather than from the printer.
 */
fun applyPreset(
    preset: Preset,
    printer: PrinterSettings,
): PrinterSettings =
    preset.settings
        .toBuilder()
        .keepIdentityOf(printer)
        .build()

fun presetFromPrinter(
    id: String,
    label: String,
    printer: PrinterSettings,
): Preset =
    Preset
        .newBuilder()
        .setId(id)
        .setLabel(label)
        .setSettings(
            printer
                .toBuilder()
                .keepIdentityOf(PrinterSettings.getDefaultInstance())
                .build(),
        ).build()

/**
 * Longest matching prefix wins, so a preset for one model beats a preset for its whole family. A
 * tie is ambiguous and matches nothing: better to ask than to guess wrong.
 */
fun matchPreset(
    presets: List<Preset>,
    bluetoothName: String?,
    usbId: String?,
): Preset? {
    val scored =
        presets.mapNotNull { preset ->
            val byName =
                bluetoothName?.let { name ->
                    preset.bluetoothNamePrefixList.filter { name.startsWith(it) }.maxOfOrNull { it.length }
                }
            val byUsb =
                if (usbId != null && preset.usbIdList.any { it.equals(usbId, ignoreCase = true) }) Int.MAX_VALUE else null
            val score = listOfNotNull(byName, byUsb).maxOrNull() ?: return@mapNotNull null
            preset to score
        }
    val best = scored.maxOfOrNull { it.second } ?: return null
    val winners = scored.filter { it.second == best }
    return if (winners.size == 1) winners[0].first else null
}

/** User presets shadow shipped ones with the same id, so a bad shipped preset can be fixed locally. */
fun mergePresets(
    builtIn: List<Preset>,
    user: Collection<Preset>,
): List<Preset> {
    val byId = user.associateBy { it.id }
    return (builtIn.map { byId[it.id] ?: it } + user.filter { candidate -> builtIn.none { it.id == candidate.id } })
        .sortedBy { it.label.lowercase() }
}

private inline fun <reified T : Enum<T>> enumNamed(
    key: String,
    name: String,
    fallback: T,
): T {
    if (name.isEmpty()) return fallback
    if (name == "UNRECOGNIZED") throw PresetFormatException("$key cannot be UNRECOGNIZED")
    return try {
        java.lang.Enum.valueOf(T::class.java, name)
    } catch (exception: IllegalArgumentException) {
        throw PresetFormatException("unknown $key \"$name\"")
    }
}

private fun JSONObject.optFloat(
    key: String,
    fallback: Float,
): Float = this.optDouble(key, fallback.toDouble()).toFloat()

private fun settingsFromJson(json: JSONObject): PrinterSettings {
    val defaults = DEFAULT_PRINTER_SETTINGS
    return defaults
        .toBuilder()
        .setDriver(enumNamed("driver", json.optString("driver"), defaults.driver))
        .setDithering(enumNamed("dithering", json.optString("dithering"), defaults.dithering))
        .setMediaType(enumNamed("mediaType", json.optString("mediaType"), defaults.mediaType))
        .setDpi(json.optInt("dpi", defaults.dpi))
        .setWidth(json.optFloat("width", defaults.width))
        .setHeight(json.optFloat("height", defaults.height))
        .setMarginLeft(json.optFloat("marginLeft", defaults.marginLeft))
        .setMarginTop(json.optFloat("marginTop", defaults.marginTop))
        .setMarginRight(json.optFloat("marginRight", defaults.marginRight))
        .setMarginBottom(json.optFloat("marginBottom", defaults.marginBottom))
        .setGap(json.optFloat("gap", defaults.gap))
        .setDensity(json.optInt("density", defaults.density))
        .setSpeedLimit(json.optFloat("speedLimit", defaults.speedLimit))
        .setCutDelay(json.optFloat("cutDelay", defaults.cutDelay))
        .setCut(json.optBoolean("cut", defaults.cut))
        .setKeepAlive(json.optBoolean("keepAlive", defaults.keepAlive))
        .setSkipWhiteLinesAtPageEnd(json.optBoolean("skipWhiteLinesAtPageEnd", defaults.skipWhiteLinesAtPageEnd))
        .build()
}

private fun settingsToJson(settings: PrinterSettings): JSONObject =
    JSONObject()
        .put("driver", settings.driver.name)
        .put("dithering", settings.dithering.name)
        .put("dpi", settings.dpi)
        .put("width", settings.width.toDouble())
        .put("height", settings.height.toDouble())
        .put("marginLeft", settings.marginLeft.toDouble())
        .put("marginTop", settings.marginTop.toDouble())
        .put("marginRight", settings.marginRight.toDouble())
        .put("marginBottom", settings.marginBottom.toDouble())
        .put("mediaType", settings.mediaType.name)
        .put("gap", settings.gap.toDouble())
        .put("density", settings.density)
        .put("cut", settings.cut)
        .put("cutDelay", settings.cutDelay.toDouble())
        .put("keepAlive", settings.keepAlive)
        .put("speedLimit", settings.speedLimit.toDouble())
        .put("skipWhiteLinesAtPageEnd", settings.skipWhiteLinesAtPageEnd)

private fun stringsFrom(
    json: JSONObject,
    key: String,
): List<String> {
    val array = json.optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.getString(it).trim() }.filter { it.isNotEmpty() }
}

private fun validate(preset: Preset) {
    fun bad(reason: String): Nothing = throw PresetFormatException("preset \"${preset.id}\": $reason")
    if (!preset.id.matches(Regex("[a-z0-9][a-z0-9-]*"))) bad("id must be lowercase letters, digits and hyphens")
    if (preset.label.isBlank()) bad("label is required")
    val settings = preset.settings
    if (settings.dpi <= 0) bad("dpi must be positive")
    if (settings.width <= 0 || settings.height <= 0) bad("width and height must be positive")
    if (settings.density < 0 || settings.density > 15) bad("density must be 0-15, where 0 leaves the printer default")
    if (settings.gap < 0) bad("gap cannot be negative")
    if (listOf(settings.marginLeft, settings.marginTop, settings.marginRight, settings.marginBottom).any { it < 0 }) {
        bad("margins cannot be negative")
    }
}

fun parsePresets(document: String): List<Preset> {
    val root =
        try {
            JSONObject(document)
        } catch (exception: JSONException) {
            throw PresetFormatException("not a JSON object: ${exception.message}")
        }
    val schema = root.optInt("schema", 0)
    if (schema != PRESETS_SCHEMA) {
        throw PresetFormatException("unsupported schema $schema, this build reads $PRESETS_SCHEMA")
    }
    val array = root.optJSONArray("presets") ?: throw PresetFormatException("missing \"presets\" array")
    val presets =
        (0 until array.length()).map { index ->
            val entry =
                array.optJSONObject(index) ?: throw PresetFormatException("presets[$index] is not an object")
            val match = entry.optJSONObject("match") ?: JSONObject()
            val preset =
                Preset
                    .newBuilder()
                    .setId(entry.optString("id").trim())
                    .setLabel(entry.optString("label").trim())
                    .setTestedBy(entry.optString("testedBy").trim())
                    .setNotes(entry.optString("notes").trim())
                    .addAllBluetoothNamePrefix(stringsFrom(match, "bluetoothNamePrefix"))
                    .addAllUsbId(stringsFrom(match, "usbId"))
                    .setSettings(settingsFromJson(entry.optJSONObject("settings") ?: JSONObject()))
                    .build()
            validate(preset)
            preset
        }
    val duplicate = presets.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
    if (duplicate != null) throw PresetFormatException("duplicate preset id \"${duplicate.key}\"")
    return presets
}

/** Always a whole document, so anything exported can be imported back or dropped into presets.json. */
fun presetsToJson(presets: Collection<Preset>): String {
    val array = JSONArray()
    presets.forEach { preset ->
        val match = JSONObject()
        if (preset.bluetoothNamePrefixCount > 0) {
            match.put("bluetoothNamePrefix", JSONArray(preset.bluetoothNamePrefixList))
        }
        if (preset.usbIdCount > 0) {
            match.put("usbId", JSONArray(preset.usbIdList))
        }
        val entry =
            JSONObject()
                .put("id", preset.id)
                .put("label", preset.label)
                .put("testedBy", preset.testedBy)
        if (preset.notes.isNotEmpty()) {
            entry.put("notes", preset.notes)
        }
        if (match.length() > 0) {
            entry.put("match", match)
        }
        array.put(entry.put("settings", settingsToJson(preset.settings)))
    }
    return JSONObject().put("schema", PRESETS_SCHEMA).put("presets", array).toString(2)
}
