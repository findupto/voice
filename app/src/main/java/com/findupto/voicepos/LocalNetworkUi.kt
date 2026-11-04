package com.findupto.voicepos

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Suppress("UnsafeOptInUsageError")
@Composable
fun LocalNetworkDialog(store: Store, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voice_local_network", 0) }
    var hosting by remember { mutableStateOf(LocalNetworkSync.isRunning()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Connect staff phones to the same Wi-Fi or hotspot, then scan this QR code.") }
    var pairedAddress by remember { mutableStateOf(prefs.getString("owner_ip", "").orEmpty()) }
    val localIp = remember(hosting) { LocalNetworkSync.ipAddress() }
    val accessCode = remember(hosting) { LocalNetworkSync.token(context) }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val raw = result.data?.getStringExtra(LiveQrScannerActivity.EXTRA_QR_CODE).orEmpty()
            val parts = raw.split("|")
            if (parts.size == 2 && parts[0] == "VOICEPOS-PAIR" && parts[1].isNotBlank()) {
                pairedAddress = parts[1]
                prefs.edit().putString("owner_ip", parts[1]).apply()
                PosBackgroundSync.enqueueNow(context)
                message = "Paired with main device at $pairedAddress. Staff can now sign in using their own name and PIN."
                Toast.makeText(context, "Paired with main POS device", Toast.LENGTH_SHORT).show()
            } else message = "Invalid pairing QR. Scan the QR shown by the Owner device."
        }
    }
    val qrBitmap = remember(hosting, localIp) {
        if (!hosting || localIp == "IP unavailable") null else runCatching {
            val matrix = QRCodeWriter().encode("VOICEPOS-PAIR|$localIp:8765", BarcodeFormat.QR_CODE, 640, 640)
            Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).also { bitmap ->
                for (x in 0 until 640) for (y in 0 until 640) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("MAIN DEVICE & QR PAIRING", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The Admin phone hosts the main restaurant server. Start it on the Admin phone, then let staff scan its QR code. Owner Malik keeps full business ownership. All phones must use the same Wi-Fi or hotspot.")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ADMIN — MAIN SERVER", fontWeight = FontWeight.Bold)
                        Text(if (hosting) "Main server is running" else "Main server is stopped", color = if (hosting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        if (hosting) {
                            Text("Staff: scan this QR code to pair", fontWeight = FontWeight.SemiBold)
                            qrBitmap?.let { Image(it.asImageBitmap(), "Main device pairing QR code", modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
                            Text("Address: $localIp:8765", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            if (hosting) {
                                LocalNetworkSync.stop()
                                hosting = false
                                message = "Main server stopped."
                            } else {
                                hosting = LocalNetworkSync.start(context, store)
                                message = if (hosting) "Main server ready. Show the QR code to staff." else "Server not started. Only the Admin or Owner device can host the main server; also check port 8765."
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (hosting) "STOP MAIN SERVER" else "MAKE THIS THE MAIN DEVICE") }
                    }
                }
                HorizontalDivider()
                Text("STAFF DEVICE", fontWeight = FontWeight.Bold)
                Text(if (pairedAddress.isBlank()) "Scan the Admin server QR code once. Then sign in with your own staff name and PIN." else "Paired address: $pairedAddress")
                OutlinedButton(onClick = { scanner.launch(Intent(context, LiveQrScannerActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("SCAN ADMIN SERVER QR") }
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } }
    )
}
