package cz.zamereni.app

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openCvOk = OpenCVLoader.initLocal()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (openCvOk) App() else Text("Knihovnu OpenCV se nepodařilo načíst.", Modifier.padding(16.dp))
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Wizard : Screen
    data object ModeA : Screen
    data object ModeB : Screen
    data object Camera : Screen
    data object Processing : Screen
    data class Measure(val bitmap: Bitmap, val result: TargetDetector.Result) : Screen
}

@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Wizard) }
    var error by remember { mutableStateOf<String?>(null) }

    fun process(uri: Uri) {
        screen = Screen.Processing
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val bmp = loadOrientedBitmap(context, uri)
                    bmp to TargetDetector.detect(bmp)
                }
            }.onSuccess { (bmp, res) -> screen = Screen.Measure(bmp, res) }
                .onFailure { error = it.message; screen = Screen.ModeA }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) process(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) screen = Screen.Camera else error = "Bez oprávnění ke kameře lze jen vybrat fotku z galerie."
    }

    BackHandler(enabled = screen != Screen.Wizard) {
        screen = when (screen) {
            is Screen.Measure, Screen.Camera -> Screen.ModeA
            else -> Screen.Wizard
        }
    }

    when (val s = screen) {
        Screen.Wizard -> ModeWizard(onModeA = { screen = Screen.ModeA }, onModeB = { screen = Screen.ModeB })
        Screen.ModeA -> ModeAHome(
            error = error,
            onCamera = { error = null; cameraPermission.launch(Manifest.permission.CAMERA) },
            onGallery = {
                error = null
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onBack = { screen = Screen.Wizard },
        )
        Screen.ModeB -> InfoScreen(
            "Režim B – fotogrammetrie",
            "Sběr snímků a odeslání do PC bude v další verzi. Zatím použijte režim A.",
            onBack = { screen = Screen.Wizard },
        )
        Screen.Camera -> CameraScreen(onCaptured = { process(it) }, onCancel = { screen = Screen.ModeA })
        Screen.Processing -> Column(Modifier.padding(24.dp)) {
            CircularProgressIndicator(); Text("Hledám terče…")
        }
        is Screen.Measure -> MeasureScreen(s.bitmap, s.result, onBack = { screen = Screen.ModeA })
    }
}

/** Průvodce volbou režimu podle podmínek na místě (viz dokument s architekturou). */
@Composable
private fun ModeWizard(onModeA: () -> Unit, onModeB: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    val questions = listOf(
        "Potřebujete sklon a hrany střechy (hřeben, úžlabí, přesahy, vikýře)?",
        "Lze dům obejít a nafotit dokola (30–80 snímků s překryvem)?",
        "Je k dispozici dron nebo vyvýšené místo?",
    )
    val noAnswers = listOf(
        "Režim A: fasády a štíty, výsledek hned na místě.",
        "Režim A + dopočet střechy ze výšky hřebene a hloubky domu.",
        "Režim B ze země: střecha jen částečně, ověřte laserem.",
    )
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Nová zakázka", style = MaterialTheme.typography.headlineSmall)
        val r = result
        if (r == null) {
            Text(questions[step], style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    if (step < questions.lastIndex) step++ else result = "Režim B ze země + shora: kompletní 3D model."
                }) { Text("Ano") }
                OutlinedButton(onClick = { result = noAnswers[step] }) { Text("Ne") }
            }
        } else {
            Text(r, style = MaterialTheme.typography.titleMedium)
            Button(onClick = { if (r.startsWith("Režim A")) onModeA() else onModeB() }) { Text("Pokračovat") }
            OutlinedButton(onClick = { step = 0; result = null }) { Text("Znovu") }
        }
        OutlinedButton(onClick = onModeA) { Text("Rovnou režim A") }
    }
}

@Composable
private fun ModeAHome(error: String?, onCamera: () -> Unit, onGallery: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Režim A – měření v rovině terče", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Opřete terč naplocho o měřenou plochu. Foťte hlavním fotoaparátem, co nejvíc kolmo. " +
                "Měřit lze jen body ležící ve stejné rovině jako terč.",
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onCamera) { Text("Vyfotit") }
        OutlinedButton(onClick = onGallery) { Text("Vybrat fotku z galerie") }
        OutlinedButton(onClick = onBack) { Text("Zpět") }
    }
}

@Composable
private fun InfoScreen(title: String, text: String, onBack: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(text)
        OutlinedButton(onClick = onBack) { Text("Zpět") }
    }
}
