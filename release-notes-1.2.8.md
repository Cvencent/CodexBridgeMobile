# CodexBridgeMobile 1.2.8

## 修复：长时间挂后台后「未命名任务」和「一直连接超时」

挂后台 10~12 小时再回到前台的两个症状都已定位，一个是手机端、一个是 Bridge 侧。

### 1. 连接假死（手机端，已修）

Doze/NAT 会在睡眠中丢弃 TCP 连接，但进程被冻结时**没有任何 socket 回调**，
`CbdpSession.state` 仍然是 `READY`。`CbdpClient.connect()` 开头的
`if (state == READY) return` 于是直接短路，所有命令都写进死连接直到超时——这就是
“发任何消息都是连接超时，必须杀掉 App 重开”。

修复：

- 新增 `CbdpClient.reconnect()`：显式释放旧 socket / EventSource 并重新拨号
- `ApiClient.reconnectCbdp()`
- `AppViewModel.setForeground(true)` 检测到后台超过 **2 分钟**时，
  先强制重连，再刷新列表

### 2. 「未命名任务」刷不掉（一半是 Bridge 侧）

Bridge 的 `list_threads()` 支持 `refresh`，但 CBDP 命令白名单里**没有放行**：

```python
'threads.list': ('tasks', (), ('limit', 'search', 'cursor', 'project_id'))  # 缺 refresh
```

而 `read_cache` 对终态线程的 TTL 是 **30 秒**，所以占位标题一旦进缓存，
手机在 30 秒内怎么刷都是同一份——正是“点刷新好像没真正刷新”。

手机端已做临时绕过：识别占位标题（空 / `未命名任务` / `新任务`）后，
按 `1s / 5s / 10s / 20s` 自动重试（合计约 36 秒，跨过缓存窗口），
不需要用户手动刷新。

Bridge 侧建议修复见交接文档：
`docs/codexbridge-threads-refresh-and-stale-socket-2026-10-25.md`

## 验证

- 单元测试通过；`lintDebug` 为 0 errors
- 建议实测：锁屏/切后台 10 分钟以上，回到 App 直接发消息应成功；
  若列表仍是占位标题，应自动在 ~36 秒内恢复
