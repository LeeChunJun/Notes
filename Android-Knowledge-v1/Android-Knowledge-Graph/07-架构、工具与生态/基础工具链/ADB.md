---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 初级
created: 2026-11-04
updated: 2026-11-04
related: "[[AndroidStudio]], [[逆向工具]], [[../README]]"
---

# ADB

## 一句话定义

ADB（Android Debug Bridge）是连接开发机与 Android 设备/模拟器的**命令行工具**，通过 **client → server → adbd** 三段式架构提供安装、调试、日志抓取、文件传输、进程管理、端口转发等能力，是 Android 调试的基础设施。

## 核心原理

### 1. 三段式架构

```text
┌──────────────┐      TCP 5037       ┌──────────────┐      USB / TCP      ┌──────────────┐
│ adb client   │ ◀──────────────────▶ │ adb server   │ ◀──────────────────▶ │ adbd (设备端)│
│ (命令行)      │                      │ (守护进程)     │   adbd 以 root 或   │              │
└──────────────┘                      └──────────────┘   shell 用户运行      └──────────────┘
     ↑ 你的命令                            ↑ 设备列表在这里
   adb shell / install / logcat
```

关键点：

- **adb server** 通常在开发机上运行（默认端口 **5037**），负责管理所有连接的设备。
- **adbd** 是设备上的守护进程，通过 **USB（USB 协议）或 TCP（网络调试）** 与 server 通信。
- **一个设备可以同时被多个 client 使用**（server 会复用连接），但**同一时刻只能有一个 adb client 执行需要独占设备的操作**（如 `adb uninstall`）。
- **`adb` 命令与 `adb shell` 内执行的命令是两套**：`adb shell` 之后执行的是设备上的命令（如 `ls`、`pm`、`dumpsys`）。

### 2. 设备连接的三种方式

```bash
# 1. USB 连接
adb devices -l          # -l 显示详细信息
# 输出：emulator-5554  device product:sdk_gphone64_x86_64 model:Android_SDK_built_for_x86_64 device:emu64xa

# 2. 无线调试（Android 11+，推荐）
#    设备端：开发者选项 → 无线调试 → 打开
adb pair <host>:<port>          # 配对（输入配对码）
adb connect <host>:<port>       # 连接

# 3. 传统网络调试（需先 adb tcpip 5555）
adb tcpip 5555                  # 设备上把 adbd 切到 TCP 模式
adb connect 192.168.1.100:5555
```

> **注意**：无线调试端口每次配对后可能变化；`adb pair` 与 `adb connect` 是**两步**，容易漏。

### 3. 核心命令分类

#### 设备与文件

```bash
adb devices                      # 列出设备
adb install app.apk              # 安装
adb install -r app.apk           # 覆盖安装（保留数据）
adb install -d app.apk           # 允许降级版本
adb install -t app.apk           # 允许测试包
adb uninstall com.example.app   # 卸载
adb push local.txt /sdcard/      # 推文件到设备
adb pull /sdcard/a.txt ./        # 从设备拉文件
adb shell ls -l /sdcard/         # 列出设备文件
```

#### 应用管理

```bash
adb shell pm list packages                     # 所有包名
adb shell pm list packages | grep myapp        # 过滤
adb shell pm path com.example.app              # APK 路径
adb shell dumpsys package com.example.app      # 包信息详情

adb shell am start -n com.example.app/.MainActivity       # 启动 Activity
adb shell am start -a android.intent.action.VIEW -d "https://x.com"
adb shell am start -W -n com.example.app/.MainActivity    # -W 等待启动并输出耗时
adb shell am force-stop com.example.app        # 杀进程
adb shell am kill com.example.app               # 杀后台进程
adb shell am broadcast -a com.example.ACTION
adb shell am profile start ... / stop           # 性能剖析
```

#### 日志

```bash
adb logcat                         # 全部日志
adb logcat -v threadtime          # 带线程时间戳
adb logcat *:E                    # 只看 Error 及以上
adb logcat TAG1 TAG2              # 按 Tag 过滤
adb logcat --pid=$(adb shell pidof com.example.app)   # 只看某进程
adb logcat -c                      # 清空缓冲区
adb logcat -b all                  # 所有缓冲区（main/crash/system/radio/events）
adb logcat -b crash                # 崩溃日志
adb logcat -v threadtime > log.txt # 保存到文件
```

#### Shell 与进程

```bash
adb shell                         # 进入交互式 shell
adb shell whoami
adb shell getprop ro.build.version.sdk      # API 级别
adb shell getprop | grep -i version
adb shell ps -A | grep myapp
adb shell pidof com.example.app
adb shell kill -9 <pid>
adb shell top -n 1                # 内存/CPU 占用
adb shell dumpsys meminfo com.example.app
adb shell dumpsys cpuinfo
adb shell dumpsys activity activities       # Activity 栈
adb shell dumpsys window windows
adb shell dumpsys power
adb shell dumpsys battery
adb shell dumpsys connectivity
adb shell settings list global
adb shell wm size                 # 屏幕分辨率
adb shell wm density               # 屏幕密度
adb shell getprop ro.product.cpu.abi        # CPU 架构
```

#### 权限与 Root

```bash
adb shell pm grant com.example.app android.permission.CAMERA
adb shell pm revoke com.example.app android.permission.CAMERA
adb shell pm reset-permissions com.example.app
adb shell su                      # 有 root 时切换到 root
adb root                          # 重启 adbd 为 root（userdebug/eng 构建）
adb unroot
```

#### 模拟器控制

```bash
adb emu kill                     # 关闭模拟器
adb emu avd name -no-window      # 启动指定 AVD
adb shell settings put global window_animation_scale 0.0   # 关闭动画（测试用）
adb shell settings put global transition_animation_scale 0.0
adb shell settings put global animator_duration_scale 0.0
```

#### 端口转发与网络

```bash
adb forward tcp:8080 tcp:8080          # 主机 8080 → 设备 8080
adb reverse tcp:3000 tcp:3000          # 设备 3000 → 主机 3000（App 访问主机服务）
adb forward --list
adb reverse --list
# 用途：App 调试服务器、Charles/mitmproxy 抓包（需要 App 信任证书）
```

#### 系统与杂项

```bash
adb shell input text "hello"           # 输入文本
adb shell input keyevent KEYCODE_HOME
adb shell input tap 500 800            # 点击坐标
adb shell input swipe 500 800 500 200  # 滑动
adb shell input keyevent 26            # 电源键
adb shell screencap -p /sdcard/s.png   # 截图
adb pull /sdcard/s.png ./
adb shell screenrecord /sdcard/s.mp4   # 录屏
adb shell input keyevent 82            # 解锁（开发者选项里开启"不锁屏"）
adb shell svc wifi disable
adb shell settings put global http_proxy 10.0.2.2:8888
adb bugreport bugreport.zip            # 生成完整诊断包
adb shell dumpsys dropbox --print dataerror  # 查看系统级崩溃/ANR 记录
```

### 4. `am` 与 `dumpsys` 的威力

**`am`（Activity Manager）**——启动组件：

```bash
# 启动 Activity
adb shell am start -n com.example.app/.MainActivity

# 带参数启动
adb shell am start -n com.example.app/.MainActivity --es name "Tom" --ei age 18

# 隐式 Intent
adb shell am start -a android.intent.action.VIEW -d "https://example.com"

# 等待并输出启动耗时（冷启动测量）
adb shell am start -W -n com.example.app/.MainActivity
# 输出：Status: ok / LaunchState: COLD / TotalTime: 823 / WaitTime: 850

# 启动 Service
adb shell am startservice -n com.example.app/.SyncService

# 发送广播
adb shell am broadcast -a com.example.app.ACTION_SYNC -p com.example.app
```

**`dumpsys`**——系统服务状态快照，是"**线上问题排查的瑞士军刀**"：

```bash
adb shell dumpsys activity activities      # Activity 栈、任务、焦点窗口
adb shell dumpsys activity top             # 当前前台 Activity 详情
adb shell dumpsys window                    # 窗口、焦点、insets
adb shell dumpsys package com.example.app   # 包信息（权限、版本、dex 优化状态）
adb shell dumpsys meminfo com.example.app   # 内存占用明细
adb shell dumpsys gfxinfo com.example.app   # 渲染/帧统计（掉帧）
adb shell dumpsys batterystats              # 电量统计
adb shell dumpsys power                     # 唤醒锁、电源状态
adb shell dumpsys deviceidle                # Doze 状态
adb shell dumpsys jobscheduler              # 任务调度状态
adb shell dumpsys notification              # 通知
adb shell dumpsys sensorservice             # 传感器
adb shell dumpsys usagestats               # 使用统计
```

> **实战价值**：用户反馈"App 切到后台被杀"、"白屏"、"转场卡顿"，很多问题可以靠 `dumpsys` 现场取证。

### 5. 抓日志与 bugreport

```bash
# 复现问题时的标准取证流程
adb logcat -c                                  # 1. 清空旧日志
# 2. 复现问题
adb logcat -v threadtime > /tmp/crash.log      # 3. 抓取完整日志
adb shell dumpsys activity top > /tmp/top.txt  # 4. 抓取现场快照
adb bugreport bugreport.zip                    # 5. 生成诊断包（含 logcat、dumpsys、堆栈）
```

`bugreport` 包含：所有缓冲区的 logcat、dumpsys 输出、ANR traces、内存快照、系统属性 → **可以直接发给开发或上传到 Issue Tracker**。

### 6. 常见的 ADB 坑

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| `adb: no devices/emulators found` | USB 调试未开 / 授权弹窗被拒 / 数据线问题 | 换线（**必须用数据线，充电线不行**）、开启 USB 调试、撤销授权后重插 |
| `device unauthorized` | 没确认授权弹窗 | 撤销 USB 调试授权后重插 |
| `more than one device/emulators found` | 多设备 | 加 `-s <serial>`：`adb -s emulator-5554 shell` |
| `adb: command not found` | 没装 platform-tools 或没配 PATH | 装 platform-tools，加入 PATH |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致 | 先 `adb uninstall` 再装 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 版本降级 | 加 `-d` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（split apk） | 安装了 split 而非完整包 | 完整安装 |
| `adb forward` 后 App 访问不了 | 端口被占用 / 目标服务未启动 | `adb forward --list` 检查、换端口 |
| 无线调试连不上 | 配对后端口变了 | 重新 `adb pair` + `adb connect` |
| `SELinux is permissive` 报错 | 某些定制 ROM | 正常，不影响使用 |

### 7. 常用脚本片段

```bash
# 一键抓取某应用的完整诊断信息
#!/bin/bash
PKG=com.example.app
OUT=./diagnostics_$(date +%Y%m%d_%H%M%S)
mkdir -p $OUT
adb shell dumpsys package $PKG      > $OUT/package.txt
adb shell dumpsys meminfo $PKG      > $OUT/meminfo.txt
adb shell dumpsys activity top      > $OUT/activity_top.txt
adb shell dumpsys gfxinfo $PKG      > $OUT/gfxinfo.txt
adb logcat -d -v threadtime        > $OUT/logcat.txt
adb bugreport $OUT/bugreport.zip
echo "已生成: $OUT"
```

```bash
# 检查应用是否被系统"优化"（dex 压缩/后台限制）
adb shell dumpsys package com.example.app | grep -i -E "pkgFlags|DEXOPT|hidden"
```

## 常见问题 / 面试要点

**读者问：ADB 的工作原理是什么？为什么 `adb shell` 能执行系统命令？**

**AI答：**

1. **三段式**：`adb client`（命令行）→ 通过 TCP 5037 连接 `adb server` → `adb server` 通过 USB 或 TCP 与设备上的 **`adbd`** 通信。
2. **`adb shell` 的原理**：
   - `adb` 客户端告诉 server："给设备 X 起一个 shell"；
   - server 通过 adbd 启动一个 **shell 进程**（uid 为 `shell` 用户，或 root 构建下为 root）；
   - 之后输入的命令通过 **stdin/stdout** 在这个进程里执行。
3. **权限模型**：
   - `adb shell` 默认以 **`shell` 用户**运行（uid 2000），属于受限用户组；
   - 它能调用大多数 `dumpsys`（因为 `dumpsys` 属于 shell 组）、`pm`、`am` 等；
   - **`su`** 可以切到 root（前提是设备已 root 或 userdebug/eng 构建）。
4. **`adb root` 的本质**：重启 `adbd` 以 root 身份运行（只对 `userdebug`/`eng` 构建有效，user 版会报 "cannot run as root in production builds"）。
5. **安全含义**（能加分）：**生产设备上的 ADB 是关闭的**（`ro.debuggable=0`），这也意味着"**能连 ADB 的设备 = 高权限设备**"，是安全评估的敏感点。

---

**读者问：线上/测试环境遇到"App 无响应、卡死、闪退"，你怎么用 ADB 排查？**

**AI答：**

给一条**有顺序的排查路径**（面试时按流程讲，显示工程思维）：

1. **先确认是不是 ANR**：
   ```bash
   adb shell dumpsys activity processes | grep -A 20 "ANR"
   adb shell ls -l /data/anr/            # 需要 root
   adb shell dumpsys dropbox --print dataerror | grep -A 50 ANR
   ```
   ANR 的 trace 里会写明**主线程卡在哪一行**（常见：主线程 IO、主线程等锁、BroadcastReceiver 超时）。
2. **看日志**：
   ```bash
   adb logcat -c && adb logcat -v threadtime > crash.log   # 复现时抓
   adb logcat -b crash                                   # 崩溃缓冲区
   ```
   找 `FATAL EXCEPTION`、`ANR in`、`Watchdog`、自定义错误码。
3. **看内存**：
   ```bash
   adb shell dumpsys meminfo com.example.app    # 关注 PSS / Native Heap / Dalvik Heap
   ```
   - Native 异常高 → 可能是**图片/编解码泄漏**或 JNI 泄漏；
   - Dalvik 高 → Java 侧对象堆积（列表没清、缓存无上限）。
4. **看卡顿**：
   ```bash
   adb shell dumpsys gfxinfo com.example.app framestats
   ```
   统计 `Janky frames` 比例与超长帧。
5. **看进程状态与被杀**：
   ```bash
   adb shell dumpsys activity exit-info com.example.app   # 历史退出原因（ANR / crash / lowmem）
   ```
   `EXIT_REASON_ANR` / `EXIT_REASON_CRASH` / `REASON_USER_REQUESTED` 一目了然。
6. **留证**：
   ```bash
   adb bugreport bugreport.zip
   ```
7. **总结表述**："**先用 `dumpsys` 判断问题类型（ANR/内存/卡顿/被杀），再用 logcat 定位细节，最后用 `exit-info` 与 bugreport 留证。**"

---

**读者问：`adb forward` 和 `adb reverse` 有什么区别？各自用在哪？**

**AI答：**

1. **方向不同**：
   - **`adb forward tcp:主机端口 tcp:设备端口`**：**主机 → 设备**。设备上的 `localhost:设备端口` 会被转发到主机的 `主机端口`。
   - **`adb reverse tcp:设备端口 tcp:主机端口`**：**设备 → 主机**。设备上的 `localhost:设备端口` 会被转发到主机的 `主机端口`。
2. **典型场景**：
   - **`forward`**：设备上的 App 要访问**主机上的调试服务**，例如：
     - App 调 `http://localhost:8080/api` 实际打到主机的 8080（本地后端）；
     - 配合 **Charles/mitmproxy** 在主机上抓 App 的 HTTP 流量（App 配 `localhost:8888` 代理）。
   - **`reverse`**：主机上的工具要访问**设备上的服务**，例如：
     - 手机上的 App 开启本地服务器（`127.0.0.1:3000`），主机用浏览器访问 `http://localhost:3000`；
     - React Native / Flutter 调试时加载 JS bundle（**Metro 打包器在主机上**，需要 reverse 才能让设备访问主机）。
3. **查看与清理**：
   ```bash
   adb forward --list
   adb forward --remove tcp:8080
   adb reverse --list
   adb reverse --remove tcp:3000
   ```
4. **注意**：
   - 转发是**按设备**的，多设备时用 `-s <serial>` 指定；
   - 端口占用会失败（`cannot bind listener`），换端口即可；
   - 抓包场景还需让 App **信任抓包证书**（Android 7+ 默认不信任用户 CA → 需在 debug 构建里加网络安全配置信任用户证书）。

---

**读者问：Android 7.0 之后抓包（HTTPS）一直失败，`Charles` 抓不到包，是什么原因？怎么解决？**

**AI答：**

1. **根因**：Android 7.0（API 24）引入 **Network Security Configuration**，**默认不再信任用户安装的 CA 证书**（系统 CA 仍信任）→ Charles 的自签证书被拒 → HTTPS 无法解密。
2. **解决方案**（按推荐度）：
   - **方案 A（推荐，调试用）**：在**debug 构建**里配置网络安全规则信任用户 CA：
     ```xml
     <!-- res/xml/network_security_config.xml -->
     <network-security-config>
         <debug-overrides>
             <trust-anchors>
                 <certificates src="system" />
                 <certificates src="user" />   <!-- 信任用户安装的 CA -->
             </trust-anchors>
         </debug-overrides>
     </network-security-config>
     ```
     ```kotlin
     // debug 源码集
     android {
         sourceSets.getByName("debug") {
             res.srcDir("src/debug/res")
         }
     }
     ```
     **关键**：只放在 `src/debug/res`，**绝不能进 release**（这是安全红线）。
   - **方案 B**：把 Charles 根证书**安装进系统 CA 目录**（`/system/etc/security/cacerts`）→ 需要 root + 重启，且会**影响全局信任**，只在测试机上做。
   - **方案 C**：**SSL Pinning** 是另一回事——即使信任了用户 CA，被 pin 的 App 仍无法被抓包。绕过需改代码（debug 构建关闭 pinning）或用 Frida hook（见 [[逆向工具]]）。
3. **其他常见原因**：
   - **App 走了 HTTP/2 或 QUIC（HTTP/3）** → 抓包工具可能不支持，需在 Charles 里禁用 QUIC；
   - **证书固定（Certificate Pinning）**；
   - **App 使用了自研网络栈**（OkHttp 自定义 TrustManager）；
   - **代理未生效**：部分 App 忽略系统代理（`ProxySelector` 被改写）→ 需要 VPN 模式抓包（PCAPdroid、HttpCanary）。
4. **面试加分**：能说明"**这套配置只能进 debug 构建**，release 里信任用户 CA 等于打开了中间人攻击的门"。

## 关联笔记

- [[AndroidStudio]]：图形化调试与 Profiler
- [[逆向工具]]：APK 分析、Frida、Hook 与抓包
- [[../06-系统原理与性能优化/内存优化]]：内存与 ANR 的排查工具
- [[../08-企业级工程与运维/日志与监控]]：线上日志与崩溃采集体系
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · ADB 文档：https://developer.android.com/tools/adb
- Android 官方 · `adb shell` 命令参考：https://developer.android.com/tools/adb#shell
- Android 官方 · 网络安全配置：https://developer.android.com/privacy-and-security/security-config
- Platform Tools 下载：https://developer.android.com/tools/releases/platform-tools
