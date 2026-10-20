package com.example.mimirmtg

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Implementación del protocolo WalkPrint para impresoras térmicas BLE (Cat Printers).
 * Utiliza el encabezado 0x51 0x78 y paquetes de 56 bytes para datos de imagen.
 */
object CatPrinter {
    private const val TAG = "CatPrinter"
    private val gattMutex = Mutex()
    private var negotiatedMtu = 23

    private val SERVICE_UUIDS = listOf(
        UUID.fromString("0000af30-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    )
    private val CHARACTERISTIC_UUIDS = listOf(
        UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616"), // ISSC RX (Write)
        UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000af01-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
    )

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun convertToMonochromeDithered(bitmap: Bitmap): List<ByteArray> {
        val width = 384
        val height = ((bitmap.height.toFloat() / bitmap.width.toFloat()) * width).toInt()
        
        // Redimensionado con mayor calidad si es posible, o simplemente asegurando el ancho
        val resized = if (bitmap.width == width) bitmap 
                      else Bitmap.createScaledBitmap(bitmap, width, height, true)
        
        val pixels = IntArray(width * height)
        resized.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            
            // Luminancia con corrección Gamma agresiva (0.45) para negros profundos y blancos limpios
            val luminance = 0.299f * r + 0.587f * g + 0.114f * b
            val normalized = luminance / 255f
            val corrected = Math.pow(normalized.toDouble(), 0.45).toFloat() * 255f
            
            // Aumento de contraste y ajuste de curva para resaltar texto de MTG
            val v = (corrected - 128f) * 2.5f + 128f
            gray[i] = v.coerceIn(0f, 255f)
        }

        // Sharpening mejorado (High-Pass Filter)
        val sharpened = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                    val center = gray[idx]
                    // Kernel de enfoque más agresivo
                    val surrounding = gray[idx - 1] + gray[idx + 1] + gray[idx - width] + gray[idx + width] +
                                    gray[idx - width - 1] + gray[idx - width + 1] + 
                                    gray[idx + width - 1] + gray[idx + width + 1]
                    val valSharpened = center * 13f - surrounding * 1.5f
                    sharpened[idx] = valSharpened.coerceIn(0f, 255f)
                } else {
                    sharpened[idx] = gray[idx]
                }
            }
        }

        // Difusión de error Floyd-Steinberg (Mejor para arte de MTG que Atkinson)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val oldVal = sharpened[idx]
                val newVal = if (oldVal > 128) 255f else 0f
                val err = oldVal - newVal
                
                sharpened[idx] = newVal
                if (x + 1 < width) sharpened[idx + 1] += err * 7f / 16f
                if (y + 1 < height) {
                    if (x - 1 >= 0) sharpened[idx + width - 1] += err * 3f / 16f
                    sharpened[idx + width] += err * 5f / 16f
                    if (x + 1 < width) sharpened[idx + width + 1] += err * 1f / 16f
                }
            }
        }

        val rows = mutableListOf<ByteArray>()
        for (y in 0 until height) {
            val row = ByteArray(48)
            for (x in 0 until width) {
                // Usar el array 'sharpened' que ya tiene el enfoque y el dithering aplicados
                if (sharpened[y * width + x] < 128) {
                    val byteIdx = x / 8
                    val bitIdx = 7 - (x % 8)
                    row[byteIdx] = (row[byteIdx].toInt() or (1 shl bitIdx)).toByte()
                }
            }
            rows.add(row)
        }
        return rows
    }

    private fun makePacket(cmd: Byte, payload: ByteArray, includeHeaderInCrc: Boolean = false): ByteArray {
        val len = payload.size
        val packet = ByteArray(len + 8)
        packet[0] = 0x51
        packet[1] = 0x78
        packet[2] = cmd
        packet[3] = 0x00
        packet[4] = (len and 0xFF).toByte()
        packet[5] = ((len shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 6, len)

        var crc = 0
        // Para impresoras YHK, el CRC suele ser parcial (desde el byte de comando)
        val startIdx = if (includeHeaderInCrc) 0 else 2
        for (i in startIdx until (6 + len)) {
            crc += (packet[i].toInt() and 0xFF)
        }
        packet[6 + len] = (crc and 0xFF).toByte()
        packet[7 + len] = 0x16.toByte() // El footer 0x16 es el más común en WalkPrint
        
        return packet
    }

    private fun makeEscPosRasterPacket(rows: List<ByteArray>): ByteArray {
        val widthBytes = 48
        val height = rows.size
        val stream = java.io.ByteArrayOutputStream()
        // GS v 0 m xL xH yL yH
        stream.write(0x1D)
        stream.write(0x76)
        stream.write(0x30)
        stream.write(0x00)
        stream.write(widthBytes and 0xFF)
        stream.write((widthBytes shr 8) and 0xFF)
        stream.write(height and 0xFF)
        stream.write((height shr 8) and 0xFF)
        
        for (row in rows) {
            stream.write(row)
        }
        return stream.toByteArray()
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(context: Context): List<Pair<String, String>> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter ?: return emptyList()
        val paired = bluetoothAdapter.bondedDevices
        val connected = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        return (paired + connected).distinctBy { it.address }.map { (it.name ?: "Unknown") to it.address }
    }

    fun createTextBitmap(text: String): Bitmap {
        val width = 384
        val paint = TextPaint()
        paint.color = android.graphics.Color.BLACK
        paint.textSize = 30f // Aumentado para mejor legibilidad térmica
        paint.isFakeBoldText = true // Texto en negrita para asegurar quemado
        paint.isAntiAlias = true

        val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 40)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(true)
            .build()

        val height = staticLayout.height + 40
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.save()
        canvas.translate(20f, 20f)
        staticLayout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    @SuppressLint("MissingPermission")
    suspend fun printText(context: Context, text: String, targetAddress: String? = null, onStatusUpdate: (String) -> Unit) {
        val bitmap = createTextBitmap(text)
        printBitmap(context, bitmap, targetAddress, onStatusUpdate)
    }

    private fun findWriteCharacteristic(allChars: List<BluetoothGattCharacteristic>): BluetoothGattCharacteristic? {
        val found = allChars.find { it.uuid.toString().contains("49535343-8841", ignoreCase = true) }
            ?: allChars.find { it.uuid.toString().contains("49535343-1e4d", ignoreCase = true) }
            ?: allChars.find { it.uuid.toString().contains("ae01", ignoreCase = true) }
            ?: allChars.find { it.uuid.toString().contains("af01", ignoreCase = true) }
            ?: allChars.find { it.uuid.toString().contains("e7810a71", ignoreCase = true) }
            ?: allChars.find { CHARACTERISTIC_UUIDS.contains(it.uuid) }
            ?: allChars.find { (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0 }
        
        if (found != null) {
            Log.d("CatPrinter", "Found write characteristic: ${found.uuid}, properties: ${found.properties}")
        }
        return found
    }

    private fun findNotifyCharacteristic(allChars: List<BluetoothGattCharacteristic>): BluetoothGattCharacteristic? {
        return allChars.find { it.uuid.toString().contains("49535343-aca3", ignoreCase = true) }
            ?: allChars.find { (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 }
    }

    @SuppressLint("MissingPermission")
    suspend fun feedPaper(context: Context, targetAddress: String? = null, onStatusUpdate: (String) -> Unit) {
        onStatusUpdate("Conectando...")
        gattMutex.withLock {
            negotiatedMtu = 23
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter ?: return@withLock
            val device = targetAddress?.let { bluetoothAdapter.getRemoteDevice(it) } ?: return@withLock
            
            val writeDoneChannel = Channel<Int>(Channel.CONFLATED)
            val responseChannel = Channel<ByteArray>(Channel.CONFLATED)
            var writeChar: BluetoothGattCharacteristic? = null
            var connected = false

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "GATT Conectado (feedPaper)")
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        connected = false
                    }
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    Log.d(TAG, "onServicesDiscovered status: $status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val allChars = g.services.flatMap { it.characteristics }
                        writeChar = findWriteCharacteristic(allChars)
                        if (writeChar != null) {
                            Log.d(TAG, "Canal de escritura encontrado, solicitando MTU...")
                            g.requestMtu(512)
                        }
                        val notifyChar = findNotifyCharacteristic(allChars)
                        if (notifyChar != null) {
                            g.setCharacteristicNotification(notifyChar, true)
                            notifyChar.getDescriptor(CCCD_UUID)?.let { desc ->
                                if (Build.VERSION.SDK_INT >= 33) {
                                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    @Suppress("DEPRECATION")
                                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    g.writeDescriptor(desc)
                                }
                            }
                        } else {
                            connected = true
                        }
                    }
                }
                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                    connected = true
                }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    negotiatedMtu = mtu
                }
                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    writeDoneChannel.trySend(status)
                }
                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                    @Suppress("DEPRECATION")
                    val data = c.value
                    Log.d(TAG, "Respuesta: ${data?.joinToString("") { "%02X".format(it) }}")
                    data?.let { responseChannel.trySend(it) }
                }
            }

            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeoutOrNull(10000) { while (!connected) delay(100) }
                val char = writeChar ?: throw Exception("Sin canal")
                
                onStatusUpdate("Canal: ${char.uuid.toString().substring(0,8)}")
                delay(1000)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                delay(500)

                // Probaremos todas las combinaciones posibles de handshake y feed
                val tests = listOf(
                    // Wakeup
                    Pair(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00), "Wakeup"),
                    
                    // Variante 1: Handshake (Part CRC) + Feed (Part CRC)
                    Pair(makePacket(0x1B.toByte(), byteArrayOf(0x01), false), "Bind (Part CRC)"),
                    Pair(makePacket(0xBD.toByte(), byteArrayOf(0x64.toByte(), 0x00), false), "Feed 100 (Part CRC)"),
                    
                    // Variante 2: Handshake (Full CRC) + Feed (Full CRC)
                    Pair(makePacket(0x1B.toByte(), byteArrayOf(0x01), true), "Bind (Full CRC)"),
                    Pair(makePacket(0xBD.toByte(), byteArrayOf(0x64.toByte(), 0x00), true), "Feed 100 (Full CRC)"),
                    
                    // Variante 3: Comando A1 (Común en clones)
                    Pair(makePacket(0xA1.toByte(), byteArrayOf(0x05), false), "Feed A1 (Part CRC)"),
                    Pair(makePacket(0xA1.toByte(), byteArrayOf(0x05), true), "Feed A1 (Full CRC)"),
                    
                    // Variante 4: ESC/POS directo (algunos chips transparentes lo aceptan)
                    Pair(byteArrayOf(0x1B, 0x40, 0x0A, 0x0A, 0x0A, 0x0A), "ESC/POS Raw"),

                    // Variante 5: YHK / ESC-POS Extendida (del repositorio mencionado)
                    Pair(byteArrayOf(0x1B, 0x40, 0x1D, 0x49, 0xF0.toByte(), 0x19, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A), "YHK / ESC-POS Seq")
                )

                for ((p, name) in tests) {
                    onStatusUpdate("Probando: $name")
                    // Usamos WRITE_TYPE_NO_RESPONSE para máxima compatibilidad con buffers UART
                    sendPacketInternal(gatt, char, p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, writeDoneChannel, 20)
                    
                    // Intentar captar respuesta
                    val resp = withTimeoutOrNull(500) { responseChannel.receive() }
                    if (resp != null) onStatusUpdate("¡Respuesta!")
                    
                    delay(800)
                }

                onStatusUpdate("Diagnóstico fin")
            } catch (e: Exception) {
                onStatusUpdate("Error: ${e.message}")
            } finally {
                delay(2000)
                gatt.disconnect()
                gatt.close()
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun tryEscPos(context: Context, targetAddress: String? = null, onStatusUpdate: (String) -> Unit) {
        onStatusUpdate("Probando ESC/POS...")
        gattMutex.withLock {
            negotiatedMtu = 23
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter ?: return@withLock
            val device = targetAddress?.let { bluetoothAdapter.getRemoteDevice(it) } ?: return@withLock
            
            val writeDoneChannel = Channel<Int>(Channel.CONFLATED)
            var writeChar: BluetoothGattCharacteristic? = null
            var connected = false

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    }
                }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    negotiatedMtu = mtu
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        val allChars = g.services.flatMap { it.characteristics }
                        writeChar = findWriteCharacteristic(allChars)
                        if (writeChar != null) {
                            g.requestMtu(512)
                            connected = true
                        }
                    }
                }
                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    writeDoneChannel.trySend(status)
                }
            }

            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeoutOrNull(5000) { while (!connected) delay(100) }
                val char = writeChar ?: throw Exception("No se encontró canal")
                
                // Secuencia YHK mejorada y handshake (según Key Knowledge)
                val data = byteArrayOf(
                    0x1B, 0x40,                             // ESC @ (Reset/Init)
                    0x1D, 0x49, 0xF0.toByte(), 0x19,          // Handshake YHK
                    0x1B, 0x37, 0x0F, 0x78, 0x02,            // ESC 7 (1.2ms heating para estabilidad)
                    0x12, 0x23, 0x1F,                       // DC2 # n (Densidad 31)
                    0x12, 0x7E, 0x03, 0xFF.toByte()          // Energía YHK extra
                ) + "TEST ESC/POS YHK OK\nESTABLE 20B\n\n".toByteArray() + 
                byteArrayOf(0x0A, 0x0A, 0x0A)
                
                sendPacketInternal(gatt, char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, writeDoneChannel, 20)
                
                onStatusUpdate("ESC/POS YHK enviado")
            } catch (e: Exception) {
                onStatusUpdate("Error: ${e.message}")
            } finally {
                delay(1000)
                gatt.disconnect()
                gatt.close()
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun printBitmap(context: Context, bitmap: Bitmap?, targetAddress: String? = null, onStatusUpdate: (String) -> Unit) {
        gattMutex.withLock {
            negotiatedMtu = 23
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter ?: return@withLock
            
            val printerDevice = targetAddress?.let { bluetoothAdapter.getRemoteDevice(it) } ?: bluetoothAdapter.bondedDevices.firstOrNull { 
                val n = it.name?.lowercase() ?: ""
                n.contains("print") || n.contains("cat") || n.contains("gt01") || n.contains("gb01") || n.contains("mx")
            }

            if (printerDevice == null) {
                onStatusUpdate("Impresora no encontrada")
                return@withLock
            }

            onStatusUpdate("Conectando YHK...")
            val rows = bitmap?.let { convertToMonochromeDithered(it) } ?: emptyList()
            var writeChar: BluetoothGattCharacteristic? = null
            var isConnected = false
            val writeDoneChannel = Channel<Int>(Channel.CONFLATED)

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "GATT Conectado (YHK Mode)")
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        isConnected = false
                    }
                }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    negotiatedMtu = mtu
                }
                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    writeDoneChannel.trySend(status)
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        val allChars = g.services.flatMap { it.characteristics }
                        writeChar = findWriteCharacteristic(allChars)
                        if (writeChar != null) {
                            g.requestMtu(512)
                            isConnected = true
                        }
                    }
                }
            }
            
            val gatt = printerDevice.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

            try {
                withTimeoutOrNull(10000) { while (!isConnected) delay(100) }
                val characteristic = writeChar ?: throw Exception("No se encontró canal")
                
                delay(500)
                
                // 1. Handshake YHK RAW (Basado en Key Knowledge y Protocolo YHK)
                // Estas impresoras suelen ignorar comandos si no reciben el handshake exacto sin envoltura.
                onStatusUpdate("Desbloqueando YHK...")
                // Secuencia de Wakeup
                sendPacketInternal(gatt, characteristic, ByteArray(5) { 0 }, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, writeDoneChannel, 20)
                delay(100)

                // Secuencia Maestra YHK: Reset + Handshake + Energía + Calentamiento
                val yhkInit = byteArrayOf(
                    0x1B, 0x40,                     // ESC @ (Initialize)
                    0x1D, 0x49, 0xF0.toByte(), 0x19, // GS I F0 19 (YHK Handshake)
                    0x12, 0x7E, 0x03, 0xFF.toByte(), // DC2 ~ (YHK Energy Unlock / Extra Burn)
                    0x1B, 0x37, 0x07, 0x80.toByte(), 0x02, // ESC 7 (Heating: time=7, interval=128, dots=2)
                    0x12, 0x23, 0x1F                // DC2 # (Max Density 31)
                )
                sendPacketInternal(gatt, characteristic, yhkInit, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, writeDoneChannel, 20)
                delay(500)

                if (rows.isNotEmpty()) {
                    onStatusUpdate("Imprimiendo YHK...")
                    
                    // 2. Impresión Raster por Filas (GS v 0)
                    // Enviamos una cabecera GS v 0 por cada fila para evitar desbordamiento del buffer UART.
                    for (row in rows) {
                        val rowHeader = byteArrayOf(
                            0x1D, 0x76, 0x30, 0x00, 
                            0x30, 0x00, // 48 bytes de ancho (384 dots)
                            0x01, 0x00  // 1 fila de alto
                        )
                        val fullRow = rowHeader + row
                        // Usamos NO_RESPONSE para los datos de imagen para mayor velocidad
                        sendPacketInternal(gatt, characteristic, fullRow, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, writeDoneChannel, 20)
                        
                        // Retardo de 32ms: Crucial para que el cabezal térmico alcance la temperatura de quemado
                        delay(32)
                    }
                    
                    // 3. Feed Final
                    delay(200)
                    val feeds = byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A, 0x0A)
                    sendPacketInternal(gatt, characteristic, feeds, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, writeDoneChannel, 20)
                }

                onStatusUpdate("Listo")
            } catch (e: Exception) {
                onStatusUpdate("Error: ${e.message}")
            } finally {
                delay(1000)
                gatt.disconnect()
                gatt.close()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendPacketInternal(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        packet: ByteArray,
        preferredWriteType: Int,
        writeDoneChannel: Channel<Int>,
        forceMaxChunk: Int? = null
    ) {
        // Usamos el MTU negociado (restando 3 bytes de overhead GATT) o 20 por defecto.
        val mtuPayload = forceMaxChunk ?: (negotiatedMtu - 3).coerceAtLeast(20)
        var offset = 0
        
        while (offset < packet.size) {
            val end = kotlin.math.min(packet.size, offset + mtuPayload)
            val chunk = packet.copyOfRange(offset, end)
            
            var success = false
            var attempts = 0
            // Reintentos si el buffer interno de Android/BLE está saturado
            while (!success && attempts < 5) {
                success = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(char, chunk, preferredWriteType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    char.value = chunk
                    @Suppress("DEPRECATION")
                    char.writeType = preferredWriteType
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(char)
                }
                
                if (!success) {
                    attempts++
                    delay(20L * attempts) // Espera exponencial ligera
                }
            }
            
            if (success && preferredWriteType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                // Solo esperamos confirmación si el tipo de escritura lo requiere
                withTimeoutOrNull(1000) { writeDoneChannel.receive() }
            }
            
            offset += mtuPayload
            // Reducimos el delay entre fragmentos de un mismo paquete a 10ms para estabilidad
            if (offset < packet.size) {
                delay(10)
            }
        }
    }
}
