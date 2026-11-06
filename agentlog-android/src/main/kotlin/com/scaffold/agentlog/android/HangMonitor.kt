package com.scaffold.agentlog.android

import android.os.Handler
import android.os.Looper
import com.scaffold.agentlog.AgentLog
import com.scaffold.agentlog.EventType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主线程卡顿监控（>250ms 记 hang）。
 *
 * 为什么必须后台独立线程计时：主线程被业务阻塞时 Looper **根本不转**，
 * 基于 Looper/Choreographer 帧回调的观察者不会触发，永远测不到真实卡顿。
 * 对齐 macOS `HangMonitor`：主线程每 100ms 刷新心跳，后台线程发现心跳超时即判定 hang。
 */
object HangMonitor {
    private const val THRESHOLD_MS = 250L
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastPing = 0L

    @Volatile
    private var reported = false

    fun start() {
        if (!running.compareAndSet(false, true)) return // 幂等：避免重复 bootstrap 多注册
        lastPing = System.currentTimeMillis()
        val mainHandler = Handler(Looper.getMainLooper())
        val ping = object : Runnable {
            override fun run() {
                lastPing = System.currentTimeMillis()
                reported = false
                mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.post(ping)

        Thread({ watchdog() }, "AgentLog.HangMonitor").apply { isDaemon = true }.start()
    }

    private fun watchdog() {
        while (running.get()) {
            Thread.sleep(100)
            val elapsed = System.currentTimeMillis() - lastPing
            if (elapsed > THRESHOLD_MS && !reported) {
                reported = true
                AgentLog.recordAuto(
                    EventType.HANG,
                    "main thread blocked ${elapsed}ms",
                    payload = mapOf("duration_ms" to elapsed),
                )
            }
        }
    }
}
