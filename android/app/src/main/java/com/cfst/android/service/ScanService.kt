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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

data class ScanStatus(
    val running: Boolean = false,
    val phase: String = "就绪",
    val progress: Int = 0,
    val finishedWithResults: Boolean = false,
)

class ScanService : Service() {

    companion object {
        const val ACTION_START = "com.cfst.android.action.START_SCAN"
        const val ACTION_CANCEL = ScanNotifier.ACTION_CANCEL

        val scanStatus = MutableStateFlow(ScanStatus())
    }

    private lateinit var notifier: ScanNotifier
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var collectorJob: Job? = null
    private var watchdogJob: Job? = null
    private var receivedEvent = false
    private var lastResultCount = 0
    private var lastNotifyAt = 0L

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
        collectorJob?.cancel()
        watchdogJob?.cancel()
        receivedEvent = false
        lastResultCount = 0
        lastNotifyAt = 0L
        scanStatus.value = ScanStatus(running = true, phase = "正在准备…", progress = 0)
        collectorJob = serviceScope.launch {
            controller.events.collect { event -> handleEvent(event) }
        }
        watchdogJob = serviceScope.launch {
            delay(5_000)
            if (!receivedEvent) {
                controller.cancel()
                stopScan()
            }
        }
    }

    private suspend fun handleEvent(event: ScanEvent) {
        receivedEvent = true
        when (event) {
            is ScanEvent.Progress -> {
                scanStatus.update {
                    it.copy(phase = event.text, progress = event.pct.coerceIn(0, 100))
                }
                val etaText = event.etaMs?.let { "（预计 ${it / 1000}s 剩余）" } ?: ""
                notifyThrottled(event.pct, "${event.text}$etaText")
            }
            is ScanEvent.PhaseChanged -> {
                scanStatus.update { it.copy(phase = event.phase) }
                notifier.notifyProgress(0, event.phase)
            }
            is ScanEvent.Log -> {
                scanStatus.update { it.copy(phase = event.line) }
                notifyThrottled(0, event.line)
            }
            is ScanEvent.ResultReady -> {
                lastResultCount = event.results.size
                scanStatus.update { it.copy(progress = 100) }
            }
            is ScanEvent.Error -> {
                scanStatus.value = ScanStatus(running = false, phase = "错误", progress = 0)
                notifier.notifyProgress(0, "扫描失败：${event.message}")
                (application as CfApp).container.scanController.cancel()
                stopScan()
            }
            ScanEvent.Cancelled -> {
                scanStatus.value = ScanStatus(running = false, phase = "已取消", progress = 0)
                notifier.notifyProgress(0, "已取消")
                stopScan()
            }
            ScanEvent.Done -> {
                scanStatus.value = ScanStatus(
                    running = false,
                    phase = "完成",
                    progress = 0,
                    finishedWithResults = lastResultCount > 0,
                )
                notifier.notifyProgress(0, "扫描完成")
                (application as CfApp).container.scanController.cancel()
                stopScan()
            }
        }
    }

    private fun notifyThrottled(pct: Int, text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt >= 500 || pct >= 100) {
            lastNotifyAt = now
            notifier.notifyProgress(pct, text)
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
