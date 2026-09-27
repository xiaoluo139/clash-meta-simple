// ClashSimple for Windows - 原生 WinForms 客户端
// 与 Android 端 Clash Simple 配套，内核使用 mihomo。
//
// 编译（不需要装 .NET SDK，Windows 自带 csc）：
//   csc.exe /target:winexe /out:ClashSimple.exe /r:System.Web.Extensions.dll /r:System.Windows.Forms.dll /r:System.Drawing.dll ClashSimple.cs
//
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using Microsoft.Win32;

namespace ClashSimple
{
    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            // 必须在任何 HTTPS 请求之前执行（默认未开启 TLS 1.2）
            Net.Configure();

            if (args.Length > 0 && args[0] == "--selftest")
            {
                SelfTest.Run();
                return;
            }

            if (args.Length > 0 && args[0] == "--testhttps")
            {
                SelfTest.TestHttps(args.Length > 1 ? args[1] : null);
                return;
            }

            if (args.Length > 0 && args[0] == "--testcore")
            {
                SelfTest.TestCore();
                return;
            }

            if (args.Length > 0 && args[0] == "--testsub")
            {
                SelfTest.TestSub(args.Length > 1 ? args[1] : null);
                return;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    /// <summary>运行环境路径与设置</summary>
    internal static class Env
    {
        public static readonly string Root =
            Path.GetDirectoryName(Application.ExecutablePath);

        public static readonly string CoreDir = Path.Combine(Root, "core");
        public static readonly string DataDir = Path.Combine(Root, "data");
        public static readonly string CoreExe = Path.Combine(CoreDir, "mihomo.exe");
        public static readonly string ConfigFile = Path.Combine(DataDir, "config.yaml");
        public static readonly string ProfileFile = Path.Combine(DataDir, "profile.yaml");
        public static readonly string SettingsFile = Path.Combine(DataDir, "settings.json");

        public const int MixedPort = 7890;
        public const int ApiPort = 9090;
        public const string ApiSecret = "simpleclash";
        public const string TestUrl = "https://www.gstatic.com/generate_204";

        public static void EnsureData()
        {
            if (!Directory.Exists(DataDir)) Directory.CreateDirectory(DataDir);
        }

        public static Settings LoadSettings()
        {
            try
            {
                if (File.Exists(SettingsFile))
                {
                    var json = File.ReadAllText(SettingsFile, Encoding.UTF8);
                    var s = new JavaScriptSerializer().Deserialize<Settings>(json);
                    if (s != null) return s;
                }
            }
            catch { }

            return new Settings();
        }

        public static void SaveSettings(Settings s)
        {
            EnsureData();
            File.WriteAllText(SettingsFile, new JavaScriptSerializer().Serialize(s), Encoding.UTF8);
        }
    }

    internal class Settings
    {
        public string profileUrl { get; set; }
        public string profileName { get; set; }
        public string mode { get; set; }

        public Settings()
        {
            profileUrl = "";
            profileName = "";
            mode = "rule";
        }
    }
}
namespace ClashSimple
{
    /// <summary>mihomo 内核的启动、停止与配置生成</summary>
    internal static partial class Core
    {
        private static Process _process;

        public static bool Running
        {
            get { return _process != null && !_process.HasExited; }
        }

        public static void EnsureCore()
        {
            EnsureCore(false);
        }

        public static void EnsureCore(bool force)
        {
            if (!force && File.Exists(Env.CoreExe)) return;

            if (!Directory.Exists(Env.CoreDir)) Directory.CreateDirectory(Env.CoreDir);

            string api = "https://api.github.com/repos/MetaCubeX/mihomo/releases/latest";
            string json;

            json = Net.DownloadString(api);

            var ser = new JavaScriptSerializer();
            var release = ser.Deserialize<Dictionary<string, object>>(json);
            var assets = (ArrayList)release["assets"];

            string url = null;

            foreach (var a in assets)
            {
                var map = (Dictionary<string, object>)a;
                string name = Convert.ToString(map["name"]);
                if (name.StartsWith("mihomo-windows-amd64-compatible-") && name.EndsWith(".zip"))
                {
                    url = Convert.ToString(map["browser_download_url"]);
                    break;
                }
            }

            if (url == null)
                throw new Exception("找不到 mihomo 的 Windows 版本，请手动把 mihomo.exe 放到 core 目录");

            string zip = Path.Combine(Env.CoreDir, "mihomo.zip");

            Net.DownloadFile(url, zip);

            string extractDir = Path.Combine(Env.CoreDir, "extract");
            if (Directory.Exists(extractDir)) Directory.Delete(extractDir, true);
            System.IO.Compression.ZipFile.ExtractToDirectory(zip, extractDir);
            File.Delete(zip);

            string exe = null;
            foreach (var f in Directory.GetFiles(extractDir, "*.exe")) { exe = f; break; }
            if (exe == null) throw new Exception("解压后没有找到可执行文件");

            File.Copy(exe, Env.CoreExe, true);
            Directory.Delete(extractDir, true);
        }
    }
}
namespace ClashSimple
{
    internal static partial class Core
    {
        private static readonly string[] OverrideKeys = new string[]
        {
            "mixed-port", "port", "socks-port", "redir-port", "tproxy-port",
            "external-controller", "external-controller-tls", "external-controller-cors",
            "secret", "allow-lan", "bind-address"
        };

        private const string DefaultProfile =
            "proxies: []\r\n" +
            "proxy-groups:\r\n" +
            "  - name: PROXY\r\n" +
            "    type: select\r\n" +
            "    proxies:\r\n" +
            "      - DIRECT\r\n" +
            "rules:\r\n" +
            "  - MATCH,DIRECT\r\n";

        public static void BuildConfig()
        {
            Env.EnsureData();

            string profile = File.Exists(Env.ProfileFile)
                ? File.ReadAllText(Env.ProfileFile, Encoding.UTF8)
                : DefaultProfile;

            var kept = new StringBuilder();
            string[] lines = profile.Replace("\r\n", "\n").Split('\n');

            foreach (var line in lines)
            {
                bool skip = false;

                foreach (var key in OverrideKeys)
                {
                    if (line.StartsWith(key + ":", StringComparison.OrdinalIgnoreCase))
                    {
                        skip = true;
                        break;
                    }
                }

                if (!skip) kept.Append(line).Append("\r\n");
            }

            var sb = new StringBuilder();
            sb.Append("# 由 ClashSimple 生成，请勿手动修改（订阅原文保存在 profile.yaml）\r\n");
            sb.Append("mixed-port: ").Append(Env.MixedPort).Append("\r\n");
            sb.Append("allow-lan: false\r\n");
            sb.Append("external-controller: 127.0.0.1:").Append(Env.ApiPort).Append("\r\n");
            sb.Append("secret: \"").Append(Env.ApiSecret).Append("\"\r\n");
            sb.Append("log-level: warning\r\n");
            sb.Append(kept);

            File.WriteAllText(Env.ConfigFile, sb.ToString(), new UTF8Encoding(false));
        }
    }
}
namespace ClashSimple
{
    internal static partial class Core
    {
        public static void Start()
        {
            if (Running) return;

            EnsureCore();
            BuildConfig();

            var psi = new ProcessStartInfo(Env.CoreExe);
            psi.Arguments = string.Format("-d \"{0}\" -f \"{1}\" -ext-ctl 127.0.0.1:{2} -secret {3}",
                Env.DataDir, Env.ConfigFile, Env.ApiPort, Env.ApiSecret);
            psi.WorkingDirectory = Env.Root;
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;

            _process = Process.Start(psi);

            for (int i = 0; i < 60; i++)
            {
                Thread.Sleep(250);
                if (_process.HasExited) throw new Exception("内核启动失败，请检查 data 目录");
                try { Api("GET", "/version", null); return; }
                catch { }
            }

            throw new Exception("内核启动超时");
        }

        public static void Stop()
        {
            SystemProxy.Disable();

            try
            {
                if (Running) _process.Kill();
            }
            catch { }

            _process = null;
        }
    }
}
namespace ClashSimple
{
    internal static partial class Core
    {
        private static JavaScriptSerializer Ser = new JavaScriptSerializer();

        /// <summary>调用 mihomo 的 RESTful API</summary>
        public static string Api(string method, string path, string body)
        {
            var req = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:" + Env.ApiPort + path);
            req.Method = method;
            req.Headers.Add("Authorization", "Bearer " + Env.ApiSecret);
            req.Timeout = 60000;
            req.ReadWriteTimeout = 60000;

            if (body != null)
            {
                req.ContentType = "application/json";
                byte[] data = Encoding.UTF8.GetBytes(body);
                req.ContentLength = data.Length;
                using (var s = req.GetRequestStream()) s.Write(data, 0, data.Length);
            }

            using (var res = (HttpWebResponse)req.GetResponse())
            using (var reader = new StreamReader(res.GetResponseStream(), Encoding.UTF8))
            {
                return reader.ReadToEnd();
            }
        }

        public static Dictionary<string, object> ApiJson(string method, string path, string body)
        {
            string text = Api(method, path, body);
            if (string.IsNullOrEmpty(text)) return new Dictionary<string, object>();
            return Ser.Deserialize<Dictionary<string, object>>(text);
        }

        public static string GetVersion()
        {
            try { return Convert.ToString(ApiJson("GET", "/version", null)["version"]); }
            catch { return ""; }
        }

        public static string GetMode()
        {
            try { return Convert.ToString(ApiJson("GET", "/configs", null)["mode"]); }
            catch { return ""; }
        }

        public static void SetMode(string mode)
        {
            Api("PATCH", "/configs", "{\"mode\":\"" + mode + "\"}");

            var s = Env.LoadSettings();
            s.mode = mode;
            Env.SaveSettings(s);
        }
    }
}
namespace ClashSimple
{
    internal class NodeInfo
    {
        public string Name;
        public string Type;
        public int Delay;
        public bool IsGroup;
        public bool Current;
    }

    internal static partial class Core
    {
        public static List<string> GetGroupNames()
        {
            var result = new List<string>();
            var data = ApiJson("GET", "/proxies", null);
            var proxies = (Dictionary<string, object>)data["proxies"];

            foreach (var kv in proxies)
            {
                var p = (Dictionary<string, object>)kv.Value;
                if (!p.ContainsKey("all") || p["all"] == null) continue;
                if (((ArrayList)p["all"]).Count == 0) continue;

                string type = Convert.ToString(p["type"]);
                if (type == "Selector" || type == "URLTest" || type == "Fallback" ||
                    type == "LoadBalance" || type == "Relay")
                {
                    result.Add(Convert.ToString(p["name"]));
                }
            }

            return result;
        }

        public static string PickGroup(string preferred)
        {
            var names = GetGroupNames();
            if (names.Count == 0) return null;

            if (!string.IsNullOrEmpty(preferred) && names.Contains(preferred)) return preferred;
            if (names.Contains("GLOBAL")) return "GLOBAL";

            return names[0];
        }

        public static List<NodeInfo> GetNodes(string group, out string now)
        {
            now = "";
            var result = new List<NodeInfo>();
            if (string.IsNullOrEmpty(group)) return result;

            var data = ApiJson("GET", "/proxies", null);
            var proxies = (Dictionary<string, object>)data["proxies"];
            if (!proxies.ContainsKey(group)) return result;

            var g = (Dictionary<string, object>)proxies[group];
            if (g.ContainsKey("now")) now = Convert.ToString(g["now"]);
            if (!g.ContainsKey("all") || g["all"] == null) return result;

            foreach (var item in (ArrayList)g["all"])
            {
                string name = Convert.ToString(item);
                if (!proxies.ContainsKey(name)) continue;

                var p = (Dictionary<string, object>)proxies[name];
                var node = new NodeInfo();
                node.Name = name;
                node.Type = Convert.ToString(p["type"]);
                node.Current = (name == now);
                node.IsGroup = p.ContainsKey("all") && p["all"] != null && ((ArrayList)p["all"]).Count > 0;

                if (p.ContainsKey("history") && p["history"] != null)
                {
                    var history = (ArrayList)p["history"];
                    if (history.Count > 0)
                    {
                        var last = (Dictionary<string, object>)history[history.Count - 1];
                        node.Delay = Convert.ToInt32(last["delay"], CultureInfo.InvariantCulture);
                    }
                }

                result.Add(node);
            }

            return result;
        }
    }
}
namespace ClashSimple
{
    internal static partial class Core
    {
        /// <summary>
        /// 对节点组测速。
        /// 注意：mihomo 的 /group/{name}/delay 只返回「测通」的节点，
        /// 超时/不可用的不会出现在结果里。
        /// </summary>
        public static Dictionary<string, object> TestDelay(string group)
        {
            string path = "/group/" + Uri.EscapeDataString(group) +
                          "/delay?url=" + Uri.EscapeDataString(Env.TestUrl) + "&timeout=3000";

            return ApiJson("GET", path, null);
        }

        public static void SelectNode(string group, string name)
        {
            Api("PUT", "/proxies/" + Uri.EscapeDataString(group),
                "{\"name\":" + new JavaScriptSerializer().Serialize(name) + "}");
        }

        /// <summary>测速后选择延迟最低的「能通」节点</summary>
        public static NodeInfo SelectFastest(string group)
        {
            var delays = TestDelay(group);

            string best = null;
            int bestDelay = int.MaxValue;

            foreach (var kv in delays)
            {
                int d = Convert.ToInt32(kv.Value, CultureInfo.InvariantCulture);
                if (d <= 0 || d >= 65535) continue;
                if (d < bestDelay) { bestDelay = d; best = kv.Key; }
            }

            if (best == null) return null;

            SelectNode(group, best);

            var node = new NodeInfo();
            node.Name = best;
            node.Delay = bestDelay;
            return node;
        }

        public static void SaveSubscription(string url)
        {
            Env.EnsureData();

            string content = Net.DownloadString(url);

            File.WriteAllText(Env.ProfileFile, content, new UTF8Encoding(false));

            var s = Env.LoadSettings();
            s.profileUrl = url;
            if (string.IsNullOrEmpty(s.profileName)) s.profileName = "订阅";
            Env.SaveSettings(s);
        }
    }
}
namespace ClashSimple
{
    /// <summary>Windows 系统代理（WinINET）的开关</summary>
    internal static class SystemProxy
    {
        private const string Key = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
        private static bool _enabled;

        public static bool Enabled { get { return _enabled; } }

        public static void Enable()
        {
            if (_enabled) return;
            Apply(true);
            _enabled = true;
        }

        public static void Disable()
        {
            if (!_enabled) return;
            Apply(false);
            _enabled = false;
        }

        private static void Apply(bool on)
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(Key, true))
                {
                    if (k == null) return;

                    if (on)
                    {
                        k.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
                        k.SetValue("ProxyServer", "127.0.0.1:" + Env.MixedPort, RegistryValueKind.String);
                        k.SetValue("ProxyOverride", "<local>", RegistryValueKind.String);
                    }
                    else
                    {
                        k.SetValue("ProxyEnable", 0, RegistryValueKind.DWord);
                    }
                }
            }
            catch { }
        }
    }
}
namespace ClashSimple
{
    /// <summary>圆环总开关（自绘）</summary>
    internal class OrbControl : Control
    {
        public bool On;

        public OrbControl()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                     ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
            Size = new Size(150, 150);
            Cursor = Cursors.Hand;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Parent != null ? Parent.BackColor : Color.White);

            int d = Math.Min(Width, Height) - 10;
            var rect = new Rectangle((Width - d) / 2, (Height - d) / 2, d, d);

            Color c1 = On ? Color.FromArgb(62, 118, 180) : Color.FromArgb(158, 164, 172);
            Color c2 = On ? Color.FromArgb(28, 62, 110) : Color.FromArgb(122, 128, 137);

            using (var brush = new LinearGradientBrush(rect, c1, c2, 45f))
                g.FillEllipse(brush, rect);

            using (var pen = new Pen(Color.White, 7f))
            {
                pen.StartCap = LineCap.Round;
                pen.EndCap = LineCap.Round;

                float r = d * 0.26f;
                float cx = Width / 2f;
                float cy = Height / 2f;

                g.DrawArc(pen, cx - r, cy - r, r * 2, r * 2, -55f, 290f);
                g.DrawLine(pen, cx, cy - r * 1.05f, cx, cy - r * 0.2f);
            }
        }
    }

    /// <summary>可点击的「模式卡片」</summary>
    internal class ModeCard : Panel
    {
        public string Mode;
        private Label _title;
        private Label _desc;
        private Label _check;

        public ModeCard(string mode, string title, string desc)
        {
            Mode = mode;
            Height = 62;
            Dock = DockStyle.Top;
            Cursor = Cursors.Hand;
            BackColor = Color.White;
            Padding = new Padding(12, 8, 12, 8);
            Margin = new Padding(0, 6, 0, 0);

            _title = new Label();
            _title.Text = title;
            _title.Font = new Font("Microsoft YaHei UI", 10.5f, FontStyle.Bold);
            _title.AutoSize = true;
            _title.Location = new Point(14, 10);
            Controls.Add(_title);

            _desc = new Label();
            _desc.Text = desc;
            _desc.ForeColor = Color.FromArgb(120, 126, 136);
            _desc.AutoSize = true;
            _desc.Location = new Point(14, 34);
            Controls.Add(_desc);

            _check = new Label();
            _check.Text = "✓";
            _check.Font = new Font("Microsoft YaHei UI", 12f, FontStyle.Bold);
            _check.ForeColor = Color.FromArgb(30, 67, 118);
            _check.AutoSize = true;
            _check.Visible = false;
            Controls.Add(_check);

            Resize += delegate { _check.Location = new Point(Width - 34, 18); };
        }

        public void SetActive(bool active)
        {
            _check.Visible = active;
            BackColor = active ? Color.FromArgb(230, 236, 247) : Color.White;
            Invalidate();
        }
    }
}
namespace ClashSimple
{
    internal partial class MainForm : Form
    {
        private OrbControl _orb;
        private Label _stateText;
        private Label _hintText;
        private Label _metaText;
        private ModeCard _cardRule;
        private ModeCard _cardGlobal;
        private ModeCard _cardDirect;
        private ListView _nodeList;
        private Label _groupText;
        private TextBox _profileUrl;
        private Label _profileName;
        private Button _btnTest;
        private Button _btnAuto;
        private Label _status;
        private System.Windows.Forms.Timer _timer;
        private string _group;

        private static readonly Color Bg = Color.FromArgb(244, 246, 250);
        private static readonly Color Primary = Color.FromArgb(30, 67, 118);

        public MainForm()
        {
            Text = "Clash Simple";
            ClientSize = new Size(700, 880);
            MinimumSize = new Size(700, 700);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Bg;
            Font = new Font("Microsoft YaHei UI", 9f);

            var host = new Panel();
            host.Dock = DockStyle.Fill;
            host.AutoScroll = true;
            host.BackColor = Bg;
            Controls.Add(host);

            BuildHeader(host);
            BuildOrb(host);
            BuildModes(host);
            BuildNodes(host);
            BuildProfile(host);
            BuildStatusBar();

            _timer = new System.Windows.Forms.Timer();
            _timer.Interval = 2500;
            _timer.Tick += delegate { RefreshState(); };
            _timer.Start();

            Shown += delegate { RefreshState(); };
            FormClosing += delegate { Core.Stop(); };
        }

        private void BuildHeader(Panel host)
        {
            var title = new Label();
            title.Text = "Clash Simple  ·  基于 mihomo 内核";
            title.Font = new Font("Microsoft YaHei UI", 13f, FontStyle.Bold);
            title.AutoSize = true;
            title.Location = new Point(20, 18);
            host.Controls.Add(title);

            var btnIp = MakeButton("IP 检测", 580, 14, 96, false);
            btnIp.Click += delegate { Process.Start("https://ip.skk.moe/"); };
            host.Controls.Add(btnIp);
        }

        private void BuildOrb(Panel host)
        {
            _orb = new OrbControl();
            _orb.Location = new Point((700 - 150) / 2 - 20, 60);
            _orb.Click += delegate { ToggleProxy(); };
            host.Controls.Add(_orb);

            _stateText = new Label();
            _stateText.Text = "未连接";
            _stateText.Font = new Font("Microsoft YaHei UI", 14f, FontStyle.Bold);
            _stateText.AutoSize = false;
            _stateText.TextAlign = ContentAlignment.MiddleCenter;
            _stateText.SetBounds(0, 214, 680, 30);
            host.Controls.Add(_stateText);

            _hintText = new Label();
            _hintText.Text = "点击圆环启动代理";
            _hintText.ForeColor = Color.FromArgb(120, 126, 136);
            _hintText.AutoSize = false;
            _hintText.TextAlign = ContentAlignment.MiddleCenter;
            _hintText.SetBounds(0, 244, 680, 22);
            host.Controls.Add(_hintText);

            _metaText = new Label();
            _metaText.ForeColor = Color.FromArgb(120, 126, 136);
            _metaText.AutoSize = false;
            _metaText.TextAlign = ContentAlignment.MiddleCenter;
            _metaText.SetBounds(0, 266, 680, 22);
            host.Controls.Add(_metaText);
        }

        private void BuildModes(Panel host)
        {
            _cardRule = new ModeCard("rule", "规则模式", "按订阅规则分流（推荐）");
            _cardGlobal = new ModeCard("global", "全局模式", "所有流量都走代理");
            _cardDirect = new ModeCard("direct", "直连模式", "不经过代理");

            _cardRule.SetBounds(20, 300, 640, 62);
            _cardGlobal.SetBounds(20, 368, 640, 62);
            _cardDirect.SetBounds(20, 436, 640, 62);

            AddModeCard(host, _cardRule);
            AddModeCard(host, _cardGlobal);
            AddModeCard(host, _cardDirect);
        }

        private void AddModeCard(Panel host, ModeCard card)
        {
            card.Dock = DockStyle.None;
            card.Click += delegate { ChangeMode(card.Mode); };
            foreach (Control c in card.Controls) c.Click += delegate { ChangeMode(card.Mode); };
            host.Controls.Add(card);
        }
    }
}
namespace ClashSimple
{
    internal partial class MainForm
    {
        private Button MakeButton(string text, int x, int y, int w, bool primary)
        {
            var b = new Button();
            b.Text = text;
            b.SetBounds(x, y, w, 32);
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderSize = 1;
            b.FlatAppearance.BorderColor = Color.FromArgb(215, 220, 230);
            b.BackColor = primary ? Primary : Color.White;
            b.ForeColor = primary ? Color.White : Color.FromArgb(30, 34, 40);
            b.Cursor = Cursors.Hand;
            return b;
        }

        private void BuildNodes(Panel host)
        {
            var panel = new Panel();
            panel.SetBounds(20, 512, 640, 220);
            panel.BackColor = Color.White;
            panel.BorderStyle = BorderStyle.FixedSingle;
            host.Controls.Add(panel);

            var title = new Label();
            title.Text = "节点";
            title.Font = new Font("Microsoft YaHei UI", 10.5f, FontStyle.Bold);
            title.AutoSize = true;
            title.Location = new Point(14, 12);
            panel.Controls.Add(title);

            _groupText = new Label();
            _groupText.ForeColor = Color.FromArgb(120, 126, 136);
            _groupText.AutoSize = true;
            _groupText.Location = new Point(60, 15);
            panel.Controls.Add(_groupText);

            _btnTest = MakeButton("测速", 440, 8, 84, false);
            _btnTest.Click += delegate { RunTest(); };
            panel.Controls.Add(_btnTest);

            _btnAuto = MakeButton("自动选择最快", 532, 8, 96, true);
            _btnAuto.Click += delegate { RunAuto(); };
            panel.Controls.Add(_btnAuto);

            _nodeList = new ListView();
            _nodeList.SetBounds(10, 48, 618, 162);
            _nodeList.View = View.Details;
            _nodeList.FullRowSelect = true;
            _nodeList.MultiSelect = false;
            _nodeList.HeaderStyle = ColumnHeaderStyle.Nonclickable;
            _nodeList.Columns.Add("节点", 380);
            _nodeList.Columns.Add("类型", 110);
            _nodeList.Columns.Add("延迟", 110, HorizontalAlignment.Right);
            _nodeList.MouseDoubleClick += delegate { SelectCurrent(); };
            _nodeList.ItemActivate += delegate { SelectCurrent(); };
            panel.Controls.Add(_nodeList);
        }

        private void BuildProfile(Panel host)
        {
            var panel = new Panel();
            panel.SetBounds(20, 744, 640, 108);
            panel.BackColor = Color.White;
            panel.BorderStyle = BorderStyle.FixedSingle;
            host.Controls.Add(panel);

            var title = new Label();
            title.Text = "订阅";
            title.Font = new Font("Microsoft YaHei UI", 10.5f, FontStyle.Bold);
            title.AutoSize = true;
            title.Location = new Point(14, 12);
            panel.Controls.Add(title);

            _profileName = new Label();
            _profileName.ForeColor = Color.FromArgb(120, 126, 136);
            _profileName.AutoSize = true;
            _profileName.Location = new Point(60, 15);
            panel.Controls.Add(_profileName);

            _profileUrl = new TextBox();
            _profileUrl.SetBounds(14, 44, 400, 26);
            _profileUrl.BorderStyle = BorderStyle.FixedSingle;
            panel.Controls.Add(_profileUrl);

            var btnSave = MakeButton("保存并应用", 424, 42, 96, true);
            btnSave.Click += delegate { SaveProfile(); };
            panel.Controls.Add(btnSave);

            var btnUpdate = MakeButton("更新订阅", 528, 42, 96, false);
            btnUpdate.Click += delegate { UpdateProfile(); };
            panel.Controls.Add(btnUpdate);

            var tip = new Label();
            tip.Text = "支持 Clash / mihomo 订阅；保存后自动重启内核生效";
            tip.ForeColor = Color.FromArgb(140, 146, 156);
            tip.AutoSize = true;
            tip.Location = new Point(14, 78);
            panel.Controls.Add(tip);
        }

        private void BuildStatusBar()
        {
            _status = new Label();
            _status.Dock = DockStyle.Bottom;
            _status.Height = 26;
            _status.TextAlign = ContentAlignment.MiddleLeft;
            _status.Padding = new Padding(12, 0, 0, 0);
            _status.ForeColor = Color.FromArgb(110, 116, 126);
            _status.Text = "就绪";
            Controls.Add(_status);
        }
    }
}
namespace ClashSimple
{
    internal partial class MainForm
    {
        private bool _busy;
        private bool _refreshing;

        private void SetStatus(string text)
        {
            try { BeginInvoke((Action)delegate { _status.Text = text; }); }
            catch { }
        }

        private void EnableUi(bool on)
        {
            _btnTest.Enabled = on;
            _btnAuto.Enabled = on;
            _orb.Enabled = on;
            _busy = !on;
        }

        private void RunAsync(string busyText, Action work)
        {
            if (_busy) return;

            EnableUi(false);
            _status.Text = busyText;

            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    work();
                }
                catch (Exception ex)
                {
                    SetStatus("出错：" + Net.Friendly(ex));
                }
                finally
                {
                    try
                    {
                        BeginInvoke((Action)delegate
                        {
                            EnableUi(true);
                            RefreshState();
                        });
                    }
                    catch { }
                }
            });
        }

        private void RefreshState()
        {
            if (_refreshing || _busy) return;
            _refreshing = true;

            ThreadPool.QueueUserWorkItem(delegate
            {
                string version = "";
                string mode = "";
                List<NodeInfo> nodes = new List<NodeInfo>();
                string now = "";
                bool running = Core.Running;

                try
                {
                    if (running)
                    {
                        version = Core.GetVersion();
                        mode = Core.GetMode();
                        _group = Core.PickGroup(_group);
                        nodes = Core.GetNodes(_group, out now);
                    }
                }
                catch { }

                try
                {
                    BeginInvoke((Action)delegate { ApplyState(running, version, mode, _group, now, nodes); });
                }
                catch { }

                _refreshing = false;
            });
        }

        private void ApplyState(bool running, string version, string mode, string group, string now, List<NodeInfo> nodes)
        {
            _orb.On = running;
            _orb.Invalidate();

            _stateText.Text = running ? "已连接" : "未连接";
            _hintText.Text = running ? "点击圆环断开代理" : "点击圆环启动代理";

            string modeName = "规则模式";
            if (mode == "global") modeName = "全局模式";
            if (mode == "direct") modeName = "直连模式";

            _metaText.Text = running
                ? (modeName + " · " + (string.IsNullOrEmpty(now) ? "未选择节点" : now) + (string.IsNullOrEmpty(version) ? "" : (" · " + version)))
                : (modeName + (string.IsNullOrEmpty(version) ? "" : (" · " + version)));

            _cardRule.SetActive(mode == "rule");
            _cardGlobal.SetActive(mode == "global");
            _cardDirect.SetActive(mode == "direct");

            _groupText.Text = string.IsNullOrEmpty(group) ? "" : ("分组：" + group);

            var settings = Env.LoadSettings();
            _profileName.Text = string.IsNullOrEmpty(settings.profileName) ? "未设置" : settings.profileName;
            if (!_profileUrl.Focused && !string.IsNullOrEmpty(settings.profileUrl))
                _profileUrl.Text = settings.profileUrl;

            _nodeList.BeginUpdate();
            _nodeList.Items.Clear();

            if (!running)
            {
                _nodeList.Items.Add(new ListViewItem(new string[] { "先点击圆环启动代理", "", "" }));
            }
            else if (nodes.Count == 0)
            {
                _nodeList.Items.Add(new ListViewItem(new string[] { "没有可用的节点（请先设置订阅）", "", "" }));
            }
            else
            {
                foreach (var n in nodes)
                {
                    string delay = n.IsGroup ? "—" : DelayText(n.Delay);
                    var item = new ListViewItem(new string[] { n.Name, n.IsGroup ? "节点组" : n.Type, delay });
                    if (n.Current) item.Font = new Font(_nodeList.Font, FontStyle.Bold);
                    if (!n.IsGroup) item.ForeColor = DelayColor(n.Delay);
                    item.Tag = n;
                    _nodeList.Items.Add(item);
                }
            }

            _nodeList.EndUpdate();
        }

        private static string DelayText(int d)
        {
            if (d <= 0) return "未测速";
            if (d >= 65535) return "超时";
            return d + " ms";
        }

        private static Color DelayColor(int d)
        {
            if (d <= 0) return Color.FromArgb(158, 158, 158);
            if (d >= 65535) return Color.FromArgb(198, 40, 40);
            if (d <= 200) return Color.FromArgb(46, 125, 50);
            if (d <= 500) return Color.FromArgb(239, 108, 0);
            if (d <= 2000) return Color.FromArgb(249, 168, 37);
            return Color.FromArgb(198, 40, 40);
        }
    }
}
namespace ClashSimple
{
    internal partial class MainForm
    {
        private void ToggleProxy()
        {
            RunAsync(Core.Running ? "正在停止…" : "正在启动…", delegate
            {
                if (Core.Running)
                {
                    Core.Stop();
                    SetStatus("代理已停止，系统代理已还原");
                }
                else
                {
                    Core.Start();
                    SystemProxy.Enable();
                    SetStatus("代理已启动，系统代理已开启");
                }
            });
        }

        private void ChangeMode(string mode)
        {
            RunAsync("正在切换模式…", delegate
            {
                if (!Core.Running) { SetStatus("请先启动代理"); return; }
                Core.SetMode(mode);
                SetStatus("已切换模式");
            });
        }

        private void RunTest()
        {
            RunAsync("正在测速…", delegate
            {
                if (!Core.Running) { SetStatus("请先启动代理"); return; }

                var group = Core.PickGroup(_group);
                if (string.IsNullOrEmpty(group)) { SetStatus("没有可用的节点组"); return; }

                var delays = Core.TestDelay(group);
                SetStatus("测速完成，测通 " + delays.Count + " 个节点");
            });
        }

        private void RunAuto()
        {
            RunAsync("正在测速并挑选最快节点…", delegate
            {
                if (!Core.Running) { SetStatus("请先启动代理"); return; }

                var group = Core.PickGroup(_group);
                if (string.IsNullOrEmpty(group)) { SetStatus("没有可用的节点组"); return; }

                var best = Core.SelectFastest(group);

                if (best == null)
                    SetStatus("没有测通的节点，请稍后重试");
                else
                    SetStatus("已自动选择：" + best.Name + "（" + best.Delay + " ms）");
            });
        }

        private void SelectCurrent()
        {
            if (_nodeList.SelectedItems.Count == 0) return;

            var node = _nodeList.SelectedItems[0].Tag as NodeInfo;
            if (node == null || node.IsGroup) return;

            RunAsync("正在切换节点…", delegate
            {
                var group = Core.PickGroup(_group);
                Core.SelectNode(group, node.Name);
                SetStatus("已选择节点：" + node.Name);
            });
        }

        private void SaveProfile()
        {
            var url = _profileUrl.Text.Trim();
            if (url.Length == 0) { _status.Text = "请先填写订阅链接"; return; }

            RunAsync("正在下载订阅…", delegate
            {
                Core.SaveSubscription(url);
                Core.BuildConfig();

                if (Core.Running) { Core.Stop(); Core.Start(); SystemProxy.Enable(); }

                SetStatus("订阅已保存并生效");
            });
        }

        private void UpdateProfile()
        {
            RunAsync("正在更新订阅…", delegate
            {
                var s = Env.LoadSettings();
                if (string.IsNullOrEmpty(s.profileUrl)) { SetStatus("还没有订阅地址"); return; }

                Core.SaveSubscription(s.profileUrl);
                Core.BuildConfig();

                if (Core.Running) { Core.Stop(); Core.Start(); SystemProxy.Enable(); }

                SetStatus("订阅已更新");
            });
        }
    }
}
namespace ClashSimple
{
    /// <summary>
    /// 无界面自检：启动内核 -> 查询状态 -> 切模式 -> 测速 -> 停止。
    /// 结果同时写入 data/selftest.log（因为 win exe 没有控制台）。
    /// </summary>
    internal static partial class SelfTest
    {
        private static StringBuilder _log = new StringBuilder();

        private static void Say(string text)
        {
            _log.AppendLine(text);
            try { Console.WriteLine(text); } catch { }
        }

        public static void Run()
        {
            try
            {
                Env.EnsureData();

                Say("[SelfTest] 启动内核…");
                Core.Start();

                Say("[SelfTest] 内核版本: " + Core.GetVersion());
                Say("[SelfTest] 当前模式: " + Core.GetMode());

                var groups = Core.GetGroupNames();
                Say("[SelfTest] 分组数: " + groups.Count);

                string group = Core.PickGroup(null);
                Say("[SelfTest] 当前分组: " + group);

                string now;
                var nodes = Core.GetNodes(group, out now);
                Say("[SelfTest] 节点数: " + nodes.Count);
                Say("[SelfTest] 当前节点: " + now);

                Core.SetMode("global");
                Say("[SelfTest] 切换后模式: " + Core.GetMode());

                try
                {
                    var delays = Core.TestDelay(group);
                    Say("[SelfTest] 测速通过节点数: " + delays.Count);
                }
                catch (Exception ex)
                {
                    Say("[SelfTest] 测速无结果（默认配置没有可用代理时属正常）: " + ex.Message);
                }

                Core.Stop();
                Say("[SelfTest] 完成");
            }
            catch (Exception ex)
            {
                Say("[SelfTest] 失败: " + ex.Message);
            }

            try
            {
                File.WriteAllText(Path.Combine(Env.DataDir, "selftest.log"), _log.ToString(), Encoding.UTF8);
            }
            catch { }
        }
    }
}
namespace ClashSimple
{
    /// <summary>
    /// 统一的网络下载。
    ///
    /// 关键点：.NET Framework 4.x 默认不一定启用 TLS 1.2，
    /// 直接访问 GitHub / 订阅链接会报
    /// 「请求被中止: 未能创建 SSL/TLS 安全通道」，
    /// 所以这里显式打开 TLS 1.2（并尝试 TLS 1.3）。
    /// </summary>
    internal static class Net
    {
        private static bool _configured;

        public static void Configure()
        {
            if (_configured) return;
            _configured = true;

            try
            {
                ServicePointManager.SecurityProtocol |= (SecurityProtocolType)3072;  // TLS 1.2
            }
            catch { }

            try
            {
                ServicePointManager.SecurityProtocol |= (SecurityProtocolType)12288; // TLS 1.3（运行时支持才生效）
            }
            catch { }

            try { ServicePointManager.Expect100Continue = false; } catch { }
        }

        public static string UserAgent
        {
            get { return "ClashSimple/1.0 (Windows NT; .NET " + Environment.Version + ")"; }
        }

        private static HttpWebRequest Create(string url, int timeoutMs)
        {
            Configure();

            var req = (HttpWebRequest)WebRequest.Create(url);
            req.UserAgent = UserAgent;
            req.Accept = "*/*";
            req.AllowAutoRedirect = true;
            req.Timeout = timeoutMs;
            req.ReadWriteTimeout = timeoutMs;

            try
            {
                req.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            }
            catch { }

            return req;
        }

        public static string DownloadString(string url)
        {
            var req = Create(url, 120000);

            using (var res = (HttpWebResponse)req.GetResponse())
            using (var stream = res.GetResponseStream())
            using (var reader = new StreamReader(stream, Encoding.UTF8))
            {
                return reader.ReadToEnd();
            }
        }

        public static void DownloadFile(string url, string path)
        {
            var req = Create(url, 600000);

            using (var res = (HttpWebResponse)req.GetResponse())
            using (var stream = res.GetResponseStream())
            using (var file = File.Create(path))
            {
                stream.CopyTo(file);
            }
        }

        /// <summary>把常见网络异常翻译成看得懂的中文提示</summary>
        public static string Friendly(Exception ex)
        {
            string msg = ex == null ? "" : ex.Message;

            if (msg.IndexOf("SSL", StringComparison.OrdinalIgnoreCase) >= 0 ||
                msg.IndexOf("TLS", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                return "无法建立安全连接（SSL/TLS）。请先确认系统时间正确；" +
                       "若仍失败，可尝试把订阅链接换成 http 开头的，或使用带代理的下载方式。";
            }

            if (msg.IndexOf("timed out", StringComparison.OrdinalIgnoreCase) >= 0 ||
                msg.IndexOf("超时", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                return "请求超时。请检查网络，或稍后重试（订阅站点可能被墙）。";
            }

            if (msg.IndexOf("404", StringComparison.OrdinalIgnoreCase) >= 0 ||
                msg.IndexOf("NotFound", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                return "链接无效（404）。请确认订阅地址是否正确、是否已过期。";
            }

            return msg;
        }
    }
}
namespace ClashSimple
{
    internal static partial class SelfTest
    {
        /// <summary>从 data/testurl.txt 读取待测试的 URL</summary>
        private static string ReadTestUrl()
        {
            string file = Path.Combine(Env.DataDir, "testurl.txt");
            if (!File.Exists(file)) throw new Exception("缺少 data/testurl.txt");
            return File.ReadAllText(file, Encoding.UTF8).Trim();
        }

        /// <summary>测试 HTTPS 下载（用于验证 TLS 1.2 是否生效）</summary>
        public static void TestHttps(string url)
        {
            Env.EnsureData();
            if (string.IsNullOrEmpty(url)) url = ReadTestUrl();

            var log = new StringBuilder();
            log.AppendLine("[TestHttps] url = " + url);
            log.AppendLine("[TestHttps] 系统默认 TLS 协议 = " + ServicePointManager.SecurityProtocol);

            try
            {
                string text = Net.DownloadString(url);
                log.AppendLine("[TestHttps] OK，下载长度 = " + text.Length + " 字符");
            }
            catch (Exception ex)
            {
                log.AppendLine("[TestHttps] FAIL: " + ex.Message);
                log.AppendLine("[TestHttps] 友好提示: " + Net.Friendly(ex));
            }

            File.WriteAllText(Path.Combine(Env.DataDir, "nettest.log"), log.ToString(), Encoding.UTF8);
        }

        /// <summary>强制从 GitHub 重新下载内核（验证下载 + 解压链路）</summary>
        public static void TestCore()
        {
            Env.EnsureData();

            var log = new StringBuilder();

            try
            {
                log.AppendLine("[TestCore] 强制重新下载内核…");
                Core.EnsureCore(true);
                log.AppendLine("[TestCore] OK，mihomo.exe 大小 = " + new FileInfo(Env.CoreExe).Length + " 字节");
            }
            catch (Exception ex)
            {
                log.AppendLine("[TestCore] FAIL: " + ex.Message);
            }

            File.WriteAllText(Path.Combine(Env.DataDir, "coretest.log"), log.ToString(), Encoding.UTF8);
        }
    }
}
namespace ClashSimple
{
    internal static partial class SelfTest
    {
        /// <summary>订阅端到端：下载 -> 保存 -> 生成配置 -> 启动内核 -> 读取节点</summary>
        public static void TestSub(string url)
        {
            Env.EnsureData();
            if (string.IsNullOrEmpty(url)) url = ReadTestUrl();

            var log = new StringBuilder();

            try
            {
                log.AppendLine("[TestSub] 下载订阅: " + url);
                Core.SaveSubscription(url);
                log.AppendLine("[TestSub] 已保存 profile.yaml，大小 = " +
                               new FileInfo(Env.ProfileFile).Length + " 字节");

                Core.BuildConfig();
                log.AppendLine("[TestSub] 已生成 config.yaml");

                Core.Start();
                log.AppendLine("[TestSub] 内核启动 OK，版本 = " + Core.GetVersion());

                var groups = Core.GetGroupNames();
                log.AppendLine("[TestSub] 分组数 = " + groups.Count);

                string group = Core.PickGroup(null);
                log.AppendLine("[TestSub] 当前分组 = " + group);

                string now;
                var nodes = Core.GetNodes(group, out now);
                log.AppendLine("[TestSub] 节点数 = " + nodes.Count + "，当前 = " + now);

                Core.Stop();
                log.AppendLine("[TestSub] 完成");
            }
            catch (Exception ex)
            {
                log.AppendLine("[TestSub] FAIL: " + ex.Message);
            }

            File.WriteAllText(Path.Combine(Env.DataDir, "subtest.log"), log.ToString(), Encoding.UTF8);
        }
    }
}