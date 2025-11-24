package com.samsung.batterystats

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val MANAGE_STORAGE_PERMISSION_CODE = 101
    }

    private lateinit var btnOpenSysDump: Button
    private lateinit var btnReadLogs: Button
    private lateinit var tvFirstUseDate: TextView
    private lateinit var tvBatteryHealth: TextView
    private lateinit var tvChargeCycles: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private val batteryStats = BatteryStats()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        checkPermissions()
    }

    private fun initViews() {
        btnOpenSysDump = findViewById(R.id.btnOpenSysDump)
        btnReadLogs = findViewById(R.id.btnReadLogs)
        tvFirstUseDate = findViewById(R.id.tvFirstUseDate)
        tvBatteryHealth = findViewById(R.id.tvBatteryHealth)
        tvChargeCycles = findViewById(R.id.tvChargeCycles)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupListeners() {
        btnOpenSysDump.setOnClickListener { openSysDump() }
        btnReadLogs.setOnClickListener { readBatteryLogs() }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showManageStoragePermissionDialog()
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun showManageStoragePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Storage Permission Required")
            .setMessage("This app needs access to storage to read battery logs. Please grant 'All files access' permission.")
            .setPositiveButton("Grant Permission") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        startActivityForResult(intent, MANAGE_STORAGE_PERMISSION_CODE)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivityForResult(intent, MANAGE_STORAGE_PERMISSION_CODE)
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(
                    this,
                    "Permission denied. App may not work properly.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private fun openSysDump() {
        try {
            val intent = Intent(Intent.ACTION_DIAL)
            intent.data = Uri.parse("tel:${Uri.encode("*#9900#")}")
            startActivity(intent)

            AlertDialog.Builder(this)
                .setTitle("Instructions")
                .setMessage(
                    """
                    1. Tap 'Run dumpstate/logcat'
                    2. Wait 2-3 minutes until completion and select 'OK'
                    3. Now, select 'Copy to sdcard(include CP Ramdump'
                    4. Come back to this app and tap 'Read Battery Logs'
                    """.trimIndent()
                )
                .setPositiveButton("Got it", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening dialer: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readBatteryLogs() {
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Reading battery logs..."
        tvStatus.visibility = View.VISIBLE

        scope.launch {
            val success = withContext(Dispatchers.IO) { parseBatteryLogs() }

            progressBar.visibility = View.GONE
            if (success) {
                displayBatteryStats()
                tvStatus.text = "Battery stats loaded successfully!"
            } else {
                tvStatus.text = "Failed to read logs. Please ensure you've run SysDump first."
                Toast.makeText(
                    this@MainActivity,
                    "Log file not found or couldn't be read. Please run SysDump first.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun parseBatteryLogs(): Boolean {
        val primaryFile = File("/storage/emulated/0/log/battery_service/battery_service_main_history")
        if (primaryFile.exists() && parseBatteryServiceHistory(primaryFile)) return true

        // fallback to dumpState logs
        val dumpDir = File("/storage/emulated/0/log/")
        val dumpFiles = dumpDir.listFiles { f -> f.name.startsWith("dumpState_") && f.name.endsWith(".log") }
        dumpFiles?.forEach { file ->
            if (parseDumpStateFile(file)) return true
        }

        return false
    }

    // Read battery_service_main_history backwards to get the latest values
    private fun parseBatteryServiceHistory(file: File): Boolean {
        try {
            val firstUseRegex = Regex("""# \[SS]\[BattInfo]FirstUseDateData saveInfoHistory\s+efsValue:(\d+)""")
            val asocRegex = Regex("""# \[SS]\[BattInfo]AsocData saveInfoHistory\s+efsValue:(\d+)""")
            val dischargeRegex = Regex("""# \[SS]\[BattInfo]DischargeLevelData saveInfoHistory\s+efsValue:(\d+)""")

            RandomAccessFile(file, "r").use { raf ->
                var pointer = raf.length() - 1
                val sb = StringBuilder()
                while (pointer >= 0) {
                    raf.seek(pointer)
                    val c = raf.read().toChar()
                    if (c == '\n') {
                        val line = sb.reverse().toString()
                        sb.setLength(0)

                        if (batteryStats.firstUseDate == null) firstUseRegex.find(line)?.let { batteryStats.firstUseDate = it.groupValues[1] }
                        if (batteryStats.healthPercentage == -1) asocRegex.find(line)?.let { batteryStats.healthPercentage = it.groupValues[1].toInt() }
                        if (batteryStats.chargeCycles == -1) dischargeRegex.find(line)?.let {
                            val fullValue = it.groupValues[1].toInt()
                            batteryStats.chargeCycles = fullValue / 100
                        }

                        if (batteryStats.isValid()) break
                    } else {
                        sb.append(c)
                    }
                    pointer--
                }

                if (!batteryStats.isValid() && sb.isNotEmpty()) {
                    val line = sb.reverse().toString()
                    if (batteryStats.firstUseDate == null) firstUseRegex.find(line)?.let { batteryStats.firstUseDate = it.groupValues[1] }
                    if (batteryStats.healthPercentage == -1) asocRegex.find(line)?.let { batteryStats.healthPercentage = it.groupValues[1].toInt() }
                    if (batteryStats.chargeCycles == -1) dischargeRegex.find(line)?.let {
                        val fullValue = it.groupValues[1].toInt()
                        batteryStats.chargeCycles = fullValue / 100
                    }
                }
            }

            return batteryStats.isValid()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun parseDumpStateFile(file: File): Boolean {
        try {
            file.forEachLine { line ->
                if (batteryStats.firstUseDate == null && line.contains("battery FirstUseDate:")) {
                    batteryStats.firstUseDate = line.substringAfter("[").substringBefore("]")
                }
                if (batteryStats.healthPercentage == -1 && line.contains("mSavedBatteryAsoc:")) {
                    batteryStats.healthPercentage = line.substringAfter("[").substringBefore("]").toIntOrNull() ?: -1
                }
                if (batteryStats.chargeCycles == -1 && line.contains("mSavedBatteryUsage:")) {
                    val fullValue = line.substringAfter("[").substringBefore("]").toIntOrNull() ?: -1
                    if (fullValue != -1) batteryStats.chargeCycles = fullValue / 100
                }
            }
            return batteryStats.isValid()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun displayBatteryStats() {
        tvFirstUseDate.text = batteryStats.firstUseDate?.let { "First Use: ${formatDate(it)}" } ?: "First Use: Not available"

        if (batteryStats.healthPercentage != -1) {
            tvBatteryHealth.text = "Battery Health: ${batteryStats.healthPercentage}%"
            val color = when {
                batteryStats.healthPercentage >= 80 -> android.R.color.holo_green_dark
                batteryStats.healthPercentage >= 70 -> android.R.color.holo_orange_dark
                else -> android.R.color.holo_red_dark
            }
            tvBatteryHealth.setTextColor(resources.getColor(color, null))
        } else {
            tvBatteryHealth.text = "Battery Health: Not available"
        }

        if (batteryStats.chargeCycles != -1) {
            tvChargeCycles.text = "Charge Cycles: ${batteryStats.chargeCycles}"
        } else {
            tvChargeCycles.text = "Charge Cycles: Not available"
        }
    }

    private fun formatDate(dateStr: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
            val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
            val date = inputFormat.parse(dateStr)
            outputFormat.format(date!!)
        } catch (e: Exception) {
            dateStr
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Permission denied. App may not work properly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MANAGE_STORAGE_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Permission denied. App may not work properly.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

data class BatteryStats(
    var firstUseDate: String? = null,
    var healthPercentage: Int = -1,
    var chargeCycles: Int = -1
) {
    fun isValid(): Boolean {
        return firstUseDate != null || healthPercentage != -1 || chargeCycles != -1
    }
}