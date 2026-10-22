# CodexBridgeMobile 1.2.5

## 修复：通知权限未检查（lint 报错）

`postCompletionNotification()` 直接调用 `NotificationManagerCompat.notify()`，在 Android 13+ 上
`POST_NOTIFICATIONS` 被拒绝或撤销时会抛 `SecurityException`，把前台监听服务带崩，导致后续
任务完成通知全部丢失。lint 一直报 `MissingPermission`（0 errors 之前是 1 error）。

**修复**：

- 发通知前显式检查 `POST_NOTIFICATIONS`（API 33+），未授权直接跳过
- 额外用 `try/catch (SecurityException)` 兜底，权限在检查与发送之间被撤销也不会崩服务

## 其它

- `lintDebug` 现为 **0 errors**（38 个既有 warning 不阻塞）
- 全量单元测试通过
