# BT 离线改名 / btfixoffline

绕开 MIUI / HyperOS「设置 → 蓝牙 → 设备名称」的**联网校验**和**每日改名次数限制**的 Xposed 模块。

在没有网络（或者飞行模式）时，HyperOS 会让你先联网才能改蓝牙名；联网之后又会被每天的次数卡住。这个模块的做法不是去找那个计数接口，而是**让设置进程始终认为自己处于有网状态**，从而直接走改名流程。

> 开发与验证环境：REDMI K80 Pro（HyperOS / Android 16）+ KernelSU + LSPosed（Vector 分支）。
> 这是一份**针对特定 ROM 实现**的方案，不是通用工具。其它 ROM / 其它 HyperOS 版本上大概率不适用，见「已知限制」。

---

## 1. 排查过程（为什么最后是伪造网络）

一开始的方向是 hook 掉业务层里那几个明显在判断网络的方法：

- `com.android.settings.bluetooth.MiuiBTUtils.isNetwork(Context)`
- `com.android.settings.bluetooth.MiuiBTUtils.isNetworkAvailable(Context)`
- `miui.securitycenter.utils.WNCheckManager.getCheckResultASync(...)` / `getCheckResultSync(...)`

**这些 hook 全部无效。** 原因是这些方法被 R8 内联（或者调用点直接改写），你 hook 到了方法体，但设置界面走的根本不是这条路径。

继续往下挖才确认：真正的拦截点是**「当前有没有活动网络」**这个判断本身，它被内联进了调用方，所以只能从框架层拦——也就是 `android.net.ConnectivityManager`。框架类不会被内联，所以这里是唯一可靠的注入点。

模块的第二个问题随之而来：**伪造网络会伤到「无线调试」**（详见第 3 节），这也是后来几版修改的主要工作量。

---

## 2. Hook 点清单

### 作用域 `com.android.settings`

| 目标 | 方法 | 作用 |
| --- | --- | --- |
| `android.net.ConnectivityManager` | `getActiveNetwork` | 有真实 WLAN 就透传真实网络，没有才返回伪造的 `Network(9999)` |
| 同上 | `getNetworkCapabilities(Network)` | 只对伪造网络返回伪造的 `NetworkCapabilities` |
| 同上 | `getLinkProperties(Network)` | 只对伪造网络借用真实 WLAN 的链路信息（否则无线调试页 IP 显示「无法获取」） |
| 同上 | `getActiveNetworkInfo` | 老接口兜底 |
| `android.net.NetworkCapabilities` | `hasTransport(int)` | 仅对伪造的 caps 强制 `TRANSPORT_WIFI` 为 true |
| `com.android.settings.bluetooth.LocalDeviceNameDialogFragment` | `setDeviceName(String)` | 直接执行改名，跳过校验 |
| `android.provider.Settings$Secure` | `putString(ContentResolver, String, String)` | 名字落盘后触发日志清理 |
| `MiuiBTUtils` | `isInternationalBuild` / `isNetwork` / `isNetworkAvailable` / `checkDeviceNameA` / `checkDeviceName` | 全部短路为「通过」 |
| `miui.securitycenter.utils.WNCheckManager` | `getCheckResultASync` / `getCheckResultSync` | 直接回「校验通过」 |

伪造的网络是 `Network(9999)`，`NetworkCapabilities` 里手工加了：

- `NET_CAPABILITY_INTERNET`(12)
- `NET_CAPABILITY_VALIDATED`(16)
- `NET_CAPABILITY_NOT_RESTRICTED`(11)
- `TRANSPORT_WIFI`（`mTransportTypes = 1L << 1`，反射失败时依次回退到 `setTransportType` / `setTransportTypes`）

### 作用域 `com.android.bluetooth`（可选，但建议勾上）

| 目标 | 方法 | 作用 |
| --- | --- | --- |
| `com.android.bluetooth.btservice.AdapterServiceBinder` | `setName(String)` | 改名成功后同步写 `persist.bluetooth.device_name`，避免重启回退 |

> 如果不勾 `com.android.bluetooth`，改名在当次开机内有效，重启后可能回到旧名字。

---

## 3. 伪造网络带来的两个副作用（都已修复）

**① 开发者选项里的「无线调试」开关变灰、点不动，提示先连 WLAN。**

第一版是无条件伪造网络，而且伪造的 caps 里**没有声明的传输类型**（`mTransportTypes` 为 0）。系统判断「无线调试」要求 `TRANSPORT_WIFI`，于是认为你没连 WLAN。

修复分两步：
1. 改成「有真实网络就透传真实网络，只有真的没网才造假」；
2. 给伪造的 caps 补上 `TRANSPORT_WIFI`。

**② 「无线调试」里 IP 地址和端口显示「无法获取」。**

伪造的 `Network(9999)` 在系统里没有对应的 `LinkProperties`，`getLinkProperties()` 返回 null。

修复：hook `getLinkProperties`，当入参是伪造网络时，借真实 WLAN 的链路信息返回；顺带对老接口做了兜底。

---

## 4. 使用

1. 需要 root + LSPosed / Vector（本机用的是 Vector 2.2）。
2. 安装 `app-release.apk`（或自己构建的 APK）。模块**没有 Activity、没有桌面图标**，安装后在 LSPosed / Vector 的模块列表里启用。
3. 作用域：
   - 必选：`com.android.settings`
   - 建议：`com.android.bluetooth`（属性同步）
4. 强制停止「设置」，或者重启后再生效。
5. 打开 设置 → 蓝牙 → 设备名称，随便改。

日志过滤关键字：`BTFIX`。

---

## 5. 构建

### 方式 A：Gradle / Android Studio

```bash
./gradlew :app:assembleRelease
```

Xposed API 只在编译期依赖（`compileOnly`），不会打进 APK。

### 方式 B：纯命令行（不需要 Gradle）

```bash
chmod +x tools/build-cli.sh        # 从压缩包解出的文件可能没有执行位
ANDROID_JAR=/path/to/android.jar \
BUILD_TOOLS=/path/to/build-tools/34.0.0 \
./tools/build-cli.sh

# 没有 SDK、只有 r8.jar 时：
R8_JAR=/path/to/r8.jar ANDROID_JAR=/path/to/android.jar ./tools/build-cli.sh
```

产物 `build/BTFixOffline.apk`，用自动生成的 `build/debug.keystore` 签名（发布请换成自己的密钥）。

`stub/` 目录是 Xposed API 的**编译桩**（只保留本项目用到的方法签名，没有运行时实现），仅供方式 B 使用。桩类不会被编进 APK。

---

## 6. 已知限制

- **强依赖 ROM 实现。** 类名、方法签名、`NetworkCapabilities` 内部字段都来自 HyperOS 3（Android 16）。系统升级后可能失效——hook 失败时只往 Xposed 日志里写一行，不会让设置崩溃。
- **只在设置进程内生效。** 不影响其它 App 看到的网络状态。
- **属性权限限制**（这是 Android 的 SELinux 属性上下文决定的，不是模块 bug）：
  - `persist.bluetooth.device_name` 属于 `bluetooth_prop`，**只有蓝牙进程能写** —— 所以 `com.android.bluetooth` 必须进作用域；
  - `persist.sys.bt_local_name` 属于 `system_prop`，只有 `system_server` / `init` 能写，模块里的这次写入在大多数 ROM 上会失败，留着只是兜底。
- **没有桌面入口**，不要指望在应用列表里找到它。
- 只处理「设置界面改名」这条路径。通过 ADB / 其它 App 改名不在范围内。

---

## 7. 关于日志清理（请先读这一段）

改名成功 1.5 秒后，模块会执行：

```bash
logcat -b main -b system -b events -b crash -c
```

把日志缓冲区清空，避免「改过什么名字」被留在 logcat 里。设置进程持有 `READ_LOGS` 权限，所以不需要 root 也能做。

**这是一个有争议的行为，请自己判断：**

- 它是我做这个模块时的个人取舍，不是绕开限制所必需的功能；
- 清日志会影响你自己之后排查问题（同一时间段的日志一起没了）；
- 不想要的话，删掉 `installSettingsHooks` 里对 `scheduleLogClear()` 的调用，以及 `scheduleLogClear` / `clearLogs` 两个方法即可，其余功能不受影响。

---

## 8. 免责声明

本项目仅供**在自己拥有/控制的设备上**学习与折腾系统行为。

- 模块会在系统「设置」进程里伪造网络状态、改写蓝牙名称属性，属于对系统行为的篡改；
- 不同 ROM、不同系统版本上可能出现不可预期的问题，请自行备份并确认救砖手段（本机依赖 KernelSU + RescueX 之类的保底措施）；
- 请勿用于绕过他人的设备管控，或任何违反服务条款、法律法规的场景；
- 使用风险自负，作者不对任何直接或间接后果负责。

---

## 9. 如果要发布到 GitHub

- 包名 `com.example.btfixoffline` 是**占位名**。真要用，建议改成自己域名反写；需要同步修改：
  1. `app/src/main/AndroidManifest.xml` 的 `package`
  2. `app/build.gradle` 的 `namespace` / `applicationId`
  3. `app/src/main/assets/xposed_init` 里的入口类名
  4. `app/src/main/java/` 下的目录结构（包名要和目录一致）
- `LICENSE` 里的 `btfixoffline contributors` 换成你自己的名字或 ID。
- **不要把 `build/debug.keystore` 提交上去。** 那是一次性调试密钥，`.gitignore` 里已经挡掉了 `*.keystore`；正式发布请自己生成并妥善保存密钥（模块更新必须用同一个签名）。
- 仓库里不要放成品 APK，交给 Releases 或 CI 构建。

---

## 10. 许可证

MIT，见 [LICENSE](LICENSE)。

这份代码是在自己设备上一点点试出来的，不是从别处抄的实现。如果你在别的 ROM 上适配成功了，欢迎把改动提回来。