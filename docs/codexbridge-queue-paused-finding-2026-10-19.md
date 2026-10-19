# Codex Bridge queue `paused` 生命周期问题（Mobile 只读排查）

日期：2026-10-19
发现方：CodexBridgeMobile
Bridge 版本：2.1.2（本机运行版）

## 现象

线程处于空闲状态（没有运行中的 turn），手机端发送消息后，Bridge 只返回
`queue.enqueue` 成功，消息一直停在 `pending`，不会派发成真实 turn。

用户侧表现：消息永远留在“待发送队列”里，看起来像发送失败。

## 根因（Bridge 侧，Mobile 未修改 Bridge）

`codexbridge/companion/queue.py` 的队列在派发失败时会把线程加入
`_paused`：

```python
except Exception as error:
    item["status"] = "failed"
    item["error"] = str(error)[:500]
    self._paused.add(thread_id)
    self._emit("queue_paused", thread_id, item)
```

而 `paused` 只在 `edit()` 中被清除：

```python
self._paused.discard(thread_id)
```

`delete()` 不会清 `paused`，后续 `enqueue()` 也不会清 `paused`，
线程 turn 完成时的 `on_turn_finished()` 同样不会清。结果是：

1. 任意一次派发失败（例如 thread/resume 报错）都会把该线程队列永久暂停。
2. 用户删掉失败项、或线程空闲后重新发消息，`_ready_thread()` 仍然
   `thread_id in self._paused`，于是新消息继续排队不派发。
3. 暂停状态没有通过协议暴露给设备用于恢复；`queue_paused` 事件只带一个
   失败项，桥接器没有“unpause”命令。

## 复现（2026-10-19，对运行中的 Bridge 只读/正常接口调用）

1. `queue.enqueue` 到不存在的线程 → 返回 `pending`。
2. 约 1 秒后 `queue.list` → `status=failed`，`paused=true`，错误为
   `thread/resume: invalid session id`。
3. `queue.delete` 删除该项 → 返回 `paused=true`，说明暂停未被清除。
4. 再 `queue.enqueue` 新消息 → 仍是 `pending`，不会派发。
5. `queue.edit` 同一条目 → `paused` 清除，恢复派发。

复现用的测试线程和条目已清理，`queues.json` 恢复为空。

## 建议 Bridge 修复方向

1. **`delete()` 清除该线程的 `paused`**，或至少在没有剩余失败项时清除；
2. **`enqueue()` 对已暂停线程自动清除 `paused` 并唤醒派发**，让用户重发即可恢复；
3. 增加显式 `queue.resume` / `queue.retry` 命令，并把 `paused` 状态作为
   可恢复状态暴露给设备；
4. 派发失败时区分“可重试/不可重试”，不可重试的才暂停，并可设置重试上限。

## Mobile 当前规避

Mobile 已显示 `paused` 与失败原因，并提供“重新派发”按钮；其实现等价于
调用 Bridge 唯一能清除暂停状态的 `queue.edit`，不修改 Bridge。
