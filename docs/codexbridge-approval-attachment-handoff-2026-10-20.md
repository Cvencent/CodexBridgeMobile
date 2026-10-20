# CodexBridge 审批与附件能力交接

日期：2026-10-20

范围：CodexBridge Mobile 侧排查结果，以及需要 Bridge 侧实现或修复的接口。
本文件只描述 Bridge 需要处理的内容；本次没有修改 `D:\CodexBridge` 的代码。

## 0. 结论

截图中的卡片是 Codex 在调用 `codegraph_context` 前发起的
**MCP elicitation（工具请求确认）**，不是普通的 Codex 错误。

手机端当前有两个独立问题：

1. **提交审批会报 `Not Found`**：Companion 的
   `POST /api/companion/approvals/<request_id>` 路由长度判断写成了
   `len(parts) == 5`，但实际路径只有 4 段，所以请求在路由层直接落到 404。
2. **即使修掉 404，审批内容仍然不完整**：CBDP 路径发送的是
   `approval_ref`，Bridge 的命令参数白名单只识别 `approval_id`；MCP 的
   `action/content` 结构化响应也没有从手机端传到 Bridge 的命令层。

另外，手机端目前没有“请求批准 / 完全允许”切换，也没有图片、文件附件发送
链路。协议里已经有对应字段，但 Bridge 的共享命令层还没有真正实现这些方法。

## 1. 用户可见问题

### 1.1 MCP 工具确认提交失败

现象：

- 在手机端新建会话并提问。
- Codex 调用 MCP 工具前，手机显示“工具请求确认”。
- 点“提交”提示“请求失败 Not Found”。
- 点卡片或其他入口也可能显示 `Not Found`。

### 1.2 没有审批模式切换

手机端目前只有单条审批卡片上的“允许 / 本会话允许 / 拒绝 / 取消”，
没有类似 Codex 的“请求批准 / 完全允许”会话级切换。

### 1.3 不能发送图片和文件

手机输入区目前只有文字发送框，没有图片选择、文件选择、上传、预览和
附件发送入口。

## 2. 根因与证据

### 2.1 Companion 审批路由 404（直接原因）

Bridge 代码位置：

- `codexbridge/companion/api.py`，`do_POST()` 中审批分支附近。
- 当前判断近似为：

```python
if len(parts) == 5 and parts[:3] == ["api", "companion", "approvals"]:
    request_id = int(parts[4])
```

实际请求：

```text
POST /api/companion/approvals/123
```

经过 `path.strip("/").split("/")` 后：

```python
parts == ["api", "companion", "approvals", "123"]  # 长度 4
```

所以条件永远不成立，最终执行到末尾的：

```python
self._error(404, "not_found")
```

这不是审批已经失效，而是路由没有进入处理方法。

### 2.2 CBDP 审批引用字段不匹配

手机端当前发送：

```text
APPROVAL_RESOLVE
  approval_ref = 0x0027
  decision     = 0x0082
```

Bridge 的 `CbdpGateway.PARAM_TAGS` 只包含：

```python
"approval_id": 0x0028
```

没有 `approval_ref`。因此 `_decode_params()` 解析后拿不到 `approval_id`，
`approval.resolve` 的必填字段校验失败，返回 `INVALID_PARAM`。如果手机端随后
回退到 Companion 路径，就会走到上面的 404。

Bridge 需要在 CBDP 命令层同时接受：

- `approval_ref`（0x0027，u32）
- `approval_id`（0x0028，utf8）

并把 `approval_ref` 通过引用注册表解析为真正的 `approval_id`，再交给共享
命令层。两个选择器同时出现时必须校验一致，不能猜测。

### 2.3 MCP 结构化响应被丢弃

手机端 UI 会为 MCP 构造类似：

```json
{
  "action": "accept",
  "content": {}
}
```

但当前 `ApiClient.respondApproval()` 只从对象中取 `decision` 字段，没有
`decision` 时默认传 `decline`，因此完整的 `action/content` 被丢弃。

Bridge 侧当前共享命令只有：

```python
'approval.resolve': ('approval', ('approval_id', 'decision'), ())
```

`approval.respond` 虽然在 `COMMANDS` 中存在，但 CBDP 的
`METHOD_IDS` 没有把它暴露成可用方法；`APPROVAL_RESOLVE` 的 `response`
字段也没有进入 `PARAM_TAGS`。

结果：

- MCP 的 `accept + content` 无法提交。
- 用户输入的 `answers` 无法提交。
- 权限审批的 `permissions + scope` 无法提交。
- 手机端即使修好提取逻辑，也只能退回 `decision`。

Bridge 需要支持 `APPROVAL_RESOLVE` 的 `response` 子 TLV，并在存在
`response` 时调用 `approval.respond`，否则再走 `approval.resolve`。

建议的 MCP 响应映射：

```json
{
  "action": "accept",
  "content": {}
}
```

Bridge 对桌面持有的线程应继续调用：

```text
thread-follower-submit-mcp-server-elicitation-response
  conversationId
  requestId
  response
```

对用户输入审批应调用：

```text
thread-follower-submit-user-input
  conversationId
  requestId
  response: {"answers": {...}}
```

### 2.4 审批 kind 映射错误或丢失

`codexbridge/cbdp/gateway.py` 的 `_encode_approval_object` 当前近似映射：

```python
{"command": 0, "file": 1, "permissions": 2, "mcp": 3, "input": 4}
```

但 CBDP 协议定义的 `approval_kind` 是：

| 值 | 协议含义 |
|---:|---|
| 0 | command_execution |
| 1 | file_change |
| 2 | network_access |
| 3 | permission |
| 4 | mcp_tool |
| 5 | user_input |
| 6 | other |
| 7 | mcp_elicitation |

当前把 `mcp` 编成 3（permission），把 `input` 编成 4（mcp_tool），语义错误。
手机端会因为错误的 kind 显示错误的表单类型，甚至把普通审批当成权限审批。

Bridge 应按协议值编码，并保留：

- `prompt`
- `approval_detail`
- `options`
- `expires_at_ms`
- MCP 表单内容 / questions
- 用户输入问题列表

如果协议对象暂时不能完整承载 MCP 表单，至少应通过 `mcp_content`（0x0142）
或可解析的 `approval_detail` JSON 传递，不能让手机端只拿到空对象 `{}`。

### 2.5 审批事件没有可靠地走 CBDP

当前手机端主要依赖 `thread.read` / `approvals.list` 轮询拿到审批。CBDP 的
`approval.requested`、`approval.resolved`、`approval.expired` 事件应当作为
线程 scope 的 critical 事件推送，否则：

- 新审批出现时手机要等轮询；
- 审批在其他设备处理后，手机可能继续显示旧卡片；
- 后台通知和实时状态不一致。

Bridge 需要在审批创建、解决、过期时向对应线程发布 CBDP 事件，并保证事件
字段和 `approvals.list` 的对象一致。

### 2.6 能力声明和实际实现不一致

`codexbridge/cbdp/http_transport.py` 的 `PHONE_CAPABILITY_EXPANSION` 已经把
`attachment`、`file_reference`、`image_input`、`thread_settings` 等能力放进
手机能力集合，但共享命令层并没有对应的实际命令。

这会造成危险的行为：手机端看到能力可用，调用后才收到
`UNSUPPORTED_FEATURE` 或 `NOT_FOUND`。

Bridge 在实现前应二选一：

- 真正实现这些命令；或
- 从手机能力集合中移除，直到实现完成。

不要只声明能力而不实现。

## 3. P0：先修复 MCP 审批链路

目标：手机上的“提交”能够把 MCP `action/content` 原样送到 Codex，不再返回
404，也不再被降级为 decline。

### 3.1 Companion 路由

必须支持：

```http
POST /api/companion/approvals/{request_id}
Authorization: Bearer <token>
Content-Type: application/json

{
  "response": {
    "action": "accept",
    "content": {}
  }
}
```

同时保留旧格式：

```json
{"decision": "accept"}
```

处理规则：

1. `parts` 长度应为 4，不是 5。
2. 请求体有 `response` 时调用 `respond_approval()`。
3. 请求体只有 `decision` 时调用 `resolve_approval()`。
4. 审批不存在返回 404 `not_found` 是合理的；路由存在但审批已过期应返回
   明确的 `invalid_state` / `expired`，不要混淆。
5. 成功返回：

```json
{"ok": true, "resolved": true}
```

6. 重复提交同一审批应幂等或返回明确的 `invalid_state`，不能产生第二次
   MCP 响应。

### 3.2 CBDP `APPROVAL_RESOLVE`

手机端期望发送：

```text
approval_ref / approval_id
decision
response (optional nested TLV)
```

Bridge 需要：

1. 在 `PARAM_TAGS` 中增加 `approval_ref: 0x0027`。
2. 在 `_decode_params()` 中把 `approval_ref` 解成 u32。
3. 在 `_normalize_params()` 中把 `approval_ref` 通过引用注册表解析为
   `approval_id`。
4. 同时接受 `response`（0x0083）子 TLV。
5. 存在 `response` 时调用 `approval.respond`；否则调用
   `approval.resolve`。
6. 对 MCP 的 `mcp_action` / `mcp_content` 解码为 JSON 对象。
7. 对用户输入的 `answers` 解码为 answers 对象。
8. 对权限审批的 `permissions` / `scope` 解码为响应对象。

建议的响应结构：

```json
{"action":"accept","content":{}}
```

```json
{"answers":{"question-id":{"answers":["yes"]}}}
```

```json
{"permissions":{},"scope":"turn"}
```

### 3.3 审批对象

`_encode_approval_object` 至少需要输出：

- `approval_ref`
- `approval_id`
- `thread_ref`
- `turn_ref`
- 正确的 `approval_kind`
- `prompt`
- `approval_detail`
- `options`（如果有）
- `expires_at_ms`
- `cwd`（如果有）
- `created_at_ms`（如果有）

对于 MCP elicitation，额外输出表单内容；对于用户输入，额外输出问题列表。
字段名和协议 TLV 见 `docs/CBDP-V1-PROTOCOL.md` 第 20 节。

### 3.4 P0 验收

1. 手机端提交一个 MCP `{}` 表单，Bridge 返回 200，桌面端收到
   `submit-mcp-server-elicitation-response`，`action=accept`、`content={}`。
2. 手机端点拒绝，Bridge 收到 `action=decline`，审批卡片消失。
3. 手机端提交用户输入 answers，问题答案不丢失。
4. CBDP 路径用 `approval_ref` 提交也能命中同一审批。
5. Companion 路径不再因为路由长度返回 404。
6. 审批处理后，其他设备收到 `approval.resolved`，卡片不再可操作。

## 4. P1：审批模式切换

### 4.1 需要的命令

协议已有：

```text
thread.settings.read   0x001A
thread.settings.update 0x001B
```

但当前 CBDP Gateway 的 `METHOD_IDS` 和共享命令层没有真正实现。Bridge 需要：

- `thread.settings.read` 返回当前 `approval_policy`、`sandbox_policy`、
  `approvals_reviewer` 等有效设置。
- `thread.settings.update` 接受并持久化这些字段。
- 对桌面持有的线程和 app-server 自己持有的线程都要有明确实现。
- 更新成功后发布 `thread.settings.changed`。

### 4.2 建议的两种模式

| 手机显示 | approval_policy | sandbox_policy | approvals_reviewer |
|---|---|---|---:|
| 请求批准 | `on-request`（2） | `workspace-write`（1） | `user`（0） |
| 完全允许 | `never`（3） | `danger-full-access`（2） | `user`（0） |

说明：

- “完全允许”只表示不再因命令/文件/网络审批而暂停，不代表 MCP
  elicitation 或用户输入表单会自动消失。
- 仍需要用户填写的 MCP 表单属于工具输入，不应被“完全允许”伪造为空答案。
- 该切换建议按线程保存，而不是全局改写所有会话。
- 在运行中的 turn 上切换时，建议 Bridge 明确返回“下一轮生效”或拒绝，
  不能让用户误以为当前 turn 已改变权限。

### 4.3 桌面线程的实现边界

对桌面持有的线程，现有 `DesktopIpcClient.update_thread_settings()` 只支持
model/effort。Bridge 需要扩展它，或新增等价调用，把：

```text
approvalPolicy
sandboxPolicy
approvalsReviewer
```

写入桌面线程设置。不能只改 Bridge 自己的缓存，否则下一轮仍会按旧策略执行。

### 4.4 验收

1. 空闲线程切到“请求批准”，下一条命令/文件操作出现审批卡片。
2. 切到“完全允许”，下一条命令不再出现普通审批；危险操作仍按 Codex
   自身的安全策略处理。
3. MCP 表单仍然正常出现并可提交，不被“完全允许”吞掉。
4. 更新失败时手机端恢复旧模式并显示原因。
5. 重启 Bridge 后线程模式仍保持。

## 5. P2：图片和文件附件

### 5.1 需要的命令

协议已定义但 Bridge 共享命令层尚未实现：

```text
attachment.upload.begin   0x0063
attachment.upload.chunk   0x0064
attachment.upload.end     0x0065
attachment.upload.cancel  0x0066
attachment.add            0x0060
attachment.list           0x0061
attachment.remove         0x0062
input.capabilities.read   0x00C0
content.read              0x00C1
```

至少需要实现：

- 上传事务：begin -> chunk -> end / cancel
- SHA-256、总大小、分片偏移和 MIME 校验
- 线程附件绑定
- `turn.start` / `turn.steer` / `queue.add` / `queue.update` 接受
  `input_parts`
- `input_parts` 与 `body` 互斥
- 附件事件：`attachment.added/updated/removed/upload.state`

### 5.2 内容存储

建议 Bridge 使用自己的有界内容存储，不直接暴露手机本地路径：

- 每个文件有 `content_ref`
- 保存 `filename`、`mime_type`、`byte_size`、`content_sha256`
- 图片限制在合理大小（建议默认 20 MB，可配置）
- 普通文件限制在合理大小（建议默认 50 MB，可配置）
- 拒绝路径穿越、符号链接逃逸和可执行文件伪装成图片
- 上传失败/取消后清理临时文件，不留下半成品

### 5.3 转成 Codex app-server 输入

Bridge 收到 `input_parts` 后，应构造 Codex app-server 的 `UserInput` 列表：

```json
[
  {"type":"text","text":"请分析这张图"},
  {"type":"localImage","path":"C:\\...\\uploaded-image.png"}
]
```

普通文件建议使用 Codex 的文件引用语义：

```json
{"type":"mention","name":"report.pdf","path":"C:\\...\\report.pdf"}
```

如果当前 app-server 版本不支持 `mention`，Bridge 必须明确返回
`UNSUPPORTED_FEATURE` 或 `INVALID_PARAM`，不能静默丢掉附件。也可以在确认
目标版本支持后使用 `fileId`，但不要把普通文件伪装成图片。

桌面持有的线程也要把非文本输入继续传给
`thread-follower-start-turn` / `thread-follower-steer-turn`，不能只保留
text 部分。

### 5.4 手机端发送结构

建议手机端发送：

```text
input_parts = [
  { part_kind: text, body: "请分析这张图" },
  { part_kind: image, content_ref: "..." },
  { part_kind: file_ref, content_ref: "..." }
]
```

或者在上传完成后直接：

```text
input_parts = [
  { part_kind: text, body: "分析这些文件" },
  { part_kind: image, attachment_ref: ... },
  { part_kind: file_ref, attachment_ref: ... }
]
```

`input_parts` 非空时不得同时发送 `body`。顺序必须保留。

### 5.5 验收

1. 手机选一张图片，上传后发送，Codex 能看到图片。
2. 手机选一个 PDF/文本文件，发送后 Codex 能通过路径读取。
3. 只发附件、不发文字也能成功发送。
4. 两个附件顺序与选择顺序一致。
5. 上传中断后可重试，服务端不会留下孤儿内容。
6. 超过大小限制时返回明确错误，手机保留草稿并允许删除/重试。
7. 不支持的 MIME 类型被明确拒绝，不能伪装成图片。

## 6. 建议实现顺序

1. **P0**：Companion 路由 404 + CBDP `approval_ref` + 结构化 response。
2. **P0**：审批对象 kind 映射和 MCP 表单内容。
3. **P1**：审批事件推送。
4. **P1**：`thread.settings.read/update` 与“请求批准 / 完全允许”。
5. **P2**：附件上传、`input_parts`、图片/文件发送。
6. **P2**：附件事件和内容清理。

## 7. Mobile 侧后续改动（不在本文件实现）

Bridge 完成后，Mobile 需要配套：

- `ApiClient.respondApproval()` 保留完整 `action/content`，不再默认
  `decline`。
- 增加 `CbdpTag.RESPONSE`、`MCP_ACTION`、`MCP_CONTENT`、`ANSWERS` 等编码。
- 审批卡片按真实 kind 显示；MCP 表单有结构化字段时不要再强制用户编辑
  裸 JSON。
- 在聊天页增加线程级“请求批准 / 完全允许”切换。
- 增加图片/文件选择、上传进度、附件 chip、删除和重试。
- 发送时构造 `input_parts`，并在 Bridge 不支持时隐藏入口或显示
  “请先更新电脑端”。
- 对附件和审批模式补充单元测试与端到端验收。

## 8. 相关代码位置

Bridge 侧建议优先检查：

- `codexbridge/companion/api.py`：审批 POST 路由长度判断、response 解析。
- `codexbridge/cbdp/gateway.py`：`METHOD_IDS`、`PARAM_TAGS`、
  `_decode_params`、`_normalize_params`、`_encode_approval_object`。
- `codexbridge/protocol/commands.py`：`COMMANDS`、`approval.resolve`、
  `approval.respond`、`thread.settings.*`、附件命令。
- `codexbridge/companion/service.py`：`APPROVAL_KINDS`、
  `resolve_approval`、`respond_approval`、`start_turn`、`steer_turn`。
- `codexbridge/cbdp/http_transport.py`：手机能力扩展。
- `codexbridge/codex/desktop_ipc.py`：桌面线程设置、审批响应、turn 输入。
- `docs/CBDP-V1-PROTOCOL.md`：第 17、20、26、27 节。

## 9. 待确认的产品决策

1. “完全允许”是仅当前线程，还是同时提供全局默认？建议先做线程级。
2. 附件第一版是否必须同时支持图片和任意文件？建议两者一起做，但分阶段验收。
3. 单个附件大小上限；建议图片 20 MB、普通文件 50 MB，均可配置。
4. 普通文件用 `mention` 路径引用，还是等待 app-server 提供通用 file 输入？
   建议先用 `mention`，并在不支持时明确失败。
5. 运行中的 turn 是否允许切换审批模式？建议只允许空闲时切换，或明确
   标注“下一轮生效”。

## 10. 本机实测结果（2026-10-20，Bridge 2.1.11）

用临时配对身份直连本机 Bridge（8568）做协议级验证，结果如下。

### 已修复并验证通过

- `POST /api/companion/approvals/<request_id>` 路由已改为 4 段判断，不再返回
  404。传入不存在的审批 id 会返回 `200 {"ok":true,"resolved":false}`，
  说明请求确实进入了处理器。
- 命令目录 `GET /api/companion/protocol` 已暴露
  `thread.settings.read`、`thread.settings.update`、`approval.respond`、
  `approval.resolve`。
- `thread.settings.read` 正常返回。

### 仍需 Bridge 修复：`thread.settings.update` 在 app-server 线程上失败

实测（Bridge 2.1.11）：

1. 只发 `approval_policy=never`：

```text
HTTP 503 {"error":"codex_unavailable",
"message":"App Server 调用失败 thread/settings/update：
thread/settings/update requires experimentalApi capability"}
```

2. 只发 `sandbox_policy="danger-full-access"`：

```text
HTTP 503 {"error":"codex_unavailable",
"message":"App Server 调用失败 thread/settings/update：
Invalid request: invalid type: string \"danger-full-access\",
expected internally tagged enum SandboxPolicyDeserialize"}
```

3. 发送对象形式 `sandbox_policy={"type":"dangerFullAccess"}`：被命令层拒绝，
返回 `400 invalid_params`。

结论：

- `codexbridge/codex/app_server.py` 的 `initialize` 只发送了 `clientInfo`，
  没有声明 `capabilities.experimentalApi=true`，所以 app-server 直接拒绝
  `thread/settings/update`。需要补上能力声明。
- `sandbox_policy` 在 app-server 协议里是内部标记枚举对象
  （`{"type":"dangerFullAccess" | "readOnly" | "workspaceWrite"}`），不是
  字符串 `danger-full-access`。Bridge 需要在
  `update_thread_settings` 里做映射，并放开命令层对该字段的对象类型校验。
- 桌面持有的线程（`_desktop_followed` 路径）走
  `thread-follower-update-thread-settings`，参数名是 `sandboxPolicy`，字符串
  形式；两条路径需要分别处理，不能共用一个字符串直传。

### 未实现

- 附件命令（`attachment.upload.begin/chunk/end`、`attachment.add`、
  `content.read`、`input.capabilities.read`）在本机目录里都不存在。
  手机端已据此隐藏图片/文件入口，等 Bridge 实现后会自动出现。

## 11. 复测结果（Bridge 2.1.13）

再次用临时配对身份直连本机 Bridge 复测。

### 已修复

- `initialize` 已声明 `capabilities.experimentalApi=true`，
  `experimentalApi` 报错消失。
- `sandbox_policy` 已做对象映射（`_sandbox_policy_object`）。

### 手机新建会话（Bridge 自己持有）：通过

```text
thread.start            -> 200, 新线程 01a157d8-...
thread.settings.read    -> 200
thread.settings.update  -> 200 {"applied": true}
  params: approval_policy=never, sandbox_policy=danger-full-access
thread.settings.update  -> 200 {"applied": true}   # 切回 on-request / workspace-write
```

说明手机端“请求批准 / 完全允许”的写入链路已经可用。

### 仍存在的 Bridge 缺口

1. **桌面持有的线程仍然无法改设置**

```text
thread.settings.update -> 503
"App Server 调用失败 thread/settings/update：thread not found: <id>"
```

`CompanionService.update_thread_settings` 只查 `_desktop_followed`，没有像
`start_turn` 那样调用 `_desktop_owner()` 做 owner discovery，所以桌面线程
找不到 owner 就落到 Bridge 自己的 app-server，而那个 app-server 不认识这条
线程。需要按 `start_turn` 的方式先发现 owner 再走
`thread-follower-update-thread-settings`。

2. **`thread.settings.read` 读不回策略值**

`thread/read` 返回的 thread 对象里没有 `approvalPolicy` /
`sandboxPolicy` / `approvalsReviewer`（实测 keys 里完全没有这三个字段），
所以 `read_thread_settings` 永远返回空字符串。

影响：手机端无法在重进会话时恢复上次选择的模式，只能保持本地默认值。
建议 Bridge 从 app-server 的 `thread/resume` 或线程设置接口取实际值，
或在 update 成功后缓存并在 read 时返回。

3. **附件命令仍然不存在**

`attachment.upload.begin/chunk/end`、`attachment.add`、
`input.capabilities.read`、`content.read` 在命令目录里都不存在，
手机端继续隐藏图片/文件入口。

## 12. 复测结果（Bridge 2.1.14）

### 已修复并验证通过

**1. 手机新建会话的审批模式：写入 + 读回都正常**

```text
thread.start            -> 200
thread.settings.update  -> 200 {"applied":true,"settings_revision":1}
   params: approval_policy=never, sandbox_policy=danger-full-access
thread.settings.read    -> 200
   {"approval_policy":"never","sandbox_policy":"danger-full-access",
    "settings_revision":1}
thread.settings.update  -> 200 {"applied":true,"settings_revision":2}  # 切回
```

`_thread_settings_snapshot` 生效，读回不再返回空字符串。手机端可以正确
恢复上次选择的模式。

### 仍需 Bridge 修复：桌面持有的线程改不了设置

实测同一个桌面会话：

```text
thread.settings.update -> 503
"App Server 调用失败 thread/settings/update：thread not found: <thread_id>"
```

根因（已定位到具体代码）：

`CompanionService._desktop_owner()` 开头有：

```python
# Custom app-server factories are isolated from the user's live desktop.
if self._desktop is None and self._client_factory is not None:
    return None
```

但生产环境的 `CompanionService` 构造时传了
`client_factory=lambda command, cwd: shared_app_server`（见
`codexbridge/__main__.py` 的 `CompanionService(...)`），所以
`_desktop_owner()` 永远直接返回 `None`，永远发现不到桌面 owner。

`start_turn` 之所以能写桌面线程，是因为它在 owner 判定之后还有
`_writable_thread()` 重新挂载线程；而 `update_thread_settings` 只依赖
`_desktop_owner()`，拿不到 owner 就直接落到 Bridge 自己的 app-server，
于是报 `thread not found`。

修复方向（二选一）：

- 让 `_desktop_owner()` 只在"确实要隔离的测试工厂"时短路，生产环境
  （共享 desktop app-server）仍然执行 DesktopIPC owner discovery；或
- `update_thread_settings` 复用 `start_turn` 的路径：先
  `_writable_thread(thread_id)` 挂载/回收，再更新设置。

注意第二条要谨慎：`_writable_thread` 会改动 writer lock，属于写操作，
仅为了改设置就回收桌面会话可能不合适，建议优先走第一条。

### 附件仍然未实现

Bridge 2.1.14 的命令目录里仍然没有：

```text
attachment.upload.begin / chunk / end
attachment.add / list / remove
input.capabilities.read
content.read
```

手机端继续隐藏图片/文件入口。
