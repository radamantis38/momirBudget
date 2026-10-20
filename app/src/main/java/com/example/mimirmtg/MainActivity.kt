package com.example.mimirmtg

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.mimirmtg.ui.theme.MimirMtgTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import android.annotation.SuppressLint

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MimirMtgTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        BuscadorCartas()
                    }
                }
            }
        }
    }
}

@Composable
fun BuscadorCartas() {
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var numero by remember { mutableStateOf("") }
    var cardName by remember { mutableStateOf<String?>(null) }
    var oracleText by remember { mutableStateOf<String?>(null) }
    var combinedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var printerStatus by remember { mutableStateOf("") }
    val sharedPrefs = remember { context.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE) }
    var selectedPrinterAddress by remember { mutableStateOf(sharedPrefs.getString("last_printer", null)) }
    var showDeviceSelector by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var isScanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val scryfallApi = remember {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "MimirMtgApp/1.0")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.scryfall.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(ScryfallApi::class.java)
    }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                @SuppressLint("MissingPermission")
                val name = device.name ?: "Desconocido"
                val address = device.address
                if (address != null && !pairedDevices.any { it.second == address }) {
                    pairedDevices = (pairedDevices + (name to address)).distinctBy { it.second }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
        if (scanner != null && !isScanning) {
            isScanning = true
            scope.launch {
                try {
                    scanner.startScan(scanCallback)
                    delay(10000)
                    scanner.stopScan(scanCallback)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error escaneando", e)
                } finally {
                    isScanning = false
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            pairedDevices = CatPrinter.getPairedDevices(context)
            showDeviceSelector = true
            startScan()
        } else {
            Toast.makeText(context, "Permisos necesarios para Bluetooth", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = numero,
            onValueChange = { if (it.all { char -> char.isDigit() }) numero = it },
            label = { Text("CMC de Criatura Aleatoria (ej: 3)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                keyboardController?.hide()
                
                scope.launch {
                    isLoading = true
                    printerStatus = ""
                    cardName = null
                    combinedBitmap = null
                    oracleText = null
                    try {
                        val valTrimmed = numero.trim()
                        if (valTrimmed.isBlank()) {
                            Toast.makeText(context, "Ingresa un número para el CMC", Toast.LENGTH_SHORT).show()
                            isLoading = false
                            return@launch
                        }
                        val query = "t:creature cmc=$valTrimmed game:paper not:funny -is:acorn -set:unf -set:ust -set:unh -set:ugl -set:und"
                        Log.d("MainActivity", "Buscando random con query: $query")
                        val response = scryfallApi.getCard(query)
                        
                        val urls = mutableListOf<String>()
                        response.card_faces?.forEach { face ->
                            face.image_uris?.normal?.let { urls.add(it) }
                        }
                        if (urls.isEmpty()) {
                            response.image_uris?.normal?.let { urls.add(it) }
                        }
                        
                        if (urls.isNotEmpty()) {
                            cardName = response.name
                            // Procesar Oracle Text (Nombre, Coste, Tipo, Reglas, P/T o Lealtad)
                            oracleText = if (!response.card_faces.isNullOrEmpty()) {
                                response.card_faces.joinToString("\n---\n") { face ->
                                    buildString {
                                        face.name?.let { appendLine(it) }
                                        face.mana_cost?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                                        face.type_line?.let { appendLine(it) }
                                        face.oracle_text?.let { appendLine(it) }
                                        if (!face.power.isNullOrBlank() || !face.toughness.isNullOrBlank()) {
                                            appendLine("${face.power ?: "?"}/${face.toughness ?: "?"}")
                                        }
                                        face.loyalty?.let { appendLine("Loyalty: $it") }
                                    }.trim()
                                }
                            } else {
                                buildString {
                                    response.name?.let { appendLine(it) }
                                    response.mana_cost?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                                    response.type_line?.let { appendLine(it) }
                                    response.oracle_text?.let { appendLine(it) }
                                    if (!response.power.isNullOrBlank() || !response.toughness.isNullOrBlank()) {
                                        appendLine("${response.power ?: "?"}/${response.toughness ?: "?"}")
                                    }
                                    response.loyalty?.let { appendLine("Loyalty: $it") }
                                }.trim()
                            }

                            printerStatus = "Descargando imágenes..."
                            val bitmaps = urls.mapNotNull { downloadBitmap(context, it, 600) }
                            if (bitmaps.isNotEmpty()) {
                                val combined = combineBitmaps(bitmaps)
                                combinedBitmap = combined
                                printerStatus = "Imprimiendo imagen..."
                                CatPrinter.printBitmap(context, combined, selectedPrinterAddress) { status ->
                                    printerStatus = status
                                }
                            } else {
                                printerStatus = "Error al descargar imágenes"
                            }
                        } else {
                            Toast.makeText(context, "Carta sin imagen disponible", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: HttpException) {
                        val errorMsg = e.response()?.errorBody()?.string() ?: e.message()
                        Log.e("MainActivity", "HttpError ${e.code()}: $errorMsg")
                        if (e.code() == 404) {
                            Toast.makeText(context, "No se encontró la carta o criterios", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Error Scryfall (${e.code()})", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error general", e)
                        Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    } finally {
                        isLoading = false
                    }
                }
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Text("Buscar e Imprimir Carta")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        cardName?.let {
            Text(text = it, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
        }

        combinedBitmap?.let { bitmap ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(bitmap)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Carta MTG",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(500.dp)
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        printerStatus = "Reimprimiendo..."
                        CatPrinter.printBitmap(context, bitmap, selectedPrinterAddress) { status ->
                            printerStatus = status
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Reimprimir Carta Completa")
            }

            oracleText?.let { text ->
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            printerStatus = "Imprimiendo texto..."
                            CatPrinter.printText(context, text, selectedPrinterAddress) { status ->
                                printerStatus = status
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Imprimir Oracle Text")
                }
            }

            if (printerStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = printerStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                val neededPermissions = if (Build.VERSION.SDK_INT >= 31) {
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }

                val missing = neededPermissions.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }

                if (missing.isEmpty()) {
                    pairedDevices = CatPrinter.getPairedDevices(context)
                    showDeviceSelector = true
                    startScan()
                } else {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (selectedPrinterAddress == null) "Configurar Impresora" else "Impresora: $selectedPrinterAddress")
        }
    }

    if (showDeviceSelector) {
        AlertDialog(
            onDismissRequest = { showDeviceSelector = false },
            title = { Text("Seleccionar Impresora") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (pairedDevices.isEmpty()) {
                        Text("No hay dispositivos vinculados.")
                    } else {
                        pairedDevices.forEach { (name, address) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        selectedPrinterAddress = address
                                        sharedPrefs.edit().putString("last_printer", address).apply()
                                        showDeviceSelector = false
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedPrinterAddress == address,
                                    onClick = null
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(text = name, style = MaterialTheme.typography.bodyLarge)
                                    Text(text = address, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceSelector = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}

private suspend fun downloadBitmap(context: Context, url: String, targetWidth: Int? = null): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            val loader = ImageLoader(context)
            val requestBuilder = ImageRequest.Builder(context)
                .data(url)
                .addHeader("User-Agent", "MimirMtgApp/1.0")
                .allowHardware(false)
            
            if (targetWidth != null) {
                requestBuilder.size(targetWidth, 2000)
            }

            val result = (loader.execute(requestBuilder.build()) as? SuccessResult)?.drawable
            (result as? BitmapDrawable)?.bitmap
        } catch (e: Exception) {
            null
        }
    }
}

private fun combineBitmaps(bitmaps: List<Bitmap>): Bitmap {
    if (bitmaps.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    if (bitmaps.size == 1) return bitmaps[0]

    val width = bitmaps.maxOf { it.width }
    val spacing = 20
    val totalHeight = bitmaps.sumOf { it.height } + (spacing * (bitmaps.size - 1))
    
    val result = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(result)
    canvas.drawColor(android.graphics.Color.WHITE)
    
    var currentY = 0f
    for (bitmap in bitmaps) {
        canvas.drawBitmap(bitmap, 0f, currentY, null)
        currentY += bitmap.height + spacing
    }
    return result
}
