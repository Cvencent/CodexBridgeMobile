# CodexBridge 重启后手机配对令牌失效 — 修复请求

日期：2026-10-21
适用版本：CodexBridge 2.1.16（本机实测）
来源：CodexBridgeMobile 手机端实测
严重程度：高（每次 Bridge 更新/重启都可能导致手机断连）

---

## 一、用户看到的现象

1. 手机端反复弹出"配对令牌无效，请重新配对"。
2. 在手机上返回、重试都没有用——因为令牌确实已经失效了。
3. 用户重启了 CodexBridge 和 Codex，不确定是哪个导致的。
4. 用户只能手动重新配对才能恢复。

---

## 二、实测证据

### 2.1 配对存储被重置

重启 CodexBridge 后，`%LOCALAPPDATA%\CodexBridge\companion.json` 从
**15 条手机记录**变成 **1 条**（重新配对后的新记录）。

修复前的 companion.json（15 条手机记录，包括用户的
`phone-a8923e6a6cea` 和多条测试记录）：

```json
{
  "phones": [
    {"phone_id": "phone-a8923e6a6cea", "name": "Xiaomi 24129PN74C", ...},
    {"phone_id": "phone-c6e0641df76f", "name": "queue-probe", ...},
    ... 共 15 条 ...
  ]
}
```

重启/更新后的 companion.json（只有 1 条新配对记录）：

```json
{
  "phones": [
    {"phone_id": "phone-051b642ae212", "name": "Xiaomi 24129PN74C", ...}
  ],
  "prior_tokens": [],
  "pairing": {"code": "", "expires_at": 0}
}
```

旧的 `phone-a8923e6a6cea` 记录不存在了，所以手机的旧令牌无法匹配任何
记录，所有 API 调用返回 401。

### 2.2 重启后的当前状态

用户重新配对后（新 phone_id `phone-051b642ae212`）：

- `last_seen` 正常更新（手机实时通信中）
- `enabled` 包含全部能力（含 attachment）
- `prior_tokens: []`（没有宽限令牌）
- `token_hash` 已设置

说明手机当前令牌有效，但**旧令牌已经彻底丢失**。

---

## 三、根因分析

### 正常重启的行为（符合预期）

`CompanionStore` 持久化到 `companion.json`。Bridge 重启时 `load()`
从磁盘读取，令牌哈希不变，手机不需要重新配对。Bridge 还有宽限机制：

```python
TOKEN_GRACE_SECONDS = 60 * 60.0   # 1 小时
TOKEN_GRACE_HISTORY = 4           # 保留最近 4 个令牌
```

即使 Bridge 重启后轮换了令牌，旧令牌也在宽限期内有效。

### 实际发生的事

重启/更新后 companion.json 被重置为空。可能的原因：

1. **Bridge 更新流程重置了数据目录**——如果 2.1.16 的安装/更新流程
   替换了 `%LOCALAPPDATA%\CodexBridge\` 或删除了 companion.json，
   所有配对数据就丢了。
2. **Bridge 从不同路径启动**——如果更新后的版本使用了不同的
   `data_dir`，它会读不到旧的 companion.json，从空开始。
3. **store 格式变更无迁移**——如果 2.1.16 改了 companion.json 的
   schema 且没有向后兼容读取，`load()` 会静默返回空（代码里
   `except json.JSONDecodeError: return` 就是这么写的）。

需要瑞杰确认是哪种情况。

---

## 四、修复方向

### 如果是更新流程重置了数据目录

确保更新时保留 `%LOCALAPPDATA%\CodexBridge\` 下的所有 .json 文件，
或者提供迁移逻辑。

### 如果是 store 格式变更

`load()` 里的异常处理目前是静默返回空：

```python
try:
    payload = json.loads(raw)
except json.JSONDecodeError:
    return                    # ← 静默丢弃所有配对数据
```

建议改为：

1. 记录警告日志，不要静默丢弃。
2. 如果是 schema 版本变更，提供向后兼容读取或迁移。
3. 如果文件损坏，备份原文件（`.bak`）再从空开始，方便排查。

### 无论哪种原因

Bridge 重启不应该导致手机需要重新配对。这是用户对 Bridge 最基本的
可靠性预期。

---

## 五、Mobile 端已做的配套改进

手机端 1.1.0 已修复：收到 401 时自动导航到配对页（地址预填好），
而不是只弹错误让用户不知所措。瑞杰修复 Bridge 侧后，这个改进仍然有用
（作为最后防线）。

---

## 六、验证方法

1. 在 Bridge 上配对一部手机。
2. 重启 CodexBridge。
3. 手机端应该能正常使用，不需要重新配对。
4. 检查 `companion.json` 的 phones 列表是否保持不变。
