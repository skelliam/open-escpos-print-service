# Printer presets

A preset is a named bundle of printer settings — driver, paper size, margins,
dithering and the TSPL media options. Applying one overwrites every setting
except the four that identify the printer itself: whether it is enabled, its
address, its name and its interface.

The app ships a few presets in [`app/src/main/assets/presets.json`](../app/src/main/assets/presets.json)
and stores any the user saves or imports alongside the printer settings.

## Nobody verifies these

There is no review process that can confirm a preset works, because nobody but
the contributor has the printer. So a preset records who claims to have printed
with it, in `testedBy`, and the app shows that next to the name. A preset with
an empty `testedBy` is displayed as "not tested by anyone".

The worst a wrong preset can do is waste labels — the wrong size, the wrong
density, or a media type that makes the printer feed looking for a gap that is
not there. All of it is fixable by hand in the same screen that applied it.
Still: only contribute a preset for a printer you have actually printed from,
and put your handle in `testedBy` when you do.

## Contributing one

1. Configure a printer in the app until it prints correctly.
2. Open the printer, give the preset a name and tap **Save these settings as a
   preset**.
3. Scroll to **Presets** at the bottom of the screen and tap **Copy** next to
   the one you saved.
4. Paste it into an issue, or into `presets.json` in a pull request — take the
   object out of the `presets` array and add it to the array in that file.
5. Fill in `testedBy` with your GitHub handle, and `id` with something readable
   like `munbyn-itpp941`. The `id` the app generates is a UUID.

`./gradlew test` parses the shipped file and validates every entry, so a
malformed preset fails the build rather than the app.

## Format

Exporting always produces a whole document, so anything copied out of the app
can be pasted back into it.

```json
{
  "schema": 1,
  "presets": [
    {
      "id": "jadens-jd-268bt",
      "label": "Jadens JD-268BT (4x6 label)",
      "testedBy": "skelliam",
      "notes": "203 dpi confirmed by measurement.",
      "match": {
        "bluetoothNamePrefix": ["JD-268"],
        "usbId": ["04b8:0202"]
      },
      "settings": {
        "driver": "TSPL",
        "dithering": "NONE",
        "dpi": 203,
        "width": 10.16,
        "height": 15.24,
        "marginLeft": 0.0,
        "marginTop": 0.0,
        "marginRight": 0.0,
        "marginBottom": 0.0,
        "mediaType": "GAP",
        "gap": 0.3,
        "density": 8,
        "cut": false,
        "cutDelay": 0.0,
        "keepAlive": false,
        "speedLimit": 2.0,
        "skipWhiteLinesAtPageEnd": false
      }
    }
  ]
}
```

Enums are written as names rather than numbers, so the format does not depend
on the numbering in `settings.proto`. `driver` is `ESC_POS`, `CPCL` or `TSPL`;
`dithering` is `GRADIENT` or `NONE`; `mediaType` is `GAP`, `BLACK_MARK` or
`CONTINUOUS`.

Every setting should be present. A missing one falls back to the app default,
not to whatever the printer was set to before — a preset that only changed some
of the settings would leave the rest in a state nobody could reason about.

Lengths are centimetres. `density` is 1-15, or 0 to leave the printer's own
default alone. Sizes and margins cannot be negative.

## Matching

`match` is optional. Where it is present, a newly discovered printer gets the
preset applied automatically — but only on discovery, when there is no
configuration to overwrite. After that a match is only ever a suggestion the
user accepts with a button.

`bluetoothNamePrefix` matches the start of the Bluetooth device name, and the
longest matching prefix wins, so a preset for one model beats a preset for its
whole family. `usbId` is the `vendorId:productId` pair in hex, as shown in the
app. If two presets match equally well the match is ambiguous and neither is
applied.

Leave `match` out for a preset that describes a paper size rather than a
device, so it is never applied on its own.
