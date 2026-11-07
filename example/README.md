# Android 示例演示

演示 [agent-log-android](../README.md) 的接入方式与四类自动埋点 + 业务手动日志。

## 构建与运行

```bash
./gradlew :example:assembleDebug
```

安装到设备后，点击界面按钮分别触发：

- **info / warn / error**：业务手动日志（category）
- **child 上下文**：绑定 `demo=child-logger` 作用域，后续日志自动携带
- **network_request**：经 `AgentNetworkInterceptor`（OkHttp）记录，并串联 `traceparent`
- **hang**：主线程阻塞 >250ms 触发 watchdog
- **crash**：子线程抛未捕获异常，由崩溃处理器记录

日志落盘于 `context.cacheDir/agentlog/agent-log.jsonl`，点 **Show log path** 查看路径后用 `adb pull` 取回分析。
每条记录均为 Schema v1 合规 JSONL，可被 Agent 按行消费。
