# CodexBridge 手机回复不可见 + 回复中途停止 — 修复请求

日期：2026-10-21
适用版本：CodexBridge 2.1.16（本机实测）
来源：CodexBridgeMobile 手机端实测
严重程度：高（核心功能受损）

---

## 一、用户看到的现象

1. 手机上回复一个桌面端正在使用的会话，Codex 的回复流式输出了一会儿就停止了。
2. 电脑端 Codex 完全看不到手机输入的内容。
3. 重启桌面 Codex 后能看到，但用户不想每次重启。

实际现场（`01a15341` 检查表情渲染器预览状态）：

- 用户在手机上发了 3 条"他说了"（因为没反应重试了 3 次）。
- 3 条消息都进了线程 rollouts（thread.read 能读到）。
- turn 卡死在 working，无 agent 回复。
- `turn/interrupt` 返回 "no active turn to interrupt"（turn 已经死了）。
- 桌面端 Codex 完全看不到这 3 条消息。

---

## 二、根因（已定位到具体行）

`codexbridge/companion/service.py` 的 `_desktop_owner()` 方法开头：

```python
def _desktop_owner(self, thread_id: str) -> str | None:
    from codexbridge.codex.desktop_ipc import DesktopIpcClient, DesktopIpcError
    if self._stop.is_set():
        raise DesktopIpcError('Companion stopped')
    # Custom app-server factories are isolated from the user's live desktop.
    if self._desktop is None and self._client_factory is not None:
        return None                          # ← 生产环境永远在这里返回 None
    ...
```

而 `codexbridge/__main__.py` 构造 `CompanionService` 时传了：

```python
companion_service = CompanionService(
    ...,
    client_factory=lambda command, cwd: shared_app_server,   # ← 恒为非 None
    ...,
)
```

所以 `_desktop_owner()` **在生产环境永远返回 `None`**，永远不会执行
DesktopIPC owner discovery。

### 这导致了什么

手机 turn 调用 `start_turn` → `_desktop_owner()` 返回 `None` →
落到 `_writable_thread(thread_id)` → Bridge 清掉桌面的 writer lock、
把线程 resume 到 **Bridge 自己的 app-server 进程**上执行 turn。

这产生了两个问题：

### 问题 1：桌面看不到手机消息

桌面 Codex app 有自己的 app-server 进程；Bridge 有另一个 app-server 进程。
手机 turn 写入了同一个 rollout 文件，但桌面 Codex UI 不会自动重读——
只有重启桌面 Codex（或重新打开会话触发 re-hydrate）才看得到。

### 问题 2：回复中途停止

桌面 Codex app 也在同一个线程上持有 writer lock 和内存状态。
两个 app-server 进程同时操作一个线程会产生竞争：

- Bridge 的 `_writable_thread` 清了桌面的 writer lock 后 resume 线程
- 桌面自己的 app-server 感知到后重新拿锁或写状态
- Bridge 的 turn 被弄死，手机端看到流式中断

空闲会话没有这个问题（实测能正常完成），**活跃会话必现**。

---

## 三、修复方向

让 `_desktop_owner()` 在生产环境执行 DesktopIPC owner discovery。
短路只应该针对测试隔离场景。

### 方案 A（推荐）：加一个 `isolated` 构造参数

```python
class CompanionService:
    def __init__(self, ..., client_factory=None, isolated=False, ...):
        self._client_factory = client_factory
        self._isolated = isolated       # ← 新增
```

`_desktop_owner()` 改为：

```python
if self._desktop is None and self._isolated:
    return None
```

`__main__.py` 的生产构造**不传** `isolated`（默认 False），
测试代码传 `isolated=True`。

这样生产环境 `_desktop_owner()` 会正常做 DesktopIPC owner discovery，
手机 turn 通过 `thread-follower-start-turn` 走桌面 Codex：

- 桌面实时显示手机消息（是桌面自己的 turn）
- 只有一个 writer，不再竞争
- 手机流式输出来自桌面 turn，不会中途停止

### 方案 B（不推荐但可行）

`update_thread_settings` 已经用了 `_desktop_owner()` 并在 2.1.15+
修复了（瑞杰改过），说明 DesktopIPC 在生产环境是可以工作的。
`_desktop_owner()` 的短路守卫是历史遗留，直接删掉也可能可行——
但需要确认没有测试依赖这个短路行为。

---

## 四、修复后的效果

1. 手机回复桌面持有的会话 → turn 走桌面 Codex → 桌面实时看到。
2. 手机回复手机新建的会话 → turn 走 Bridge 自己的 app-server（和现在一样）。
3. 不再有双 app-server 竞争，不再中途停止。
4. 不再需要重启桌面 Codex 才能看到手机消息。

---

## 五、修复后 Mobile 需要做的

无。手机端已经正确发送消息到 Bridge，不关心 Bridge 内部路由到哪个
app-server。

---

## 六、验证方法

1. 在桌面 Codex 打开一个会话（保持活跃）。
2. 手机上回复同一条会话。
3. 桌面 Codex 应该实时显示手机输入的内容和 Codex 的回复。
4. 手机的流式输出不应该中途停止。
5. `thread.read` 的消息列表应该和桌面显示一致。

---

## 七、用户当前的临时解决办法

手机输入的内容**没有丢**，已经在 rollout 文件里。在桌面 Codex app 里
切到另一个会话，再切回来——Codex 重新打开会话时会从磁盘 re-hydrate，
手机消息就会显示。不需要重启整个 Codex。

卡死的 working 状态会在 rollout 活动停止约 150 秒后自动清为 idle
（`LOG_ACTIVE_SECONDS`），或者用户在手机上点中断也可以。

---

## 附加：队列卡住问题

手机消息有时卡在"待发送队列"里不发。不是必现的——取决于该会话在桌面上
是否同时活跃。

### 根因

和上面的可见性问题**同一个根因**。队列派发调用 `start_turn` →
`_desktop_owner()` 返回 `None` → `_writable_thread` 和桌面争 writer
lock → turn 死了 → Bridge 的 `_active_turn_id(thread_id)` 残留（turn 没有正常
完成所以没被清理）→ 队列的 `_is_active()` 看到线程"活跃"就永远不派发。

`on_turn_finished(thread_id)` 也不会被调用（turn 没有正常完成），
所以 `_paused` 和 stale active turn 状态永远不会被清除。

上面的 `_desktop_owner()` 修复（方案 A，`isolated` 参数）同时解决这个
问题：手机 turn 走桌面 Codex，只有单一 writer，不存在竞争。

---

## 2.1.25 复测结果

你从 master 5183afe 重建的 2.1.25 已部署并验证。

### 已修复 ✅

- `_desktop_owner()` 的 `isolated` 参数修复**已生效**。验证：`update_thread_settings` 现在能路由到桌面（错误从 `codex_unavailable` 变成了 `desktop_unavailable`，说明成功找到了桌面 owner）。
- 手机回复正常完成（发到 idle 线程，turn 正常跑完）。
- 手机回复不再卡队列。

### 仍需修复 ❌：sandbox_policy 在桌面 IPC 路径上格式错误

`_sandbox_policy_object()` 确实存在（我之前搜索 .exe 的方法有误，PyInstaller 压缩了字节码），但你为桌面 IPC 写的转换函数 `_sandbox_policy_wire_name()` 返回了**错误的格式**。

`codexbridge/codex/desktop_ipc.py` 第 21–28 行：

```python
_SANDBOX_POLICY_NAMES = {
    "danger-full-access": "danger-full-access",   # ← 返回字符串
    ...
}

def _sandbox_policy_wire_name(value: Any) -> str:
    """Desktop IPC expects the dotted string name for sandbox policies."""
    ...
    return _SANDBOX_POLICY_NAMES[name]             # ← 返回 "danger-full-access" 字符串
```

然后 `update_thread_settings` 里：

```python
settings['sandboxPolicy'] = _sandbox_policy_wire_name(sandbox_policy)  # ← 发的是字符串
```

但桌面 Codex 的 `SandboxPolicyDeserialize` 期望的是**内部标记枚举对象**：

```json
{"type": "dangerFullAccess"}
```

不是字符串 `"danger-full-access"`。

### 修复

把 `_sandbox_policy_wire_name()` 改为返回对象：

```python
_SANDBOX_POLICY_NAMES = {
    "read-only": "readOnly",
    "readOnly": "readOnly",
    "workspace-write": "workspaceWrite",
    "workspaceWrite": "workspaceWrite",
    "danger-full-access": "dangerFullAccess",
    "dangerFullAccess": "dangerFullAccess",
}

def _sandbox_policy_wire_name(value: Any) -> dict:
    """Desktop IPC expects the internally tagged enum object."""

    if isinstance(value, dict):
        if str(value.get("type") or ""):
            return dict(value)
        raise DesktopIpcError("sandbox_policy")
    name = _SANDBOX_POLICY_NAMES.get(str(value or ""))
    if name is None:
        raise DesktopIpcError(f"Unsupported sandbox policy: {name}")
    return {"type": name}
```

### 2.1.25 验证明细

```text
✅ 配对正常
✅ threads.list 正常
❌ thread.settings.update (sandbox_policy="danger-full-access") → 503 desktop_unavailable
     错误: Invalid request: invalid type: string "danger-full-access",
           expected internally tagged enum SandboxPolicyDeserialize
✅ 手机回复发到 idle 线程 → 200，turn 正常完成
✅ 回复不再卡队列
```

### 之前文档中的更正

我之前说 ".exe 里搜不到 _sandbox_policy_object 字符串" —— 这个判断方法有误。
PyInstaller 会把 Python 模块压缩进 PYZ.pyz，原始字节搜索找不到是正常的。
你的解包检查确认了函数确实存在。问题不在于函数缺失，而在于
`_sandbox_policy_wire_name()` 的返回值格式不对。
