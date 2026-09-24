package com.moonmicrophone.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.moonmicrophone.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var micService: MicStreamService? = null
    private var serviceBound = false

    // ── Запрос разрешения на микрофон ────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startStreaming()
        else Toast.makeText(this, "Нужен доступ к микрофону!", Toast.LENGTH_LONG).show()
    }

    // ── Service connection ────────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            micService = (binder as MicStreamService.LocalBinder).getService()
            serviceBound = true
            micService?.setStatusCallback { status, isConnected ->
                runOnUiThread { updateStatus(status, isConnected) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            micService = null
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Восстанавливаем сохранённый IP
        val prefs = getSharedPreferences("moon_mic", Context.MODE_PRIVATE)
        binding.etIpAddress.setText(prefs.getString("last_ip", ""))

        binding.btnConnect.setOnClickListener { onConnectClick() }
        binding.btnDisconnect.setOnClickListener { onDisconnectClick() }

        updateStatus("Не подключён", false)
    }

    override fun onStart() {
        super.onStart()
        Intent(this, MicStreamService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ── UI actions ────────────────────────────────────────────────────────

    private fun onConnectClick() {
        val ip = binding.etIpAddress.text.toString().trim()
        if (ip.isEmpty()) {
            binding.etIpAddress.error = "Введи IP-адрес ПК"
            return
        }

        // Сохраняем IP
        getSharedPreferences("moon_mic", Context.MODE_PRIVATE).edit()
            .putString("last_ip", ip).apply()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startStreaming()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onDisconnectClick() {
        micService?.stopStreaming()
        stopService(Intent(this, MicStreamService::class.java))
        updateStatus("Не подключён", false)
    }

    private fun startStreaming() {
        val ip = binding.etIpAddress.text.toString().trim()
        val intent = Intent(this, MicStreamService::class.java).apply {
            putExtra(MicStreamService.EXTRA_HOST, ip)
            putExtra(MicStreamService.EXTRA_PORT, MicStreamService.DEFAULT_PORT)
        }
        ContextCompat.startForegroundService(this, intent)
        updateStatus("Подключение…", false)
    }

    private fun updateStatus(status: String, connected: Boolean) {
        binding.tvStatus.text = status
        binding.tvStatusDot.text = if (connected) "🟢" else "🔴"
        binding.btnConnect.isEnabled = !connected
        binding.btnDisconnect.isEnabled = connected
        binding.etIpAddress.isEnabled = !connected
    }
}
