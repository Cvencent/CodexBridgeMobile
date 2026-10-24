# Bridge 交接：`threads.list` 无法强制刷新 + 长后台后手机连不上

- 日期：2026-10-25
- 影响：手机端 CodexBridgeMobile 1.2.7 及以前
- 结论：两个症状里，**「未命名任务刷不掉」有一半是 Bridge 侧**，
  **「发消息一直连接超时」是手机端**（1.2.8 已修）。

---

## 症状

用户手机挂后台 10~12 小時后回到前台，两种表现：

1. 列表变成「未命名任务」，手动点刷新也拿不到真实标题，要过一阵再刷才行。
2. 能看到真实任务，但点刷新没有效果，发任何消息都提示“连接超时”，
   必须杀掉 App 重开才恢复；重开后又会先出现「未命名任务」。

---

## Bridge 侧问题：CBDP `threads.list` 缺少 `refresh` 参数

`codexbridge/companion/service.py:2308`：

```python
def list_threads(self, limit=40, search="", cursor=None,
                 refresh: bool = False, project_id="") -> dict:
```

**服务层是支持 `refresh=True` 的**，但 CBDP 命令参数白名单没有放行它：

`codexbridge/protocol/commands.py:16`

```python
'threads.list': ('tasks', (), ('limit', 'search', 'cursor', 'project_id')),
#                                            ^ 缺 refresh
```

后果：

- CBDP 客户端无法要求绕过读缓存，只能拿到 `ReadCache` 里的旧页。
- `read_cache.py` 的 TTL 是 **2 秒**，但**终态线程 30 秒**（`TERMINAL_READ_TTL_SECONDS = 30.0`）。
- Bridge/app-server 在长时间空闲或重启后，`thread/list` 会先返回占位标题
  （`未命名任务` / `新任务`）。这个占位页一旦进缓存且被判为终态，
  **接下来 30 秒内手机怎么刷都是同一份**——正是用户说的“点刷新好像没真正刷新”。

### 建议修复

1. 在 `commands.py` 的 `threads.list` 可选参数里加上 `'refresh'`：
   ```python
   'threads.list': ('tasks', (), ('limit', 'search', 'cursor', 'project_id', 'refresh')),
   ```
   并在 CBDP gateway 把布尔值透传给 `service.list_threads()`。
2. 更彻底一点：`list_threads` 检测到整页标题都是占位值
   （空 / `未命名任务` / `新任务`）时，**不要写入读缓存**，
   或强制 `refresh=True` 重读一次，避免占位页被固化 30 秒。

### 手机端已做的临时缓解（1.2.8）

- 识别占位标题后，按 `1s / 5s / 10s / 20s`（合计约 36 秒）自动重试，
  足以跨过 30 秒缓存窗口，用户不需要手动刷新。
- 这只是绕过；Bridge 修好后可以把这段重试降频。

---

## 手机端问题：长后台后连接假死（1.2.8 已修）

- 手机挂后台后，Doze/NAT 会丢弃 TCP 连接，但进程被冻结，
  **没有任何 socket 回调**，`CbdpSession.state` 仍然是 `READY`。
- `CbdpClient.connect()` 开头有 `if (state == READY ...) return`，
  于是直接短路，所有命令写进死连接，直到超时 → 用户看到“连接超时”。
- 只有杀进程重开（新 socket）才能恢复，与用户描述完全一致。

修复：新增 `CbdpClient.reconnect()`，并在 `AppViewModel.setForeground(true)`
检测到后台超过 2 分钟时，**先强制重连再刷新列表**。

---

## 复测建议

1. 手机锁屏/切后台 10 分钟以上，回到 App：
   - 应能直接发消息成功（不再需要重启 App）
   - 列表若仍是占位标题，应自动在 ~36 秒内恢复，不需要手动刷新
2. Bridge 修好 `refresh` 后，验证方式：
   ```bash
   # 强制刷新应绕过缓存
   POST /api/companion/threads?refresh=1
   ```
   连续两次调用之间不应返回同一份占位页。
