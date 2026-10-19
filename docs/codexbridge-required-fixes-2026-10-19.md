# CodexBridge 需要修复的问题清单（2026-10-19）

来源：CodexBridgeMobile 1.0.6 实测。
下面只列 Bridge 需要改的部分；Mobile 已做兼容兜底，但 Bridge 是状态权威，
最终还是要由 Bridge 修复数据语义。

## P0：终态必须有有效 `completed_at`

### 现象

Bridge 会把线程列表和详情里的终态返回成：

```json
{
  "status": "completed",
  "completed_at": 0
}
```

也会返回：

```json
{
  "status": "failed",
  "completed_at": 0,
  "error_message": ""
}
```

实测样例：

```json
{
  "thread_id": "01a15341-4afd-7f01-9a56-380ddc70bac9",
  "title": "检查表情渲染器预览状态",
  "status": "failed",
  "completed_at": 0,
  "updated_at": 1792403089,
  "recency_at": 1792399737,
  "turn_id": "01a15359-8271-73a3-94f9-08a92adbca40"
}
```

当时用户确认任务实际仍在运行；Bridge 的 task 里也没有
`error_message`，但状态已经是 `failed`。

### 影响

1. `completed` 且 `completed_at = 0` 的会话无法可靠标记已读。
   `mark_thread_read()` 现在只有 `completed_at > 0` 才写已读状态，所以手机
   点开后，Bridge 下一次列表刷新又会把它刷回 `completed`。
2. `failed` 且 `completed_at = 0` 无法区分真实失败和网络/模型重连造成的
   瞬态失败。手机端会把这种数据显示成失败，但任务实际还在运行。
3. 手机、桌面、其他设备会对同一个会话显示不同状态。

### Bridge 修复要求

1. 所有 `completed` / `failed` 终态必须携带有效的 `completed_at`。
2. 如果 Codex 上游没有给最终时间戳，Bridge 应使用 rollout 日志里的结束时间、
   最后更新时间或本机观测时间补齐。
3. 不能因为缺少最终时间就把任务推断成 `completed`。
4. 终态时间戳必须单调，不能比上一次终态更旧。
5. `thread/list`、`thread/read`、`thread_status`、`turn_completed` 里的状态
   与 `completed_at` 必须一致。

## P1：网络/模型重连不能落成终态失败

### 现象

模型网络重连一次时，Codex 任务实际仍在运行，但 Bridge 会把 thread 状态暴露成
`failed`，且 `completed_at = 0`、`error_message = ""`。手机端只能靠“没有
完成时间”来怀疑这不是真实失败。

### Bridge 修复要求

1. 网络重连、模型供应商限流重试、客户端流重连这类瞬态状态不要映射为
   `turn_failed`。
2. 这些情况下 Bridge 应继续暴露 `working` / `running`，也可以新增明确的
   `reconnecting` 状态，但不要占用 `failed`。
3. 只有任务真正结束且能拿到终态时间戳时，才发布：

```json
{
  "type": "turn_completed",
  "status": "failed",
  "completed_at": 1792403089
}
```

4. 如果失败后任务被系统自动恢复，Bridge 需要发布新的 active/running 状态，
   并撤回或覆盖之前的 failed 展示。

## P1：`completed_at = 0` 时已读状态无法持久化

`CompanionService.mark_thread_read()` 当前逻辑等价于：

```python
if status in {"completed", "failed"} and completed_at > 0:
    self._read_state.mark_thread(thread_id, completed_at)
```

所以 `completed_at = 0` 的已完成线程永远不会进入已读存储。

### Bridge 修复要求

1. 先按 P0 补齐有效 `completed_at`。
2. 或者允许显式线程已读接口在没有终态时间戳时也写一个可靠已读水位。
3. 已读状态要按线程持久化，并且下次 `threads.list` 仍然生效。

## 建议的验收标准

1. 制造一个 `status = failed` 且 `completed_at = 0` 的线程；修复后 Bridge
   要么返回有效 `completed_at`，要么保持 `working`。
2. 制造一个 `status = completed` 且 `completed_at = 0` 的线程；修复后 Bridge
   要么返回有效 `completed_at`，要么保证 `/read` 后状态变为 `idle`。
3. 手机打开任何 `completed` 会话后，返回列表，列表必须变成 `idle`，再次刷新
   不回退。
4. 模型网络重连期间，手机列表和详情必须保持运行中，不出现 `failed`。
5. 同一会话在 `threads.list` 与 `thread.read` 中返回一致的
   `status` / `completed_at` / `turn_id`。

## 相关版本

- Mobile：1.0.6 已增加客户端兜底：
  - `failed + completed_at = 0` 显示为运行中；
  - `completed + completed_at = 0` 点开后本地标记 idle，避免刷新回退。
- Bridge：不要依赖这些 Mobile 兜底，仍应修复权威状态。

## 已确认不需要重复修

- 队列 `paused` 卡住：Bridge v2.1.3 已修复。
- `message.read.update` / thread.read 的部分协议问题：Bridge v2.1.4 已处理。
