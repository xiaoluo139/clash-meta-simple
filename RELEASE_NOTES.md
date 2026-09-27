## Clash Meta Simple v2.11.35

本次重点：**首页交互重做** + **内置 IP 检测**。

### 🔄 首页交互调整

- **大圆环 = 总开关**（截图里圈的那个）
  - 以前它只是状态指示、点不动，现在**可以直接点击**
  - 未连接时点它 → 按当前选中的模式启动
  - 已连接时点它 → 断开代理
- **下面两张卡片 = 模式选择**
  - 「规则模式」「全局模式」
  - 点击**只切换模式，不会触发启动**
  - 选中的那张会**高亮并显示勾选**，当前模式一目了然
  - 运行中切换模式会实时生效，不需要重连
- 顶部状态区会显示「当前：规则模式」等文字
- 只有**大圆环**会触发启动/停止，避免误触

### 🌐 新增内置 IP 检测

- 首页新增「IP 检测」入口，**在应用内直接打开** <https://ip.skk.moe/>
- 无需跳转浏览器，方便检查当前出口 IP 与连通性
- 标题栏有刷新按钮，返回键可回退网页历史

### 📦 下载哪个

| 文件 | 适用 |
| --- | --- |
| `cmfa-2.11.35-meta-arm64-v8a-release.apk` | **推荐**，绝大多数现代手机（Meta 版） |
| `cmfa-2.11.35-meta-universal-release.apk` | 不确定机型就选它（含全部 ABI） |
| `cmfa-2.11.35-meta-armeabi-v7a-release.apk` | 老款 32 位设备 |
| `cmfa-2.11.35-meta-x86 / x86_64-release.apk` | 模拟器 |
| `cmfa-2.11.35-alpha-*.apk` | Alpha 通道版本（与原版 CMFA 的 Alpha 分包一致） |

### ⚙️ 说明

- **包名已改为独立标识，可与官方版共存**：
  - Meta 版：`com.github.xiaoluo139.clashsimple.meta`
  - Alpha 版：`com.github.xiaoluo139.clashsimple.alpha`
- 应用名显示为 **Clash Simple** / **Clash Simple Alpha**，不会和官方 App 混淆
- 版本 `2.11.35`（与上一版相同，安装包已替换）
- 支持 Android 5.0（API 21）及以上
- 与上一版签名相同；**与官方 ClashMetaForAndroid 可同时安装**（包名不同，不再冲突）
- 若装过本项目 2.11.35 之前的旧包（旧包名），需先卸载一次旧包
- 首次启动需授予 VPN 权限，然后在「当前订阅」导入订阅链接

### ⚠️ 免责声明

仅供学习与技术交流，请遵守当地法律法规，勿用于非法用途。订阅与节点资源由使用者自行提供。

### 📄 协议

GPL-3.0，与上游一致。感谢 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)
与 [mihomo](https://github.com/MetaCubeX/mihomo) 及其贡献者。