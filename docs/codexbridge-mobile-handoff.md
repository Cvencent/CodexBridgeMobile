# CodexBridge 手机接入侧缺口交接

日期：2026-10-18

来源：CodexBridge Mobile 端联调。

范围：只记录 Bridge 需要处理的问题；Mobile 仓库未修改 CodexBridge 代码。

## 1. Companion HTTP 端口尚未暴露 CBDP HTTP/WebSocket 路由

联调环境：CodexBridge `2.0.1`，手机 Companion 端口 `8568`。

观察结果：

- `GET /cbdp/v1/ws` 返回 `401`，没有 WebSocket upgrade。
- `GET /cbdp/v1/events` 返回 `404`。
- `POST /cbdp/v1/frame` 没有对应 HTTP 路由。
- 同一端口的 `/api/companion/*` 正常工作。
- CBDP v1 目前只接在设备 TCP 网关（`8764/8765`），没有接到手机 HTTP Companion 端口。

影响：

- 按 `CBDP-V1-PROTOCOL.md` 实现的手机客户端无法直接在手机上使用 CBDP v1。
- Mobile 已加入“CBDP 优先，旧 Companion 兼容回退”，当前联调能工作，但走的是旧 HTTP/SSE。
- 新旧两套协议会长期并存，无法真正完成手机端 CBDP 切换。

建议 Bridge 侧处理：

- 在 Companion HTTP 端口提供 `/cbdp/v1/ws`、`/cbdp/v1/events`、`POST /cbdp/v1/frame`。
- WebSocket/SSE 路径与设备 TCP 网关复用同一个 `CbdpGateway`、事件 spine、订阅、writer lease 和引用注册表。
- 明确 HTTP 鉴权语义：现有 `/api/companion/*` 使用 `Authorization: Bearer <pairing token>`；CBDP HTTP 是否复用同一 token，或仍走 CBDP `pairing_token` HMAC，需要文档化。
- 路由存在时的错误码要与“路由不存在”区分，避免客户端把 `401/404` 当成 CBDP 路由不存在。

## 2. SSE 断开与 `Connection: close` 的语义

Bridge 已在普通 JSON 响应使用 `Connection: close`。当前联调里，Companion SSE 在 Bridge 关闭监听后，客户端长时间没有收到明确的 `onFailure/onClosed`，旧连接一度保持到 TCP 层超时。

建议 Bridge 侧处理：

- Companion 服务关闭时主动结束现有 SSE 响应，触发客户端重新连接。
- SSE 心跳失败或 read timeout 时服务端及时关闭连接，避免连接停留在半开状态。
- `Connection: close` 属于正常协议行为，文档中明确客户端应重新连接而不是当作业务错误。

## 3. CBDP 的 HTTP/SSE 能力声明

管理接口 `/api/admin/companion` 目前只描述 Companion HTTP 端口，没有字段说明该端口是否支持 CBDP。

建议增加：

- `cbdp_v1` / `transport` 能力或端口条目。
- 支持路径、鉴权方式和协议版本。
- 便于手机端在有旧版和新版 Bridge 时自动选择正确协议，无需先探测 `401/404`。

## 4. 验收建议

- 手机使用 CBDP-only 客户端连接 `8568`，可以完成 HELLO/WELCOME/AUTH、订阅、线程列表、详情、发送、队列、审批。
- Bridge 停止/恢复时，SSE/WS 均能及时结束并让客户端按退避恢复。
- 管理页能看到当前 Companion 端口是否已启用 CBDP v1。
