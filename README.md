# Clash Meta Simple（简易版）

> 基于 [Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid) 二次开发的**新手友好版**。
> 在原版强大功能之上，加了一套「简易模式」首页，让第一次用 Clash 的人也能一次点会。

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](./LICENSE)
[![Build APK](https://github.com/xiaoluo139/clash-meta-simple/actions/workflows/build.yml/badge.svg)](https://github.com/xiaoluo139/clash-meta-simple/actions/workflows/build.yml)
![Platform](https://img.shields.io/badge/Platform-Android-green.svg)
![minSdk](https://img.shields.io/badge/minSdk-21-orange.svg)

---

## ✨ 相比原版多了什么

### 1. 简易模式首页（默认进入）

- 首屏只回答三件事：**连没连上 / 怎么启动 / 现在是什么模式**
- **两个明确的启动按钮**：`规则启动代理`（按订阅规则分流，推荐）、`全局启动代理`（所有流量走代理）
- 连接中/已连接有状态圆环动画（旋转弧线、扩散光环、呼吸光环、对勾）
- 已连接时变为 `停止代理` + 分段控件，可随时切换 `规则 / 全局 / 直连`
- 分流模式写入持久化配置，**重启后保持**
- 「当前节点」直接显示节点名与延迟（`香港 01 · 45 ms`），运行中每 5 秒自动刷新
- 「更新订阅」一键拉取机场最新节点列表
- 右上角一键切回「高级模式」（原版完整界面），功能一个都没少

### 2. 图形化自定义分流规则

桌面级的分流编辑体验，但只有两个问题：

| 能力 | 说明 |
| --- | --- |
| 图形化添加 | 只问「域名/IP」+「走哪里」，规则字符串自动生成 |
| 拖动排序 | 长按整行或拖右侧手柄，顺序即优先级 |
| 滑动删除 | 左右滑动删除，支持**撤销** |
| 规则模板 | 内置 3 个模板，也可**保存当前规则为自定义模板**（可应用 / 删除） |
| 导入 / 导出 / 分享 | 纯文本一行一条，走系统文件选择器与分享面板 |
| **规则测试** | 输入域名或 IP，本地模拟匹配，直接告诉你命中哪条规则（支持 IPv4/IPv6） |
| **命中统计** | 每条规则显示**真实命中次数**（来自内核计数器），每 3 秒刷新 |
| **按订阅保存** | 规则按订阅（profile UUID）分别存储，换机场不串规则 |

实现要点：原生层在加载配置时把自定义规则**前置插入**到 `rules:` 之前，
既保证优先级，又不覆盖订阅自带规则；如果规则有问题导致解析失败，会自动降级重载，
**不会把代理搞挂**。

### 3. 节点管理

| 能力 | 说明 |
| --- | --- |
| 节点网速测试 | 一键对当前节点组做 URL 测试，延迟按区间着色 |
| **自动选择最快** | 测速后自动切到延迟最小的节点 |
| 自选节点 | 点击即切换，当前节点打勾 |
| 节点刷新 | 先更新所有 `proxy-provider`，再重新拉取节点列表 |
| 排序切换 | 配置顺序 / 按名称 / 按延迟，选择会记住 |
| 节点组切换 | 多节点组时顶部可切换 |

---

## 📥 下载安装

前往本仓库的 **[Releases](../../releases)** 页面下载 APK：

| 文件 | 适用 |
| --- | --- |
| `ClashMeta-Simple-universal-release.apk` | 通用（含全部 4 种 ABI，体积最大） |
| `ClashMeta-Simple-arm64-v8a-release.apk` | **推荐**，绝大多数现代手机 |
| `ClashMeta-Simple-armeabi-v7a-release.apk` | 较老的 32 位设备 |
| `ClashMeta-Simple-x86 / x86_64-release.apk` | 模拟器 |

安装后首次启动需要授予 VPN 权限；再在「当前订阅」里导入你的订阅链接即可。

> **包名已改为独立的 `com.github.xiaoluo139.clashsimple.meta`（Alpha 版为 `.alpha`）**
> ，因此可以**和官方 ClashMetaForAndroid 同时安装、互不影响**。
>
> ⚠️ 如果你装过本项目 **2.11.35 之前的旧包**（包名为 `com.github.metacubex.clash.meta`），
> 因为包名变了，旧的那个需要**手动卸载**一次；之后升级就不用了。

---

## 🌐 获取订阅（节点）

本 App 只是客户端，**需要你自己提供 Clash 订阅链接**才能使用。

### 第三方免费资源（未经验证，自担风险）

社区里有一些公益/免费节点站点，例如：

- **FreeSocks** —— <https://freesocks.org/>

> ⚠️ **重要提示，请务必阅读**
>
> 上面的链接是**第三方站点，与本项目无任何关系**，我们**没有验证过它是否安全、稳定或合法**。
> 免费公共节点通常存在以下风险，请自行判断：
>
> - 流量可能被记录或篡改（**不要用它登录网银、邮箱、公司账号等敏感服务**）
> - 节点随时失效、速度不稳、可能突然停止服务
> - 部分站点会夹带推广或要求不必要的权限
> - 是否允许使用，取决于你所在国家/地区的法律法规，**请自行确认并承担后果**
>
> 如果你在意稳定与隐私，建议使用**付费机场**或**自己搭建**节点。

### 导入方式

拿到订阅链接后，在 App 里：

1. 首页点「当前订阅」→ 右上角 `+` → 填写名称与订阅 URL → 保存
2. 回到首页点「规则启动代理」（推荐）或「全局启动代理」
3. 需要换节点时，点首页「当前节点」→ 测速 → 选一个延迟低的
## 🔨 自行编译

### 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | **21**（项目使用 `sourceCompatibility = VERSION_21`） |
| Go | 1.20+（实测 1.23.4 可用） |
| Android SDK | `platforms;android-35`、`build-tools;35.0.0` |
| NDK | `29.0.14206865` |
| mihomo 子模块 | `git submodule update --init --recursive` |

### 编译

```bash
# 1. 拉取内核源码
git submodule update --init --recursive

# 2. 编译（Meta flavor）
./gradlew :app:assembleMetaDebug
./gradlew :app:assembleMetaRelease

# Alpha flavor
./gradlew :app:assembleAlphaRelease
```

产物在 `app/build/outputs/apk/` 下。

### 三个容易踩的坑（本项目已修复）

1. **`sing-tun` 版本**
   若 mihomo 子模块较新，需保证 `core/src/main/golang/go.mod` 与
   `core/src/foss/golang/go.mod` 里的 `github.com/metacubex/sing-tun` 与
   子模块 `go.mod` 一致，否则报
   `unknown field TCPCongestionControl in struct literal of type tun.StackOptions`。

2. **`RawConfig` 的规则字段**
   原生层取规则用 `cfg.Rule`（单数，`yaml:"rules" json:"rule"`），不是 `cfg.Rules`。

3. **CMake 版本号**
   `core/src/main/cpp/CMakeLists.txt` 原先依赖 `git submodule foreach` 取版本号，
   在非 git 工作区（例如下载的源码压缩包）会导致配置失败，现已加降级分支。

### Release 签名

在项目根目录创建 `signing.properties`：

```properties
store.file=your-release.keystore
keystore.password=你的密码
key.alias=你的别名
key.password=你的密钥密码
```

不配置时会回退使用 debug 签名（仅供本地测试）。

---

## 🤖 自动构建（GitHub Actions）

仓库已配置 [`.github/workflows/build.yml`](./.github/workflows/build.yml)：

| 触发 | 行为 |
| --- | --- |
| push 到 `main` / 提交 PR | 自动编译 Meta 版 **debug** APK，作为 Artifacts 上传（在 Actions 运行页底部下载） |
| 手动触发 | 同上（Actions → Build APK → Run workflow） |
| 推送 `v*` 标签 | 额外编译 **release** APK，并自动创建 Release 附上安装包 |

想让它产出**你自己密钥签名的 release 包**，在仓库设置里加 4 个 Secrets
（Settings → Secrets and variables → Actions）即可：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | 密钥库文件转 base64：`base64 -w0 your.keystore` |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 别名 |
| `KEY_PASSWORD` | 密钥密码 |

未配置时会回退成 debug 签名（仅供测试）。

> 说明：如果某个 tag 下**已经存在 Release**（例如手工签名的正式版），
> CI 会跳过上传，**不会覆盖你手工签名的安装包**。
## 📖 更多文档

开发细节、实现原理、完整改动清单见 **[SIMPLE_MODE_NOTES.md](./SIMPLE_MODE_NOTES.md)**。

原版 CMFA 的说明保留在 **[README-CMFA-upstream.md](./README-CMFA-upstream.md)**。

---

## ⚠️ 免责声明

本项目仅供学习与技术交流使用，请遵守你所在地区的法律法规，
不要用于任何非法用途。订阅、节点等资源均由使用者自行提供，与本项目无关。

---

## 📄 开源协议与致谢

本项目基于 **GNU General Public License v3.0** 发布（见 [LICENSE](./LICENSE)），
与原项目保持一致。使用、修改、分发请遵守 GPL-3.0：**保留版权声明、开源修改后的源码**。

特别感谢：

- [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) —— 本项目的基础
- [mihomo](https://github.com/MetaCubeX/mihomo) —— 提供核心（Clash Meta）内核
- 以及 Clash / Clash.Meta 生态的所有贡献者

---

## English Summary

**Clash Meta Simple** is a beginner-friendly fork of Clash Meta for Android featuring:

- A **Simple Mode home screen** with two explicit start buttons (rule-based / global), animated status orb, persistent routing mode, live node latency and one-tap subscription update
- A **graphical custom-rule editor**: add/reorder/swipe-delete rules, built-in & user templates, import/export/share, **local rule testing (IPv4/IPv6)**, and **real per-rule hit counters** read from the mihomo core
- **Per-subscription rule storage**, so switching profiles does not mix rules
- A **node manager** with latency test, auto-pick-fastest, manual selection, node-group switching and provider refresh

Custom rules are *prepended* to the subscription rules by the native layer, with an
automatic fallback that reloads without them if they fail to parse — a bad rule can
never take the tunnel down.

Licensed under **GPL-3.0**, same as the upstream project.