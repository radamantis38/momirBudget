package com.example.mimirmtg

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

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
    var allImageUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var oracleText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var printerStatus by remember { mutableStateOf("") }
    val sharedPrefs = remember { context.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE) }
    var selectedPrinterAddress by remember { mutableStateOf(sharedPrefs.getString("last_printer", null)) }
    var showDeviceSelector by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var isScanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = try { device.name } catch (e: SecurityException) { null } ?: "Desconocido"
                val address = device.address
                if (pairedDevices.none { it.second == address }) {
                    pairedDevices = pairedDevices + (name to address)
                }
            }
        }
    }

    fun startScan() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
        if (scanner != null && !isScanning) {
            isScanning = true
            pairedDevices = CatPrinter.getPairedDevices(context)
            try {
                scanner.startScan(scanCallback)
                scope.launch {
                    kotlinx.coroutines.delay(10000)
                    try { scanner.stopScan(scanCallback) } catch(e: SecurityException) {}
                    isScanning = false
                }
            } catch (e: SecurityException) {
                isScanning = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            pairedDevices = CatPrinter.getPairedDevices(context)
            showDeviceSelector = true
            startScan()
        } else {
            Toast.makeText(context, "Permisos de Bluetooth denegados", Toast.LENGTH_SHORT).show()
        }
    }

    val scryfallApi = remember {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "MimirMtgApp/1.0")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.scryfall.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ScryfallApi::class.java)
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
            label = { Text("Valor de CMC (ej: 3)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = { keyboardController?.hide() }
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                keyboardController?.hide()
                val valTrimmed = numero.trim()
                if (valTrimmed.isBlank()) {
                    Toast.makeText(context, "Ingresa un número", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                
                scope.launch {
                    isLoading = true
                    printerStatus = ""
                    cardName = null
                    allImageUrls = emptyList()
                    oracleText = null
                    try {
                        val query = "t:creature cmc=$valTrimmed game:paper not:funny -is:acorn -set:unf -set:ust -set:unh -set:ugl -set:und"
                        Log.d("MainActivity", "Buscando con query: $query")
                        val response = scryfallApi.getCard(query)
                        
                        val urls = mutableListOf<String>()
                        val texts = mutableListOf<String>()
                        
                        val faces = response.card_faces
                        if (!faces.isNullOrEmpty()) {
                            faces.forEach { face ->
                                val sb = StringBuilder()
                                sb.append(face.name ?: "Desconocida")
                                if (!face.mana_cost.isNullOrBlank()) sb.append("  ${face.mana_cost}")
                                sb.append("\n${face.type_line ?: ""}")
                                if (!face.oracle_text.isNullOrBlank()) sb.append("\n\n${face.oracle_text}")
                                if (!face.power.isNullOrBlank() || !face.toughness.isNullOrBlank()) {
                                    sb.append("\n\n${face.power ?: "?"}/${face.toughness ?: "?"}")
                                }
                                texts.add(sb.toString())
                                
                                face.image_uris?.normal?.let { urls.add(it) }
                            }
                            // Si las caras no tienen imagen propia (Split, Flip, Adventure, Prepare) usamos la del root
                            if (urls.isEmpty() && response.image_uris != null) {
                                urls.add(response.image_uris.normal)
                            }
                        } else {
                            if (response.image_uris != null) {
                                urls.add(response.image_uris.normal)
                            }
                            val sb = StringBuilder()
                            sb.append(response.name ?: "Desconocida")
                            if (!response.mana_cost.isNullOrBlank()) sb.append("  ${response.mana_cost}")
                            sb.append("\n${response.type_line ?: ""}")
                            if (!response.oracle_text.isNullOrBlank()) sb.append("\n\n${response.oracle_text}")
                            if (!response.power.isNullOrBlank() || !response.toughness.isNullOrBlank()) {
                                sb.append("\n\n${response.power ?: "?"}/${response.toughness ?: "?"}")
                            }
                            texts.add(sb.toString())
                        }

                        if (urls.isNotEmpty()) {
                            cardName = response.name
                            allImageUrls = urls
                            oracleText = texts.joinToString("\n\n")
                            
                        printerStatus = "Descargando imagen..."
                        val bitmaps = urls.mapNotNull { downloadBitmap(context, it, 600) }
                        if (bitmaps.isNotEmpty()) {
                                val combined = combineBitmaps(bitmaps)
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
                        if (e.code() == 404) {
                            Toast.makeText(context, "No se encontraron criaturas", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Error del servidor: ${e.code()}", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
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

        if (allImageUrls.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                allImageUrls.forEachIndexed { index, url ->
                    if (allImageUrls.size > 1) {
                        Text(
                            text = "Cara ${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(url)
                            .crossfade(true)
                            .addHeader("User-Agent", "MimirMtgApp/1.0")
                            .build(),
                        contentDescription = "Carta MTG Face $index",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(350.dp)
                            .padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        printerStatus = "Descargando para reimprimir..."
                        val bitmaps = allImageUrls.mapNotNull { downloadBitmap(context, it, 600) }
                        if (bitmaps.isNotEmpty()) {
                            val combined = combineBitmaps(bitmaps)
                            printerStatus = "Reimprimiendo..."
                            CatPrinter.printBitmap(context, combined, selectedPrinterAddress) { status ->
                                printerStatus = status
                            }
                        } else {
                            printerStatus = "Error al descargar imágenes"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(if (allImageUrls.size > 1) "Reimprimir ambas caras" else "Reimprimir Carta")
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

            Spacer(modifier = Modifier.height(8.dp))

            if (printerStatus.isNotEmpty()) {
                Text(
                    text = printerStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            CatPrinter.feedPaper(context, selectedPrinterAddress) { printerStatus = it }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Probar Feed")
                }
                
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            CatPrinter.tryEscPos(context, selectedPrinterAddress) { printerStatus = it }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Probar ESC/POS")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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

private suspend fun saveImageToGallery(context: Context, url: String) {
    withContext(Dispatchers.IO) {
        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .addHeader("User-Agent", "MimirMtgApp/1.0")
                .allowHardware(false)
                .build()

            val result = (loader.execute(request) as? SuccessResult)?.drawable
            val bitmap = (result as? BitmapDrawable)?.bitmap ?: return@withContext

            val filename = "MTG_${System.currentTimeMillis()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MimirMtg")
            }

            val contentResolver = context.contentResolver
            val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            imageUri?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error guardando", e)
        }
    }
}
