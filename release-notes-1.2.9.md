# CodexBridgeMobile 1.2.9

## 新增：Bridge 换 IP / 换端口后不再要求重新配对

之前手机把 `baseUrl`（IP + 端口）当成 Bridge 的身份。电脑 DHCP 换 IP 后，
旧地址要么不可达、要么被其它主机占用返回 401，手机就直接跳到配对页提示
“令牌配对失败”，其实 Bridge 里的配对记录和令牌都还在。

### 接入 Bridge 的稳定实例码

Bridge 现在提供 `bridge_instance_id`（32 位十六进制）和 `device_id`
（`bridge-xxxxxxxxxxxx`），出现在 UDP 发现、`/api/companion/ping` 和
`/api/companion/pair` 的 `bridge` 对象里。手机端已：

- 配对成功时与 `token`、`phone_id` 一起持久化这两个字段
- 旧记录没有实例码时保留为空，首次成功发现后自动补写

### 地址失效时先重新发现

启动、路由探测失败或收到 401 时：

1. 执行 UDP 发现
2. 按 `bridge_instance_id` 匹配已配对的 Bridge
3. 匹配成功后把新的 `host + port` 写回 `baseUrl` / `lanUrl` / `lastSuccessfulUrl`
4. 保留原 `token`、`phone_id`，用新地址重试
5. 重试成功即恢复，不再弹“令牌配对失败”

### 只有这些情况才要求重新配对

- 发现了**同一个** `bridge_instance_id`，但原令牌仍返回 `401 unauthorized`
- 用户主动解除配对
- 电脑端已移除该手机

网络不可达、旧 IP 被占用、端口变化都不会清除令牌。

### 防止连到冒名 Bridge

匹配只认 `bridge_instance_id`。名称相同**不算**同一台机器。
旧记录没有实例码时，只有在名称 + 版本 + 端口**唯一匹配**时才自动补写；
匹配不唯一则不发送令牌、不自动迁移。

## 验证

- 新增 5 个 `matchDiscoveredBridge` 单元测试（实例码优先 / 同名不同实例拒绝 /
  旧记录唯一匹配 / 歧义拒绝 / 无匹配）
- 全量单元测试通过；`lintDebug` 为 0 errors
- 实测本机 Bridge 2.2.11：`ping` 与 `pair` 均返回
  `bridge_instance_id = 298597748cf2a51e608b0fed3bc6fa12`、
  `device_id = bridge-298597748cf2`
