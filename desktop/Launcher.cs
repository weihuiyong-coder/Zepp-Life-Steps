using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

internal static class Program
{
    internal static readonly string DefaultRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ZeppLifeSteps");
    internal static readonly JavaScriptSerializer Json = new JavaScriptSerializer();

    [STAThread]
    private static int Main(string[] args)
    {
        AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
        AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
        if (Array.IndexOf(args, "--self-test") >= 0) return SelfTest(args);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        bool created;
        string sid = WindowsIdentity.GetCurrent().User.Value;
        using (var mutex = new Mutex(true, "Local\\ZeppLifeStepsDesktop-" + sid, out created))
        {
            if (!created)
            {
                try
                {
                    var state = Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(Path.Combine(DefaultRoot, "current.json")));
                    int port = Convert.ToInt32(state["port"]);
                    if (port < 1024 || port > 65535) throw new InvalidDataException();
                    string url = "http://127.0.0.1:" + port;
                    var health = Json.Deserialize<Dictionary<string, object>>(Runner.Request(url + "/api/health", null, null, 1500));
                    if ((string)health["desktopInstance"] != (string)state["instance"]) throw new InvalidDataException();
                    OpenBrowser(url);
                }
                catch { MessageBox.Show("程序已在运行或正在启动，请使用现有窗口。", "Zepp 步数助手"); }
                return 0;
            }
            try { Application.Run(new LauncherForm(Array.IndexOf(args, "--no-browser") < 0)); }
            finally { mutex.ReleaseMutex(); }
        }
        return 0;
    }

    internal static void OpenBrowser(string url)
    {
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
    }

    private static string Option(string[] args, string name)
    {
        int index = Array.IndexOf(args, name);
        if (index < 0 || index + 1 >= args.Length) throw new ArgumentException("Missing " + name);
        return Path.GetFullPath(args[index + 1]);
    }

    private static int SelfTest(string[] args)
    {
        string report = Option(args, "--report");
        var data = new Dictionary<string, object>();
        Runner runner = null;
        try
        {
            runner = new Runner(Option(args, "--cache-root"));
            runner.Start(CancellationToken.None);
            data["port"] = runner.Port;
            data["pid"] = runner.ServerId;
            string html = Runner.Request(runner.Url + "/", null, null, 10000);
            Match asset = Regex.Match(html, "src=\"([^\"]*/_next/static/[^\"]+\\.js)\"");
            if (!asset.Success) throw new InvalidDataException("Page did not include production assets.");
            string js = Runner.Request(runner.Url + WebUtility.HtmlDecode(asset.Groups[1].Value), null, null, 10000);
            if (js.Length < 100) throw new InvalidDataException("Production asset was empty.");
            string body = "{\"account\":\"not-an-account\",\"password\":\"offline-check\",\"steps\":1234}";
            string result = Runner.Request(runner.Url + "/api/update-steps", body, runner.Url, 15000, 400);
            var rejected = Json.Deserialize<Dictionary<string, object>>(result);
            if ((string)rejected["code"] != "INVALID_INPUT") throw new InvalidDataException("Bundled Python bridge did not reject invalid input.");
            Runner.Request(runner.Url + "/api/update-steps", body, "http://example.invalid", 5000, 403);
            data["health"] = "passed";
            data["pageAndAssets"] = "passed";
            data["bundledPythonBridge"] = "passed";
            data["originCheck"] = "passed";
            data["externalAccountRequests"] = 0;
            int pid = runner.ServerId;
            runner.Dispose();
            runner = null;
            bool alive;
            try { using (Process process = Process.GetProcessById(pid)) alive = !process.WaitForExit(5000); }
            catch (ArgumentException) { alive = false; }
            if (alive) throw new InvalidOperationException("Server did not stop with the launcher.");
            data["shutdown"] = "passed";
            data["success"] = true;
            File.WriteAllText(report, Json.Serialize(data), new UTF8Encoding(false));
            return 0;
        }
        catch (Exception error)
        {
            data["success"] = false;
            data["error"] = error.Message;
            File.WriteAllText(report, Json.Serialize(data), new UTF8Encoding(false));
            return 1;
        }
        finally { if (runner != null) runner.Dispose(); }
    }
}

internal sealed class LauncherForm : Form
{
    private readonly Label status;
    private readonly Label address;
    private readonly Button open;
    private readonly ProgressBar progress;
    private readonly CancellationTokenSource stop = new CancellationTokenSource();
    private readonly Runner runner = new Runner(Program.DefaultRoot);
    private bool running;

    internal LauncherForm(bool autoOpenBrowser)
    {
        Text = "Zepp 步数助手";
        ClientSize = new Size(490, 245);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        Font = new Font("Microsoft YaHei UI", 10);
        BackColor = Color.White;
        Controls.Add(new Label { Text = "Zepp 步数助手", Font = new Font(Font.FontFamily, 18, FontStyle.Bold), AutoSize = true, Location = new Point(26, 24) });
        status = new Label { Text = "正在准备，首次打开需要稍等片刻…", AutoSize = false, Size = new Size(440, 44), Location = new Point(27, 76) };
        address = new Label { Text = "", AutoSize = true, Location = new Point(27, 115), ForeColor = Color.DimGray };
        progress = new ProgressBar { Location = new Point(29, 143), Size = new Size(427, 5), Style = ProgressBarStyle.Marquee };
        open = new Button { Text = "打开网页", Enabled = false, Location = new Point(27, 164), Size = new Size(128, 36) };
        open.Click += delegate { TryOpen(); };
        var close = new Button { Text = "退出程序", Location = new Point(170, 164), Size = new Size(110, 36) };
        close.Click += delegate { Close(); };
        Controls.AddRange(new Control[] { status, address, progress, open, close, new Label { Text = "使用时保留此窗口；关闭窗口会停止本机服务。", AutoSize = true, ForeColor = Color.DimGray, Location = new Point(27, 211) } });
        Shown += async delegate
        {
            try
            {
                await Task.Run(() => runner.Start(stop.Token));
                if (stop.IsCancellationRequested) return;
                running = true;
                status.Text = "已启动，请在网页中输入账号、密码和步数。";
                address.Text = runner.Url;
                progress.Visible = false;
                open.Enabled = true;
                if (autoOpenBrowser) TryOpen();
            }
            catch (OperationCanceledException) { }
            catch (Exception error)
            {
                if (stop.IsCancellationRequested) return;
                runner.Dispose();
                progress.Visible = false;
                status.Text = "启动失败：" + error.Message;
            }
        };
        FormClosing += delegate { stop.Cancel(); runner.Dispose(); };
        var timer = new System.Windows.Forms.Timer { Interval = 2000 };
        timer.Tick += delegate
        {
            if (running && !runner.IsRunning)
            {
                running = false;
                open.Enabled = false;
                status.Text = "后台服务已停止，请退出后重新打开程序。";
            }
        };
        timer.Start();
        FormClosed += delegate { timer.Dispose(); };
    }

    private void TryOpen()
    {
        try { Program.OpenBrowser(runner.Url); }
        catch { MessageBox.Show("无法自动打开浏览器，请手动访问：\n" + runner.Url, Text); }
    }
}

internal sealed class Runner : IDisposable
{
    private readonly string root;
    private readonly object gate = new object();
    private readonly string instance = Guid.NewGuid().ToString("N");
    private Process server;
    private IntPtr job;
    private bool disposed;
    internal int Port { get; private set; }
    internal int ServerId { get { return server.Id; } }
    internal string Url { get { return "http://127.0.0.1:" + Port; } }
    internal bool IsRunning { get { lock (gate) { return !disposed && server != null && !server.HasExited; } } }

    internal Runner(string cacheRoot) { root = cacheRoot; }

    internal void Start(CancellationToken cancellation)
    {
        string directory = Prepare(cancellation);
        cancellation.ThrowIfCancellationRequested();
        Port = FindPort();
        var info = new ProcessStartInfo(Path.Combine(directory, "runtime", "node.exe"), "server.js") {
            WorkingDirectory = Path.Combine(directory, "app"), UseShellExecute = false,
            CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
            WindowStyle = ProcessWindowStyle.Hidden
        };
        info.EnvironmentVariables.Remove("NODE_OPTIONS");
        info.EnvironmentVariables.Remove("NODE_PATH");
        info.EnvironmentVariables["NODE_ENV"] = "production";
        info.EnvironmentVariables["NEXT_TELEMETRY_DISABLED"] = "1";
        info.EnvironmentVariables["PORT"] = Port.ToString();
        info.EnvironmentVariables["HOSTNAME"] = "127.0.0.1";
        info.EnvironmentVariables["ZEPP_LOCAL_PORT"] = Port.ToString();
        info.EnvironmentVariables["ZEPP_DESKTOP_INSTANCE"] = instance;
        info.EnvironmentVariables["ZEPP_PYTHON"] = Path.Combine(directory, "runtime", "python", "python.exe");
        info.EnvironmentVariables["NO_PROXY"] = (info.EnvironmentVariables["NO_PROXY"] ?? "") + ",localhost,127.0.0.1,::1,.huami.com,.zepp.com";
        lock (gate)
        {
            if (disposed) throw new OperationCanceledException();
            cancellation.ThrowIfCancellationRequested();
            job = Native.CreateKillJob();
            server = Process.Start(info);
            if (!Native.AssignProcessToJobObject(job, server.Handle))
            {
                server.Kill();
                throw new InvalidOperationException("无法管理后台进程，请重新启动程序。");
            }
            // The application already sanitizes API errors; do not persist raw process output.
            server.OutputDataReceived += delegate { };
            server.ErrorDataReceived += delegate { };
            server.BeginOutputReadLine();
            server.BeginErrorReadLine();
        }
        var elapsed = Stopwatch.StartNew();
        while (elapsed.Elapsed.TotalSeconds < 45)
        {
            cancellation.ThrowIfCancellationRequested();
            if (!IsRunning) throw new InvalidOperationException("本机服务未能启动，请确认系统为 Windows 10/11 64 位。");
            try
            {
                var health = Program.Json.Deserialize<Dictionary<string, object>>(Request(Url + "/api/health", null, null, 1000));
                if (health.ContainsKey("desktopInstance") && (string)health["desktopInstance"] == instance)
                {
                    File.WriteAllText(Path.Combine(root, "current.json"), Program.Json.Serialize(new { port = Port, instance = instance }), new UTF8Encoding(false));
                    return;
                }
            }
            catch (WebException) { }
            catch (ArgumentException) { }
            catch (InvalidDataException) { }
            cancellation.WaitHandle.WaitOne(200);
        }
        throw new TimeoutException("启动用时过长，请退出后再试。");
    }

    private string Prepare(CancellationToken cancellation)
    {
        Directory.CreateDirectory(root);
        string target = Path.Combine(root, "app-" + BuildInfo.PayloadId);
        string complete = Path.Combine(target, ".complete");
        if (File.Exists(complete) && File.ReadAllText(complete) == BuildInfo.PayloadId &&
            File.Exists(Path.Combine(target, "runtime", "node.exe")) &&
            File.Exists(Path.Combine(target, "runtime", "python", "python.exe")) &&
            File.Exists(Path.Combine(target, "app", "server.js"))) return target;
        if (Directory.Exists(target)) target += "-" + Guid.NewGuid().ToString("N");
        string staging = Path.Combine(root, ".prepare-" + Guid.NewGuid().ToString("N").Substring(0, 12));
        Directory.CreateDirectory(staging);
        string prefix = Path.GetFullPath(staging) + Path.DirectorySeparatorChar;
        using (Stream resource = Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip"))
        using (var archive = new ZipArchive(resource, ZipArchiveMode.Read))
        {
            foreach (var entry in archive.Entries)
            {
                cancellation.ThrowIfCancellationRequested();
                string output = Path.GetFullPath(Path.Combine(staging, entry.FullName.Replace('/', Path.DirectorySeparatorChar)));
                if (!output.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Invalid archive entry.");
                if (entry.FullName.EndsWith("/")) { Directory.CreateDirectory(output); continue; }
                Directory.CreateDirectory(Path.GetDirectoryName(output));
                using (var source = entry.Open())
                using (var destination = new FileStream(output, FileMode.CreateNew, FileAccess.Write)) source.CopyTo(destination);
            }
        }
        File.WriteAllText(Path.Combine(staging, ".complete"), BuildInfo.PayloadId);
        Directory.Move(staging, target);
        return target;
    }

    private static int FindPort()
    {
        for (int port = 3107; port <= 3126; port++)
        {
            var listener = new TcpListener(IPAddress.Loopback, port);
            try { listener.Start(); return port; }
            catch (SocketException) { }
            finally { listener.Stop(); }
        }
        var fallback = new TcpListener(IPAddress.Loopback, 0);
        try { fallback.Start(); return ((IPEndPoint)fallback.LocalEndpoint).Port; }
        finally { fallback.Stop(); }
    }

    internal static string Request(string url, string body, string origin, int timeout, int expectedStatus = 200)
    {
        var request = (HttpWebRequest)WebRequest.Create(url);
        request.Proxy = null;
        request.Timeout = timeout;
        request.ReadWriteTimeout = timeout;
        if (body != null)
        {
            request.Method = "POST";
            request.ContentType = "application/json";
            request.Headers["Origin"] = origin;
            byte[] bytes = Encoding.UTF8.GetBytes(body);
            request.ContentLength = bytes.Length;
            using (Stream input = request.GetRequestStream()) input.Write(bytes, 0, bytes.Length);
        }
        HttpWebResponse response;
        try { response = (HttpWebResponse)request.GetResponse(); }
        catch (WebException error) { if (error.Response == null) throw; response = (HttpWebResponse)error.Response; }
        using (response)
        {
            if ((int)response.StatusCode != expectedStatus) throw new InvalidDataException("Unexpected local HTTP status " + (int)response.StatusCode);
            using (var reader = new StreamReader(response.GetResponseStream())) return reader.ReadToEnd();
        }
    }

    public void Dispose()
    {
        lock (gate)
        {
            if (disposed) return;
            disposed = true;
            if (job != IntPtr.Zero) { Native.CloseHandle(job); job = IntPtr.Zero; }
            if (server != null)
            {
                try { if (!server.WaitForExit(3000)) server.Kill(); } catch (InvalidOperationException) { }
                server.Dispose();
            }
        }
    }
}

internal static class Native
{
    [StructLayout(LayoutKind.Sequential)] private struct BasicLimits {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)] private struct IoCounters { public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount; }
    [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits {
        public BasicLimits BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint length);
    [DllImport("kernel32.dll", SetLastError = true)] internal static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError = true)] internal static extern bool CloseHandle(IntPtr handle);

    internal static IntPtr CreateKillJob()
    {
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero) throw new InvalidOperationException("无法创建后台服务管理器。");
        var limits = new ExtendedLimits();
        limits.BasicLimitInformation.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        int size = Marshal.SizeOf(typeof(ExtendedLimits));
        IntPtr buffer = Marshal.AllocHGlobal(size);
        try
        {
            Marshal.StructureToPtr(limits, buffer, false);
            if (!SetInformationJobObject(job, 9, buffer, (uint)size))
            {
                CloseHandle(job);
                throw new InvalidOperationException("无法设置后台服务退出保护。");
            }
            return job;
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }
}
