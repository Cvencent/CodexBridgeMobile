# Bridge 交接：手机新建会话必失败（desktop_handoff_pending）— 2026-10-22

## 结论（TL;DR）

手机端"新建会话"在 CodexBridge **2.1.21 起必失败**，返回：

```
POST /api/companion/threads
503 {"ok": false, "error": "desktop_unavailable", "message": "desktop_handoff_pending"}
```

根因是 Bridge 端 `start_thread()` 的回归，**手机端无任何问题**。

## 复现（实测，2026-10-22，Bridge 2.1.28，端口 8568）

用探针手机配对后直接调 companion API：

```
thread/start        -> 503 {"error": "desktop_unavailable", "message": "desktop_handoff_pending"}   (约 11 秒后)
thread/start-nomodel-> 503 {"error": "desktop_unavailable", "message": "desktop_handoff_pending"}
```

- 模型带不带都一样，与 cwd 无关（传的是 `D:\CodexBridgeMobile`）。
- 两次调用共耗时约 21 秒，每次都是先成功创建、再等桌面 10 秒超时。
- 探针手机已撤销（remove_phone），不留垃圾配对。

## 根因链（代码级）

位置：`codexbridge/companion/service.py`

1. `start_thread()` 里 `use_desktop_handoff = self._creation_client_factory is not None and not ephemeral`。
   生产环境 `__main__.py` 注入了 `client_factory=lambda command, cwd: shared_app_server`，
   所以 `_creation_client_factory = None`，生产**永远走 else 分支**：
   `self._ensure_client().request("thread/start", params)`。

2. **else 分支没有调用 `thread/name/set`**。命名落盘（提交 rollout 文件）只在 handoff 分支里做：
   ```python
   if thread_id:
       creator.request('thread/name/set', {'threadId': thread_id, 'name': _BRIDGE_PENDING_TITLE})
   ```
   而生产走不到这个分支。代码注释自己也写了："An empty thread/start row has no rollout file"、
   "it has no rollout file until its first turn runs"。

3. 8f31940（2.1.21，10-21 00:47）把 `poll_thread_changes()` 的 desktop discovery 从
   `if self._client_factory is None` 改成 `if not self._isolated`，生产开始做 discovery，
   `self._desktop` 在启动几秒内就被 `_desktop_owner()` 赋值为 `DesktopIpcClient`。

4. 于是 `start_thread()` 的桌面 handoff 检查
   `if self._desktop is not None or self._client_factory is None:` 在生产**恒成立**：
   - `open_thread(thread_id)` 发 codex:// deep link（不报错，OS 接受了）
   - 轮询 `_desktop_owner(thread_id)` 40 次 x 0.25s = 10 秒

5. 桌面**无法 hydrate 没有 rollout 文件的线程** → `thread-owner-discovery` 恒返回 None
   （实测：用独立 `DesktopIpcClient` 查已知线程 owner 正常返回，查陌生 UUID 返回 None）。
   deep link 打开也显示 Not Found（用户实测"点它就 Not Found"）。

6. 10 秒超时 → `raise DesktopIpcError('desktop_handoff_pending')`
   → `api.py:615` 捕获 → 503 `desktop_unavailable`。

7. 线程此时已经创建成功（app-server 侧），但没有 rollout 文件 →
   `thread/list` 看不到（实测探针创建的两个线程完全不在列表里）→
   手机报错、电脑不可见、deep link Not Found，变成僵尸线程。

### 为什么 ≤2.1.20 是好的

2.1.20 及以前 `poll_thread_changes()` 在生产不做 discovery（`_client_factory is None` 才做），
`self._desktop` 恒为 None → `start_thread()` 直接跳过整个桌面 handoff 检查 → 新建成功。
8f31940 打开了 discovery 后，这个恒失败的 handoff 检查才暴露出来。

## 建议修复（两处都要）

### 1. else 分支补命名（治本）

`start_thread()` 的 else 分支创建成功后立即命名，让 rollout 文件落盘，
桌面才能 hydrate、认领：

```python
else:
    result = self._ensure_client().request("thread/start", params)
    thread_id = str(((result.get("thread") or {}).get("id")) or "") if isinstance(result, dict) else ""
    if thread_id:
        self._ensure_client().request('thread/name/set',
                                      {'threadId': thread_id, 'name': _BRIDGE_PENDING_TITLE})
```

### 2. owner 超时不要 hard fail（兜底）

线程已经创建成功，把整个请求判 503 会把它变成用户看不见的僵尸线程。
超时后应返回已创建的线程（可加 `"desktop_handoff": "pending"` 警告字段），
第一轮 turn 走 app-server 后 rollout 自然生成。

两处都修的话，1 保证桌面能正常接管（deep link 能打开、电脑能看到），
2 保证即使桌面慢/离线，手机端新建也不再报错。

## 复测方法

探针脚本（改 host/port 即可）：

```python
# 1. POST http://127.0.0.1:8788/api/admin/companion {"action":"new_code"} 拿配对码
# 2. POST http://127.0.0.1:8568/api/companion/pair {"code": ...} 拿 token
# 3. POST http://127.0.0.1:8568/api/companion/threads
#    Authorization: Bearer <token>   body: {"cwd": "D:\\某真实目录", "model": "gpt-5.1-codex"}
# 4. 期望 200 且线程出现在 GET /api/companion/threads 列表里
# 5. 清理：POST /api/admin/companion {"action":"remove_phone","phone_id":...}
```

复测还要验证：手机新建后桌面 deep link 能打开该线程（不再 Not Found），
电脑 Codex 列表能看到该新任务。

## 本次实测产生的副作用

- 探针在 app-server 创建了 2 个无 rollout 的僵尸线程（任何列表都不可见，无法清理，
  随修复 1+2 上线后同类僵尸不会再产生）。
- 探针手机 phone-185a6a411452 已通过 remove_phone 撤销。

