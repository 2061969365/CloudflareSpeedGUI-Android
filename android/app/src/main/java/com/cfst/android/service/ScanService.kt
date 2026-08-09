package com.cfst.android.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.cfst.android.CfApp
import com.cfst.android.engine.model.ScanEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScanService : Service() {

    companion object {
        const val ACTION_START = "com.cfst.android.action.START_SCAN"
        const val ACTION_CANCEL = ScanNotifier.ACTION_CANCEL
    }

    private lateinit var notifier: ScanNotifier
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var receivedEvent = false

    override fun onCreate() {
        super.onCreate()
        notifier = ScanNotifier(this)
        notifier.ensureChannel()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cfst:scan")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startScan()
            ACTION_CANCEL -> cancelScan()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startScan() {
        startForeground(
            ScanNotifier.NOTIFICATION_ID,
            notifier.buildNotification(0, "正在准备…"),
        )
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
        val controller = (application as CfApp).container.scanController
        serviceScope.launch {
            controller.events.collect { event -> handleEvent(event) }
        }
        serviceScope.launch {
            delay(5_000)
            if (!receivedEvent) stopScan()
        }
    }

    private suspend fun handleEvent(event: ScanEvent) {
        receivedEvent = true
        when (event) {
            is ScanEvent.Progress -> {
                val etaText = event.etaMs?.let { "（预计 ${it / 1000}s 剩余）" } ?: ""
                notifier.notifyProgress(event.pct, "${event.text}$etaText")
            }
            is ScanEvent.PhaseChanged -> notifier.notifyProgress(0, event.phase)
            is ScanEvent.Log -> notifier.notifyProgress(0, event.line)
            is ScanEvent.Error -> {
                notifier.notifyProgress(0, "扫描失败：${event.message}")
                stopScan()
            }
            is ScanEvent.Done -> stopScan()
            is ScanEvent.ResultReady -> { /* 结果展示由 ViewModel 负责 */ }
        }
    }

    private fun cancelScan() {
        (application as CfApp).container.scanController.cancel()
        stopScan()
    }

    private fun stopScan() {
        notifier.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        notifier.cancel()
        super.onDestroy()
    }
}
