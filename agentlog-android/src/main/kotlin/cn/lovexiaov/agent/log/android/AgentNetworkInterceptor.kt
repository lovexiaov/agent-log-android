package cn.lovexiaov.agent.log.android

import cn.lovexiaov.agent.log.AgentLog
import cn.lovexiaov.agent.log.EventType
import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/**
 * OkHttp 拦截器：自动注入并串联 `traceparent`，完成时收敛为一条 `network_request` 事件。
 * 业务把本拦截器加入 `OkHttpClient`（见 README）即获得网络自动埋点。
 *
 * 对齐 macOS `NetworkInstrumentation`：不记 body，只记 method/host/path/status/耗时/error；
 * 出站请求注入 `traceparent`，使「用户点击 → 网络请求 → 响应/错误」在 Agent 视角可回溯。
 */
class AgentNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val existing = request.header("traceparent")
        val traceId = existing ?: "trace_${UUID.randomUUID()}"
        val traced = if (existing == null) {
            request.newBuilder().header("traceparent", traceId).build()
        } else {
            request
        }

        val start = System.currentTimeMillis()
        val response = try {
            chain.proceed(traced)
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - start
            AgentLog.recordAuto(
                EventType.NETWORK_REQUEST,
                "network ${request.method} ${request.url.encodedPath} -> ERROR",
                traceId = traceId,
                payload = mapOf(
                    "method" to request.method,
                    "host" to request.url.host,
                    "path" to request.url.encodedPath,
                    "duration_ms" to duration,
                    "error" to (e.message ?: e.javaClass.simpleName),
                ),
            )
            throw e
        }

        val duration = System.currentTimeMillis() - start
        val status = response.code
        AgentLog.recordAuto(
            EventType.NETWORK_REQUEST,
            "network ${request.method} ${request.url.encodedPath} -> $status",
            traceId = traceId,
            payload = mapOf(
                "method" to request.method,
                "host" to request.url.host,
                "path" to request.url.encodedPath,
                "status" to status,
                "duration_ms" to duration,
            ),
        )
        return response
    }
}
