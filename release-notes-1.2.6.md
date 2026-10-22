# CodexBridgeMobile 1.2.6

## 修复：长连接泄漏与连接/线程膨胀（PC 卡顿根因）

Bridge 侧观测到本机对 `/cbdp/v1/events` 并发持有数百条 SSE，进程线程数涨到 500+。
逐条核对设备代码后确认问题属实，已修复。

### 1. OKHttpClient 每实例新建 → 全应用共享

`ApiClient` 原先在 `ApiClient.kt:79` 建自己的 `OkHttpClient`，`CbdpClient` 又建一个；
而 `ApiClient` 在路由探测、配对、通知服务里被反复创建，连接池与线程池随之膨胀。

现改为新增 `SharedHttp`：全进程固定 3 个实例（base / probe / stream），
由同一个 base `newBuilder()` 派生，**共享同一连接池、Dispatcher 与清理线程**，
数量不再随 `ApiClient` 增加。

### 2. CBDP 长连接重连前不释放旧连接 → 泄漏数百条 SSE

`CbdpClient.connectSse()` 直接 `newEventSource(...)` 覆盖 `sseSource`，旧流从未 cancel；
握手超时后 `sseSource` 仍被保留，下一次 `connect()` 再叠加一条。

现修复：

- `connect()` 在拨号前 `releaseStreams()`，先 cancel 旧 WebSocket / EventSource
- `connectSse()` 建新流前先 cancel 旧流，握手失败时立刻清理自己
- `ApiClient.close()` 释放其 `CbdpClient`；路由切换、重新配对、解绑时都会调用
- 通知服务失败重建 client 时先 `close()` 旧实例（原先只是 `cachedClient = null`，直接漏流）

### 3. 固定间隔重连 → 指数退避 + 抖动 + 429 支持

`CbdpClient` 原为固定 `1.2s` 重连，且每次失败回调各自起协程。

现改为：

- 单飞重连（`reconnectJob` 去重），一次故障只排一次重连
- 指数退避 1s→2s→4s→8s→16s→30s，±20% 抖动
- 收到 HTTP 429 时读取 `Retry-After` 并优先遵守（缺省 20s，上限 5min）

### 4. 轮询周期说明（文档数据有误）

交接文档引用的 `2000ms / 4000ms / 5000ms` 与当前代码不符：
`THREADS_REFRESH_MS`、`THREAD_POLL_MS`、`POLL_MS` 实际均为 **30s**，
且线程列表轮询仅在列表页运行、进入聊天后停止。本次未再压缩周期。

## 验证

- 单元测试通过；`lintDebug` 为 0 errors
- 建议实机验证：断开网络 30 秒再恢复，重连次数应随退避递减；
  PC 端该设备连接数应稳定在个位数
