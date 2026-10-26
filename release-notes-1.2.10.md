# CodexBridgeMobile 1.2.10

## 修复：任务完成没有通知（后台服务其实收不到完成事件）

### 根因一：完成事件发在“线程主题”，服务只订阅了“系统摘要主题”

Bridge 的 `_append_turn_terminal_event()` 把 `turn_completed` 发到
`_cbdp_topic(thread_id)`（每条线程自己的主题）：

```python
self._append_event(self._cbdp_topic(record.thread_id), event_type, ...)
```

而手机的通知服务调用 `client.events()`（不带 threadId），只订阅了
`SYSTEM_THREAD_SUMMARIES`。所以后台服务**从来没收到过 `turn_completed`**，
只能靠 30 秒一次的轮询兜底。

### 根因二：轮询只认 `status == completed`，可完成态很快就变 `idle`

任务完成后，只要有任何设备读过它，Bridge 就把线程标成 `idle`。
轮询跑起来时经常已经是 `idle`，于是 `status == "completed"` 判断失败，
**通知被永久漏掉**。

### 根因三：CBDP 时间戳是毫秒，其余代码按秒比较

`TAG_COMPLETED_AT_MS` 由 Bridge 以毫秒下发，而手机端的水位线、`completed_at`
全按秒存储，导致去重与比较全部错位。

## 修复

1. **轮询改为按完成时间判定**，不再依赖状态：
   `notificationCompletedAt(thread)` 在 `completed_at` 有效且线程不在运行时返回
   完成时间，因此即使已是 `idle` 也能补发通知。
2. **毫秒统一归一化为秒**（`> 100000000000` 视为毫秒）。
3. **终端摘要事件触发即时检查**：收到 `thread_status = completed/failed` 时立刻
   读一次列表并判断，不用等 30 秒轮询。
4. **修正 `listenEvents` 的退避计数器**：原来 `attempt` 写在循环内，失败时永远
   2 秒重连；已移到循环外，指数退避生效。

## 验证

- 新增 5 个 `notificationCompletedAt` 单元测试（idle 仍上报 / completed+failed /
  运行中不上报 / 毫秒归一化 / 无完成时间不上报）
- 全量单元测试通过；`lintDebug` 为 0 errors
