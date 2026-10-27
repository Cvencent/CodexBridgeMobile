# CodexBridgeMobile 1.2.15

## 修复：实例码不一致就强制重新配对（本次「配对失效」的真正原因）

1.2.13 引入的 `IdentityChanged` 判断过于激进：

```kotlin
updated = matchDiscoveredBridge(...) ?: return if (
    settings.bridgeInstanceId.isNotBlank() &&
    found.any { it.name == settings.bridgeName }
) RediscoveryOutcome.IdentityChanged   // → 直接要求重新配对
```

只要手机存的实例码和发现到的实例码不同，**连原令牌都不试**就判定配对失效。
而 Bridge 的实例码可能被重置过（重装、数据目录重建、早期版本未持久化），
此时 Bridge 里的手机记录和令牌**仍然有效**，手机却永久卡在「请重新配对」。

### 修复

实例码不一致时改为**用原令牌实测**：

- 原令牌在候选 Bridge 上能用 → 静默采用新的实例码，直接恢复，不弹重新配对
- 原令牌返回 `401` → 才判定令牌失效，提示重新配对
- 全部失败 → 报网络问题

安全性不变：令牌只会发给「电脑名称相同」的候选，且用不用得上一验便知。

## Bridge 侧已确认无问题

实测本机 Bridge 2.2.13：

- `_data_dir()` 固定为 `%LOCALAPPDATA%\CodexBridge`，与安装盘符无关
- 通过管理接口改 `enabled`：`mtime 19:06:14 → 19:08:20 → 19:08:23`，
  内存与磁盘每次一致
- 两个 `companion.json` 路径（Local 与 Packages LocalCache）是**同一个文件**
  （File ID 相同），不存在双写

## 验证

- 新增 2 个测试（实例码不一致时交由令牌验证 / 多候选中精确匹配优先）
- 全量单元测试通过；`lintDebug` 为 0 errors
