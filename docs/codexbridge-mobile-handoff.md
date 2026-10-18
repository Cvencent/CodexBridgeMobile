# CodexBridge 手机接入侧缺口交接

日期：2026-10-18

来源：CodexBridge Mobile 端联调。

范围：记录 Bridge/设备侧需要处理的问题；Mobile 仓库未修改 CodexBridge 代码。

## 当前状态（CodexBridge v2.1.1）

Bridge 侧此前 2.0.1/2.0.4 的缺口均已修复，Mobile 已按 v2.1.1 实测通过：

- `/cbdp/v1/ws`、`/cbdp/v1/events`、`POST /cbdp/v1/frame` 已可用，Bearer 鉴权正常。
- Companion 端口鉴权已澄清为两层：HTTP Bearer 之后仍需标准 `HELLO -> WELCOME -> AUTH -> AUTH_OK`。
- `GET /cbdp/v1` 与 `/api/companion/ping` 的 `cbdp_v1` 已提供能力发现，Mobile 不再依赖 401/404 探测。
- `threads.list`、`thread.read`、`models.list`、`projects.list`、`approvals.list` 的 `objects` 数组已按协议编码。
- `next_cursor` 已提供会话内 u64 映射，Mobile 已实现 `threads.list` 与 `thread.read` 翻页。
- Companion SSE 在服务停止时会结束连接，`Connection: close` 作为正常传输生命周期处理。

## 仍需设备端（ESP / AI Passport）处理

以下事项来自 CodexBridge 的跨仓库问题清单，Mobile 无法代改：

- `s_wireless_tx_id` 目前只在文本路径递增，`AUDIO_BEGIN/CHUNK/END` 复用同一个 `tx_id` 和 `idem`。
- 音频超过 `max_audio_bytes` 时设备不发 `AUDIO_END`，Bridge 侧事务可能残留。
- 建议每次 `AUDIO_BEGIN` 前推进 `tx_id`，并让 `idem` 随之变化；失败重试复用同一组值。
- 建议确认无活动会话时使用 `use_active_thread=1`，并把 Bridge 的 `NO_ACTIVE_THREAD` 错误展示给用户。

## Mobile 侧验收结果

- 线程列表：CodexBridge v2.1.1 下显示 40 个任务。
- 能力发现：命中 `GET /cbdp/v1` 的 `cbdp_v1.enabled`。
- 鉴权：严格两层鉴权通过。
- 连接：前台稳定单连接；后台按策略收敛；无回退连接泄漏。
- 测试：84 个单元测试通过。
- Release：签名 v2、可安装。
