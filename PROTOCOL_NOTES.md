# HP LaserJet Pro M175a — Scan Protocol Notes

**Date:** 2026-07-24
**Device:** HP LaserJet Pro 100 colorMFP M175a (USB VID:PID `03F0:062A`)
**Interface:** 0 ("HP SCAN") — Vendor-specific (0xFF), class 0x02, subclass 0x01
**Endpoints:** Bulk EP 0x03 OUT (commands), Bulk EP 0x83 IN (responses), Interrupt EP 0x04 IN (status)

---

## Framing: gSOAP over USB

This printer does **not** use raw SCL/ESCP escape sequences on the command
channel. Instead it speaks **gSOAP 2.7** — a SOAP/XML-over-HTTP stack
transported over USB bulk endpoints.

Every outbound command (EP 0x03) is a chunked HTTP POST:

```
POST / HTTP/1.1
Host: 127.0.0.1:80
Transfer-Encoding: chunked
Content-Type: application/soap+xml; charset=utf-8
User-Agent: gSOAP/2.7
Accept: application/soap+xml, multipart/related
Connection: Keep-Alive

<hex-encoded-chunk-size>
<hex-encoded-SOAP-XML-body>
0
```

Every inbound response (EP 0x83) starts with:

```
HTTP/1.1 202 ACCEPTED
Content-Type: application/soap+xml; charset=utf-8
Server: Linux/2.6.9, HP Linux Imaging and Printing (HPLIP)
Keep-Alive: timeout=5, max=100
Connection: Keep-Alive
Transfer-Encoding: chunked
```

Chunk sizes are hex-encoded (e.g. `4e7\n` = 1255 bytes), terminated by `0`.

---

## SOAP namespace

All scan-related elements live under `http://schemas.hp.com/imaging/escl/2011/05/03`
(`wscn:` prefix).  The scanner responds with the same namespace in responses.

---

## Command sequence (from captured captures)

The 4 captures (`scan_flatbed_300dpi_{greyscale,colour}.pcapng`,
`scan_adf_300dpi_{greyscale,colour}.pcapng`) all follow the same
command sequence on EP 0x03:

| # | Operation | SOAP action (in `action` header) |
|---|-----------|----------------------------------|
| 1 | GetScannerElements | Query device capabilities |
| 2 | SetScannerConfig | Set scan parameters |
| 3 | CreateScanJob | Submit the actual scan job |
| 4 | CreateScanJob | (duplicate/confirmation) |
| 5 | GetScanCaps | Retrieve scan capabilities |
| 6 | Calibrate | Request calibration |
| 7 | GetCalibrationCaps | Query calibration options |

Responses on EP 0x83 follow the same order, interleaved with status
updates via the interrupt endpoint (EP 0x04).

---

## CreateScanJobRequest — key parameters

The `<wscn:CreateScanJobRequest>` element contains all scan configuration:

### Colour mode
```xml
<ColorProcessing>GrayScale8</ColorProcessing>   <!-- greyscale, 8-bit -->
<ColorProcessing>RGB24</ColorProcessing>          <!-- colour, 24-bit -->
```

### Input source
```xml
<InputSource>Platen</InputSource>   <!-- flatbed glass -->
<InputSource>ADF</InputSource>      <!-- automatic document feeder -->
```

### Paper size (InputMediaSize, in 1/100 mm)
| Paper | Width | Height |
|-------|-------|--------|
| A4 (flatbed) | 8500 | 11690 |
| A4 (ADF) | 8500 | 14000 |
| Letter (flatbed) | 8500 | 11690 |
| Letter (ADF) | 8500 | 14000 |

Note: ADF height of 14000 vs flatbed 11690 likely reflects the longer
physical paper path through the ADF mechanism. The ADF also sets
`<ScanRegionXOffset>113</ScanRegionXOffset>` (vs `0` for platen),
suggesting the ADF sensor is offset ~11.3 mm from the platen edge.

### Scan region (sub-region within paper size)
```xml
<ScanRegionXOffset>0</ScanRegionXOffset>         <!-- ADF: 113 -->
<ScanRegionYOffset>0</ScanRegionYOffset>
<ScanRegionWidth>8270</ScanRegionWidth>           <!-- 82.7mm -->
<ScanRegionHeight>11690</ScanRegionHeight>        <!-- same as paper height -->
```

### Other parameters (identical across all 4 captures)
```xml
<Format>jfif</Format>
<CompressionQualityFactor>0</CompressionQualityFactor>
<ImagesToTransfer>0</ImagesToTransfer>
<ContentType>Auto</ContentType>
<AutoExposure>false</AutoExposure>
<Contrast>0</Contrast>
<DocumentSizeAutoDetect>false</DocumentSizeAutoDetect>
<RetrieveImageTimeout>300</RetrieveImageTimeout>
<DisableImageProcessing>false</DisableImageProcessing>
```

### Resolution
```xml
<Resolution><Width>300</Width><Height>300</Height></Resolution>
```
All 4 captures used 300 dpi.  Other resolutions not yet captured.

---

## Known SCL bytes (in responses, EP 0x83)

The ESC-byte sequences documented in `captures/README.md` appear in the
**response** data on EP 0x83, NOT in the command channel on EP 0x03.
These are likely part of the JPEG/JFIF image stream framing or a
secondary control channel within the response, but they are not the
primary way scan jobs are submitted.

```
ESC * s 1 M — Set color mode (3=greyscale, 4=colour)
ESC & a <h>V — Set vertical position (paper size related)
ESC E — Page eject / form feed
ESC O — Unknown
```

---

## Key unknowns

- **Other resolutions:** No captures at 150/200/600 dpi yet. The XML
  `<Resolution>` element likely takes arbitrary integer values, but
  printer-reported limits are not yet captured.
- **PDF vs JPEG output:** All captures used `jfif`. The `<Format>` element
  may also accept `pdf` or `raw` — not yet tested.
- **Calibration flow:** What `GetCalibrationCaps` returns and what
  `Calibrate` does in practice.
- **ADF multi-page flow:** How the printer signals page boundaries and
  completion for multi-page ADF scans.
- **Error handling:** What SOAP fault XML looks like on EP 0x83.
