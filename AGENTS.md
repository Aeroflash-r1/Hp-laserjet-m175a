# Project context for AI agents

Read this whole file before doing anything else in this repo. It's the
condensed state of the project so you don't have to re-derive it.

## Goal

Build a native Android app that talks directly to an **HP LaserJet Pro 100
colorMFP M175a** over USB (no network — this printer model has no WiFi/
Ethernet), implementing print, scan (flatbed + ADF), manual duplex, and
paper-size handling without relying on a PC.

## Confirmed hardware facts (do not re-derive these — they're settled)

- USB VID:PID = **03F0:062A** (HP Inc.)
- USB-only connectivity (2.0). No network stack on this specific "a" model.
- 35-page ADF + flatbed. **No auto-duplexer** — manual duplex only
  (software must handle print-odd-pages / prompt-flip / print-even-pages).
- Composite USB device with 3 interfaces:
  | # | Class | String | Endpoints | Role |
  |---|---|---|---|---|
  | 0 | Vendor-specific (0xFF/0x02/0x01) | "HP SCAN" | Bulk EP3 in/out, Interrupt EP4 in | Scanning |
  | 1 | **Standard USB Printer Class** (0x07/0x01/0x02, bidirectional) | — | Bulk EP1 in/out | Printing |
  | 2 | Vendor-specific (0xFF/0x04/0x01) | "HP LEDM" | Bulk EP9 in/out, Interrupt EP10 in | Status/toner/device info (lower priority) |
- HP's own technical documentation confirms driver support for **PCL 6,
  PCL 5, and PostScript** — this is a real page-description-language
  printer, NOT a host-based/GDI-only raster device. This significantly
  de-risks the print side: interface 1 uses the documented USB-IF Printer
  Class transport, and the data format is standard PCL, not something
  fully proprietary.
- The scan interface (0) is vendor-specific but does **not** use raw SCL
  escape sequences. It speaks **HTTP/1.1 (chunked transfer encoding)**
  carrying **gSOAP 2.7 SOAP/XML** over bulk USB endpoints, using the
  namespace `http://tempuri.org/wscn.xsd`. See `PROTOCOL_NOTES.md`
  for the full protocol spec.

## Current progress

- [x] USB descriptor tree captured (VID/PID, interfaces, endpoints) — done via USBTreeView on Windows.
- [x] Confirmed PCL6/PCL5/PS support via HP docs.
- [x] One flatbed scan captured at 300dpi grayscale (USBPcap, on Windows) — `.pcapng` file to be dropped in `captures/`.
- [x] Extract and label the SCL command sequence from that capture (endpoint 0x03 OUT = commands, 0x83/0x04 IN = responses).
- [x] Repeat captures at other resolutions/color modes to find which bytes vary (diff them).
- [ ] Capture + analyze a print job (endpoint 0x01) to confirm the exact PCL flavor/headers the driver emits.
- [ ] Capture an ADF scan and diff against flatbed to find the feeder-specific commands.
- [ ] Capture a manual-duplex print job.
- [ ] Only after the above: start the actual Android implementation (USB Host API, PCL raster generation, SCL command replication).

## How captures get here

USB capture (USBPcap) only works on a Windows PC physically connected to
the printer — it can't be done inside this Codespace. See
`windows_capture_toolkit/` for the automation script used on that side.
The user copies/uploads resulting `.pcapng` files into `captures/`.

## Your job in this repo

Use `tools/analyze.py` to turn raw `.pcapng` files in `captures/` into
readable reports in `reports/`. Prefer filtering by endpoint
(`--endpoint 0x03` etc.) over dumping everything — the bulk image/raster
data is large and not useful for protocol reverse-engineering; the small
command-channel payloads are what matter.

When labeling bytes, be honest about confidence: this printer uses
gSOAP/SOAP-XML, not raw SCL. Flag "likely" vs "confirmed" rather than
asserting certainty.

Don't try to reconstruct or output actual scanned image content in
reports — only the protocol framing/commands matter here.

## Longer-term Android architecture notes (for when we get there)

- Printing: Android `UsbManager`/`UsbDeviceConnection`, claim interface 1,
  issue a `GET_DEVICE_ID` control request to confirm supported command
  set at runtime, then send PJL header + PCL (raster mode is simpler to
  implement than full PCL6 XL object encoding) over the bulk OUT endpoint.
- Scanning: claim interface 0, replicate the captured SOAP/XML command
  sequence over bulk EP3, poll/read status via EP3 IN and interrupt EP4,
  then stream the raster data back and decode it into a bitmap/PDF.
- Manual duplex: no special protocol needed — it's a UI/workflow problem
  (print odd pages, prompt user to flip the stack, print even pages in
  the correct order).
