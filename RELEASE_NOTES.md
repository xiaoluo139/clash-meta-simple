## Clash Meta Simple v2.11.35

> 本次为**节点模块重点优化**，安装包已原地替换（版本号不变），
> 已经装过的直接覆盖安装即可。

### 🚀 「自动选择最快节点」重点修复

**之前的问题**：自动选择经常选到**实际超时/不可用**的节点。

**根因**（在内核侧）：mihomo 会按不同「测速地址」分别保存延迟历史，
而原实现用 `for k := range map` 随机取一个地址去读延迟 —— Go 的 map 遍历顺序是随机的，
所以可能读到**过期数据**、甚至**从未测过**的地址，从而返回 `0xffff`（超时值）。
结果是节点列表里好好的节点显示「超时」，自动选择也会选到不可用的节点。

**修复**：
- 原生层改为读取**时间戳最新**的那次测速记录，保证拿到的是最近一次真实结果
- 自动选择只考虑：**非节点组 + 已测速 + 延迟有效（排除超时 65535）**
- 优先选择 **3 秒以内**的节点；若一次测速没有任何可用节点，**自动重测一次**再判断
- 切换后会把节点名和实测延迟一起提示出来，例如「已自动选择：香港 01（45 ms）」

### 🏠 首页新增「自动选择最快节点」

- 首页新增入口，**不用进节点页**就能一键择优
- **全局模式下同样可用**：此时作用于内核的 `GLOBAL` 节点组
- 规则模式下作用于第一个可选择的节点组

### 🎯 手动选择节点更清晰

- 节点页新增提示：「点击任意节点即可切换，当前节点带勾选标记」
- **当前节点整行高亮** + 右侧勾选标记，一眼看出在用哪个
- 延迟配色细化：≤200ms 绿 / ≤500ms 橙 / ≤2s 黄 / >2s 红 / 超时红 / 未测速灰

### 🖥️ 新增 Windows 客户端

同一个 Release 里新增了 Windows 版：**`ClashSimple-Windows-v2.11.35.zip`**

- **原生 WinForms 桌面程序**：解压后双击 `ClashSimple.exe` 即可（不是脚本、不是浏览器套壳）
- 已内置 mihomo 内核（v1.19.31），不需要装 Python / Node / .NET SDK（Win10/11 自带 .NET Framework 4.x）
- 交互与手机版一致：**大圆环 = 总开关**，下面三张卡 = 规则 / 全局 / 直连
- 启动时自动设置 Windows 系统代理，停止时自动还原
- 节点一键测速、**自动选择最快**、点击手动切换
- 订阅链接保存 / 一键更新
- 右上角 **IP 检测**（ip.skk.moe）
- 「自动选择最快」调用内核 `/group/{分组}/delay`，该接口**只返回测通节点**，因此不会选到超时节点

### 📦 下载哪个

| 文件 | 适用 |
| --- | --- |
| `cmfa-2.11.35-meta-arm64-v8a-release.apk` | **推荐**，现代手机（Meta 版） |
| `cmfa-2.11.35-meta-universal-release.apk` | 不确定机型选它（含全部 ABI） |
| `cmfa-2.11.35-meta-armeabi-v7a-release.apk` | 老款 32 位设备 |
| `cmfa-2.11.35-meta-x86 / x86_64-release.apk` | 模拟器 |
| `cmfa-2.11.35-alpha-*.apk` | Alpha 通道版本 |
| `ClashSimple-Windows-v2.11.35.zip` | **Windows 客户端**（含内核，解压即用） |

### ⚙️ 说明

- **包名已与官方版完全区分，可与官方 ClashMetaForAndroid 共存**：
  - Meta：`com.github.xiaoluo139.clashsimple.meta`
  - Alpha：`com.github.xiaoluo139.clashsimple.alpha`
- 应用名：**Clash Simple** / **Clash Simple Alpha**
- 版本 `2.11.35`，与上一版**签名相同**，可直接覆盖安装
- 首次启动需授予 VPN 权限，然后在「当前订阅」导入订阅链接

### ⚠️ 免责声明

仅供学习与技术交流，请遵守当地法律法规，勿用于非法用途。订阅与节点资源由使用者自行提供。

### 📄 协议

GPL-3.0，与上游一致。感谢 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)
与 [mihomo](https://github.com/MetaCubeX/mihomo) 及其贡献者。