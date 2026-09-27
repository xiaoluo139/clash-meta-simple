# Clash Simple for Windows

**原生 Windows 桌面程序**（WinForms，单个 `ClashSimple.exe`），内核使用 **mihomo**，
与 Android 端 *Clash Simple* 配套，交互一致：**大圆环 = 总开关，下面几张卡 = 模式选择**。

## 📦 直接使用

到 [Releases](../../releases) 下载 `ClashSimple-Windows-*.zip`，解压后：

```
ClashSimple-Windows/
├── ClashSimple.exe     ← 双击运行
├── core/
│   └── mihomo.exe      内置内核
└── README.md
```

1. 双击 **`ClashSimple.exe`**
2. 在「订阅」里粘贴订阅链接 → **保存并应用**
3. 点**大圆环**启动代理（会自动设置 Windows 系统代理）
4. 需要时点「**自动选择最快**」
5. 关窗口即停止代理并还原系统代理

> 不需要装 Python / Node / .NET SDK。Windows 10 / 11 自带 .NET Framework 4.x 即可运行。

## ✨ 功能

| 功能 | 说明 |
| --- | --- |
| 一键开关 | 点大圆环启动 / 停止；启动时**自动设置系统代理**，停止时自动还原 |
| 分流模式 | 规则 / 全局 / 直连 三张卡，点击只切模式，不会误触发启动 |
| 节点测速 | 一键测速，列表按延迟着色（≤200 绿 / ≤500 橙 / ≤2s 黄 / 其余红 / 超时红） |
| **自动选择最快** | 只挑「**测通**」的节点，取延迟最低的 |
| 手动选节点 | 双击（或选中后回车）切换，当前节点**加粗**显示 |
| 订阅 | 粘贴链接保存；一键「更新订阅」 |
| **IP 检测** | 右上角一键打开 <https://ip.skk.moe/> |

### 「自动选择最快」为什么不会选到超时节点

调用内核的 `/group/{分组}/delay` 接口，**该接口只返回测速成功的节点**
（超时 / 不可用的节点根本不会出现在返回结果里），所以直接取延迟最低的即可 —— 
从机制上就不可能选中超时节点。

## 🔧 自行编译

不需要 .NET SDK，用 Windows 自带的 `csc.exe` 即可：

```powershell
cd windows
.\build.ps1
```

源码：`src/ClashSimple.cs`（单文件，约 1000 行，WinForms + mihomo REST API）。

### 无界面自检

```powershell
.\ClashSimple.exe --selftest
Get-Content .\data\selftest.log     # 结果写在这里（winexe 没有控制台）
```

自检会：启动内核 → 读版本 → 列分组/节点 → 切换模式 → 测速 → 停止。

## 📁 目录

```
windows/
├── src/ClashSimple.cs   主程序源码（原生 WinForms）
├── build.ps1            一键编译
├── README.md            本文件
├── SimpleClash.ps1      （备用）PowerShell + 浏览器界面的脚本版
├── ui.html              （备用）脚本版的界面
├── Start.bat            （备用）脚本版启动器
├── core/                mihomo.exe
└── data/                运行时生成：订阅、config.yaml、设置、日志
```

## ⚙️ 说明

- 默认混合代理端口 **7890**，控制端口 **9090**（仅监听 127.0.0.1）
- 订阅原文保存为 `data/profile.yaml`；程序生成的 `data/config.yaml` 会**剔除订阅里自带的
  端口/控制器字段**再写入自己的配置，避免 YAML 重复键报错
- 只设置**系统代理（WinINET）**，不走 TUN；极少数不读系统代理的程序需自行配置

## ❓ 常见问题

**Q：提示"没有测通的节点"？**
A：说明当前节点组里所有节点都连不上。确认订阅有效、网络正常，或换个节点组再试。

**Q：杀毒软件报警？**
A：单文件 exe + 自行下载的内核，属常见误报。可自行核对 mihomo 官方校验值。

## ⚠️ 免责声明

仅供学习与技术交流，请遵守当地法律法规。订阅与节点资源由使用者自行提供。

## 📄 协议

GPL-3.0，与上游 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) 一致。
内核来自 [mihomo](https://github.com/MetaCubeX/mihomo)。