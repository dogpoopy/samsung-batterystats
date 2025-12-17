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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val MANAGE_STORAGE_PERMISSION_CODE = 101
    }

    private lateinit var btnOpenSysDump: Button
    private lateinit var btnReadLogs: Button
    private lateinit var btnDeleteLogs: Button
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
        btnDeleteLogs = findViewById(R.id.btnDeleteLogs)
        tvFirstUseDate = findViewById(R.id.tvFirstUseDate)
        tvBatteryHealth = findViewById(R.id.tvBatteryHealth)
        tvChargeCycles = findViewById(R.id.tvChargeCycles)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupListeners() {
        btnOpenSysDump.setOnClickListener { openSysDump() }
        btnReadLogs.setOnClickListener { readBatteryLogs() }
        btnDeleteLogs.setOnClickListener { openSysDumpForDeletion() }
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
            .setCancelable(false)
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
                    "Permission not granted. App will exit.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
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
                    3. Now, select 'Copy to sdcard(include CP Ramdump)'
                    4. Come back to this app and tap 'Read Battery Logs'
                    """.trimIndent()
                )
                .setPositiveButton("Got it", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening dialer: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSysDumpForDeletion() {
        AlertDialog.Builder(this)
            .setTitle("Delete Log Files")
            .setMessage(
                """
                1. After opening SysDump, tap 'Delete dumpstate/logcat'
                
                2. This will delete the log files and free up storage space.
                
                3. You'll need to run SysDump (Step 1-3) again to read battery stats after deletion.
                
                Ready to proceed to SysDump?
                """.trimIndent()
            )
            .setPositiveButton("Open SysDump") { _, _ ->
                launchSysDumpForDeletion()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchSysDumpForDeletion() {
        try {
            val intent = Intent(Intent.ACTION_DIAL)
            intent.data = Uri.parse("tel:${Uri.encode("*#9900#")}")
            startActivity(intent)
            Toast.makeText(
                this, 
                "Tap 'Delete dumpstate/logcat'", 
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening dialer: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readBatteryLogs() {
        val dumpDir = File("/storage/emulated/0/log/")
        if (!dumpDir.exists()) {
            tvStatus.text = "Log files not found. Please run SysDump first."
            tvStatus.visibility = View.VISIBLE
            Toast.makeText(
                this,
                "No log directory found. Please complete SysDump steps 1-3 first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val dumpFiles = dumpDir.listFiles { f -> 
            f.name.startsWith("dumpState_") && f.name.endsWith(".log") 
        }
        
        if (dumpFiles == null || dumpFiles.isEmpty()) {
            tvStatus.text = "No log files found. Please run SysDump first."
            tvStatus.visibility = View.VISIBLE
            Toast.makeText(
                this,
                "No dump files found. Please complete SysDump steps 1-3 first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

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
                tvStatus.text = "Failed to parse battery data from logs."
                Toast.makeText(
                    this@MainActivity,
                    "Could not extract battery information from logs.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun parseBatteryLogs(): Boolean {
        val dumpDir = File("/storage/emulated/0/log/")
        if (!dumpDir.exists()) return false
        
        val dumpFiles = dumpDir.listFiles { f -> 
            f.name.startsWith("dumpState_") && f.name.endsWith(".log") 
        }
        
        dumpFiles?.sortedByDescending { it.lastModified() }?.forEach { file ->
            if (parseDumpStateFile(file)) return true
        }

        return false
    }

    private fun parseDumpStateFile(file: File): Boolean {
        try {
            file.forEachLine { line ->
                if (batteryStats.firstUseDate == null && line.contains("battery FirstUseDate:")) {
                    val value = extractValue(line)
                    if (value.isNotEmpty() && value.matches(Regex("\\d{8}"))) {
                        batteryStats.firstUseDate = value
                    }
                }
                
                if (batteryStats.healthPercentage == -1 && line.contains("mSavedBatteryAsoc:")) {
                    val value = extractValue(line)
                    batteryStats.healthPercentage = value.toIntOrNull() ?: -1
                }
                
                if (batteryStats.chargeCycles == -1 && line.contains("mSavedBatteryUsage:")) {
                    val value = extractValue(line)
                    val fullValue = value.toIntOrNull() ?: -1
                    if (fullValue != -1) batteryStats.chargeCycles = fullValue / 100
                }
            }
            return batteryStats.isValid()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun extractValue(line: String): String {
        val bracketMatch = Regex("""\[([^\]]+)\]""").find(line)
        if (bracketMatch != null) {
            return bracketMatch.groupValues[1].trim()
        }
        
        val colonIndex = line.indexOf(':')
        if (colonIndex != -1) {
            return line.substring(colonIndex + 1).trim()
        }
        
        return ""
    }

    private fun displayBatteryStats() {
        tvFirstUseDate.text = batteryStats.firstUseDate?.let { 
            "First Use: ${formatDate(it)}" 
        } ?: "First Use: Not available"

        tvBatteryHealth.text = if (batteryStats.healthPercentage != -1) {
            "Battery Health: ${batteryStats.healthPercentage}%"
        } else {
            "Battery Health: Not available"
        }

        tvChargeCycles.text = if (batteryStats.chargeCycles != -1) {
            "Charge Cycles: ${batteryStats.chargeCycles}"
        } else {
            "Charge Cycles: Not available"
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
            if (!(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(
                    this,
                    "Permission not granted. App will exit.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MANAGE_STORAGE_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(
                        this,
                        "Permission not granted. App will exit.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
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
