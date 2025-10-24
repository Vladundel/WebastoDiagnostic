package com.webasto.diagnostic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceList: ListView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var sendCommandButton: Button
    private lateinit var commandEditText: EditText
    private lateinit var scanButton: Button
    private lateinit var deviceInfoText: TextView
    private lateinit var temperatureText: TextView
    private lateinit var voltageText: TextView
    private lateinit var errorCodeText: TextView
    
    private var connectedDevice: BluetoothDevice? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val deviceListAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    
    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_LOCATION_PERMISSION = 2
        private val WEBASTO_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeViews()
        setupBluetooth()
        setupListeners()
    }
    
    private fun initializeViews() {
        deviceList = findViewById(R.id.deviceList)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        sendCommandButton = findViewById(R.id.sendCommandButton)
        commandEditText = findViewById(R.id.commandEditText)
        scanButton = findViewById(R.id.scanButton)
        deviceInfoText = findViewById(R.id.deviceInfoText)
        temperatureText = findViewById(R.id.temperatureText)
        voltageText = findViewById(R.id.voltageText)
        errorCodeText = findViewById(R.id.errorCodeText)
        
        deviceList.adapter = deviceListAdapter
        updateConnectionStatus(false)
    }
    
    private fun setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            showToast("Bluetooth не поддерживается на этом устройстве")
            finish()
        }
    }
    
    private fun setupListeners() {
        scanButton.setOnClickListener {
            if (checkPermissions()) {
                scanDevices()
            }
        }
        
        deviceList.setOnItemClickListener { _, _, position, _ ->
            if (position < discoveredDevices.size) {
                connectToDevice(discoveredDevices[position])
            }
        }
        
        connectButton.setOnClickListener {
            connectedDevice?.let { connectToDevice(it) }
        }
        
        disconnectButton.setOnClickListener {
            disconnectDevice()
        }
        
        sendCommandButton.setOnClickListener {
            val command = commandEditText.text.toString().trim()
            if (command.isNotEmpty()) {
                sendCommand(command)
            }
        }
        
        findViewById<Button>(R.id.btnGetStatus).setOnClickListener { sendCommand("STATUS") }
        findViewById<Button>(R.id.btnGetTemp).setOnClickListener { sendCommand("TEMP") }
        findViewById<Button>(R.id.btnGetVoltage).setOnClickListener { sendCommand("VOLT") }
        findViewById<Button>(R.id.btnGetErrors).setOnClickListener { sendCommand("ERRORS") }
        findViewById<Button>(R.id.btnStartHeater).setOnClickListener { sendCommand("START") }
        findViewById<Button>(R.id.btnStopHeater).setOnClickListener { sendCommand("STOP") }
        findViewById<Button>(R.id.btnResetErrors).setOnClickListener { sendCommand("RESET") }
    }
    
    private fun checkPermissions(): Boolean {
        val permissions = arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
        )
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        return if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_LOCATION_PERMISSION)
            false
        } else {
            true
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun scanDevices() {
        if (!bluetoothAdapter.isEnabled) {
            showToast("Включите Bluetooth")
            return
        }
        
        deviceListAdapter.clear()
        discoveredDevices.clear()
        addToLog("Начало сканирования устройств...")
        
        val pairedDevices = bluetoothAdapter.bondedDevices
        pairedDevices?.forEach { device ->
            if (isWebastoDevice(device)) {
                discoveredDevices.add(device)
                deviceListAdapter.add("${device.name}\n${device.address}")
            }
        }
        
        bluetoothAdapter.startDiscovery()
        
        handler.postDelayed({
            bluetoothAdapter.cancelDiscovery()
            addToLog("Сканирование завершено. Найдено устройств: ${discoveredDevices.size}")
        }, 10000)
    }
    
    private fun isWebastoDevice(device: BluetoothDevice): Boolean {
        val name = device.name ?: ""
        return name.contains("Webasto", ignoreCase = true) ||
               name.contains("WB", ignoreCase = true) ||
               device.address.startsWith("00:12:")
    }
    
    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        addToLog("Подключение к ${device.name}...")
        statusText.text = "Подключение..."
        
        executor.execute {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(WEBASTO_UUID)
                bluetoothSocket?.connect()
                
                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream
                
                connectedDevice = device
                
                handler.post {
                    updateConnectionStatus(true)
                    addToLog("Успешно подключено к ${device.name}")
                    startReadingData()
                }
            } catch (e: IOException) {
                handler.post {
                    addToLog("Ошибка подключения: ${e.message}")
                    statusText.text = "Ошибка подключения"
                }
            }
        }
    }
    
    private fun disconnectDevice() {
        executor.execute {
            try {
                bluetoothSocket?.close()
                inputStream?.close()
                outputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            
            handler.post {
                updateConnectionStatus(false)
                addToLog("Отключено от устройства")
                connectedDevice = null
                bluetoothSocket = null
                inputStream = null
                outputStream = null
            }
        }
    }
    
    private fun sendCommand(command: String) {
        if (bluetoothSocket == null || !bluetoothSocket!!.isConnected) {
            showToast("Нет подключения к устройству")
            return
        }
        
        executor.execute {
            try {
                val formattedCommand = formatCommand(command)
                outputStream?.write(formattedCommand.toByteArray())
                outputStream?.flush()
                
                handler.post {
                    addToLog("Отправлена команда: $command")
                }
            } catch (e: IOException) {
                handler.post {
                    addToLog("Ошибка отправки команды: ${e.message}")
                }
            }
        }
    }
    
    private fun formatCommand(command: String): String {
        return when (command.uppercase()) {
            "STATUS" -> "ATSTATUS\r\n"
            "TEMP" -> "ATTEMP\r\n"
            "VOLT" -> "ATVOLT\r\n"
            "ERRORS" -> "ATERRORS\r\n"
            "START" -> "ATSTART\r\n"
            "STOP" -> "ATSTOP\r\n"
            "RESET" -> "ATRESET\r\n"
            else -> "$command\r\n"
        }
    }
    
    private fun startReadingData() {
        executor.execute {
            val buffer = ByteArray(1024)
            var bytes: Int
            
            while (bluetoothSocket?.isConnected == true) {
                try {
                    bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val data = String(buffer, 0, bytes)
                        handler.post {
                            processReceivedData(data)
                        }
                    }
                } catch (e: IOException) {
                    handler.post {
                        addToLog("Ошибка чтения данных: ${e.message}")
                        disconnectDevice()
                    }
                    break
                }
            }
        }
    }
    
    private fun processReceivedData(data: String) {
        addToLog("Получено: $data")
        
        when {
            data.contains("TEMP:") -> {
                val temp = extractValue(data, "TEMP:")
                temperatureText.text = "Температура: $temp°C"
            }
            data.contains("VOLT:") -> {
                val volt = extractValue(data, "VOLT:")
                voltageText.text = "Напряжение: $volt V"
            }
            data.contains("ERROR:") -> {
                val error = extractValue(data, "ERROR:")
                errorCodeText.text = "Код ошибки: $error"
                interpretErrorCode(error)
            }
            data.contains("STATUS:") -> {
                val status = extractValue(data, "STATUS:")
                deviceInfoText.text = "Статус: ${interpretStatus(status)}"
            }
        }
    }
    
    private fun extractValue(data: String, prefix: String): String {
        return data.substringAfter(prefix).substringBefore("\r").trim()
    }
    
    private fun interpretStatus(status: String): String {
        return when (status) {
            "0" -> "Выключен"
            "1" -> "Запуск"
            "2" -> "Работа"
            "3" -> "Охлаждение"
            "4" -> "Ошибка"
            else -> "Неизвестно ($status)"
        }
    }
    
    private fun interpretErrorCode(error: String) {
        val errorMessage = when (error) {
            "0" -> "Нет ошибок"
            "1" -> "Перегрев"
            "2" -> "Низкое напряжение"
            "3" -> "Высокое напряжение"
            "4" -> "Ошибка пламени"
            "5" -> "Ошибка вентилятора"
            "6" -> "Ошибка датчика температуры"
            "7" -> "Ошибка топливного насоса"
            "8" -> "Перезапуск"
            else -> "Неизвестная ошибка ($error)"
        }
        addToLog("Интерпретация ошибки: $errorMessage")
    }
    
    private fun updateConnectionStatus(connected: Boolean) {
        connectButton.isEnabled = !connected
        disconnectButton.isEnabled = connected
        sendCommandButton.isEnabled = connected
        
        statusText.text = if (connected) {
            "Подключено к ${connectedDevice?.name ?: "устройству"}"
        } else {
            "Не подключено"
        }
    }
    
    private fun addToLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"
        
        logText.append(logEntry)
        
        val scrollView = findViewById<ScrollView>(R.id.logScrollView)
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnectDevice()
        executor.shutdown()
    }
}
