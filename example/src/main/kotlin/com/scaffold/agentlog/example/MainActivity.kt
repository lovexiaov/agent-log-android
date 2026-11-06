package com.scaffold.agentlog.example

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.Toast
import com.scaffold.agentlog.AgentLog
import com.scaffold.agentlog.android.AgentNetworkInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 演示四种自动埋点 + 业务手动日志：
 * - screen_view：进入本页自动记录（无需代码）
 * - crash：点「Trigger crash」由未捕获异常处理器记录
 * - hang：点「Simulate hang」主线程阻塞 >250ms 触发 watchdog
 * - network：点「Network request」经 AgentNetworkInterceptor 记录 network_request + 串联 traceparent
 * 日志落盘于 `context.cacheDir/agentlog/agent-log.jsonl`，可用「Show log path」查看并 pull 分析。
 */
class MainActivity : Activity() {
    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor(AgentNetworkInterceptor())
            .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnInfo).setOnClickListener {
            AgentLog.info("用户点击了 info 按钮", category = "ui")
        }
        findViewById<Button>(R.id.btnWarn).setOnClickListener {
            AgentLog.warn("用户点击了 warn 按钮", category = "ui")
        }
        findViewById<Button>(R.id.btnError).setOnClickListener {
            AgentLog.error(
                "用户点击了 error 按钮",
                category = "ui",
                payload = mapOf("source" to "demo"),
            )
        }
        findViewById<Button>(R.id.btnChild).setOnClickListener {
            // child：绑定上下文字段，后续日志自动携带（用 info 以匹配 bootstrap 的 minLevel=INFO）
            val child = AgentLog.child(mapOf("demo" to "child-logger"))
            child.info("这是一条带上下文字段(sub-logger)的日志")
        }
        findViewById<Button>(R.id.btnNetwork).setOnClickListener { doNetworkRequest() }
        findViewById<Button>(R.id.btnHang).setOnClickListener { simulateHang() }
        findViewById<Button>(R.id.btnCrash).setOnClickListener { triggerCrash() }
        findViewById<Button>(R.id.btnPath).setOnClickListener {
            Toast.makeText(this, "日志: ${AgentLog.exportPath()}", Toast.LENGTH_LONG).show()
        }
    }

    private fun doNetworkRequest() {
        Thread {
            try {
                val req = Request.Builder().url("https://httpbin.org/get").build()
                val code = client.newCall(req).execute().code
                runOnUiThread { Toast.makeText(this, "网络请求完成: $code", Toast.LENGTH_SHORT).show() }
            } catch (e: IOException) {
                runOnUiThread {
                    Toast.makeText(this, "网络失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun simulateHang() {
        // 主线程阻塞 >250ms，触发 HangMonitor 记录 hang 事件
        SystemClock.sleep(700)
        Toast.makeText(this, "已模拟主线程卡顿(700ms)", Toast.LENGTH_SHORT).show()
    }

    private fun triggerCrash() {
        // 子线程抛未捕获异常，验证 crash 自动埋点（进程会退出，重启后查 agent-log.jsonl）
        Thread {
            throw RuntimeException("演示崩溃：手动触发以验证 crash 自动埋点")
        }.start()
    }
}
