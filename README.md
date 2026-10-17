# Codex 手机端（CodexBridge Mobile）

> 本目录是 [CodexBridge](../README.md) 主仓库的一部分（`mobile/` 子目录），
> 桌面端伴侣接口与手机端应用随同一个仓库发布。

通过局域网或外网连接电脑上的 **CodexBridge**，在手机上查看 Codex 的全部任务与消息、继续对话、切换模型、新建任务、处理审批请求，并在对话进行中把后续消息排入电脑端统一持有的待发送队列。

- 连接方式：CodexBridge 的“手机伴侣接口”（默认端口 `8568`，可在电脑端修改），同一 Wi-Fi 下自动发现（UDP `8569`，默认值，可在电脑端修改）
- 双地址自动切换：可同时保存内网地址和外网地址；同一 Wi-Fi 或以太网优先内网，蜂窝网络优先外网，首选线路不可用时自动回退
- 反代兼容：外网地址可以填写反向代理入口（例如 `tokenplanx.vip:8568`，旧的 `:8080` 入口仍可继续使用），配对信息不会因线路切换或应用升级失效
- 手机发现：手机端配对页和设置页都可以修改“扫描端口”；电脑端改了手机发现端口后，手机端填写同一个值即可重新扫描
- 令牌：配对成功后由电脑端签发，保存在手机本地，可在电脑端随时解除
- 通信：默认使用 HTTP；局域网为明文，外网连接请确保反向代理和访问控制配置正确

## 快速开始

1. 电脑端启动包含伴侣接口的 CodexBridge（`1.4.0` 或更新版本，见 CodexBridge 仓库的 `release/CodexBridge-1.4.0-win64-portable.zip`）。
2. 打开电脑端的设置页 `http://127.0.0.1:8788/settings`，在“手机连接”里：
   - 打开“启用手机连接”；
   - 记录显示的局域网地址（形如 `192.168.x.x:8568`）；
   - 点击“生成配对码”，得到 6 位数字（10 分钟内有效，仅可使用一次）。
3. 手机安装 APK（`release/CodexBridgeMobile-0.3.6-release.apk`），打开应用：
   - 确认“扫描端口”与电脑端“手机发现”端口一致（默认 `8569`），点“扫描”自动发现电脑；也可以手动填写电脑地址（如 `192.168.1.10:8568`，不带端口时默认补 `8568`）；
   - 需要在外网使用时，填写外网地址（可带反向代理端口）；输入 6 位配对码，点“连接电脑”。
4. 连接成功后即可看到任务列表；点开任意任务可继续对话，右上角可切换模型。

## 功能

- 任务列表：状态、预览、模型、最近活动时间、搜索
- 对话：完整消息流（用户 / Codex / 思考 / 命令 / 文件改动 / 计划），流式输出，`Ctrl+C` 等价的“中断”
- 待发送队列：对话进行中继续输入，消息由电脑端 CodexBridge 排队；队列对手机、硬件和电脑端是同一份，可逐条 `引导`、`编辑` 或确认后 `删除`
- 审批：命令执行、文件修改、权限、工具提问均可直接在手机上允许 / 本会话允许 / 拒绝 / 取消
- 新建任务：选择项目目录与模型
- 账号信息：套餐、5 小时与本周额度、token 用量
- 设置：内网地址、外网地址、当前线路、版本、手机 ID、扫描端口、深色主题、解除配对
- 状态栏通知：电脑端任务完成后提醒，点击通知直接进入对应任务

## 构建

前置：JDK 17、Android SDK（platform 35、build-tools 35.0.0）、Gradle 8.12。

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
gradle -p android assembleRelease
```

- 产物：`android/app/build/outputs/apk/release/app-release.apk`
- 签名：`android/signing/keystore.properties` 指向 `android/signing/codexbridge-mobile.jks`
  （别名/口令均为 `codexbridge`，仅用于个人分发与自用安装；请妥善保管，丢失后无法覆盖安装升级）

## 目录

```
android/                     Android 工程（Kotlin + Jetpack Compose）
  app/src/main/java/com/codexbridge/mobile/
    MainActivity.kt          入口与页面路由
    AppViewModel.kt          状态机（配对、任务、对话、事件流）
    data/                    伴侣接口客户端、模型、发现、设置存储
    ui/                      配对 / 任务列表 / 对话 / 设置界面
release/                     构建好的 APK
```

## 接口对照

手机端仅使用 CodexBridge 伴侣接口（`/api/companion/*`）：`ping`、`pair`、`status`、`threads`、
`threads/<id>`、`threads/<id>/messages`、`threads/<id>/interrupt`、`approvals/<id>`、`models`、
`projects`、`events`（SSE），以及 `commands` 上的 `queue.*` 队列命令。协议细节见
CodexBridge 仓库的 `PROTOCOL.md` 第 11A 节。
