# CodexBridge Mobile：审批模式与附件发送设计

日期：2026-10-20

状态：设计草案，等待确认；尚未修改产品代码。

## 1. 目标

在 CodexBridge Mobile 的聊天页补齐两项与 Codex 桌面端接近的能力：

1. **线程级审批模式切换**：在“请求批准”和“完全允许”之间切换。
2. **图片和文件发送**：从手机选择图片或文件，上传到电脑端，并作为
   `input_parts` 随消息发送给 Codex。

同时修复现有 MCP 工具确认无法提交的问题，确保审批卡片能正确显示和提交
`action/content`。

## 2. 约束

- 只修改 CodexBridge Mobile；Bridge 改动见
  `docs/codexbridge-approval-attachment-handoff-2026-10-20.md`。
- 手机端必须在旧 Bridge 上可用：Bridge 没有对应能力时隐藏入口或给出明确
  提示，不能显示一个必然失败的按钮。
- 审批模式按线程保存；不默认把全局策略改成“完全允许”。
- 附件上传采用 Bridge 内容存储，不把手机本地路径直接交给电脑端。
- 未确认 Bridge 支持前，不实现“看起来成功但实际丢附件”的降级。

## 3. 用户界面

### 3.1 聊天页顶部

在模型选择器旁边增加一个紧凑的权限模式入口：

```text
[模型：deepseek-v4.1-flash] [🛡 请求批准] [中断]
```

点击后打开底部选择器：

```text
审批模式
────────────────────────
请求批准
  命令、文件修改和网络操作前询问你。

完全允许
  不再询问普通命令、文件和网络审批。
  仍然可能需要填写 MCP 工具表单。

[取消]
```

- 当前模式显示在顶部。
- 切到“完全允许”需要二次确认，并说明风险。
- 当前 turn 正在运行时，可以显示“下一轮生效”，或直接禁用切换；两者择一，
  推荐“下一轮生效”，避免打断正在执行的任务。
- Bridge 不支持 `thread_settings` 时，整个入口隐藏。

### 3.2 审批卡片

按 `approval.kind` 显示不同内容：

- `command`：命令、cwd、允许/本会话允许/拒绝/取消。
- `fileChange`：文件列表、diff、允许/拒绝。
- `permissions`：权限范围 JSON 或结构化 permission 列表。
- `input`：问题列表、单选项、文本框、提交。
- `mcp`：MCP 服务器名、工具名、请求内容；如果 Bridge 提供表单 schema，
  渲染表单；没有 schema 时才显示“高级：原始 JSON”。
- `other`：摘要 + 允许/拒绝。

MCP 提交必须发送：

```json
{"action":"accept","content":{...}}
```

拒绝发送：

```json
{"action":"decline","content":null}
```

不能再用“缺失 decision 就 decline”的兼容逻辑覆盖用户选择。

### 3.3 输入区

在文本框左侧增加两个入口：

- 相册/图片
- 文件

选择后显示附件 chip：

```text
[图片 thumbnail] photo.jpg  1.2 MB  ×
[文件] report.pdf          428 KB   ×
```

- 支持一次选择多个文件。
- 上传中的附件显示进度；失败显示重试。
- 发送按钮在“有文字或有附件”时可用。
- 发送中禁止重复提交。
- 附件上传失败时保留已选附件和文字，不自动清空。

第一版建议限制：

- 最多 10 个附件/条消息。
- 单张图片 20 MB。
- 单个普通文件 50 MB。
- 具体上限以 Bridge 返回的能力为准。

## 4. 数据模型

### 4.1 审批模式

```kotlin
enum class ApprovalMode {
    RequestApproval,
    FullAccess,
}
```

映射：

```text
RequestApproval -> approval_policy=on-request, sandbox_policy=workspace-write
FullAccess      -> approval_policy=never,      sandbox_policy=danger-full-access
```

`approvals_reviewer` 第一版保持 `user`。

线程状态增加：

```kotlin
val approvalMode: ApprovalMode = ApprovalMode.RequestApproval
val approvalModeChanging: Boolean = false
```

### 4.2 附件草稿

```kotlin
data class PendingAttachment(
    val localId: String,
    val uri: String,
    val filename: String,
    val mimeType: String,
    val byteSize: Long,
    val kind: AttachmentKind,
    val uploadState: UploadState,
    val contentRef: String = "",
    val attachmentRef: Long = 0,
    val error: String = "",
)

enum class AttachmentKind { Image, File }
enum class UploadState { Pending, Uploading, Uploaded, Failed }
```

发送时构造：

```kotlin
sealed class OutgoingPart {
    data class Text(val text: String) : OutgoingPart()
    data class Attachment(val pending: PendingAttachment) : OutgoingPart()
}
```

## 5. 数据流

### 5.1 读取审批模式

1. 打开线程。
2. 如果 Bridge 声明 `thread_settings`：
   - 调用 `thread.settings.read`。
   - 更新 `UiState.approvalMode`。
3. 如果调用失败：
   - 保持默认“请求批准”显示。
   - 隐藏或禁用切换入口。
   - 显示一次非阻塞提示，不阻断聊天。

### 5.2 切换审批模式

1. 用户选择模式。
2. 如果是“完全允许”，弹出风险确认。
3. 调用 `thread.settings.update`。
4. 成功：
   - 更新本地状态。
   - 显示“已切换；运行中的任务下一轮生效”（如果正在运行）。
5. 失败：
   - 恢复旧模式。
   - 显示 Bridge 返回的原因。

### 5.3 选择并上传附件

1. 通过 Android Photo Picker / `ACTION_OPEN_DOCUMENT` 获取 URI。
2. 读取 `displayName`、`mimeType`、`size`。
3. 校验数量和大小。
4. 调用 `attachment.upload.begin`。
5. 分块调用 `attachment.upload.chunk`。
6. 调用 `attachment.upload.end` 获取 `content_ref`。
7. 调用 `attachment.add` 绑定到线程，获取 `attachment_ref`。
8. 更新 chip 为“已上传”。
9. 失败时保留 chip，显示重试；取消时调用 `attachment.upload.cancel`。

### 5.4 发送消息

1. 等待所有附件上传完成。
2. 构造 `input_parts`：
   - 文字 -> text part。
   - 图片 -> image part + content_ref。
   - 文件 -> file_ref part + content_ref。
3. 调用 `turn.start` 或 `queue.add`。
4. 成功后清空输入区。
5. 失败时保留文字和附件。

## 6. API 层改动

### 6.1 ApiClient

新增：

```kotlin
suspend fun threadSettings(threadId: String): ThreadSettings
suspend fun updateThreadSettings(threadId: String, settings: ThreadSettingsUpdate): ThreadSettings
suspend fun uploadAttachment(threadId: String, draft: PendingAttachment): UploadedAttachment
suspend fun sendMessage(
    threadId: String,
    text: String,
    attachments: List<UploadedAttachment>,
    model: String,
    effort: String,
): SendResponse
```

`respondApproval` 改为发送完整 `response`：

```kotlin
suspend fun respondApproval(requestId: Int, response: JsonObject): ActionResponse
```

CBDP 编码：

- `CbdpTag.RESPONSE`（0x0083）
- `CbdpTag.MCP_ACTION`（0x0141）
- `CbdpTag.MCP_CONTENT`（0x0142）
- `CbdpTag.INPUT_PARTS`（0x015B）
- `CbdpTag.PART_KIND`（0x015C）
- `CbdpTag.CONTENT_REF`（0x0157）

### 6.2 AppViewModel

新增状态：

- `approvalMode`
- `approvalModeChanging`
- `pendingAttachments`
- `attachmentUploads`

新增动作：

- `setApprovalMode(mode)`
- `addAttachments(uris)`
- `removeAttachment(localId)`
- `retryAttachment(localId)`
- `sendWithAttachments(text, attachments)`

### 6.3 ChatScreen

新增参数：

```kotlin
onSetApprovalMode: (ApprovalMode) -> Unit
onPickImages: () -> Unit
onPickFiles: () -> Unit
onRemoveAttachment: (String) -> Unit
onRetryAttachment: (String) -> Unit
```

## 7. 兼容和降级

- Bridge 不支持 `thread_settings`：隐藏审批模式入口。
- Bridge 不支持 `attachment` / `image_input` / `file_reference`：隐藏图片/文件入口。
- Bridge 支持附件但拒绝某 MIME：保留草稿并显示具体错误。
- Companion 旧路径：
  - 审批如果仍走 Companion，先修 Bridge 404，再使用 `response` 字段。
  - 附件不通过 Companion 降级，因为旧接口没有可靠的二进制附件语义。
- 不允许“上传成功但发送时静默丢附件”。

## 8. 测试

### 8.1 单元测试

- `ApprovalMode` 到 `approval_policy` / `sandbox_policy` 的映射。
- `respondApproval` 保留 `action/content`。
- MCP 表单提交构造正确的 CBDP response TLV。
- `input_parts` 与 `body` 互斥。
- 附件大小、数量、MIME 校验。
- 上传失败后保留草稿。
- Bridge 不支持能力时隐藏入口。

### 8.2 集成测试

1. 新建线程，切换“请求批准”，执行命令，出现审批，允许后继续。
2. 切换“完全允许”，执行普通命令，不出现普通审批。
3. MCP 工具请求表单，提交 `{}`，任务继续。
4. 选择图片上传并发送，Codex 返回基于图片的回答。
5. 选择 PDF/文本上传并发送，Codex 能读取文件。
6. 只发附件，发送成功。
7. 上传中断、重试、删除后没有孤儿文件。
8. 两个手机/桌面同时查看同一线程，审批状态同步。

## 9. 分阶段交付

### Phase A：修复审批提交

- Mobile 修复 `respondApproval` 的 response 构造。
- Bridge 修复 Companion 404、`approval_ref`、结构化 response。
- 验收 MCP 工具确认可以提交。

### Phase B：审批模式

- Bridge 实现 `thread.settings.read/update`。
- Mobile 增加顶部切换和风险确认。
- 验收两种模式的实际行为。

### Phase C：附件

- Bridge 实现附件上传和 `input_parts`。
- Mobile 增加选择、上传、预览、发送。
- 验收图片和文件。

## 10. 需要确认

1. “完全允许”只作用于当前线程，还是还要在设置页保存全局默认？
   推荐：当前线程优先，后续再加全局默认。
2. 第一版是否同时支持图片和任意文件？推荐：同时支持，但先验收图片。
3. 单附件上限是否接受“图片 20 MB / 普通文件 50 MB”？
4. 运行中的 turn 切换审批模式：推荐“下一轮生效”，是否接受？
5. MCP 表单没有结构化 schema 时，是否保留“高级 JSON”入口？
