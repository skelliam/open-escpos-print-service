package com.farminos.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PresetsTest {
    private val shipped = File("src/main/assets/$PRESETS_ASSET")

    private fun document(vararg entries: String) = """{"schema": $PRESETS_SCHEMA, "presets": [${entries.joinToString(",")}]}"""

    private fun preset(
        id: String = "a-printer",
        settings: String = """"driver": "TSPL", "dpi": 203, "width": 10.16, "height": 15.24""",
        match: String = "",
    ) = """{"id": "$id", "label": "A printer"${if (match.isEmpty()) "" else ", \"match\": $match"}, "settings": {$settings}}"""

    private fun parseOne(document: String) = parsePresets(document).single()

    private fun expectRejection(
        because: String,
        document: String,
    ) {
        try {
            parsePresets(document)
            fail("expected $because to be rejected")
        } catch (exception: PresetFormatException) {
            assertTrue(exception.message!!.isNotBlank())
        }
    }

    @Test
    fun shippedPresetsParse() {
        assertTrue("${shipped.absolutePath} is missing", shipped.isFile)
        val presets = parsePresets(shipped.readText())
        assertTrue("no presets are shipped", presets.isNotEmpty())
    }

    @Test
    fun shippedPresetsRoundTripThroughJson() {
        val presets = parsePresets(shipped.readText())

        assertEquals(presets, parsePresets(presetsToJson(presets)))
    }

    @Test
    fun applyingAPresetKeepsTheFieldsThatIdentifyThePrinter() {
        val printer =
            DEFAULT_PRINTER_SETTINGS
                .toBuilder()
                .setEnabled(true)
                .setAddress("DC:1D:30:B3:6E:7A")
                .setName("a paired printer")
                .setInterface(Interface.BLUETOOTH)
                .build()

        val applied = applyPreset(parseOne(document(preset())), printer)

        assertTrue(applied.enabled)
        assertEquals("DC:1D:30:B3:6E:7A", applied.address)
        assertEquals("a paired printer", applied.name)
        assertEquals(Interface.BLUETOOTH, applied.`interface`)
        assertEquals(Driver.TSPL, applied.driver)
    }

    @Test
    fun anOmittedFieldFallsBackToTheDefaultNotToTheCurrentValue() {
        val printer =
            DEFAULT_PRINTER_SETTINGS
                .toBuilder()
                .setDensity(8)
                .setGap(0.3F)
                .build()

        val applied = applyPreset(parseOne(document(preset())), printer)

        assertEquals(DEFAULT_PRINTER_SETTINGS.density, applied.density)
        assertEquals(DEFAULT_PRINTER_SETTINGS.gap, applied.gap, 0F)
    }

    @Test
    fun aPresetSavedFromAPrinterDropsThatPrintersIdentity() {
        val printer =
            DEFAULT_PRINTER_SETTINGS
                .toBuilder()
                .setEnabled(true)
                .setAddress("DC:1D:30:B3:6E:7A")
                .setName("a paired printer")
                .setDensity(8)
                .build()

        val saved = presetFromPrinter("user-1", "mine", printer).settings

        assertEquals("", saved.address)
        assertEquals("", saved.name)
        assertTrue(!saved.enabled)
        assertEquals(8, saved.density)
    }

    @Test
    fun theLongestMatchingPrefixWins() {
        val presets =
            parsePresets(
                document(
                    preset(id = "family", match = """{"bluetoothNamePrefix": ["JD-"]}"""),
                    preset(id = "model", match = """{"bluetoothNamePrefix": ["JD-268"]}"""),
                ),
            )

        assertEquals("model", matchPreset(presets, "JD-268BT", null)?.id)
        assertEquals("family", matchPreset(presets, "JD-100", null)?.id)
    }

    @Test
    fun anAmbiguousMatchIsNoMatch() {
        val presets =
            parsePresets(
                document(
                    preset(id = "one", match = """{"bluetoothNamePrefix": ["POS"]}"""),
                    preset(id = "two", match = """{"bluetoothNamePrefix": ["POS"]}"""),
                ),
            )

        assertNull(matchPreset(presets, "POS-80", null))
    }

    @Test
    fun usbIdsMatchCaseInsensitivelyAndNamesDoNotLeakAcrossTransports() {
        val presets = parsePresets(document(preset(id = "usb-one", match = """{"usbId": ["04b8:0202"]}""")))

        assertNotNull(matchPreset(presets, null, "04B8:0202"))
        assertNull(matchPreset(presets, "04b8:0202", null))
    }

    @Test
    fun aUserPresetShadowsAShippedOneWithTheSameId() {
        val builtIn = parsePresets(document(preset(id = "shared")))
        val user = parseOne(document(preset(id = "shared", settings = """"driver": "ESC_POS"""")))

        val merged = mergePresets(builtIn, listOf(user))

        assertEquals(1, merged.size)
        assertEquals(Driver.ESC_POS, merged[0].settings.driver)
    }

    @Test
    fun malformedPresetsAreRejectedRatherThanSilentlyDefaulted() {
        expectRejection("an unknown driver", document(preset(settings = """"driver": "TSPL2"""")))
        expectRejection("an UNRECOGNIZED enum", document(preset(settings = """"driver": "UNRECOGNIZED"""")))
        expectRejection("a density above 15", document(preset(settings = """"density": 99""")))
        expectRejection("a zero width", document(preset(settings = """"width": 0""")))
        expectRejection("a negative margin", document(preset(settings = """"marginTop": -1""")))
        expectRejection("an uppercase id", document(preset(id = "NotAnId")))
        expectRejection("duplicate ids", document(preset(id = "same"), preset(id = "same")))
        expectRejection("a future schema", """{"schema": 99, "presets": []}""")
        expectRejection("a missing presets array", """{"schema": $PRESETS_SCHEMA}""")
        expectRejection("something that is not JSON", "not json at all")
    }
}
