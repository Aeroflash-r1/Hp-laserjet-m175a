package com.hpm175.scan

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.hpm175.scan.USB_PERMISSION"
        private const val HP_VID = 0x03F0
        private const val HP_PID = 0x062A
        private const val READ_TIMEOUT_MS = 5000
        private const val TAG = "HPScan"

        // Scan interface: class=0xFF, subclass=0x02, protocol=0x01
        private const val IFACE_CLASS = 0xFF
        private const val IFACE_SUBCLASS = 0x02
        private const val IFACE_PROTOCOL = 0x01
    }

    private lateinit var usbManager: UsbManager
    private lateinit var btnScan: Button
    private lateinit var imageView: ImageView
    private lateinit var tvStatus: TextView

    private var deviceConnection: UsbDeviceConnection? = null
    private var scanInterface: UsbInterface? = null
    private var epOut: UsbEndpoint? = null
    private var epIn: UsbEndpoint? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    log("USB permission granted")
                    connectToDevice(device)
                } else {
                    log("USB permission denied")
                }
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                log("Device detached")
                disconnect()
            }
        }
    }

    private val log = StringBuilder()
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        imageView = findViewById(R.id.imageView)
        tvStatus = findViewById(R.id.tvStatus)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        btnScan.setOnClickListener { startScan() }

        // Auto-find and connect to printer
        findAndRequestDevice()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        disconnect()
        executor.shutdown()
    }

    private fun log(msg: String) {
        val thread = Thread.currentThread().name
        val line = "[$thread] $msg"
        runOnUiThread {
            log.appendLine(line)
            tvStatus.text = log.toString()
        }
    }

    private fun appendLog(msg: String) {
        log.appendLine(msg)
        runOnUiThread { tvStatus.text = log.toString() }
    }

    private fun findAndRequestDevice() {
        val deviceList = usbManager.deviceList
        log("Found ${deviceList.size} USB device(s)")

        var target: UsbDevice? = null
        for ((_, device) in deviceList) {
            log("  ${device.deviceName}: VID=${String.format("%04X", device.vendorId)} PID=${String.format("%04X", device.productId)}")
            if (device.vendorId == HP_VID && device.productId == HP_PID) {
                target = device
            }
        }

        if (target == null) {
            log("HP M175a not found. Plug in the printer.")
            return
        }

        log("Found HP M175a: ${target.deviceName}")

        if (usbManager.hasPermission(target)) {
            log("USB permission already granted")
            connectToDevice(target)
        } else {
            log("Requesting USB permission...")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(target, permissionIntent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        // Find the scan interface by descriptor fields, not hardcoded index
        var foundInterface: UsbInterface? = null
        var bulkOut: UsbEndpoint? = null
        var bulkIn: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == IFACE_CLASS &&
                iface.interfaceSubclass == IFACE_SUBCLASS &&
                iface.interfaceProtocol == IFACE_PROTOCOL) {
                log("Found scan interface ${iface.id}: ${iface.name}")
                foundInterface = iface

                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT) {
                            bulkOut = ep
                            log("  Bulk OUT endpoint: 0x${String.format("%02X", ep.address)}")
                        } else {
                            bulkIn = ep
                            log("  Bulk IN endpoint: 0x${String.format("%02X", ep.address)}")
                        }
                    }
                }
                break
            }
        }

        if (foundInterface == null || bulkOut == null || bulkIn == null) {
            log("ERROR: Could not find scan interface or endpoints")
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            log("ERROR: Failed to open USB device")
            return
        }

        if (!connection.claimInterface(foundInterface, true)) {
            log("ERROR: Failed to claim interface")
            connection.close()
            return
        }

        deviceConnection = connection
        scanInterface = foundInterface
        epOut = bulkOut
        epIn = bulkIn
        btnScan.isEnabled = true
        log("Connected — ready to scan")
    }

    private fun disconnect() {
        deviceConnection?.let { conn ->
            scanInterface?.let { conn.releaseInterface(it) }
            conn.close()
        }
        deviceConnection = null
        scanInterface = null
        epOut = null
        epIn = null
        runOnUiThread { btnScan.isEnabled = false }
    }

    // --- USB I/O helpers ---

    private fun sendChunkedHttp(xmlBody: String): Int {
        val conn = deviceConnection ?: throw IllegalStateException("Not connected")
        val out = epOut ?: throw IllegalStateException("No OUT endpoint")
        val bodyBytes = xmlBody.toByteArray(StandardCharsets.UTF_8)
        val chunkSizeHex = bodyBytes.size.toString(16)

        // Build full HTTP POST with chunked encoding
        val header = buildString {
            append("POST / HTTP/1.1\r\n")
            append("Host: 127.0.0.1:80\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Content-Type: application/soap+xml; charset=utf-8\r\n")
            append("User-Agent: gSOAP/2.7\r\n")
            append("Accept: application/soap+xml, multipart/related\r\n")
            append("Connection: Keep-Alive\r\n")
            append("\r\n")
        }

        // Total packet: header + chunk-size\r\n + body + 0\r\n\r\n
        val fullPacket = ByteBuffer.allocate(
            header.toByteArray(StandardCharsets.US_ASCII).size +
            chunkSizeHex.toByteArray(StandardCharsets.US_ASCII).size + 2 + // \r\n
            bodyBytes.size + 4 + // 0\r\n\r\n
            2 // final \r\n after body chunk
        ).apply {
            put(header.toByteArray(StandardCharsets.US_ASCII))
            put(chunkSizeHex.toByteArray(StandardCharsets.US_ASCII))
            put("\r\n".toByteArray(StandardCharsets.US_ASCII))
            put(bodyBytes)
            put("\r\n0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        }.array()

        val sent = conn.bulkTransfer(out, fullPacket, fullPacket.size, READ_TIMEOUT_MS)
        log("Sent $sent bytes (${fullPacket.size} total)")
        return sent
    }

    private fun readHttpResponse(): ByteArray {
        val conn = deviceConnection ?: throw IllegalStateException("Not connected")
        val inp = epIn ?: throw IllegalStateException("No IN endpoint")

        val rawChunks = mutableListOf<ByteArray>()
        val buf = ByteArray(16384)

        // Read until timeout or no more data
        while (true) {
            val n = conn.bulkTransfer(inp, buf, buf.size, READ_TIMEOUT_MS)
            if (n <= 0) break
            rawChunks.add(buf.copyOf(n))
        }

        if (rawChunks.isEmpty()) {
            throw Exception("No response received")
        }

        val raw = merge(rawChunks)
        log("Raw response: ${raw.size} bytes")

        // Parse HTTP response
        val rawText = String(raw, StandardCharsets.US_ASCII)
        val headerEnd = rawText.indexOf("\r\n\r\n")
        if (headerEnd < 0) {
            log("WARNING: Could not find HTTP header boundary")
            return raw
        }

        val headers = rawText.substring(0, headerEnd)
        log("HTTP headers:\n$headers")

        val headerBytesSize = headerEnd + 4
        val bodyWithChunks = raw.copyOfRange(headerBytesSize, raw.size)

        // Dechunk: split on \r\n, parse hex chunk sizes
        val body = dechunkBody(bodyWithChunks)
        log("Dechunked body: ${body.size} bytes")
        return body
    }

    private fun dechunkBody(data: ByteArray): ByteArray {
        val result = mutableListOf<ByteArray>()
        var pos = 0

        while (pos < data.size) {
            // Find end of chunk-size line
            val lineEnd = findCrLf(data, pos)
            if (lineEnd < 0) break

            val sizeLine = String(data, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim()
            pos = lineEnd + 2

            val chunkSize = sizeLine.toIntOrNull(16) ?: break
            if (chunkSize == 0) break

            if (pos + chunkSize <= data.size) {
                result.add(data.copyOfRange(pos, pos + chunkSize))
            }
            pos += chunkSize + 2 // skip chunk data + \r\n after chunk
        }

        return merge(result)
    }

    private fun findCrLf(data: ByteArray, from: Int): Int {
        for (i in from until data.size - 1) {
            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte()) return i
        }
        return -1
    }

    private fun merge(chunks: List<ByteArray>): ByteArray {
        val total = chunks.sumOf { it.size }
        val result = ByteArray(total)
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c, 0, result, offset, c.size)
            offset += c.size
        }
        return result
    }

    // --- Scan pipeline ---

    private fun startScan() {
        if (deviceConnection == null) {
            log("Not connected to printer")
            return
        }
        btnScan.isEnabled = false
        imageView.setImageBitmap(null)
        log.setLength(0)
        log("=== Starting scan ===")

        executor.execute {
            try {
                val t0 = System.currentTimeMillis()

                // Step 1: GetScannerElements
                log("--- Step 1: GetScannerElements ---")
                val step1 = System.currentTimeMillis()
                sendChunkedHttp(GET_SCANNER_ELEMENTS)
                readHttpResponse()
                log("Step 1 done in ${System.currentTimeMillis() - step1}ms")

                // Step 2: CreateScanJobRequest
                log("--- Step 2: CreateScanJobRequest ---")
                val step2 = System.currentTimeMillis()
                sendChunkedHttp(CREATE_SCAN_JOB)
                val body = readHttpResponse()
                log("Step 2 done in ${System.currentTimeMillis() - step2}ms")

                // Dump raw CreateScanJob response to /sdcard/Download/hp_debug/
                dumpDebugResponse("create_scan_job_response.txt", body)

                // Try to find JobId in the response
                val jobId = extractJobId(body)
                log("JobId result: $jobId")

                // Step 3: RetrieveImageRequest
                val effectiveJobId = jobId ?: "1"
                log("--- Step 3: RetrieveImageRequest (JobId=$effectiveJobId) ---")
                val step3 = System.currentTimeMillis()
                val retrieveXml = RETRIEVE_IMAGE_TEMPLATE.replace("{JOB_ID}", effectiveJobId)
                sendChunkedHttp(retrieveXml)
                val imageBody = readHttpResponse()
                log("Step 3 done in ${System.currentTimeMillis() - step3}ms")

                // Dump raw retrieve response
                dumpRawResponse("retrieve_image_response.raw", imageBody)

                // Try to extract JPEG
                val image = extractJpeg(imageBody)
                if (image != null) {
                    runOnUiThread {
                        imageView.setImageBitmap(image)
                    }
                    log("Image displayed: ${image.width}x${image.height}")
                } else {
                    log("scan pipeline ran, but image extraction failed — raw data saved to hp_debug/")
                }

                log("=== Total: ${System.currentTimeMillis() - t0}ms ===")
                runOnUiThread { btnScan.isEnabled = true }
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                e.printStackTrace()
                runOnUiThread { btnScan.isEnabled = true }
            }
        }
    }

    private fun dumpDebugResponse(filename: String, body: ByteArray) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "hp_debug")
            dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { fos ->
                fos.write("=== Raw body (${body.size} bytes) ===\n".toByteArray())
                fos.write("=== Hex dump (first 2048 bytes) ===\n".toByteArray())
                val hexLimit = minOf(body.size, 2048)
                for (i in 0 until hexLimit step 16) {
                    val end = minOf(i + 16, body.size)
                    val hex = body.sliceArray(i until end).joinToString(" ") {
                        String.format("%02X", it)
                    }
                    val ascii = body.sliceArray(i until end).map { b ->
                        if (b in 0x20..0x7E) b.toInt().toChar() else '.'
                    }.joinToString("")
                    fos.write(String.format("%04X: %-48s |%s|\n", i, hex, ascii).toByteArray())
                }
                fos.write("\n=== Best-effort UTF-8 text ===\n".toByteArray())
                fos.write(body.toString(Charsets.UTF_8).toByteArray())
                fos.write("\n".toByteArray())
            }
            log("Dumped to /sdcard/Download/hp_debug/$filename")
        } catch (e: Exception) {
            log("WARNING: Could not dump $filename: ${e.message}")
        }
    }

    private fun dumpRawResponse(filename: String, body: ByteArray) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "hp_debug")
            dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { it.write(body) }
            log("Raw dump: /sdcard/Download/hp_debug/$filename (${body.size} bytes)")
        } catch (e: Exception) {
            log("WARNING: Could not dump $filename: ${e.message}")
        }
    }

    private fun extractJobId(body: ByteArray): String? {
        val text = body.toString(Charsets.UTF_8)

        // Try multiple possible tag shapes
        val patterns = listOf(
            Regex("<JobId>(.*?)</JobId>"),
            Regex("<jobId>(.*?)</jobId>"),
            Regex("<job_id>(.*?)</job_id>"),
            Regex("JobId>\\s*(\\d+)")
        )

        for (p in patterns) {
            val match = p.find(text)
            if (match != null) {
                val id = match.groupValues[1].trim()
                log("Found JobId via pattern: $id")
                return id
            }
        }

        // Last resort: just log what we got
        log("No JobId tag found. Body text (first 500 chars): ${text.take(500)}")
        return null
    }

    private fun extractJpeg(data: ByteArray): android.graphics.Bitmap? {
        // Find JPEG SOI marker (FFD8) and EOI marker (FFD9)
        var soi = -1
        var eoi = -1

        for (i in 0 until data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) {
                soi = i
                break
            }
        }

        if (soi < 0) {
            log("No JPEG SOI marker (FFD8) found in ${data.size} bytes")
            return null
        }

        for (i in data.size - 2 downTo soi + 2) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                eoi = i + 2
                break
            }
        }

        if (eoi <= soi) {
            log("No JPEG EOI marker (FFD9) found after SOI at $soi")
            return null
        }

        val jpegBytes = data.copyOfRange(soi, eoi)
        log("Extracted JPEG: $soi..$eoi (${jpegBytes.size} bytes)")

        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    // --- Hardcoded SOAP/XML payloads ---

    private val GET_SCANNER_ELEMENTS = """
        <?xml version="1.0" encoding="UTF-8"?><SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" xmlns:SOAP-ENC="http://www.w3.org/2003/05/soap-encoding" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:wscn="http://tempuri.org/wscn.xsd"><SOAP-ENV:Body><wscn:GetScannerElements></wscn:GetScannerElements></SOAP-ENV:Body></SOAP-ENV:Envelope>
    """.trimIndent()

    private val CREATE_SCAN_JOB = """
        <?xml version="1.0" encoding="UTF-8"?><SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" xmlns:SOAP-ENC="http://www.w3.org/2003/05/soap-encoding" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:wscn="http://tempuri.org/wscn.xsd"><SOAP-ENV:Body><wscn:CreateScanJobRequest><ScanIdentifier></ScanIdentifier><ScanTicket><JobDescription></JobDescription><DocumentParameters><Format>jfif</Format><CompressionQualityFactor>0</CompressionQualityFactor><ImagesToTransfer>0</ImagesToTransfer><InputSource>Platen</InputSource><ContentType>Auto</ContentType><InputSize><InputMediaSize><Width>8500</Width><Height>11690</Height></InputMediaSize><DocumentSizeAutoDetect>false</DocumentSizeAutoDetect></InputSize><Exposure><AutoExposure>false</AutoExposure><ExposureSettings><Contrast>0</Contrast></ExposureSettings></Exposure><MediaSides><MediaFront><ScanRegion><ScanRegionXOffset>0</ScanRegionXOffset><ScanRegionYOffset>0</ScanRegionYOffset><ScanRegionWidth>8270</ScanRegionWidth><ScanRegionHeight>11690</ScanRegionHeight></ScanRegion><ColorProcessing>GrayScale8</ColorProcessing><Resolution><Width>300</Width><Height>300</Height></Resolution></MediaFront></MediaSides></DocumentParameters><RetrieveImageTimeout>300</RetrieveImageTimeout><ScanManufacturingParameters><DisableImageProcessing>false</DisableImageProcessing></ScanManufacturingParameters></ScanTicket></wscn:CreateScanJobRequest></SOAP-ENV:Body></SOAP-ENV:Envelope>
    """.trimIndent()

    private val RETRIEVE_IMAGE_TEMPLATE = """
        <?xml version="1.0" encoding="UTF-8"?><SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" xmlns:SOAP-ENC="http://www.w3.org/2003/05/soap-encoding" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:wscn="http://tempuri.org/wscn.xsd"><SOAP-ENV:Body><wscn:RetrieveImageRequest><JobId>{JOB_ID}</JobId><JobToken></JobToken><DocumentDescription></DocumentDescription></wscn:RetrieveImageRequest></SOAP-ENV:Body></SOAP-ENV:Envelope>
    """.trimIndent()
}
