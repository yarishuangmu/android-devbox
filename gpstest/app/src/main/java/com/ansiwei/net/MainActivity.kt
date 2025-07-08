package com.ansiwei.net

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.ansiwei.net.ui.theme.GpsTestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GpsTestTheme { Surface(modifier = Modifier.fillMaxSize()) { MainScreen() } } }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var nmeaMessage by remember { mutableStateOf("") }
    var signalStrengthDbm by remember { mutableStateOf("") }
    val locationManager = context.getSystemService(LocationManager::class.java)
    val telephonyManager = context.getSystemService(TelephonyManager::class.java)

    // 权限请求
    val permissionLauncher =
            rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
            ) {}
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
                arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                )
        )
    }

    // NMEA监听
    DisposableEffect(Unit) {
        val nmeaListener = OnNmeaMessageListener { message, _ -> nmeaMessage = message }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            locationManager?.addNmeaListener(nmeaListener)
        }
        onDispose { locationManager?.removeNmeaListener(nmeaListener) }
    }

    // 信号强度监听
    DisposableEffect(Unit) {
        val phoneStateListener =
                object : PhoneStateListener() {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                        super.onSignalStrengthsChanged(signalStrength)
                        signalStrengthDbm =
                                signalStrength?.let {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        it.cellSignalStrengths.firstOrNull()?.dbm?.toString() ?: "-"
                                    } else {
                                        it.gsmSignalStrength.toString()
                                    }
                                }
                                        ?: "-"
                    }
                }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }
        onDispose { telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(text = "GPS NMEA报文：", style = MaterialTheme.typography.titleMedium)
        Text(text = nmeaMessage, modifier = Modifier.padding(bottom = 16.dp))
        Text(text = "无线信号强度 (dBm)：", style = MaterialTheme.typography.titleMedium)
        Text(text = signalStrengthDbm)
    }
}
