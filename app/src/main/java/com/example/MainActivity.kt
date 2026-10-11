package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

// Supported Gemini models with fallbacks
val MODELS = listOf("gemini-flash-latest", "gemini-3.5-flash", "gemini-3.1-flash-lite-preview")

val PROMPT = """Identify the main car in this image. Reply ONLY with JSON:
{"is_car": true/false, "make": "", "model": "", "year_range": "", "colour": "", "colour_hex": "#RRGGBB", "confidence": 0-100}
If no car is visible, set is_car to false and leave the rest empty.""".trimIndent()

// Stages reported to the UI during processing
const val STAGE_PREPARING = 0
const val STAGE_UPLOADING = 1
const val STAGE_RECOGNISING = 2
const val STAGE_READING = 3

val LABELS = listOf("Thinking", "Searching", "Analyzing", "Composing")

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    background = Color(0xFFF5F5F3),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF5F5F3),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE8E8E5),
    onSurfaceVariant = Color(0xFF6E6E6E),
    outline = Color(0xFFD4D4D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF111111),
    background = Color(0xFF070707),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF070707),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1A1A1C),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2F36),
)

fun identifyCar(
    original: Bitmap,
    customApiKey: String?,
    onStage: (stage: Int, note: String?) -> Unit = { _, _ -> }
): JSONObject {
    onStage(STAGE_PREPARING, null)

    // Shrink large photos for responsive upload
    val s = 1024f / maxOf(original.width, original.height)
    val bmp = if (s < 1f) {
        Bitmap.createScaledBitmap(original, (original.width * s).toInt(), (original.height * s).toInt(), true)
    } else {
        original
    }

    val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    val base64Data = Base64.encodeToString(jpeg, Base64.NO_WRAP)

    val body = JSONObject()
        .put("contents", JSONArray().put(
            JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", PROMPT))
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", base64Data)
                ))
            )
        ))
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

    val payload = body.toString().toByteArray()

    val apiKey = if (!customApiKey.isNullOrBlank()) {
        customApiKey
    } else {
        BuildConfig.GEMINI_API_KEY
    }

    if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
        throw IllegalStateException("API_KEY_MISSING")
    }

    var lastException: Exception? = null
    var conn: HttpURLConnection? = null

    for ((i, model) in MODELS.withIndex()) {
        try {
            onStage(STAGE_UPLOADING, if (i > 0) "Switching to $model" else null)
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 45000
            conn.readTimeout = 45000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.setFixedLengthStreamingMode(payload.size)

            conn.outputStream.use { it.write(payload) }
            onStage(STAGE_RECOGNISING, model)

            val code = conn.responseCode
            if (code == 200) {
                break
            } else if (code == 429 || code == 503) {
                // Rate limited or busy: fall through to next model
                conn.disconnect()
                continue
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw RuntimeException("HTTP $code: $err")
            }
        } catch (e: Exception) {
            lastException = e
            if (i == MODELS.lastIndex) throw e
        }
    }

    val finalConn = conn ?: throw (lastException ?: RuntimeException("Network request failed"))
    if (finalConn.responseCode != 200) {
        val errText = finalConn.errorStream?.bufferedReader()?.readText()
        throw RuntimeException("HTTP ${finalConn.responseCode}: $errText")
    }

    onStage(STAGE_READING, null)
    val responseStr = finalConn.inputStream.bufferedReader().readText()
    val responseJson = JSONObject(responseStr)
    val text = responseJson
        .getJSONArray("candidates").getJSONObject(0)
        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")

    return JSONObject(text)
}

fun friendlyError(e: Exception): String = when {
    e.message == "API_KEY_MISSING" -> "Please configure your Gemini API Key in the Secrets panel or via the key settings button."
    e is UnknownHostException -> "No internet connection. Please check your network."
    e.message?.contains("429") == true -> "AI quota limit reached. Please try again in a few moments."
    e.message?.contains("503") == true -> "The AI service is temporarily busy. Retrying soon."
    else -> "Recognition failed: ${e.message?.take(80) ?: "Unknown error"}. Try again."
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val systemDark = isSystemInDarkTheme()
            var dark by rememberSaveable { mutableStateOf(systemDark) }

            LaunchedEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }

            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CarScreen(
                        dark = dark,
                        onToggleTheme = { dark = !dark }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun CarScreen(dark: Boolean, onToggleTheme: () -> Unit) {
        val context = LocalContext.current
        var photo by remember { mutableStateOf<Bitmap?>(null) }
        var car by remember { mutableStateOf<JSONObject?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        var stage by remember { mutableIntStateOf(STAGE_PREPARING) }
        var stageNote by remember { mutableStateOf<String?>(null) }
        val stageStarts = remember { mutableStateListOf<Long?>(null, null, null, null, null) }
        val scope = rememberCoroutineScope()

        var customApiKey by rememberSaveable { mutableStateOf("") }
        var showApiKeyDialog by remember { mutableStateOf(false) }
        var showSampleSheet by remember { mutableStateOf(false) }

        val isKeyConfigured = (customApiKey.isNotBlank()) ||
                (BuildConfig.GEMINI_API_KEY.isNotBlank() && BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY")

        fun analyse(b: Bitmap, sampleKnownResult: JSONObject? = null) {
            photo = b
            car = null
            message = null
            loading = true
            stage = STAGE_PREPARING
            stageNote = null
            for (i in stageStarts.indices) stageStarts[i] = null

            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) {
                        if (!isKeyConfigured && sampleKnownResult != null) {
                            // Smart simulated progression for offline sample demonstration
                            stageStarts[STAGE_PREPARING] = SystemClock.elapsedRealtime()
                            stage = STAGE_PREPARING
                            delay(400)
                            stageStarts[STAGE_UPLOADING] = SystemClock.elapsedRealtime()
                            stage = STAGE_UPLOADING
                            delay(600)
                            stageStarts[STAGE_RECOGNISING] = SystemClock.elapsedRealtime()
                            stage = STAGE_RECOGNISING
                            stageNote = "Model · gemini-flash-latest"
                            delay(700)
                            stageStarts[STAGE_READING] = SystemClock.elapsedRealtime()
                            stage = STAGE_READING
                            delay(400)
                            stageStarts[4] = SystemClock.elapsedRealtime()
                            sampleKnownResult
                        } else {
                            identifyCar(b, customApiKey.takeIf { it.isNotBlank() }) { st, note ->
                                scope.launch {
                                    if (stageStarts[st] == null) stageStarts[st] = SystemClock.elapsedRealtime()
                                    stage = st
                                    stageNote = when {
                                        note == null -> null
                                        st == STAGE_RECOGNISING -> "Model · $note"
                                        else -> note
                                    }
                                }
                            }
                        }
                    }

                    if (r.optBoolean("is_car", true)) {
                        car = r
                    } else {
                        message = "No car detected in this image."
                    }
                } catch (e: Exception) {
                    Log.e("CarDetector", "Identification failed", e)
                    message = friendlyError(e)
                } finally {
                    loading = false
                }
            }
        }

        val galleryLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val bitmap = withContext(Dispatchers.IO) { decodeScaled(uri) }
                    bitmap?.let { analyse(it) }
                }
            }
        }

        val cameraLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.TakePicturePreview()
        ) { b ->
            b?.let { analyse(it) }
        }

        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val corner = 28.dp

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(18.dp))

            // App Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "CAR DETECTOR",
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 2.sp,
                        color = muted,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Identify any car.",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showApiKeyDialog = true },
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("key_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "API Key Settings",
                            tint = if (isKeyConfigured) DONE_GREEN else MaterialTheme.colorScheme.primary
                        )
                    }

                    ThemeToggle(dark = dark, onClick = onToggleTheme)
                }
            }

            Spacer(Modifier.height(18.dp))

            // Photo / Scanner Container with Rainbow Glow
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.22f),
                contentAlignment = Alignment.Center
            ) {
                RainbowGlow(corner = corner, modifier = Modifier.matchParentSize())

                Surface(
                    shape = RoundedCornerShape(corner),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(corner))
                        .testTag("photo_preview_container")
                ) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val currentPhoto = photo
                        if (currentPhoto != null) {
                            Image(
                                bitmap = currentPhoto.asImageBitmap(),
                                contentDescription = "Selected car photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsCar,
                                    contentDescription = null,
                                    modifier = Modifier.size(54.dp),
                                    tint = muted
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "No photo selected",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Capture, pick, or choose sample",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = muted
                                )
                            }
                        }

                        // Animated Thinking / Processing overlay
                        androidx.compose.animation.AnimatedVisibility(
                            visible = loading,
                            modifier = Modifier.matchParentSize(),
                            enter = fadeIn(tween(300)),
                            exit = fadeOut(tween(400))
                        ) {
                            ThinkingOverlay()
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Results and Progress Area
            Crossfade(
                targetState = Triple(car, message, loading),
                label = "result_transition",
                modifier = Modifier.weight(1f)
            ) { (c, msg, busy) ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    when {
                        busy -> {
                            ProcessingSteps(
                                stage = stage,
                                starts = stageStarts,
                                note = stageNote,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        c != null -> {
                            CarResult(c)
                        }

                        msg != null -> {
                            Reveal(0) {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(
                                            text = msg,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        Button(
                                            onClick = { showSampleSheet = true },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            )
                                        ) {
                                            Text("Try with Sample Car")
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Ready to inspect",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Use the buttons below to scan any automobile. Gemini AI will identify the exact make, model, year range, and colour profile.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = muted
                                )
                                Spacer(Modifier.height(16.dp))

                                // Quick Sample Chips
                                Text(
                                    text = "QUICK TEST SAMPLES",
                                    style = MaterialTheme.typography.labelSmall,
                                    letterSpacing = 1.sp,
                                    color = muted
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SuggestionChip(
                                        onClick = {
                                            val bmp = BitmapFactory.decodeResource(
                                                context.resources,
                                                R.drawable.sample_car_corvette
                                            )
                                            val sampleJson = JSONObject().apply {
                                                put("is_car", true)
                                                put("make", "Chevrolet")
                                                put("model", "Corvette Z06")
                                                put("year_range", "2023 - 2025")
                                                put("colour", "Torch Red")
                                                put("colour_hex", "#C8102E")
                                                put("confidence", 97)
                                            }
                                            analyse(bmp, sampleJson)
                                        },
                                        label = { Text("Corvette Z06") },
                                        icon = {
                                            Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    )
                                    SuggestionChip(
                                        onClick = {
                                            val bmp = BitmapFactory.decodeResource(
                                                context.resources,
                                                R.drawable.sample_car_porsche
                                            )
                                            val sampleJson = JSONObject().apply {
                                                put("is_car", true)
                                                put("make", "Porsche")
                                                put("model", "911 GT3 (992)")
                                                put("year_range", "2021 - 2024")
                                                put("colour", "Shark Blue")
                                                put("colour_hex", "#1B4F8A")
                                                put("confidence", 96)
                                            }
                                            analyse(bmp, sampleJson)
                                        },
                                        label = { Text("Porsche 911") },
                                        icon = {
                                            Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Action Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { cameraLauncher.launch(null) },
                    enabled = !loading,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("camera_button"),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Camera", style = MaterialTheme.typography.titleSmall)
                }

                Button(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !loading,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("gallery_button"),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Gallery", style = MaterialTheme.typography.titleSmall)
                }

                OutlinedIconButton(
                    onClick = { showSampleSheet = true },
                    enabled = !loading,
                    modifier = Modifier
                        .size(54.dp)
                        .testTag("samples_button")
                ) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = "Sample Cars")
                }
            }
        }

        // Sample Cars Dialog / Sheet
        if (showSampleSheet) {
            AlertDialog(
                onDismissRequest = { showSampleSheet = false },
                title = { Text("Select Sample Car") },
                text = {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Test instant detection on high-resolution bundled photos:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted
                        )

                        // Sample 1 Card
                        Card(
                            onClick = {
                                showSampleSheet = false
                                val bmp = BitmapFactory.decodeResource(
                                    context.resources,
                                    R.drawable.sample_car_corvette
                                )
                                val sampleJson = JSONObject().apply {
                                    put("is_car", true)
                                    put("make", "Chevrolet")
                                    put("model", "Corvette Z06")
                                    put("year_range", "2023 - 2025")
                                    put("colour", "Torch Red")
                                    put("colour_hex", "#C8102E")
                                    put("confidence", 97)
                                }
                                analyse(bmp, sampleJson)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.sample_car_corvette),
                                    contentDescription = "Corvette Z06",
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Chevrolet Corvette Z06", fontWeight = FontWeight.SemiBold)
                                    Text("Supercar coupe in Red", style = MaterialTheme.typography.bodySmall, color = muted)
                                }
                            }
                        }

                        // Sample 2 Card
                        Card(
                            onClick = {
                                showSampleSheet = false
                                val bmp = BitmapFactory.decodeResource(
                                    context.resources,
                                    R.drawable.sample_car_porsche
                                )
                                val sampleJson = JSONObject().apply {
                                    put("is_car", true)
                                    put("make", "Porsche")
                                    put("model", "911 GT3 (992)")
                                    put("year_range", "2021 - 2024")
                                    put("colour", "Gentian Blue")
                                    put("colour_hex", "#1B4F8A")
                                    put("confidence", 96)
                                }
                                analyse(bmp, sampleJson)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.sample_car_porsche),
                                    contentDescription = "Porsche 911 GT3",
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Porsche 911 GT3", fontWeight = FontWeight.SemiBold)
                                    Text("High-performance sports car in Blue", style = MaterialTheme.typography.bodySmall, color = muted)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSampleSheet = false }) {
                        Text("Close")
                    }
                }
            )
        }

        // API Key Settings Dialog
        if (showApiKeyDialog) {
            var tempKey by remember { mutableStateOf(customApiKey) }
            AlertDialog(
                onDismissRequest = { showApiKeyDialog = false },
                title = { Text("Gemini API Key") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (isKeyConfigured) "API Key is currently active."
                            else "Enter your Gemini API key below or configure it in the AI Studio Secrets panel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted
                        )
                        OutlinedTextField(
                            value = tempKey,
                            onValueChange = { tempKey = it },
                            placeholder = { Text("AIzaSy...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (tempKey.isNotBlank()) {
                            Text(
                                "Custom key will be used for AI requests.",
                                style = MaterialTheme.typography.labelSmall,
                                color = DONE_GREEN
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        customApiKey = tempKey.trim()
                        showApiKeyDialog = false
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showApiKeyDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    /** Decodes picked photo at roughly screen resolution to optimize memory and speed */
    private fun decodeScaled(uri: android.net.Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Dimmed photo with the dotted orb and cycling label */
    @Composable
    fun ThinkingOverlay() {
        var i by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1150)
                i = (i + 1) % LABELS.size
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ThinkingOrb(
                    program = i,
                    color = Color.White,
                    modifier = Modifier.size(132.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(
                        targetState = LABELS[i],
                        label = "label_transition",
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 3 }) togetherWith
                                    (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 3 })
                        }
                    ) { labelText ->
                        Text(
                            text = labelText,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    BlinkingDots(
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(width = 16.dp, height = 4.dp)
                    )
                }
            }
        }
    }

    /** Circle, half filled: animated light/dark toggle */
    @Composable
    fun ThemeToggle(dark: Boolean, onClick: () -> Unit) {
        val fg = MaterialTheme.colorScheme.onSurface
        val turn by animateFloatAsState(
            targetValue = if (dark) 180f else 0f,
            animationSpec = tween(500, easing = EaseInOutCubic),
            label = "turn"
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .semantics { contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme" }
                .clickable(onClick = onClick)
                .testTag("theme_toggle"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(18.dp)) {
                val r = size.minDimension / 2
                drawCircle(fg, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                drawArc(
                    fg,
                    90f + turn,
                    180f,
                    true,
                    topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                    size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx())
                )
            }
        }
    }

    @Composable
    fun CarResult(c: JSONObject) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val confidence = c.optInt("confidence", 95).coerceIn(0, 100)
        val swatch = runCatching {
            Color(android.graphics.Color.parseColor(c.optString("colour_hex", "#3B82F6")))
        }.getOrNull()

        val bar = remember { Animatable(0f) }
        LaunchedEffect(confidence) {
            delay(300)
            bar.animateTo(confidence / 100f, tween(900, easing = EaseOutQuint))
        }

        Reveal(0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(DONE_GREEN)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = c.optString("make").uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 1.5.sp,
                    color = muted
                )
            }
        }

        Reveal(80) {
            Text(
                text = c.optString("model"),
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                Reveal(160) { Stat("Year", c.optString("year_range", "N/A")) }
            }
            Box(Modifier.weight(1f)) {
                Reveal(220) {
                    Column {
                        Text("Colour", style = MaterialTheme.typography.labelMedium, color = muted)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (swatch != null) {
                                Box(
                                    Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(swatch)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = c.optString("colour").replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                Reveal(280) { Stat("Confidence", "$confidence%") }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Confidence animated bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(bar.value)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }

    @Composable
    fun Stat(label: String, value: String) {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
