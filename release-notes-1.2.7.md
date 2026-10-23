# CodexBridgeMobile 1.2.7

## 继续修复：打开 App 就拖慢 PC（长连接仍在增长）

1.2.6 修掉了「建新流前不释放旧流」和每实例 OkHttpClient，但实测仍有残留放大路径，1.2.7 补完。

### 1. `retransmitLoop()` 每次重连都新起一个永不取消的协程

`CbdpClient.connect()` 每成功一次就调用 `retransmitLoop()`，而它内部
`scope.launch { while (!closed) { delay(500) ... } }` **没有保存句柄**，
所以每次重连都会多一条永久 500ms 定时循环。现在改为持有 `retransmitJob`，
重连前先 cancel，`close()` 时一并取消。

### 2. CBDP 客户端改为进程级共享（保证全应用只有一条流）

此前前台 ViewModel 和完成通知服务各自 `obtain` 一个 `CbdpClient`，
同一条 Bridge 上至少两条事件流，重连时还会短暂叠加。

现在 `CbdpClient.obtain()` 按 `(baseUrl, deviceId, token)` 做进程级单例并引用计数：

- 两边拿到的是**同一个**客户端、同一条 SSE/WS
- 只有最后一个持有者 `release()` 时才真正关闭连接
- 从结构上保证「同一设备对 `/cbdp/v1/events` 的活动连接数 = 1」

### 3. `AppViewModel` 缺少 `onCleared()`

界面销毁后 `api` 不会被释放，进程存活期间旧引用一直挂着。已补 `onCleared()`：
取消全部轮询/流任务并 `api.close()`。

### 4. 1.2.6 已完成的部分（本次保留）

- `SharedHttp`：全应用共享连接池与 Dispatcher，不再每实例新建 OkHttpClient
- 拨号前 `releaseStreams()`，`connectSse()` 建流前先 cancel 旧流，握手失败自清理
- 重连单飞去重 + 指数退避 1s→30s（±20% 抖动）+ 遵循 429 `Retry-After`
- 路由切换 / 重新配对 / 解绑 / 通知服务重建时都会 close 旧 client

## 验证

- 单元测试通过；`lintDebug` 为 0 errors
- 建议验证：装好后打开 App，PC 上执行
  `Get-NetTCPConnection -OwningProcess <BridgePID> -State Established | Group-Object RemoteAddress`
  来自手机的连接应稳定在个位数，线程数不随时间增长
