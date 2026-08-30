# Tongpin Clean 本地验收记录

## 1. 测试环境

- 操作系统：Windows 11 测试环境，64-bit
- Node.js：v24.18.0
- npm：11.16.0
- Java：Temurin JDK 17.0.19，用于 Android 构建
- 现有 Java：Temurin JDK 21 保留，未卸载
- Gradle：8.10.2
- Android SDK：`C:\Users\<USERNAME>\AppData\Local\Android\Sdk`
- Android SDK Platform：android-35
- Android SDK Build-Tools：35.0.0
- Android SDK Command-line Tools：latest
- 手机型号：Android 测试设备
- 手机 Android 版本：Android 16，SDK 36
- 测试服务端地址：`http://YOUR_LAN_IP:3000`
- 本地 MCP 地址：`http://localhost:3000/mcp`

## 2. 服务端测试

- 启动命令：`npm start`
- 监听地址：`http://0.0.0.0:3000`
- `/health`：通过，返回 `ok: true`、`service: tongpin-clean`、`version: 1.3.1`、`lan: true`
- `/control`：通过，返回 HTTP 200，网页遥控器可打开
- 房间 API：通过，App 可创建房间
- playback API：通过，Android App 可上传真实 QQ 音乐播放状态
- 数据文件：默认保存到 `services/server/data/rooms.json`
- Windows 防火墙：已允许测试端口 TCP 3000 入站访问，测试设备可访问 `/health`

## 3. Android 测试

- APK 构建：通过
- 构建命令：`gradle -p apps/android assembleDebug --no-daemon --console=plain`
- APK 路径：`apps/android/app/build/outputs/apk/debug/app-debug.apk`
- APK 大小：约 44.9 MB
- APK 安装：通过，`adb install -r` 返回 `Success`
- 包名：`com.linjian.tongpin`
- App 启动：通过，`com.linjian.tongpin/.MainActivity` 可正常打开，无启动崩溃
- 服务器连接：通过，App 保存 `http://YOUR_LAN_IP:3000` 后检测成功
- 通知权限：已授权
- 通知监听：已开启，`enabled_notification_listeners` 包含 `com.linjian.tongpin.media.TongpinNotificationListener`
- 后台同步：已开启，状态为 `服务器已连接 · 实时同步中`
- QQ 音乐读取：通过
- 真实播放歌曲：`I Believe I Can Fly`，`R. Kelly`
- 上传内容：歌名、歌手、播放器、播放状态、进度、歌词均成功同步
- 歌词来源：LRCLIB

## 4. MCP 测试

- `initialize`：通过，HTTP 200
- `tools/list`：通过，返回工具列表
- `get_room`：通过，可读取真实手机播放状态
- `add_listening_note`：通过，写入 `手机真实播放测试成功`
- 网页笔记显示：通过，网页遥控器显示该笔记
- `set_playback_command`：通过
- 测试命令：`pause`
- 初始状态：`queued`
- 真机执行结果：Android App 领取命令并暂停 QQ 音乐，服务端回写 `executed`
- 未测试：`search_and_play`

## 5. 已知修改

- 修改文件：`services/server/src/index.ts`
- 修改原因：Windows 下直接使用 `new URL(..., import.meta.url).pathname` 会导致 Express 静态文件路径异常，例如 `/control` 返回 404。
- 修复方式：使用 Node 官方 `fileURLToPath()` 将 file URL 转换为 Windows/Linux 均正确的本地文件路径。
- 影响范围：仅修复 `/control` 和 public 静态目录路径解析，不改变业务逻辑、REST API 或 MCP 行为。
- 是否建议保留：建议保留。

## 6. 部署前风险（历史测试状态）

- Render 临时存储问题（迁移前）：
  - 本报告编写时，`render.yaml` 使用 `DATA_FILE=/tmp/tongpin-rooms.json`。
  - 当时 `/tmp` 为临时存储，服务重启或重新部署后房间、笔记和状态可能丢失。
  - 当前版本已改为 Supabase Postgres 单行 JSONB 快照持久化，Render 重启后会重新读取该快照。

- Android 服务地址切换：
  - 本地测试地址为 `http://YOUR_LAN_IP:3000`。
  - Render 部署后 App 服务器地址需要改为 Render 的基础地址，例如 `https://your-service.onrender.com`。
  - App 中不要填写 `/mcp`、`/api` 或 `/control`。

- 公网 HTTPS：
  - 局域网 HTTP 适合本地测试。
  - 远程使用建议使用 HTTPS 地址。
  - 支持远程 MCP 的客户端应使用 `https://your-service.onrender.com/mcp`。

- Android 后台限制：
  - vivo 等厂商系统可能限制后台服务。
  - 真机长期测试前建议检查后台运行、自启动和省电策略。
