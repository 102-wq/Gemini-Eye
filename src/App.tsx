import React, { useMemo, useRef, useState } from 'react';
import { Check, Copy, Download, FileCode, FolderGit2, Layers, Search, Smartphone } from 'lucide-react';
import { PROJECT_FILES } from './moduleFiles';
import { buildZipBlob } from './zipBuilder';

type UiLang = 'zh' | 'en';
type ActiveView = 'fix' | 'files' | 'simulator';

interface HudSnapshot {
  fiveHourQuota: string;
  weeklyLimit: string;
  resetTimer: string;
  source: string;
}

const SAMPLE_PAYLOADS: Array<{
  id: string;
  labelZh: string;
  labelEn: string;
  source: string;
  raw: string;
  parsed: HudSnapshot;
}> = [
  {
    id: 'usage-web',
    labelZh: 'gemini.google.com/usage 实时百分比提取 (UsageWeb)',
    labelEn: 'gemini.google.com/usage Live % Sync (UsageWeb)',
    source: 'UsageWeb',
    raw: 'Usage limits\nCurrent usage\n18% used\nResets in 3 hr 42 min\nWeekly limit\n7% used\nResets Sun 12:00 PM',
    parsed: {
      fiveHourQuota: '18%',
      weeklyLimit: '7%',
      resetTimer: '3h42m',
      source: 'UsageWeb',
    },
  },
  {
    id: 'compose',
    labelZh: 'Compose 用量设置页渲染嗅探 (BoringLayout / StaticLayout)',
    labelEn: 'Compose Usage Settings Sniff (BoringLayout / StaticLayout)',
    source: 'Compose',
    raw: '用量与限额\n当前用量\n已使用 24%\n4 小时 15 分钟后重置\n每周限额\n已使用 11%',
    parsed: {
      fiveHourQuota: '24%',
      weeklyLimit: '11%',
      resetTimer: '4h15m',
      source: 'Compose',
    },
  },
  {
    id: 'json',
    labelZh: 'RPC / JSON 解码流提取 (CharsetDecoder / JSONObject)',
    labelEn: 'RPC / JSON Stream Decode (CharsetDecoder / JSONObject)',
    source: 'Decoder',
    raw: ')]}\'\n[["wrb.fr","robin", "{\\"five_hour_quota\\":\\"32/50\\",\\"weekly_limit\\":\\"140/350\\",\\"reset_in\\":\\"01:42:00\\"}"]]',
    parsed: {
      fiveHourQuota: '32/50',
      weeklyLimit: '140/350',
      resetTimer: '01:42:00',
      source: 'Decoder',
    },
  },
];

export default function App() {
  const [uiLang, setUiLang] = useState<UiLang>('zh');
  const [hudLang, setHudLang] = useState<UiLang>('zh');
  const [activeView, setActiveView] = useState<ActiveView>('fix');
  const [selectedPath, setSelectedPath] = useState<string>(
    'app/src/main/java/com/example/bardquotahook/ModernQuotaModule.kt'
  );
  const [fileQuery, setFileQuery] = useState('');
  const [copiedPath, setCopiedPath] = useState<string | null>(null);
  const [zipDownloaded, setZipDownloaded] = useState(false);

  const [selectedSampleId, setSelectedSampleId] = useState<string>('usage-web');
  const [simDarkTheme, setSimDarkTheme] = useState<boolean>(true);
  const hudBtnRef = useRef<HTMLButtonElement | null>(null);
  const hudPosRef = useRef<{ x: number; y: number }>({ x: 0, y: 0 });
  const dragRef = useRef<{
    startX: number;
    startY: number;
    origX: number;
    origY: number;
    moved: boolean;
    pointerDown: boolean;
  }>({ startX: 0, startY: 0, origX: 0, origY: 0, moved: false, pointerDown: false });

  const filteredFiles = useMemo(() => {
    const q = fileQuery.trim().toLowerCase();
    if (!q) return PROJECT_FILES;
    return PROJECT_FILES.filter(
      (f) => f.path.toLowerCase().includes(q) || f.label.toLowerCase().includes(q)
    );
  }, [fileQuery]);

  const currentFile = useMemo(
    () => PROJECT_FILES.find((f) => f.path === selectedPath) ?? PROJECT_FILES[0],
    [selectedPath]
  );

  const activeSample = useMemo(
    () => SAMPLE_PAYLOADS.find((s) => s.id === selectedSampleId) ?? SAMPLE_PAYLOADS[0],
    [selectedSampleId]
  );

  const handleDownloadZip = () => {
    const blob = buildZipBlob(
      PROJECT_FILES.map((f) => ({
        path: f.path,
        content: f.content,
      }))
    );
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'Gemini-Eye-v1.8.0-Android-Project.zip';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    setZipDownloaded(true);
    setTimeout(() => setZipDownloaded(false), 2600);
  };

  const handleCopy = async (text: string, key: string) => {
    await navigator.clipboard.writeText(text);
    setCopiedPath(key);
    setTimeout(() => setCopiedPath(null), 1800);
  };

  const onHudPointerDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    dragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      origX: hudPosRef.current.x,
      origY: hudPosRef.current.y,
      moved: false,
      pointerDown: true,
    };
  };

  const onHudPointerMove = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (!dragRef.current.pointerDown) return;
    const dx = e.clientX - dragRef.current.startX;
    const dy = e.clientY - dragRef.current.startY;
    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
      dragRef.current.moved = true;
    }
    if (dragRef.current.moved) {
      const nextX = Math.max(-150, Math.min(20, dragRef.current.origX + dx));
      const nextY = Math.max(-40, Math.min(240, dragRef.current.origY + dy));
      hudPosRef.current = { x: nextX, y: nextY };
      if (hudBtnRef.current) {
        hudBtnRef.current.style.translate = `${nextX}px ${nextY}px`;
      }
    }
  };

  const onHudPointerUp = () => {
    if (!dragRef.current.pointerDown) return;
    const wasMoved = dragRef.current.moved;
    dragRef.current.pointerDown = false;
    if (!wasMoved) {
      setHudLang((prev) => (prev === 'zh' ? 'en' : 'zh'));
    }
  };

  const isZh = uiLang === 'zh';

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 flex flex-col lg:flex-row">
      {/* Sidebar Navigation */}
      <aside className="w-full lg:w-64 shrink-0 bg-white border-b lg:border-b-0 lg:border-r border-slate-200 flex flex-col">
        <div className="px-5 py-4 border-b border-slate-200">
          <div className="text-base font-bold tracking-tight text-slate-900 whitespace-nowrap">
            Gemini-Eye
          </div>
          <div className="mt-1 text-xs text-slate-500">
            <span>LibXposed API 102 + API 82</span>
            <span aria-hidden="true"> · </span>
            <span>v1.8.0</span>
          </div>
        </div>

        <nav className="p-3 space-y-1 border-b border-slate-200">
          <button
            type="button"
            onClick={() => setActiveView('fix')}
            className={`w-full flex items-center gap-2.5 px-3 py-2 text-sm font-medium rounded-lg transition-colors whitespace-nowrap cursor-pointer ${
              activeView === 'fix'
                ? 'bg-slate-900 text-white'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <FolderGit2 className="w-4 h-4 shrink-0" />
            <span className="truncate">
              {isZh ? 'v1.8.0 根因诊断与升级方案' : 'v1.8.0 Root Cause & Fix'}
            </span>
          </button>

          <button
            type="button"
            onClick={() => setActiveView('files')}
            className={`w-full flex items-center gap-2.5 px-3 py-2 text-sm font-medium rounded-lg transition-colors whitespace-nowrap cursor-pointer ${
              activeView === 'files'
                ? 'bg-slate-900 text-white'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <FileCode className="w-4 h-4 shrink-0" />
            <span className="truncate">
              {isZh
                ? `Android 工程源码 (${PROJECT_FILES.length})`
                : `Android Source Files (${PROJECT_FILES.length})`}
            </span>
          </button>

          <button
            type="button"
            onClick={() => setActiveView('simulator')}
            className={`w-full flex items-center gap-2.5 px-3 py-2 text-sm font-medium rounded-lg transition-colors whitespace-nowrap cursor-pointer ${
              activeView === 'simulator'
                ? 'bg-slate-900 text-white'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <Smartphone className="w-4 h-4 shrink-0" />
            <span className="truncate">
              {isZh ? '双语悬浮胶囊交互预览' : 'Bilingual HUD Simulator'}
            </span>
          </button>
        </nav>

        <div className="p-3 flex-1 overflow-y-auto hidden lg:block">
          <div className="px-2 pb-2 text-xs font-medium text-slate-500">
            {isZh ? '工程文件目录' : 'Project File Tree'}
          </div>
          <div className="space-y-0.5">
            {PROJECT_FILES.map((file) => {
              const active = activeView === 'files' && selectedPath === file.path;
              return (
                <button
                  key={file.path}
                  type="button"
                  onClick={() => {
                    setSelectedPath(file.path);
                    setActiveView('files');
                  }}
                  className={`w-full text-left px-2.5 py-1.5 rounded-md text-xs font-mono truncate transition-colors cursor-pointer ${
                    active
                      ? 'bg-slate-100 text-slate-900 font-semibold'
                      : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900'
                  }`}
                  title={file.path}
                >
                  {file.label}
                </button>
              );
            })}
          </div>
        </div>
      </aside>

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0">
        <header className="flex items-center justify-between gap-8 px-6 py-3.5 bg-white border-b border-slate-200">
          <div className="flex items-center gap-2 text-sm text-slate-600 min-w-0">
            <span className="font-semibold text-slate-900 whitespace-nowrap">
              {isZh ? 'Gemini 用量监控模块' : 'Gemini Quota Module'}
            </span>
            <span aria-hidden="true">/</span>
            <span className="truncate">
              {activeView === 'fix'
                ? isZh
                  ? '修复不触发 Hook 与拿不到额度 (v1.8.0)'
                  : 'Fixing Hook & Quota Acquisition (v1.8.0)'
                : activeView === 'files'
                ? currentFile.path
                : isZh
                ? '单行自适应胶囊与解析器预览'
                : 'Single-Line HUD & Parser Simulator'}
            </span>
          </div>

          <button
            type="button"
            onClick={handleDownloadZip}
            className="inline-flex items-center gap-2 px-4 py-2 text-xs font-semibold text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            {zipDownloaded ? <Check className="w-4 h-4" /> : <Download className="w-4 h-4" />}
            <span>
              {zipDownloaded
                ? isZh
                  ? '已导出 v1.8.0 工程 .zip'
                  : 'v1.8.0 .zip Downloaded'
                : isZh
                ? '下载 v1.8.0 Android 工程 (.zip)'
                : 'Download v1.8.0 Project (.zip)'}
            </span>
          </button>
        </header>

        {/* Workspace Toolbar */}
        <div className="px-6 py-3 bg-slate-50 border-b border-slate-200 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-2 text-xs text-slate-600">
            <span>META-INF/xposed (API 102)</span>
            <span aria-hidden="true">·</span>
            <span>assets/xposed_init (API 82)</span>
            <span aria-hidden="true">·</span>
            <span>gemini.google.com/usage Live Sync</span>
          </div>

          <div className="flex items-center gap-1 p-1 bg-slate-200/70 rounded-lg">
            <button
              type="button"
              onClick={() => setUiLang('zh')}
              className={`px-3 py-1 text-xs font-medium rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                uiLang === 'zh'
                  ? 'bg-white text-slate-900 shadow-xs'
                  : 'text-slate-600 hover:text-slate-900'
              }`}
            >
              简体中文 (默认)
            </button>
            <button
              type="button"
              onClick={() => setUiLang('en')}
              className={`px-3 py-1 text-xs font-medium rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                uiLang === 'en'
                  ? 'bg-white text-slate-900 shadow-xs'
                  : 'text-slate-600 hover:text-slate-900'
              }`}
            >
              English
            </button>
          </div>
        </div>

        {/* View Content */}
        <main className="p-6 max-w-6xl w-full mx-auto flex-1">
          {activeView === 'fix' && (
            <div className="space-y-8">
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-slate-900 text-balance">
                  {isZh
                    ? 'v1.8.0 彻底解决「仍不触发 Hook」与「拿不到真实额度」三大根因'
                    : 'v1.8.0: Resolving Both “Still Doesn’t Hook” and “Can’t Get Quota”'}
                </h1>
                <p className="mt-2 text-sm text-slate-600 max-w-3xl leading-relaxed">
                  {isZh
                    ? '通过对 v1.7.12 已发布 APK 反编译核查与 LibXposed API 102 源码对齐，定位到现代框架入口缺失、Magisk 排除列表卸载以及聊天二进制流不含百分比三大核心原因，并在 v1.8.0 中一次性全部补齐。'
                    : 'Inspecting the compiled v1.7.12 APK against io.github.libxposed:api:102.0.0 revealed three exact root causes across modern Xposed loaders, Magisk DenyList behavior, and Gemini’s usage endpoint.'}
                </p>
              </div>

              <div className="bg-white border border-slate-200 rounded-xl divide-y divide-slate-200">
                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '01. 为什么在 API 102 框架 (Vector / 新版 LSPosed) 上不触发 Hook？'
                      : '01. Why API 102 Frameworks (Vector / Modern LSPosed) Ignored v1.7.12'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? 'v1.7.12 虽然在 Gradle 添加了 io.github.libxposed:api:102.0.0 依赖，但 APK 内仅有旧版 assets/xposed_init，缺少现代 API 102 强制要求的 META-INF/xposed/module.prop (minApiVersion=101, targetApiVersion=102)、java_init.list、scope.list 以及继承 XposedModule 的入口类。v1.8.0 新增 ModernQuotaModule.kt + QuotaCoreEngine.kt 双引擎架构，新旧框架通吃且互不冲突。'
                      : 'v1.7.12 only bundled legacy assets/xposed_init and lacked META-INF/xposed/{module.prop,java_init.list,scope.list} and an io.github.libxposed.api.XposedModule entry class. v1.8.0 implements ModernQuotaModule.kt + QuotaHookEntry.kt sharing QuotaCoreEngine.kt.'}
                  </p>
                </div>

                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '02. Magisk 排除列表 (DenyList) / Shamiko 隐藏导致 Google 进程被剥离模块'
                      : '02. Magisk DenyList / Shamiko Unmounting Xposed Inside Google App'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? '很多 Root 设备为了过 Play Integrity 会在 Magisk 排除列表或 Shamiko 中勾选 Google (com.google.android.googlequicksearchbox) 或 Gemini，这会导致 Zygisk 在启动该进程时直接卸载所有 Xposed 模块！v1.8.0 在 MainActivity 新增实时 Hook 心跳回执检测 (Ping)，并内置「免 Hook 全局悬浮胶囊 (SYSTEM_ALERT_WINDOW)」，即便不移出排除列表也能照常在屏幕右上角显示同款自适应悬浮窗。'
                      : 'If Google App or Gemini is checked in Magisk DenyList / Shamiko, Zygisk unmounts all Xposed modules in that process. v1.8.0 adds live Hook Heartbeat Ping diagnostics in MainActivity plus a one-tap SYSTEM_ALERT_WINDOW floating capsule fallback.'}
                  </p>
                </div>

                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '03. 为什么普通聊天拿不到额度？内置 gemini.google.com/usage 实时提取与跨进程推送'
                      : '03. Why Chat Traffic Lacks Quota & Built-in gemini.google.com/usage Live Sync'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? 'Gemini 聊天流走原生 Cronet 二进制 Protobuf 且不返回百分比，同时 Android ART 会将 String 构造器内联替换为 StringFactory 导致传统 String Hook 失效。真实 5 小时百分比 (如 "18% used") 与周限额位于 gemini.google.com/usage。v1.8.0 重构了 QuotaTextParser 精准识别双分区 "% used / 已使用 %"，并在 Gemini-Eye 应用内嵌入支持持久化 Cookie 的 gemini.google.com/usage 同步器，登录一次即可实时抓取并通过广播直接刷新 Gemini 悬浮胶囊！'
                      : 'Gemini chat uses binary Protobuf without usage percentages, and ART intrinsifies String constructors. Real 5-hour & weekly "% used" meters live at gemini.google.com/usage. v1.8.0 upgrades QuotaTextParser for "% used" meters and embeds a persistent-cookie gemini.google.com/usage live WebView sync that broadcasts directly into the Gemini HUD.'}
                  </p>
                </div>
              </div>

              {/* Action Plan */}
              <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
                <div className="flex flex-wrap items-center justify-between gap-4">
                  <div>
                    <h2 className="text-base font-semibold text-slate-900">
                      {isZh
                        ? '同步 v1.8.0 到你的 GitHub 仓库 (102-wq/Gemini-Eye)'
                        : 'Deploy v1.8.0 to Your GitHub Repository (102-wq/Gemini-Eye)'}
                    </h2>
                    <p className="mt-1 text-xs text-slate-500">
                      {isZh
                        ? '保持相同固定签名密钥 (signing.p12)，可直接覆盖安装升级，无需卸载旧版'
                        : 'Uses the exact same persistent signing.p12 key so you can upgrade in-place'}
                    </p>
                  </div>

                  <button
                    type="button"
                    onClick={() => {
                      setSelectedPath('app/src/main/java/com/example/bardquotahook/ModernQuotaModule.kt');
                      setActiveView('files');
                    }}
                    className="inline-flex items-center gap-2 px-4 py-2 text-xs font-semibold text-slate-900 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors whitespace-nowrap shrink-0 cursor-pointer"
                  >
                    <FileCode className="w-4 h-4" />
                    <span>{isZh ? '查看 v1.8.0 新增源码' : 'Inspect v1.8.0 Source'}</span>
                  </button>
                </div>

                <ol className="space-y-2.5 text-sm text-slate-700 list-decimal list-inside leading-relaxed">
                  <li>
                    {isZh
                      ? '将当前工作区变更推送至 GitHub（或下载右上角 v1.8.0 .zip 解压覆盖 android-module/），等待 GitHub Actions 自动生成 Gemini-Eye v1.8.x Release APK。'
                      : 'Push this workspace update to GitHub (or export the v1.8.0 .zip) and let GitHub Actions publish the signed Gemini-Eye v1.8.x APK.'}
                  </li>
                  <li>
                    {isZh
                      ? '安装 APK 后打开「Gemini 用量监控 Hook」桌面图标：查看顶部「Xposed 已连接」回执；若使用 Shamiko / Magisk 排除列表，请将 Google 与 Gemini 移出排除列表，或直接点击「开启免 Hook 全局悬浮胶囊」。'
                      : 'Open the Gemini-Eye app to verify the live Xposed Heartbeat indicator; if Google App is on Magisk DenyList, either remove it from DenyList or tap “Enable Hook-Free System Floating Capsule”.'}
                  </li>
                  <li>
                    {isZh
                      ? '点击应用内「展开内置网页登录并同步 gemini.google.com/usage」登录一次账号，即可提取实时 5h %、周限额 % 与重置倒计时并自动推送至 Gemini 右上角单行胶囊！'
                      : 'Tap “Sync gemini.google.com/usage” inside Gemini-Eye once to sign in and stream real 5h %, weekly %, and reset timer directly to the HUD pill.'}
                  </li>
                </ol>
              </div>
            </div>
          )}

          {activeView === 'files' && (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-4">
                <div className="relative w-full sm:w-72">
                  <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
                  <input
                    type="text"
                    value={fileQuery}
                    onChange={(e) => setFileQuery(e.target.value)}
                    placeholder={isZh ? '筛选工程文件名...' : 'Filter project files...'}
                    className="w-full pl-9 pr-3 py-2 text-xs bg-white border border-slate-200 rounded-lg focus:outline-none focus:border-slate-400"
                  />
                </div>

                <div className="flex flex-wrap items-center gap-1.5">
                  {filteredFiles.map((f) => (
                    <button
                      key={f.path}
                      type="button"
                      onClick={() => setSelectedPath(f.path)}
                      className={`px-2.5 py-1.5 text-xs font-mono rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                        currentFile.path === f.path
                          ? 'bg-slate-900 text-white'
                          : 'bg-white border border-slate-200 text-slate-600 hover:text-slate-900'
                      }`}
                    >
                      {f.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
                <div className="px-5 py-3.5 border-b border-slate-200 flex items-center justify-between gap-4">
                  <div className="min-w-0">
                    <div className="text-xs font-mono font-semibold text-slate-900 truncate">
                      {currentFile.path}
                    </div>
                    <div className="mt-0.5 text-xs text-slate-500">
                      {isZh ? currentFile.descriptionZh : currentFile.descriptionEn}
                    </div>
                  </div>

                  <button
                    type="button"
                    onClick={() => handleCopy(currentFile.content, currentFile.path)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium bg-slate-100 hover:bg-slate-200 text-slate-900 rounded-lg transition-colors whitespace-nowrap shrink-0 cursor-pointer"
                  >
                    {copiedPath === currentFile.path ? (
                      <Check className="w-3.5 h-3.5 text-emerald-600" />
                    ) : (
                      <Copy className="w-3.5 h-3.5" />
                    )}
                    <span>
                      {copiedPath === currentFile.path
                        ? isZh
                          ? '已复制源码'
                          : 'Copied'
                        : isZh
                        ? '复制文件内容'
                        : 'Copy File'}
                    </span>
                  </button>
                </div>

                <pre className="p-5 bg-slate-950 text-slate-100 text-xs font-mono overflow-x-auto max-h-[620px] leading-relaxed">
                  {currentFile.content}
                </pre>
              </div>
            </div>
          )}

          {activeView === 'simulator' && (
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
              <div className="lg:col-span-6 space-y-5">
                <div>
                  <h2 className="text-lg font-bold text-slate-900">
                    {isZh ? '多源额度嗅探与自适应单行胶囊验证' : 'Multi-Source Quota Parser & HUD Test'}
                  </h2>
                  <p className="mt-1 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? '选择下方真实 gemini.google.com/usage 或 Compose 数据格式查看解析结果；在右侧手机预览框中可按住拖动胶囊，或单击在「简体中文」与「English」之间实时切换。'
                      : 'Select a payload format below to test v1.8.0 extraction; drag the floating HUD inside the phone frame or single-tap it to toggle Simplified Chinese and English.'}
                  </p>
                </div>

                <div className="space-y-2.5">
                  {SAMPLE_PAYLOADS.map((sample) => {
                    const selected = sample.id === activeSample.id;
                    return (
                      <button
                        key={sample.id}
                        type="button"
                        onClick={() => setSelectedSampleId(sample.id)}
                        className={`w-full text-left p-4 rounded-xl border transition-colors cursor-pointer ${
                          selected
                            ? 'bg-white border-slate-900'
                            : 'bg-white border-slate-200 hover:border-slate-300'
                        }`}
                      >
                        <div className="flex items-center justify-between gap-3">
                          <span className="text-sm font-semibold text-slate-900">
                            {isZh ? sample.labelZh : sample.labelEn}
                          </span>
                          <span className="text-xs font-mono text-slate-500">
                            source={sample.source}
                          </span>
                        </div>
                        <pre className="mt-2.5 p-3 rounded-lg bg-slate-50 text-xs font-mono text-slate-700 overflow-x-auto whitespace-pre-wrap">
                          {sample.raw}
                        </pre>
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Simulated Gemini Android Screen */}
              <div className="lg:col-span-6 flex flex-col items-center gap-3">
                <div className="w-full max-w-sm flex items-center justify-between px-1">
                  <span className="text-xs font-medium text-slate-600">
                    {isZh ? '宿主界面深浅色动态主题测试' : 'Host App Dynamic Theme'}
                  </span>
                  <button
                    type="button"
                    onClick={() => setSimDarkTheme((d) => !d)}
                    className="px-3 py-1 text-xs font-medium rounded-lg border border-slate-300 bg-white text-slate-800 hover:bg-slate-100 transition-colors cursor-pointer"
                  >
                    {simDarkTheme
                      ? isZh
                        ? '当前：深色模式 (点击切浅色)'
                        : 'Dark Mode (Switch to Light)'
                      : isZh
                      ? '当前：浅色模式 (点击切深色)'
                      : 'Light Mode (Switch to Dark)'}
                  </button>
                </div>

                <div
                  className={`w-full max-w-sm border rounded-3xl overflow-hidden shadow-xs relative h-[520px] select-none flex flex-col transition-colors ${
                    simDarkTheme
                      ? 'bg-zinc-950 border-zinc-800 text-zinc-100'
                      : 'bg-white border-slate-300 text-slate-900'
                  }`}
                >
                  <div
                    className={`px-6 py-3 flex items-center justify-between text-xs font-mono border-b ${
                      simDarkTheme
                        ? 'text-zinc-400 border-zinc-800/80'
                        : 'text-slate-500 border-slate-100'
                    }`}
                  >
                    <span>09:41</span>
                    <span>Gemini · Usage Limits</span>
                  </div>

                  <div
                    className={`p-6 space-y-5 flex-1 ${
                      simDarkTheme ? 'bg-zinc-900/50' : 'bg-slate-50/60'
                    }`}
                  >
                    <div className="pt-8">
                      <div className="text-base font-bold">
                        {isZh ? 'Gemini 用量与限额' : 'Gemini Usage Limits'}
                      </div>
                      <div
                        className={`mt-1 text-xs ${
                          simDarkTheme ? 'text-zinc-400' : 'text-slate-500'
                        }`}
                      >
                        com.google.android.googlequicksearchbox ({activeSample.source})
                      </div>
                    </div>

                    <div
                      className={`border rounded-xl p-4 space-y-3 ${
                        simDarkTheme
                          ? 'bg-zinc-900 border-zinc-800'
                          : 'bg-white border-slate-200'
                      }`}
                    >
                      <div className="flex justify-between text-xs">
                        <span className={simDarkTheme ? 'text-zinc-400' : 'text-slate-500'}>
                          {isZh ? '5 小时窗口已用' : '5-hour window'}
                        </span>
                        <span className="font-mono font-semibold">
                          {activeSample.parsed.fiveHourQuota}
                        </span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className={simDarkTheme ? 'text-zinc-400' : 'text-slate-500'}>
                          {isZh ? '每周总限额已用' : 'Weekly cap'}
                        </span>
                        <span className="font-mono font-semibold">
                          {activeSample.parsed.weeklyLimit}
                        </span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className={simDarkTheme ? 'text-zinc-400' : 'text-slate-500'}>
                          {isZh ? '下次重置倒计时' : 'Next reset'}
                        </span>
                        <span className="font-mono font-semibold">
                          {activeSample.parsed.resetTimer}
                        </span>
                      </div>
                    </div>

                    <div
                      className={`flex items-center gap-2 text-xs ${
                        simDarkTheme ? 'text-zinc-400' : 'text-slate-500'
                      }`}
                    >
                      <Layers className="w-3.5 h-3.5 shrink-0" />
                      <span>
                        {isZh
                          ? '单行半透明圆角胶囊：支持拖拽、单击切换 ZH/EN、自动随宿主深浅色变色'
                          : 'Single-line translucent pill HUD: draggable, tap to toggle ZH/EN, adaptive Light/Dark'}
                      </span>
                    </div>
                  </div>

                  {/* Compact Single-Line Horizontal Adaptive HUD Pill */}
                  <button
                    ref={hudBtnRef}
                    type="button"
                    onPointerDown={onHudPointerDown}
                    onPointerMove={onHudPointerMove}
                    onPointerUp={onHudPointerUp}
                    className={`absolute top-12 right-3 max-w-[92%] inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full backdrop-blur-md border shadow-md text-[11.5px] font-mono whitespace-nowrap overflow-hidden cursor-grab active:cursor-grabbing touch-none transition-colors ${
                      simDarkTheme
                        ? 'bg-zinc-900/85 border-white/25 text-slate-50'
                        : 'bg-white/90 border-slate-900/15 text-slate-900'
                    }`}
                  >
                    <span
                      className={`w-1.5 h-1.5 rounded-full shrink-0 ${
                        simDarkTheme ? 'bg-emerald-400' : 'bg-emerald-600'
                      }`}
                    />
                    <span className="truncate">
                      <span className={simDarkTheme ? 'text-slate-400' : 'text-slate-600'}>
                        5h{' '}
                      </span>
                      <span className="font-bold">{activeSample.parsed.fiveHourQuota}</span>
                      <span className={simDarkTheme ? 'text-slate-400' : 'text-slate-600'}>
                        {' · '}
                        {hudLang === 'zh' ? '周 ' : 'Wk '}
                      </span>
                      <span className="font-bold">{activeSample.parsed.weeklyLimit}</span>
                      <span className={simDarkTheme ? 'text-slate-400' : 'text-slate-600'}>
                        {' · '}
                        {hudLang === 'zh' ? '重置 ' : 'Rst '}
                      </span>
                      <span className="font-bold">{activeSample.parsed.resetTimer}</span>
                    </span>
                    <span
                      className={`w-px h-3 mx-0.5 shrink-0 ${
                        simDarkTheme ? 'bg-white/20' : 'bg-slate-900/15'
                      }`}
                    />
                    <span
                      className={`text-[10.5px] font-sans font-bold shrink-0 ${
                        simDarkTheme ? 'text-sky-400' : 'text-sky-600'
                      }`}
                    >
                      {hudLang === 'zh' ? 'ZH' : 'EN'}
                    </span>
                  </button>
                </div>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
