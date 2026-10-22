# CodexBridgeMobile 1.2.3

## 适配 Bridge 2.1.29 新建会话协议

- 适配 `POST /api/companion/threads` 的 `desktop_handoff: "pending"` 响应：新建不再报错，桌面端暂未接管时给出提示"发出第一条消息后会自动同步"
- 修复：手机新建的会话在发出第一条消息前不出现在 Bridge 线程列表里，刷新列表会"消失"——现在手机本地保留新建会话（24 小时或出现在服务端列表后移除），不再丢失

## 依赖

- 需要 CodexBridge >= 2.1.29
