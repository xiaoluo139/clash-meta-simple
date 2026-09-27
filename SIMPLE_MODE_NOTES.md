# 简易模式（Simple Mode）改动说明

本项目基于 Clash Meta for Android（CMFA）源码修改，目标是让新手打开软件后
一眼看懂、一次点击就能用，同时高级功能一个都不少。

界面采用「简易模式 + 高级模式」双层结构，默认进入简易模式。

---

## 一、简易首页

首页只回答三个问题：**现在连没连上 / 怎么启动 / 启动后是什么模式**。

```
┌──────────────────────────────┐
│ [logo] 应用名           高级模式 │
│                              │
│         (状态圆环)            │
│         未连接 / 正在连接 / 已连接 │
│         规则模式 · 已用 1.2 MB  │
│                              │
│  未连接时：                    │
│  ┌────────────────────────┐  │
│  │ ⚡ 规则启动代理            │  │  主按钮（主题色）
│  │    按订阅规则分流（推荐）   │  │
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │ 🔒 全局启动代理            │  │  次按钮（描边卡片）
│  │    所有流量都走代理        │  │
│  └────────────────────────┘  │
│                              │
│  已连接时：                    │
│  ┌────────────────────────┐  │
│  │ ⏹ 停止代理               │  │
│  └────────────────────────┘  │
│  运行中可随时切换模式            │
│  [ 规则 | 全局 | 直连 ]        │  滑动指示器分段控件
│                              │
│  当前订阅 / 当前节点             │
│  自定义分流规则 / 分应用代理      │
│  更多设置                      │
└──────────────────────────────┘
```

- 「规则启动代理」「全局启动代理」两个按钮**放在主页**，一目了然
- 点任一按钮会先把对应模式写入 **Persist Override**（重启后保持），再启动 VPN
- 已连接时换成「停止代理」，并提供分段控件随时切换 规则 / 全局 / 直连
- 「当前节点」会直接显示节点名与延迟（如「香港 01 · 45 ms」，超时显示「超时」）
- 「更新订阅」一行可**一键拉取机场最新节点列表**（`withProfile { update(uuid) }`），更新中会禁用并显示「正在更新…」
- 未连接时两个启动按钮下方显示「上次以「规则模式」启动」
- 状态圆环 `ConnectOrbView`（Canvas 自绘）只做状态指示，不再承担点击语义
  - 未连接：灰色电源图标；连接中：旋转弧线 + 扩散光环；已连接：主题色 + 对勾 + 呼吸光环
- 两个状态区用 `animateLayoutChanges` 做平滑过渡
- 右上角「高级模式」切回原版首页；原版首页也有「简易模式」入口
## 二、自定义分流规则（完整功能）

规则页 `CustomRulesActivity` + `CustomRulesDesign`：

| 能力 | 说明 |
| --- | --- |
| 图形化添加 | 只问「域名/IP」+「走哪里」，规则字符串自动生成 |
| 拖动排序 | 长按整行或按右侧手柄拖动，顺序即优先级 |
| 滑动删除 | 左右滑动删除，Snackbar 支持**撤销** |
| 规则模板 | 内置 3 个模板（国外走代理 / 国内直连 / 局域网直连）；还可**保存当前规则为自定义模板**，选中自定义模板可应用或删除 |
| 规则测试 | 顶部搜索图标：输入域名或 IP，按规则优先级本地模拟匹配，直接告诉你命中哪一条（GEOIP/GEOSITE/RULE-SET 等由内核判断，会明确提示） |
| 概览统计 | 列表上方显示「共 N 条 · 走代理 a · 直连 b · 拒绝 c」 |
| 分享 | 顶部分享图标：通过系统分享面板把规则列表发出去 |
| 导入 / 导出 | 纯文本一行一条，通过系统文件选择器导入导出 |
| 实时生效 | 保存即写入 Override，服务收到广播后自动重载 |
| 按订阅保存 | 规则按订阅（profile UUID）分开存储，切换订阅自动用各自的规则 |

### 实现原理（重要）

Clash 的 `rules:` 来自订阅本身。本版本用「原生层前置插入」做到既保留订阅规则、
又让自定义规则优先生效：

1. `ConfigurationOverride` 增加私有字段
   ```kotlin
   @SerialName("cmfa-custom-rules")
   var customRules: List<String>? = null
   ```
   存在 **Persist Override** 中。mihomo 的 `RawConfig` JSON 解码会忽略这个陌生字段。

   同时新增 `cmfa-custom-rules-by-profile`（`Map<UUID, List<String>>`）作为按订阅存储的载体，
   原生层用 `path.Base(profileDir)` 拿到当前订阅 UUID 后优先取该订阅的规则，
   取不到才回退到旧的共享列表（兼容老数据，首次编辑时自动迁移）。

2. `core/src/main/golang/native/config/process.go` 新增 `patchCustomRules`，
   在 `patchOverride` 之后执行：
   ```go
   cfg.Rules = append(rules, cfg.Rules...)   // 前置，不覆盖订阅规则
   ```

3. 安全兜底：`load.go` 的 `Load()` 中，如果加了自定义规则后 `Parse` 失败，
   会自动「禁用自定义规则再加载一次」，一条坏规则**不会导致代理起不来**。

> 私有键不能改成 mihomo 已有的字段名（如 `rules`），否则会覆盖订阅规则。

## 三、节点管理（测速 / 自动选最快 / 刷新 / 自选）

首页「当前节点」进入新的节点页 `NodesActivity`：

| 能力 | 实现 |
| --- | --- |
| 节点网速测试 | 「测速」按钮对当前节点组执行 URL 测试，测完按当前排序规则刷新（选「按延迟」就是延迟排行榜） |
| 节点刷新 | 标题栏刷新按钮：先更新所有 `proxy-provider`，再重新拉取节点组与节点列表 |
| 自动选择最佳节点 | 「自动选择最快」：测速后取延迟最小的真实节点并切换（URLTest/Fallback 这类自动组会提示由规则自动选择） |
| 自选节点 | 点击任意节点即切换，当前节点右侧显示对勾 |
| 节点组切换 | 多节点组时顶部显示「节点组 · xxx」，点击弹窗切换 |
| 排序切换 | 顶部「排序：xxx」可切换 配置顺序 / 按名称 / 按延迟，选择会记住（`UiStore.nodeSort`） |

延迟按区间着色：≤200ms 绿、≤500ms 橙、其余红；未测速灰色、超时红色。
若节点为空，页面提示「请先在首页选择订阅并连接」。

另外，首页未连接时会在两个启动按钮下方显示「上次以「规则模式」启动」，
记忆值存在 `UiStore.lastStartMode`，打开就知道上次怎么连的。
## 四、涉及的文件

新增：

| 文件 | 作用 |
| --- | --- |
| `design/.../design/SimpleDesign.kt` | 简易首页逻辑 |
| `design/.../design/CustomRulesDesign.kt` | 自定义规则页逻辑 |
| `design/.../design/adapter/CustomRuleAdapter.kt` | 规则列表适配器（支持拖动） |
| `design/.../design/view/ConnectOrbView.kt` | 圆形连接按钮 |
| `design/.../design/view/ModeSegmentedControl.kt` | 分段控件 |
| `design/src/main/res/layout/design_simple.xml` | 简易首页布局 |
| `design/src/main/res/layout/design_custom_rules.xml` | 规则页布局 |
| `design/src/main/res/layout/adapter_custom_rule.xml` | 单条规则布局 |
| `app/.../CustomRulesActivity.kt` | 规则页 Activity（含导入导出） |

修改：

| 文件 | 改动 |
| --- | --- |
| `app/.../MainActivity.kt` | 驱动简易/高级两套首页，共享启动停止逻辑 |
| `app/src/main/AndroidManifest.xml` | 注册 `CustomRulesActivity` |
| `design/.../design/MainDesign.kt`、`design_main.xml` | 高级首页新增「简易模式」入口 |
| `design/.../design/store/UiStore.kt` | 新增 `simpleMode`（默认 true） |
| `design/src/main/res/values{,-zh}/strings.xml` | 新增中英文文案 |
| `design/src/main/res/values/dimens.xml` | 新增尺寸 |
| `core/.../core/model/ConfigurationOverride.kt` | 新增 `customRules` 字段 |
| `core/.../native/config/process.go`、`load.go` | 自定义规则前置插入 + 兜底 |

遗留：`design/src/main/res/layout/component_custom_rule.xml` 已被
`adapter_custom_rule.xml` 取代，可安全删除（保留也不影响编译）。

本轮（节点管理）新增/修改的文件：

| 文件 | 作用 |
| --- | --- |
| `design/.../design/NodesDesign.kt` | 节点页逻辑（测速 / 自动选最快 / 刷新 / 自选） |
| `design/.../design/adapter/NodeAdapter.kt` | 节点列表适配器（延迟着色 + 选中标记） |
| `design/src/main/res/layout/design_nodes.xml` | 节点页布局 |
| `design/src/main/res/layout/adapter_node.xml` | 单条节点布局 |
| `app/.../NodesActivity.kt` | 节点页 Activity（URL 测试、provider 刷新、patchSelector） |
| `app/src/main/AndroidManifest.xml` | 注册 `NodesActivity` |
| `design/.../store/UiStore.kt` | 新增 `lastStartMode`（上次启动方式） |
| `design/src/main/res/layout/design_simple.xml` | 首页新增「上次启动方式」提示 |
## 五、编译说明

1. **JDK 21**（根 `build.gradle.kts` 里 `sourceCompatibility = VERSION_21`）
2. **Go 工具链 + gomobile**
3. **mihomo 子模块**：`git submodule update --init --recursive`
4. **Android SDK**：`compileSdk = 35`
5. **NDK 29.0.14206865**

```
./gradlew :app:assembleMetaDebug      # Meta 版本（推荐）
./gradlew :app:assembleAlphaDebug     # Alpha 版本
```

产物在 `app/build/outputs/apk/` 下。本次同时改了 Kotlin 与 Go，**两边都要重编**。

## 六、后续可做

- 规则运行时的真实命中次数（需要在内核层给每条 rule 加计数器，目前内核未暴露）
- 规则测试支持 IPv6 / GEOIP 本地判断（需要内置 geo 数据库）
- 首页显示节点延迟自动刷新（目前跟随事件刷新，可加定时器）
---

## 七、构建实录（本次已实际编译出 APK）

### 7.1 准备工作

| 组件 | 版本 / 来源 |
| --- | --- |
| JDK | Temurin 21.0.12.1（项目要求 `VERSION_21`） |
| Go | 1.23.4 |
| mihomo 子模块 | `git clone --depth 1 --branch Alpha https://github.com/MetaCubeX/mihomo core/src/foss/golang/clash` |
| Android SDK | `platforms;android-35`、`build-tools;35.0.0` |
| NDK | `29.0.14206865`（与项目 `ndkVersion` 一致） |

### 7.2 为了能编译，必须做的三处修复

1. **`sing-tun` 版本对齐**
   mihomo Alpha 头部要求 `sing-tun v0.4.26`，但 `core/src/foss/golang/go.mod` 与
   `core/src/main/golang/go.mod` 还锁在 `v0.4.24`，导致
   `unknown field TCPCongestionControl in struct literal of type tun.StackOptions`。
   已把两个 go.mod 改成 `v0.4.26`。

2. **`RawConfig` 的规则字段名**
   Go 侧取规则要用 `cfg.Rule`（单数，`yaml:"rules" json:"rule"`），
   不是 `cfg.Rules`。已修正 `native/config/process.go`。

3. **CMake 版本号容错**
   `core/src/main/cpp/CMakeLists.txt` 依赖 `git submodule foreach` 取版本号，
   在非 git 工作区（源码压缩包）会直接配置失败。已加降级分支（无 git 时用 `nogit`）。

### 7.3 编译命令

```powershell
$env:JAVA_HOME   = "<jdk21>"
$env:ANDROID_HOME= "<android-sdk>"
$env:GOROOT      = "<go>"
$env:PATH        = "$env:GOROOT\bin;" + $env:PATH

# 全 ABI（通用版，约 7 分钟）
.\gradlew.bat :app:assembleMetaDebug
```

### 7.4 产物

`app/build/outputs/apk/meta/debug/` 下：

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `cmfa-2.11.34-meta-universal-debug.apk` | 133.7 MB | **通用版**，含全部 4 个 ABI |
| `cmfa-2.11.34-meta-arm64-v8a-debug.apk` | 55 MB | 现代手机（推荐） |
| `cmfa-2.11.34-meta-armeabi-v7a-debug.apk` | 54.5 MB | 老设备 |
| `cmfa-2.11.34-meta-x86-debug.apk` | 56.6 MB | 模拟器 |
| `cmfa-2.11.34-meta-x86_64-debug.apk` | 56.8 MB | 模拟器 |

同时复制到项目根目录 `dist/` 下便于取用。

- 包名：`com.github.metacubex.clash.meta`
- 版本：`2.11.34.Meta.debug`
- minSdk 21 / targetSdk 35
- 签名：debug keystore（v1 + v2 校验通过）
- 每个 ABI 内含 `lib/arm64-v8a/libclash.so` 等原生库（内核本体 ~60 MB）
---

## 八、Release 版（已签名）

### 8.1 签名密钥

`signing.properties` 不存在时，构建脚本会回退到 debug 签名。本次生成了独立的发布密钥：

| 项 | 值 |
| --- | --- |
| 密钥库文件 | `clash-simple-release.keystore`（项目根目录） |
| 别名 (alias) | `clashsimple` |
| 密钥库密码 | `clashmeta2026` |
| 密钥密码 | `clashmeta2026` |
| 有效期 | 10950 天（约 30 年） |
| 证书 | `CN=Clash Meta Simple, O=Personal, C=CN` |

`signing.properties` 内容：

```properties
store.file=clash-simple-release.keystore
keystore.password=clashmeta2026
key.alias=clashsimple
key.password=clashmeta2026
```

> **务必保管好这个 keystore 和密码**：以后发布新版本必须用同一把密钥签名，
> 否则用户无法覆盖安装（会报签名不一致）。丢了就只能卸载重装。

同时把 `build.gradle.kts` 的签名配置改成支持 `store.file`：

```kotlin
storeFile = rootProject.file(prop.getProperty("store.file") ?: "release.keystore")
```

这样既不动原作者自带的 `release.keystore`，也能用自己的密钥。

### 8.2 编译命令

```powershell
.\gradlew.bat :app:assembleMetaRelease
```

Release 会启用 R8 混淆 + 资源压缩（`isMinifyEnabled` / `isShrinkResources`），
本次约 10 分钟（含 4 个 ABI 的 Go 原生库编译）。

### 8.3 产物

`app/build/outputs/apk/meta/release/`，并复制到 `dist/`：

| 文件 | 大小 |
| --- | --- |
| `ClashMeta-Simple-universal-release.apk` | 117.4 MB |
| `ClashMeta-Simple-arm64-v8a-release.apk` | 46.6 MB |
| `ClashMeta-Simple-armeabi-v7a-release.apk` | 46.1 MB |
| `ClashMeta-Simple-x86_64-release.apk` | 48.1 MB |
| `ClashMeta-Simple-x86-release.apk` | 48.2 MB |

- 包名 `com.github.metacubex.clash.meta`，版本 `2.11.34.Meta`（无 `.debug` 后缀）
- 签名校验：v1 + v2 通过，签名者 `CN=Clash Meta Simple`
- 相比 debug 版体积更小（R8 + 资源压缩生效）
- R8 后已确认：`MainActivity / NodesActivity / CustomRulesActivity` 仍在清单中，
  4 个 ABI 的 `libclash.so` 齐全，`cmfa-custom-rules-by-profile`、
  `hitCount`、`RuleStatList` 等关键串仍在 dex 中（序列化与 Binder 未被破坏）

### 8.4 安装注意

Release 版用的是**新密钥**，与之前的 debug 版签名不同：

```
# 如果装过 debug 版，先卸载
adb uninstall com.github.metacubex.clash.meta

# 再安装 release 版
adb install -r "dist\ClashMeta-Simple-universal-release.apk"
```