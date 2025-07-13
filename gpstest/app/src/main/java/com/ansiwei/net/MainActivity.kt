package com.ansiwei.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.ansiwei.net.ui.theme.GpsTestTheme
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GpsTestTheme { Surface(modifier = Modifier.fillMaxSize()) { MainScreen() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var nmeaMessage by remember { mutableStateOf("等待GPS数据...") }
    var signalStrengthDbm by remember { mutableStateOf("未知") }
    var signalType by remember { mutableStateOf("") }
    val logList = remember { mutableStateListOf<String>() }
    val locationManager = context.getSystemService(LocationManager::class.java)
    val telephonyManager = context.getSystemService(TelephonyManager::class.java)

    // 监听权限状态变化
    var hasLocationPermission by remember {
        mutableStateOf(
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    var hasPhonePermission by remember {
        mutableStateOf(
            ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        hasPhonePermission = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
            )
        )
    }

    // NMEA监听 - 依赖权限状态
    DisposableEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            val nmeaListener = OnNmeaMessageListener { message, _ ->
                nmeaMessage = message
                val log = "[${getNowTimeString()}] NMEA: $message"
                logList.add(log)
            }
            locationManager?.addNmeaListener(nmeaListener)
            onDispose { locationManager?.removeNmeaListener(nmeaListener) }
        } else {
            nmeaMessage = "需要位置权限才能获取GPS数据"
            onDispose { }
        }
    }

    // 信号强度监听 - 依赖权限状态
    DisposableEffect(hasPhonePermission) {
        if (hasPhonePermission) {
            val phoneStateListener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    super.onSignalStrengthsChanged(signalStrength)
                    val (dbmStr, typeStr) = signalStrength?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val cellSignal = it.cellSignalStrengths.firstOrNull()
                            val dbm = cellSignal?.dbm?.toString() ?: "未知"
                            val type = when {
                                it.cellSignalStrengths.any { signal -> signal.toString().contains("Lte") } -> "LTE"
                                it.cellSignalStrengths.any { signal -> signal.toString().contains("Gsm") } -> "GSM"
                                it.cellSignalStrengths.any { signal -> signal.toString().contains("Wcdma") } -> "WCDMA"
                                else -> "未知"
                            }
                            Pair(dbm, type)
                        } else {
                            val gsmStrength = it.gsmSignalStrength
                            val dbm = if (gsmStrength != 99) (-113 + 2 * gsmStrength).toString() else "未知"
                            Pair(dbm, "GSM")
                        }
                    } ?: Pair("未知", "未知")

                    signalStrengthDbm = dbmStr
                    signalType = typeStr
                    val log = "[${getNowTimeString()}] Signal: $dbmStr dBm ($typeStr)"
                    logList.add(log)
                }
            }
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            onDispose { telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE) }
        } else {
            signalStrengthDbm = "需要电话权限"
            signalType = "未知"
            onDispose { }
        }
    }

    // Tab 布局
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("GPS") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("信号强度") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("系统设置") }
            )
        }

        when (selectedTab) {
            0 -> GpsTab(nmeaMessage = nmeaMessage)
            1 -> SignalTab(signalStrengthDbm = signalStrengthDbm, signalType = signalType)
            2 -> SettingsTab(context = context, logList = logList)
        }
    }
}

@Composable
fun GpsTab(nmeaMessage: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "GPS NMEA报文", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "实时NMEA数据：",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = nmeaMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SignalTab(signalStrengthDbm: String, signalType: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "无线信号强度", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "信号强度：",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$signalStrengthDbm dBm",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "网络类型: $signalType",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 信号强度指示器
                val signalLevel = when {
                    signalStrengthDbm == "未知" -> 0
                    signalStrengthDbm.toIntOrNull()?.let { it >= -70 } == true -> 4
                    signalStrengthDbm.toIntOrNull()?.let { it >= -85 } == true -> 3
                    signalStrengthDbm.toIntOrNull()?.let { it >= -100 } == true -> 2
                    else -> 1
                }

                Text(
                    text = "信号等级: ${signalLevel}/4",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (signalLevel) {
                        4 -> Color.Green
                        3 -> Color(0xFF8BC34A)
                        2 -> Color(0xFFFF9800)
                        1 -> Color.Red
                        else -> Color.Gray
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsTab(context: Context, logList: List<String>) {
    val locationPermission = ActivityCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val phonePermission = ActivityCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "系统设置", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        // 权限状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "权限状态",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "位置权限: ${if (locationPermission) "✓ 已授予" else "✗ 未授予"}",
                    color = if (locationPermission) Color.Green else Color.Red
                )
                Text(
                    text = "电话权限: ${if (phonePermission) "✓ 已授予" else "✗ 未授予"}",
                    color = if (phonePermission) Color.Green else Color.Red
                )

                if (!locationPermission || !phonePermission) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "提示：请在系统设置中手动授予权限后重启应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 日志管理卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "日志管理",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "日志条数: ${logList.size}")

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { saveLogToFile(context, logList) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存日志到文件")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 实时日志显示
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "实时日志 (最近10条)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                logList.takeLast(10).forEach { log ->
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }

                if (logList.isEmpty()) {
                    Text(
                        text = "暂无日志数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

// 保存日志到文件
fun saveLogToFile(context: Context, logs: List<String>) {
    val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val fileName = "gps_log_$time.txt"
    val file = File(context.getExternalFilesDir(null), fileName)
    try {
        FileOutputStream(file).use { out ->
            logs.forEach { out.write((it + "\n").toByteArray()) }
        }
        Toast.makeText(context, "日志已保存: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// 获取当前时间字符串
fun getNowTimeString(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}
