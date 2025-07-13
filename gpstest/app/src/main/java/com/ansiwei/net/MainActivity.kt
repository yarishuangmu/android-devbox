package com.ansiwei.net

import android.Manifest
import android.Manifest.permission.READ_PHONE_STATE
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.telephony.gsm.GsmCellLocation
import android.util.Log
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
import androidx.core.content.ContextCompat
import com.ansiwei.net.ui.theme.GpsTestTheme
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

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

    // 扩展的信号信息状态
    var signalStrengthDbm by remember { mutableStateOf("未知") }
    var signalType by remember { mutableStateOf("") }
    var networkOperator by remember { mutableStateOf("未知") }
    var imsi by remember { mutableStateOf("未知") }
    var phoneNumber by remember { mutableStateOf("未知") }
    var cellId by remember { mutableStateOf("未知") }
    var lac by remember { mutableStateOf("未知") }
    var mcc by remember { mutableStateOf("未知") }
    var mnc by remember { mutableStateOf("未知") }
    var frequency by remember { mutableStateOf("未知") }
    var networkGeneration by remember { mutableStateOf("未知") }

    var gpsStatus by remember { mutableStateOf("检查中...") }

    // 新增GPS解析数据
    var satelliteCount by remember { mutableStateOf(0) }
    var usedSatellites by remember { mutableStateOf(0) }
    var latitude by remember { mutableStateOf("未知") }
    var longitude by remember { mutableStateOf("未知") }
    var altitude by remember { mutableStateOf("未知") }
    var speed by remember { mutableStateOf("未知") }
    var hdop by remember { mutableStateOf("未知") }
    val satelliteList = remember { mutableStateListOf<SatelliteInfo>() }

    // 使用专门的线程池和原子操作
    val nmeaQueue = remember { ConcurrentLinkedQueue<String>() }
    val isProcessing = remember { AtomicBoolean(false) }
    val processingThread = remember { Executors.newSingleThreadExecutor() }
    val uiUpdateExecutor = remember { Executors.newSingleThreadScheduledExecutor() }

    // 临时数据缓存，减少UI更新频率
    val tempLocationData = remember { mutableMapOf<String, String>() }
    val tempSatelliteData = remember { mutableMapOf<String, Any>() }
    val tempSatelliteList = remember { mutableListOf<SatelliteInfo>() }

    var lastNmeaMessageTime by remember { mutableStateOf(0L) }

    val logList = remember { mutableStateListOf<String>() }
    val locationManager = context.getSystemService(LocationManager::class.java)
    val telephonyManager = context.getSystemService(TelephonyManager::class.java)

    // 启动专门的NMEA处理线程
    DisposableEffect(Unit) {
        isProcessing.set(true)

        // NMEA数据处理线程
        processingThread.execute {
            while (isProcessing.get()) {
                try {
                    val message = nmeaQueue.poll()
                    if (message != null) {
                        parseNmeaMessage(
                                message,
                                onLocationUpdate = { lat, lon, alt, spd ->
                                    synchronized(tempLocationData) {
                                        if (lat != "未知") tempLocationData["latitude"] = lat
                                        if (lon != "未知") tempLocationData["longitude"] = lon
                                        if (alt != "未知") tempLocationData["altitude"] = alt
                                        if (spd != "未知") tempLocationData["speed"] = spd
                                    }
                                },
                                onSatelliteUpdate = { count, used, hdopValue ->
                                    synchronized(tempSatelliteData) {
                                        if (count > 0) tempSatelliteData["count"] = count
                                        if (used > 0) tempSatelliteData["used"] = used
                                        if (hdopValue != "未知") tempSatelliteData["hdop"] = hdopValue
                                    }
                                },
                                onSatelliteDetails = { satellites ->
                                    synchronized(tempSatelliteList) {
                                        satellites.forEach { newSatellite ->
                                            val existingIndex =
                                                    tempSatelliteList.indexOfFirst {
                                                        it.prn == newSatellite.prn &&
                                                                it.constellation ==
                                                                        newSatellite.constellation
                                                    }
                                            if (existingIndex != -1) {
                                                tempSatelliteList[existingIndex] = newSatellite
                                            } else {
                                                tempSatelliteList.add(newSatellite)
                                            }
                                        }
                                    }
                                }
                        )
                    } else {
                        // 队列为空时休眠一下，避免空转
                        Thread.sleep(10)
                    }
                } catch (e: Exception) {
                    Log.e("GPSTest", "NMEA处理线程错误: ${e.message}")
                }
            }
        }

        onDispose {
            isProcessing.set(false)
            processingThread.shutdown()
            uiUpdateExecutor.shutdown()
        }
    }

    // 使用ScheduledExecutor定时更新UI - 每500ms一次
    DisposableEffect(Unit) {
        val updateTask = Runnable {
            try {
                // 在主线程中更新UI
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                mainHandler.post {
                    // 批量更新位置数据
                    synchronized(tempLocationData) {
                        tempLocationData["latitude"]?.let { latitude = it }
                        tempLocationData["longitude"]?.let { longitude = it }
                        tempLocationData["altitude"]?.let { altitude = it }
                        tempLocationData["speed"]?.let { speed = it }
                    }

                    // 批量更新卫星统计
                    synchronized(tempSatelliteData) {
                        (tempSatelliteData["count"] as? Int)?.let { satelliteCount = it }
                        (tempSatelliteData["used"] as? Int)?.let { usedSatellites = it }
                        (tempSatelliteData["hdop"] as? String)?.let { hdop = it }
                    }

                    // 批量更新卫星列表
                    synchronized(tempSatelliteList) {
                        if (tempSatelliteList.isNotEmpty()) {
                            satelliteList.clear()
                            satelliteList.addAll(tempSatelliteList.toList())

                            // 限制卫星列表大小
                            if (satelliteList.size > 50) {
                                val toRemove = satelliteList.size - 40
                                repeat(toRemove) {
                                    if (satelliteList.isNotEmpty()) {
                                        satelliteList.removeAt(0)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GPSTest", "UI更新错误: ${e.message}")
            }
        }

        val scheduledFuture =
                uiUpdateExecutor.scheduleAtFixedRate(
                        updateTask,
                        500,
                        500,
                        java.util.concurrent.TimeUnit.MILLISECONDS
                )

        onDispose { scheduledFuture.cancel(true) }
    }

    // 定期清理过期数据 - 使用独立的清理线程
    DisposableEffect(Unit) {
        val cleanupExecutor = Executors.newSingleThreadScheduledExecutor()
        val cleanupTask = Runnable {
            synchronized(tempSatelliteList) {
                if (tempSatelliteList.size > 100) {
                    val toRemove = tempSatelliteList.size - 80
                    repeat(toRemove) {
                        if (tempSatelliteList.isNotEmpty()) {
                            tempSatelliteList.removeAt(0)
                        }
                    }
                }
            }
        }

        val cleanupFuture =
                cleanupExecutor.scheduleAtFixedRate(
                        cleanupTask,
                        30,
                        30,
                        java.util.concurrent.TimeUnit.SECONDS
                )

        onDispose {
            cleanupFuture.cancel(true)
            cleanupExecutor.shutdown()
        }
    }

    // 添加初始化日志
    LaunchedEffect(Unit) {
        val initLog = "[${getNowTimeString()}] 应用启动，开始初始化"
        logList.add(initLog)
        Log.d("GPSTest", initLog)
    }

    // 监听权限状态变化 - 扩展电话权限
    var hasLocationPermission by remember {
        mutableStateOf(
                ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasPhonePermission by remember {
        mutableStateOf(
                ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                        PackageManager.PERMISSION_GRANTED
        )
    }

    var hasPhoneNumberPermission by remember {
        mutableStateOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_PHONE_NUMBERS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    hasPhonePermission
                }
        )
    }

    // 新增设备标识符权限检查
    var hasDeviceIdentifierPermission by remember {
        mutableStateOf(
                // 移除对不存在权限的引用，直接使用现有权限状态
                hasPhonePermission
        )
    }

    // 权限请求 - 添加更多电话相关权限
    val permissionLauncher =
            rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
                val phoneGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
                val phoneNumberGranted =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            permissions[Manifest.permission.READ_PHONE_NUMBERS] ?: false
                        } else {
                            phoneGranted
                        }

                hasLocationPermission = locationGranted
                hasPhonePermission = phoneGranted
                hasPhoneNumberPermission = phoneNumberGranted
                // 注意：READ_PRIVILEGED_PHONE_STATE 是系统权限，普通应用无法获得

                val permissionLog =
                        "[${getNowTimeString()}] 权限结果 - 位置:$locationGranted, 电话:$phoneGranted, 号码:$phoneNumberGranted"
                logList.add(permissionLog)
                Log.d("GPSTest", permissionLog)
            }

    LaunchedEffect(Unit) {
        val requestLog = "[${getNowTimeString()}] 请求权限"
        logList.add(requestLog)
        Log.d("GPSTest", requestLog)

        val permissionsToRequest =
                mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        // 不请求系统权限，因为普通应用无法获得
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        //     permissionsToRequest.add(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
        // }

        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    // NMEA监听 - 依赖权限状态
    DisposableEffect(hasLocationPermission) {
        val effectLog = "[${getNowTimeString()}] DisposableEffect触发，位置权限: $hasLocationPermission"
        logList.add(effectLog)
        Log.d("GPSTest", effectLog)

        if (hasLocationPermission && locationManager != null) {
            // 检查GPS提供者状态
            val isGpsEnabled =
                    try {
                        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    } catch (e: Exception) {
                        Log.e("GPSTest", "检查GPS状态失败: ${e.message}")
                        false
                    }

            val isNetworkEnabled =
                    try {
                        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    } catch (e: Exception) {
                        Log.e("GPSTest", "检查网络定位状态失败: ${e.message}")
                        false
                    }

            val providerLog =
                    "[${getNowTimeString()}] 定位提供者 - GPS:$isGpsEnabled, 网络:$isNetworkEnabled"
            logList.add(providerLog)
            Log.d("GPSTest", providerLog)

            gpsStatus =
                    when {
                        isGpsEnabled -> "GPS已启用"
                        isNetworkEnabled -> "网络定位已启用"
                        else -> "定位服务未启用"
                    }

            if (isGpsEnabled || isNetworkEnabled) {
                val nmeaListener = OnNmeaMessageListener { message, timestamp ->
                    val currentTime = System.currentTimeMillis()
                    nmeaQueue.offer(message)
                    if ((message.startsWith("\$GPGGA") ||
                                    message.startsWith("\$GNGGA") ||
                                    message.startsWith("\$GPRMC") ||
                                    message.startsWith("\$GNRMC")) &&
                                    currentTime - lastNmeaMessageTime > 5000
                    ) {
                        nmeaMessage = message
                        val log = "[${getNowTimeString()}] NMEA: ${message.take(60)}..."
                        logList.add(log)
                        lastNmeaMessageTime = currentTime
                        if (logList.size > 200) {
                            repeat(50) {
                                if (logList.isNotEmpty()) {
                                    logList.removeAt(0)
                                }
                            }
                        }
                    }
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        // 推荐用法：传入主线程Executor
                        val mainExecutor = ContextCompat.getMainExecutor(context)
                        locationManager.addNmeaListener(mainExecutor, nmeaListener)
                        val initLog =
                                "[${getNowTimeString()}] NMEA监听器已启动 (新API, API ${Build.VERSION.SDK_INT})"
                        logList.add(initLog)
                        Log.d("GPSTest", initLog)
                    } else {
                        @Suppress("DEPRECATION")
                        val success = locationManager.addNmeaListener(nmeaListener)
                        val initLog =
                                "[${getNowTimeString()}] NMEA监听器已启动 (旧API, API ${Build.VERSION.SDK_INT}, 结果: $success)"
                        logList.add(initLog)
                        Log.d("GPSTest", initLog)
                    }

                    // 尝试请求位置更新以激活GPS
                    try {
                        locationManager.requestLocationUpdates(
                                LocationManager.GPS_PROVIDER,
                                1000,
                                0f
                        ) { location ->
                            val locationLog =
                                    "[${getNowTimeString()}] 位置更新: ${location.latitude}, ${location.longitude}"
                            logList.add(locationLog)
                            Log.d("GPSTest", locationLog)
                        }
                        val requestLog = "[${getNowTimeString()}] 已请求位置更新"
                        logList.add(requestLog)
                        Log.d("GPSTest", requestLog)
                    } catch (e: SecurityException) {
                        val errorLog = "[${getNowTimeString()}] 请求位置更新失败: 权限不足"
                        logList.add(errorLog)
                        Log.e("GPSTest", errorLog)
                    }
                } catch (e: SecurityException) {
                    gpsStatus = "权限不足: ${e.message}"
                    val errorLog =
                            "[${getNowTimeString()}] NMEA监听器启动失败: 权限不足 (API ${Build.VERSION.SDK_INT})"
                    logList.add(errorLog)
                    Log.e("GPSTest", errorLog, e)
                } catch (e: Exception) {
                    gpsStatus = "启动失败: ${e.message}"
                    val errorLog =
                            "[${getNowTimeString()}] NMEA监听器启动失败: ${e.message} (API ${Build.VERSION.SDK_INT})"
                    logList.add(errorLog)
                    Log.e("GPSTest", errorLog, e)
                }

                onDispose {
                    try {
                        locationManager.removeNmeaListener(nmeaListener)
                        locationManager.removeUpdates {}
                        val disposeLog =
                                "[${getNowTimeString()}] NMEA监听器已停止 (API ${Build.VERSION.SDK_INT})"
                        logList.add(disposeLog)
                        Log.d("GPSTest", disposeLog)
                    } catch (e: Exception) {
                        val errorLog = "[${getNowTimeString()}] NMEA监听器停止时出错: ${e.message}"
                        logList.add(errorLog)
                        Log.e("GPSTest", errorLog, e)
                    }
                }
            } else {
                nmeaMessage = "GPS和网络定位都未启用，请在设置中开启位置服务"
                gpsStatus = "定位服务未启用"
                val statusLog = "[${getNowTimeString()}] 定位服务未启用 (API ${Build.VERSION.SDK_INT})"
                logList.add(statusLog)
                Log.w("GPSTest", statusLog)
                onDispose {}
            }
        } else {
            nmeaMessage = "需要位置权限才能获取GPS数据"
            gpsStatus = if (locationManager == null) "LocationManager不可用" else "权限未授予"
            val permissionLog =
                    "[${getNowTimeString()}] 位置权限未授予或LocationManager不可用 (API ${Build.VERSION.SDK_INT})"
            logList.add(permissionLog)
            Log.w("GPSTest", permissionLog)
            onDispose {}
        }
    }

    // 信号强度监听 - 依赖权限状态
    DisposableEffect(hasPhonePermission, hasPhoneNumberPermission) {
        val phoneEffectLog =
                "[${getNowTimeString()}] 信号监听Effect触发，电话权限: $hasPhonePermission, 号码权限: $hasPhoneNumberPermission"
        logList.add(phoneEffectLog)
        Log.d("GPSTest", phoneEffectLog)

        if (hasPhonePermission && telephonyManager != null) {

            // 安全地读取电话信息，增加详细的异常处理
            try {
                if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_PHONE_STATE
                        ) == PackageManager.PERMISSION_GRANTED
                ) {

                    // 安全获取IMSI
                    imsi =
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    // Android 10+ 需要特殊处理
                                    "受限访问(Android 10+)"
                                } else {
                                    telephonyManager.subscriberId ?: "未知"
                                }
                            } catch (e: SecurityException) {
                                val errorMsg = "权限不足(${e.message})"
                                logList.add("[${getNowTimeString()}] IMSI读取失败: $errorMsg")
                                errorMsg
                            } catch (e: Exception) {
                                val errorMsg = "读取错误(${e.javaClass.simpleName})"
                                logList.add("[${getNowTimeString()}] IMSI读取异常: $errorMsg")
                                errorMsg
                            }

                    // 安全获取网络运营商
                    networkOperator =
                            try {
                                telephonyManager.networkOperatorName ?: "未知"
                            } catch (e: Exception) {
                                logList.add("[${getNowTimeString()}] 运营商名称读取失败: ${e.message}")
                                "读取失败"
                            }

                    // 获取MCC和MNC
                    try {
                        val networkOperatorCode = telephonyManager.networkOperator
                        if (networkOperatorCode != null && networkOperatorCode.length >= 5) {
                            mcc = networkOperatorCode.substring(0, 3)
                            mnc = networkOperatorCode.substring(3)
                        } else {
                            mcc = "未知"
                            mnc = "未知"
                        }
                    } catch (e: Exception) {
                        logList.add("[${getNowTimeString()}] MCC/MNC读取失败: ${e.message}")
                        mcc = "读取失败"
                        mnc = "读取失败"
                    }
                }

                // 安全获取电话号码
                if (hasPhoneNumberPermission) {
                    phoneNumber =
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    // Android 8+ 使用新的权限检查
                                    if (ActivityCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.READ_PHONE_NUMBERS
                                            ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        telephonyManager.line1Number ?: "未获取到"
                                    } else {
                                        "权限不足"
                                    }
                                } else {
                                    telephonyManager.line1Number ?: "未获取到"
                                }
                            } catch (e: SecurityException) {
                                val errorMsg = "权限不足(${e.message})"
                                logList.add("[${getNowTimeString()}] 电话号码读取失败: $errorMsg")
                                errorMsg
                            } catch (e: Exception) {
                                val errorMsg = "读取错误(${e.javaClass.simpleName})"
                                logList.add("[${getNowTimeString()}] 电话号码读取异常: $errorMsg")
                                errorMsg
                            }
                }

                // 添加详细的权限状态日志
                val statusLog =
                        "[${getNowTimeString()}] 电话信息读取完成 - IMSI:${imsi.take(10)}, 运营商:$networkOperator"
                logList.add(statusLog)
                Log.d("GPSTest", statusLog)
            } catch (e: SecurityException) {
                val errorLog = "[${getNowTimeString()}] 电话信息读取权限被拒绝: ${e.message}"
                logList.add(errorLog)
                Log.e("GPSTest", errorLog)

                // 设置错误状态
                imsi = "权限被拒绝"
                networkOperator = "权限被拒绝"
                phoneNumber = "权限被拒绝"
                mcc = "未知"
                mnc = "未知"
            } catch (e: Exception) {
                val errorLog = "[${getNowTimeString()}] 电话信息读取失败: ${e.message}"
                logList.add(errorLog)
                Log.e("GPSTest", errorLog)

                // 设置错误状态
                imsi = "读取失败"
                networkOperator = "读取失败"
                phoneNumber = "读取失败"
                mcc = "未知"
                mnc = "未知"
            }

            // 增强的信号强度监听 - 依赖权限状态
            val phoneStateListener =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Android 12+ 使用新的监听器
                        object :
                                TelephonyCallback(),
                                TelephonyCallback.SignalStrengthsListener,
                                TelephonyCallback.CellLocationListener {
                            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                                handleSignalStrengthChanged(signalStrength) {
                                        dbm,
                                        type,
                                        generation,
                                        freq ->
                                    signalStrengthDbm = dbm
                                    signalType = type
                                    networkGeneration = generation
                                    frequency = freq
                                }
                            }

                            override fun onCellLocationChanged(location: CellLocation) {
                                handleCellLocationChanged(location) { cid, lacValue ->
                                    cellId = cid
                                    lac = lacValue
                                }
                            }
                        }
                    } else {
                        // Android 11 及以下使用旧的监听器
                        object : PhoneStateListener() {
                            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                                super.onSignalStrengthsChanged(signalStrength)
                                signalStrength?.let {
                                    handleSignalStrengthChanged(it) { dbm, type, generation, freq ->
                                        signalStrengthDbm = dbm
                                        signalType = type
                                        networkGeneration = generation
                                        frequency = freq
                                    }
                                }
                            }

                            override fun onCellLocationChanged(location: CellLocation?) {
                                super.onCellLocationChanged(location)
                                location?.let {
                                    handleCellLocationChanged(it) { cid, lacValue ->
                                        cellId = cid
                                        lac = lacValue
                                    }
                                }
                            }
                        }
                    }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    telephonyManager.registerTelephonyCallback(
                            ContextCompat.getMainExecutor(context),
                            phoneStateListener as TelephonyCallback
                    )
                } else {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(
                            phoneStateListener as PhoneStateListener,
                            PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                                    PhoneStateListener.LISTEN_CELL_LOCATION
                    )
                }

                val initLog = "[${getNowTimeString()}] 增强信号监听器已启动"
                logList.add(initLog)
                Log.d("GPSTest", initLog)
            } catch (e: Exception) {
                val errorLog = "[${getNowTimeString()}] 信号监听启动失败: ${e.message}"
                logList.add(errorLog)
                Log.e("GPSTest", errorLog, e)
            }

            onDispose {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        telephonyManager.unregisterTelephonyCallback(
                                phoneStateListener as TelephonyCallback
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        telephonyManager.listen(
                                phoneStateListener as PhoneStateListener,
                                PhoneStateListener.LISTEN_NONE
                        )
                    }
                    val disposeLog = "[${getNowTimeString()}] 增强信号监听器已停止"
                    logList.add(disposeLog)
                    Log.d("GPSTest", disposeLog)
                } catch (e: Exception) {
                    val errorLog = "[${getNowTimeString()}] 信号监听器停止失败: ${e.message}"
                    logList.add(errorLog)
                    Log.e("GPSTest", errorLog, e)
                }
            }
        } else {
            signalStrengthDbm = "需要电话权限"
            signalType = "未知"
            val phonePermLog = "[${getNowTimeString()}] 电话权限未授予或TelephonyManager不可用"
            logList.add(phonePermLog)
            Log.w("GPSTest", phonePermLog)
            onDispose {}
        }
    }

    // Tab 布局
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("GPS") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("信号详情") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("系统设置") })
        }

        when (selectedTab) {
            0 ->
                    GpsTab(
                            nmeaMessage = nmeaMessage,
                            gpsStatus = gpsStatus,
                            satelliteCount = satelliteCount,
                            usedSatellites = usedSatellites,
                            latitude = latitude,
                            longitude = longitude,
                            altitude = altitude,
                            speed = speed,
                            hdop = hdop,
                            satelliteList = satelliteList
                    )
            1 ->
                    EnhancedSignalTab(
                            signalStrengthDbm = signalStrengthDbm,
                            signalType = signalType,
                            networkOperator = networkOperator,
                            imsi = imsi,
                            phoneNumber = phoneNumber,
                            cellId = cellId,
                            lac = lac,
                            mcc = mcc,
                            mnc = mnc,
                            frequency = frequency,
                            networkGeneration = networkGeneration
                    )
            2 -> SettingsTab(context = context, logList = logList)
        }
    }
}

// 处理信号强度变化的函数
fun handleSignalStrengthChanged(
        signalStrength: SignalStrength,
        onUpdate: (String, String, String, String) -> Unit
) {
    try {
        val (dbmStr, typeStr, generation, freq) =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cellSignals = signalStrength.cellSignalStrengths
                    val primarySignal = cellSignals.firstOrNull()

                    when {
                        cellSignals.any { it is CellSignalStrengthNr } -> {
                            val nrSignal =
                                    cellSignals
                                            .filterIsInstance<CellSignalStrengthNr>()
                                            .firstOrNull()
                            val dbm = nrSignal?.dbm?.toString() ?: "未知"
                            // 修复频段信息获取
                            val freq =
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            nrSignal?.let {
                                                // 使用反射或其他方式获取频段信息，或者简化处理
                                                "5G频段"
                                            }
                                                    ?: "未知"
                                        } else "未知"
                                    } catch (e: Exception) {
                                        "未知"
                                    }
                            Tuple4(dbm, "5G NR", "5G", freq)
                        }
                        cellSignals.any { it is CellSignalStrengthLte } -> {
                            val lteSignal =
                                    cellSignals
                                            .filterIsInstance<CellSignalStrengthLte>()
                                            .firstOrNull()
                            val dbm = lteSignal?.dbm?.toString() ?: "未知"
                            // 修复EARFCN访问
                            val freq =
                                    try {
                                        lteSignal?.let {
                                            // 简化处理，避免直接访问可能不存在的属性
                                            "LTE频段"
                                        }
                                                ?: "未知"
                                    } catch (e: Exception) {
                                        "未知"
                                    }
                            Tuple4(dbm, "LTE", "4G", freq)
                        }
                        cellSignals.any { it is CellSignalStrengthWcdma } -> {
                            val wcdmaSignal =
                                    cellSignals
                                            .filterIsInstance<CellSignalStrengthWcdma>()
                                            .firstOrNull()
                            val dbm = wcdmaSignal?.dbm?.toString() ?: "未知"
                            Tuple4(dbm, "WCDMA", "3G", "未知")
                        }
                        cellSignals.any { it is CellSignalStrengthGsm } -> {
                            val gsmSignal =
                                    cellSignals
                                            .filterIsInstance<CellSignalStrengthGsm>()
                                            .firstOrNull()
                            val dbm = gsmSignal?.dbm?.toString() ?: "未知"
                            Tuple4(dbm, "GSM", "2G", "未知")
                        }
                        else -> {
                            val dbm = primarySignal?.dbm?.toString() ?: "未知"
                            Tuple4(dbm, "未知", "未知", "未知")
                        }
                    }
                } else {
                    // Android 9 及以下的兼容处理
                    val gsmStrength = signalStrength.gsmSignalStrength
                    val dbm = if (gsmStrength != 99) (-113 + 2 * gsmStrength).toString() else "未知"
                    Tuple4(dbm, "GSM", "2G/3G", "未知")
                }

        onUpdate(dbmStr, typeStr, generation, freq)

        val log = "[${getNowTimeString()}] 详细信号: $dbmStr dBm ($typeStr, $generation)"
        Log.d("GPSTest", log)
    } catch (e: Exception) {
        Log.e("GPSTest", "信号强度解析错误: ${e.message}")
        onUpdate("解析错误", "未知", "未知", "未知")
    }
}

// 处理小区位置变化的函数
fun handleCellLocationChanged(location: CellLocation, onUpdate: (String, String) -> Unit) {
    try {
        when (location) {
            is GsmCellLocation -> {
                val cid = location.cid.toString()
                val lac = location.lac.toString()
                onUpdate(cid, lac)
            }
            else -> {
                onUpdate("未知", "未知")
            }
        }
    } catch (e: Exception) {
        Log.e("GPSTest", "小区位置解析错误: ${e.message}")
        onUpdate("解析错误", "解析错误")
    }
}

// 辅助数据类
data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

// 增强的信号详情Tab
@Composable
fun EnhancedSignalTab(
        signalStrengthDbm: String,
        signalType: String,
        networkOperator: String,
        imsi: String,
        phoneNumber: String,
        cellId: String,
        lac: String,
        mcc: String,
        mnc: String,
        frequency: String,
        networkGeneration: String
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(text = "详细信号信息", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        // 基本信号信息卡片
        Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = "信号强度",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                        text = "$signalStrengthDbm dBm",
                        style = MaterialTheme.typography.headlineMedium,
                        color =
                                when {
                                    signalStrengthDbm == "未知" -> Color.Gray
                                    signalStrengthDbm.toIntOrNull()?.let { it >= -70 } == true ->
                                            Color.Green
                                    signalStrengthDbm.toIntOrNull()?.let { it >= -85 } == true ->
                                            Color(0xFF8BC34A)
                                    signalStrengthDbm.toIntOrNull()?.let { it >= -100 } == true ->
                                            Color(0xFFFF9800)
                                    else -> Color.Red
                                }
                )
                Text(text = "网络类型: $signalType", style = MaterialTheme.typography.bodyMedium)
                Text(text = "网络代际: $networkGeneration", style = MaterialTheme.typography.bodyMedium)
                Text(text = "频段信息: $frequency", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 运营商信息卡片
        Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = "运营商信息",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "运营商: $networkOperator", style = MaterialTheme.typography.bodyMedium)
                Text(text = "MCC: $mcc", style = MaterialTheme.typography.bodyMedium)
                Text(text = "MNC: $mnc", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 用户身份信息卡片
        Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = "用户身份",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                        text =
                                "IMSI: ${if (imsi.length > 10) "${imsi.take(6)}***${imsi.takeLast(4)}" else imsi}",
                        style = MaterialTheme.typography.bodyMedium
                )
                Text(
                        text =
                                "电话号码: ${if (phoneNumber != "未知" && phoneNumber.length > 7) "${phoneNumber.take(3)}****${phoneNumber.takeLast(4)}" else phoneNumber}",
                        style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 小区信息卡片
        Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = "小区信息",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "小区ID: $cellId", style = MaterialTheme.typography.bodyMedium)
                Text(text = "位置区码: $lac", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun GpsTab(
        nmeaMessage: String,
        gpsStatus: String,
        satelliteCount: Int,
        usedSatellites: Int,
        latitude: String,
        longitude: String,
        altitude: String,
        speed: String,
        hdop: String,
        satelliteList: List<SatelliteInfo>
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(text = "GPS 定位信息", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        // GPS状态卡片
        Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = "GPS状态",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                        text = gpsStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                                when {
                                    gpsStatus.contains("已启用") -> Color.Green
                                    gpsStatus.contains("未启用") || gpsStatus.contains("权限") ->
                                            Color.Red
                                    else -> Color(0xFFFF9800)
                                }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 位置信息卡片
        Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = "位置信息",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "纬度: $latitude", style = MaterialTheme.typography.bodyMedium)
                Text(text = "经度: $longitude", style = MaterialTheme.typography.bodyMedium)
                Text(text = "海拔: $altitude", style = MaterialTheme.typography.bodyMedium)
                Text(text = "速度: $speed", style = MaterialTheme.typography.bodyMedium)
                Text(text = "水平精度因子: $hdop", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 卫星信息卡片
        Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = "卫星信息",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 显示实际的卫星统计
                val totalVisible = satelliteList.size
                val strongSignals =
                        satelliteList.count {
                            it.snr != "未知" && it.snr.toIntOrNull()?.let { snr -> snr > 0 } == true
                        }

                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                            text = "可见卫星: $totalVisible 颗",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                            text = "有信号: $strongSignals 颗",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(text = "使用卫星: $usedSatellites 颗", style = MaterialTheme.typography.bodyMedium)

                if (satelliteList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                            text = "卫星详情:",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 表头
                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                                "系统",
                                modifier = Modifier.weight(0.8f),
                                style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                                "编号",
                                modifier = Modifier.weight(0.8f),
                                style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                                "仰角",
                                modifier = Modifier.weight(0.8f),
                                style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                                "方位",
                                modifier = Modifier.weight(0.8f),
                                style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                                "信噪比",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // 按信噪比排序显示卫星（有信号的优先）
                    val sortedSatellites =
                            satelliteList.sortedWith { a, b ->
                                val aSnr = a.snr.toIntOrNull() ?: -999
                                val bSnr = b.snr.toIntOrNull() ?: -999
                                bSnr.compareTo(aSnr) // 降序排列
                            }

                    // 显示更多卫星（增加到20颗）
                    sortedSatellites.take(20).forEach { satellite ->
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                    satellite.constellation,
                                    modifier = Modifier.weight(0.8f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                            when (satellite.constellation) {
                                                "GPS" -> Color(0xFF2196F3)
                                                "GLONASS" -> Color(0xFF4CAF50)
                                                "Galileo" -> Color(0xFFFF9800)
                                                "BeiDou" -> Color(0xFFE91E63)
                                                else -> Color.Gray
                                            }
                            )
                            Text(
                                    satellite.prn,
                                    modifier = Modifier.weight(0.8f),
                                    style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                    "${satellite.elevation}°",
                                    modifier = Modifier.weight(0.8f),
                                    style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                    "${satellite.azimuth}°",
                                    modifier = Modifier.weight(0.8f),
                                    style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                    "${satellite.snr}dB",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                            when {
                                                satellite.snr == "未知" || satellite.snr.isEmpty() ->
                                                        Color.Gray
                                                satellite.snr.toIntOrNull()?.let { it >= 35 } ==
                                                        true -> Color.Green
                                                satellite.snr.toIntOrNull()?.let { it >= 25 } ==
                                                        true -> Color(0xFFFF9800)
                                                satellite.snr.toIntOrNull()?.let { it > 0 } ==
                                                        true -> Color.Red
                                                else -> Color.Gray
                                            }
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    if (satelliteList.size > 20) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                                text = "... 还有 ${satelliteList.size - 20} 颗卫星",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                            text = "暂无卫星数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                    )
                }
            }
        }

        // 卫星系统统计卡片
        if (satelliteList.isNotEmpty()) {
            Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                            text = "卫星系统统计",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val constellationCounts =
                            satelliteList.groupBy { it.constellation }.mapValues { it.value.size }

                    constellationCounts.forEach { (system, count) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                    text = system,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color =
                                            when (system) {
                                                "GPS" -> Color(0xFF2196F3)
                                                "GLONASS" -> Color(0xFF4CAF50)
                                                "Galileo" -> Color(0xFFFF9800)
                                                "BeiDou" -> Color(0xFFE91E63)
                                                else -> Color.Gray
                                            }
                            )
                            Text(text = "$count 颗", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SignalTab(signalStrengthDbm: String, signalType: String) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
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
                Text(text = "网络类型: $signalType", style = MaterialTheme.typography.bodyMedium)

                Spacer(modifier = Modifier.height(16.dp))

                // 信号强度指示器
                val signalLevel =
                        when {
                            signalStrengthDbm == "未知" -> 0
                            signalStrengthDbm.toIntOrNull()?.let { it >= -70 } == true -> 4
                            signalStrengthDbm.toIntOrNull()?.let { it >= -85 } == true -> 3
                            signalStrengthDbm.toIntOrNull()?.let { it >= -100 } == true -> 2
                            else -> 1
                        }

                Text(
                        text = "信号等级: ${signalLevel}/4",
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                                when (signalLevel) {
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
    val locationPermission =
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED

    val phonePermission =
            ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(text = "系统设置", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        // 系统信息卡片
        Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = "系统信息",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Android版本: ${Build.VERSION.RELEASE}")
                Text(text = "API级别: ${Build.VERSION.SDK_INT}")
                Text(text = "设备型号: ${Build.MODEL}")
                Text(text = "制造商: ${Build.MANUFACTURER}")

                val apiCompatibility =
                        when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> "使用新API"
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> "使用兼容API"
                            else -> "使用旧API"
                        }
                Text(
                        text = "API兼容性: $apiCompatibility",
                        color =
                                when {
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> Color.Green
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                                            Color(0xFFFF9800)
                                    else -> Color.Red
                                }
                )
            }
        }

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
                ) { Text("保存日志到文件") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 实时日志显示 - 扩大显示区域
        Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = "实时日志 (最近20条)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 增加日志显示区域高度
                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .height(300.dp)
                                        .verticalScroll(rememberScrollState())
                ) {
                    Column {
                        logList.takeLast(20).forEach { log ->
                            Text(
                                    text = log,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 1.dp)
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
    }
}

// 保存日志到文件
fun saveLogToFile(context: Context, logs: List<String>) {
    val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val fileName = "gps_log_$time.txt"
    val file = File(context.getExternalFilesDir(null), fileName)
    try {
        FileOutputStream(file).use { out -> logs.forEach { out.write((it + "\n").toByteArray()) } }
        Toast.makeText(context, "日志已保存: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// 获取当前时间字符串
fun getNowTimeString(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}

// 卫星信息数据类
data class SatelliteInfo(
        val prn: String, // 卫星编号
        val elevation: String, // 仰角
        val azimuth: String, // 方位角
        val snr: String, // 信噪比/载噪比
        val constellation: String // 卫星系统
)

// 优化NMEA解析函数，在工作线程中运行
fun parseNmeaMessage(
        message: String,
        onLocationUpdate: (String, String, String, String) -> Unit,
        onSatelliteUpdate: (Int, Int, String) -> Unit,
        onSatelliteDetails: (List<SatelliteInfo>) -> Unit
) {
    // 快速过滤，只处理需要的报文类型
    if (!message.startsWith("\$GP") &&
                    !message.startsWith("\$GN") &&
                    !message.startsWith("\$GL") &&
                    !message.startsWith("\$GA") &&
                    !message.startsWith("\$GB") &&
                    !message.startsWith("\$BD")
    ) {
        return
    }

    val parts = message.split(",")
    if (parts.isEmpty()) return

    when {
        // GPGGA - GPS定位数据
        message.startsWith("\$GPGGA") || message.startsWith("\$GNGGA") -> {
            if (parts.size >= 15) {
                val lat =
                        if (parts[2].isNotEmpty() && parts[3].isNotEmpty()) {
                            formatLatitude(parts[2], parts[3])
                        } else "未知"

                val lon =
                        if (parts[4].isNotEmpty() && parts[5].isNotEmpty()) {
                            formatLongitude(parts[4], parts[5])
                        } else "未知"

                val alt = if (parts[9].isNotEmpty()) "${parts[9]} ${parts[10]}" else "未知"
                val hdopValue = if (parts[8].isNotEmpty()) parts[8] else "未知"

                onLocationUpdate(lat, lon, alt, "未知")

                // 更新使用的卫星数量
                val usedSats = if (parts[7].isNotEmpty()) parts[7].toIntOrNull() ?: 0 else 0
                onSatelliteUpdate(0, usedSats, hdopValue)
            }
        }

        // GPRMC - 推荐最小导航数据
        message.startsWith("\$GPRMC") || message.startsWith("\$GNRMC") -> {
            if (parts.size >= 12) {
                val speed =
                        if (parts[7].isNotEmpty()) {
                            val knots = parts[7].toFloatOrNull() ?: 0f
                            String.format("%.2f km/h", knots * 1.852)
                        } else "未知"

                val lat =
                        if (parts[3].isNotEmpty() && parts[4].isNotEmpty()) {
                            formatLatitude(parts[3], parts[4])
                        } else "未知"

                val lon =
                        if (parts[5].isNotEmpty() && parts[6].isNotEmpty()) {
                            formatLongitude(parts[5], parts[6])
                        } else "未知"

                onLocationUpdate(lat, lon, "未知", speed)
            }
        }

        // GPGSV - 可见卫星信息
        message.startsWith("\$GPGSV") ||
                message.startsWith("\$GLGSV") ||
                message.startsWith("\$GAGSV") ||
                message.startsWith("\$GBGSV") ||
                message.startsWith("\$BDGSV") -> {
            if (parts.size >= 4) {
                val totalSatellites = parts[3].toIntOrNull() ?: 0
                val messageNumber = parts[2].toIntOrNull() ?: 0

                // 确定卫星系统
                val constellation =
                        when {
                            message.startsWith("\$GPGSV") -> "GPS"
                            message.startsWith("\$GLGSV") -> "GLONASS"
                            message.startsWith("\$GAGSV") -> "Galileo"
                            message.startsWith("\$GBGSV") -> "BeiDou"
                            message.startsWith("\$BDGSV") -> "BeiDou"
                            else -> "Unknown"
                        }

                val satellites = mutableListOf<SatelliteInfo>()
                var i = 4
                while (i + 3 < parts.size && satellites.size < 4) {
                    val prn = parts[i].ifEmpty { "未知" }
                    val elevation = parts[i + 1].ifEmpty { "未知" }
                    val azimuth = parts[i + 2].ifEmpty { "未知" }
                    val snr =
                            parts.getOrNull(i + 3)
                                    ?.let { field ->
                                        if (field.contains("*")) {
                                            field.substring(0, field.indexOf("*"))
                                        } else {
                                            field
                                        }
                                    }
                                    ?.ifEmpty { "未知" }
                                    ?: "未知"

                    if (prn != "未知" && prn.isNotEmpty()) {
                        satellites.add(SatelliteInfo(prn, elevation, azimuth, snr, constellation))
                    }
                    i += 4
                }

                // 卫星数量统计
                if (messageNumber == 1) {
                    onSatelliteUpdate(totalSatellites, 0, "未知")
                }

                // 始终更新卫星详情
                if (satellites.isNotEmpty()) {
                    onSatelliteDetails(satellites)
                }
            }
        }

        // GPGSA - 精度因子和有效卫星
        message.startsWith("\$GPGSA") ||
                message.startsWith("\$GNGSA") ||
                message.startsWith("\$GLGSA") ||
                message.startsWith("\$GAGSA") ||
                message.startsWith("\$BDGSA") -> {
            if (parts.size >= 18) {
                val usedSats = parts.subList(3, 15).count { it.isNotEmpty() }
                val hdopValue = if (parts[16].isNotEmpty()) parts[16] else "未知"
                onSatelliteUpdate(0, usedSats, hdopValue)
            }
        }
    }
}

// 格式化纬度
fun formatLatitude(latStr: String, direction: String): String {
    return try {
        val lat = latStr.toDouble()
        val degrees = (lat / 100).toInt()
        val minutes = lat - degrees * 100
        val decimal = degrees + minutes / 60
        val finalLat = if (direction == "S") -decimal else decimal
        String.format("%.6f°%s", kotlin.math.abs(finalLat), direction)
    } catch (e: Exception) {
        "解析错误"
    }
}

// 格式化经度
fun formatLongitude(lonStr: String, direction: String): String {
    return try {
        val lon = lonStr.toDouble()
        val degrees = (lon / 100).toInt()
        val minutes = lon - degrees * 100
        val decimal = degrees + minutes / 60
        val finalLon = if (direction == "W") -decimal else decimal
        String.format("%.6f°%s", kotlin.math.abs(finalLon), direction)
    } catch (e: Exception) {
        "解析错误"
    }
}
