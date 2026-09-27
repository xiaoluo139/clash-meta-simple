## Clash Meta Simple v2.11.34-meta

基于 ClashMetaForAndroid 的**新手友好版**，在保留原版全部功能的基础上新增「简易模式」。

### 🎉 本次新增

**简易首页**
- 两个明确的启动按钮：`规则启动代理`（推荐）/ `全局启动代理`
- 状态圆环动画：连接中旋转弧线、已连接呼吸光环与对勾
- 已连接时显示 `停止代理` + `规则 / 全局 / 直连` 分段切换
- 分流模式持久化保存，重启后保持
- 当前节点直接显示延迟，运行中每 5 秒自动刷新
- 一键更新订阅（拉取机场最新节点列表）
- 右上角一键切回「高级模式」（原版完整界面，功能一个不少）

**自定义分流规则（图形化）**
- 添加：只填「域名/IP」+ 选「走哪里」，规则自动生成
- 拖动排序（顺序即优先级）、滑动删除（可撤销）
- 内置 3 个模板，也可保存自己的模板（可应用 / 删除）
- 导入 / 导出 / 系统分享
- 规则测试：输入域名或 IP 本地模拟匹配，支持 IPv4 / IPv6
- **命中统计**：每条规则显示内核记录的真实命中次数
- **规则按订阅分别保存**，换机场不串规则

**节点管理**
- 节点测速（URL 测试，延迟按区间着色）
- 一键自动选择最快节点
- 手动选择节点、切换节点组
- 节点刷新（更新 proxy-provider 后重载列表）
- 排序方式切换：配置顺序 / 按名称 / 按延迟

### 📦 下载哪个

| 文件 | 适用 |
| --- | --- |
| `ClashMeta-Simple-arm64-v8a-release.apk` | **推荐**，绝大多数现代手机 |
| `ClashMeta-Simple-universal-release.apk` | 不确定机型就选它（含全部 ABI） |
| `ClashMeta-Simple-armeabi-v7a-release.apk` | 老款 32 位设备 |
| `ClashMeta-Simple-x86 / x86_64-release.apk` | 模拟器 |

### ⚙️ 说明

- 包名 `com.github.metacubex.clash.meta`，版本 `2.11.34.Meta`
- 支持 Android 5.0（API 21）及以上
- 若安装过原版 CMFA 或旧版本，需先卸载（签名不同）
- 首次启动需授予 VPN 权限，然后在「当前订阅」导入你的订阅链接

### ⚠️ 免责声明

仅供学习与技术交流，请遵守当地法律法规，勿用于非法用途。订阅与节点资源由使用者自行提供。

### 📄 协议

GPL-3.0，与上游一致。感谢 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)
与 [mihomo](https://github.com/MetaCubeX/mihomo) 及其贡献者。