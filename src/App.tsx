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
    id: 'compose',
    labelZh: 'Compose 界面文本嗅探 (StaticLayout)',
    labelEn: 'Compose UI Sniff (StaticLayout)',
    source: 'Compose',
    raw: '5 小时额度剩余 32 / 50 次\n每周限额已用 140 / 350\n3 小时 18 分钟后重置',
    parsed: {
      fiveHourQuota: '32 / 50',
      weeklyLimit: '140 / 350',
      resetTimer: '3h 18m',
      source: 'Compose',
    },
  },
  {
    id: 'json',
    labelZh: 'batchexecute 转义 JSON 载荷',
    labelEn: 'batchexecute Escaped JSON Payload',
    source: 'UTF8',
    raw: ')]}\'\n[["wrb.fr","robin", "{\\"five_hour_quota\\":\\"18/50\\",\\"weekly_limit\\":\\"210/350\\",\\"reset_in\\":\\"01:42:00\\"}"]]',
    parsed: {
      fiveHourQuota: '18/50',
      weeklyLimit: '210/350',
      resetTimer: '01:42:00',
      source: 'UTF8',
    },
  },
  {
    id: 'webview',
    labelZh: 'WebView 用量设置页 DOM 提取',
    labelEn: 'Embedded WebView Usage Page DOM',
    source: 'WebView',
    raw: '5-hour quota: 44 remaining\nWeekly limit: 88% available\nResets in 4h 05m',
    parsed: {
      fiveHourQuota: '44 remaining',
      weeklyLimit: '88% available',
      resetTimer: 'Resets in 4h 05m',
      source: 'WebView',
    },
  },
];

export default function App() {
  const [uiLang, setUiLang] = useState<UiLang>('zh'); // Simplified Chinese default
  const [hudLang, setHudLang] = useState<UiLang>('zh');
  const [activeView, setActiveView] = useState<ActiveView>('fix');
  const [selectedPath, setSelectedPath] = useState<string>(
    'app/src/main/java/com/example/bardquotahook/QuotaHookEntry.kt'
  );
  const [fileQuery, setFileQuery] = useState('');
  const [copiedPath, setCopiedPath] = useState<string | null>(null);
  const [zipDownloaded, setZipDownloaded] = useState(false);

  const [selectedSampleId, setSelectedSampleId] = useState<string>('compose');
  const [hudOffset, setHudOffset] = useState<{ x: number; y: number }>({ x: 0, y: 0 });
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
    link.download = 'BardQuotaHook-Android-Project.zip';
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
      origX: hudOffset.x,
      origY: hudOffset.y,
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
      setHudOffset({
        x: Math.max(-150, Math.min(20, dragRef.current.origX + dx)),
        y: Math.max(-40, Math.min(240, dragRef.current.origY + dy)),
      });
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
            BardQuotaHook
          </div>
          <div className="mt-1 text-xs text-slate-500">
            <span>LSPosed API 82</span>
            <span aria-hidden="true"> · </span>
            <span>AGP 8.5</span>
          </div>
        </div>

        <nav className="p-3 space-y-1 border-b border-slate-200">
          <button
            type="button"
            onClick={() => setActiveView('fix')}
            className={`w-full flex items-center gap-2.5 px-3 py-2 text-sm font-medium rounded-lg transition-colors whitespace-nowrap ${
              activeView === 'fix'
                ? 'bg-slate-900 text-white'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <FolderGit2 className="w-4 h-4 shrink-0" />
            <span className="truncate">
              {isZh ? 'GitHub 推送报错修复' : 'GitHub Push Error Fix'}
            </span>
          </button>

          <button
            type="button"
            onClick={() => setActiveView('files')}
            className={`w-full flex items-center gap-2.5 px-3 py-2 text-sm font-medium rounded-lg transition-colors whitespace-nowrap ${
              activeView === 'files'
                ? 'bg-slate-900 text-white'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <FileCode className="w-4 h-4 shrink-0" />
            <span className="truncate">
              {isZh ? 'Android 工程源码 (10)' : 'Android Source Files (10)'}
            </span>
          </button>

          <button
            type="button"
            onClick={() => setActiveView('simulator')}
            className={`w-full flex items-center gap-2.5 px-3 py-2 text-sm font-medium rounded-lg transition-colors whitespace-nowrap ${
              activeView === 'simulator'
                ? 'bg-slate-900 text-white'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <Smartphone className="w-4 h-4 shrink-0" />
            <span className="truncate">
              {isZh ? '双语悬浮窗交互预览' : 'Bilingual HUD Simulator'}
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
                  className={`w-full text-left px-2.5 py-1.5 rounded-md text-xs font-mono truncate transition-colors ${
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
        {/* Top Bar Contract: Breadcrumb left, 1 primary action right */}
        <header className="flex items-center justify-between gap-8 px-6 py-3.5 bg-white border-b border-slate-200">
          <div className="flex items-center gap-2 text-sm text-slate-600 min-w-0">
            <span className="font-semibold text-slate-900 whitespace-nowrap">
              {isZh ? 'Gemini 用量监控模块' : 'Gemini Quota Module'}
            </span>
            <span aria-hidden="true">/</span>
            <span className="truncate">
              {activeView === 'fix'
                ? isZh
                  ? 'Precondition check failed 解决方案'
                  : 'Precondition check failed Fix'
                : activeView === 'files'
                ? currentFile.path
                : isZh
                ? '悬浮窗与解析器预览'
                : 'Overlay & Sniffer Simulator'}
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
                  ? '已导出完整工程 .zip'
                  : 'Project .zip Downloaded'
                : isZh
                ? '下载完整 Android 工程 (.zip)'
                : 'Download Android Project (.zip)'}
            </span>
          </button>
        </header>

        {/* Workspace Toolbar (Language Switcher + Summary Metadata) */}
        <div className="px-6 py-3 bg-slate-50 border-b border-slate-200 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-2 text-xs text-slate-600">
            <span>com.google.android.apps.bard</span>
            <span aria-hidden="true">·</span>
            <span>com.google.android.googlequicksearchbox</span>
            <span aria-hidden="true">·</span>
            <span>WeakReference Memory-Safe</span>
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
                    ? '修复 “Failed to push commit to GitHub: Precondition check failed”'
                    : 'Fixing “Failed to push commit to GitHub: Precondition check failed”'}
                </h1>
                <p className="mt-2 text-sm text-slate-600 max-w-3xl leading-relaxed">
                  {isZh
                    ? '你在 AI Studio 点击右上角 GitHub 按钮推送时出现该报错，主要由以下三个前置条件冲突触发。我们已初始化当前工作区，并提供一键导出完整 Android Gradle 压缩包的直通方案。'
                    : 'This error in AI Studio’s GitHub drawer happens due to three repository state & OAuth scope constraints. We have now initialized this workspace and generated a standalone wrapper-free Android Gradle archive.'}
                </p>
              </div>

              {/* Root causes */}
              <div className="bg-white border border-slate-200 rounded-xl divide-y divide-slate-200">
                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '01. 工作区未初始化或 metadata.json 为空（已自动修复）'
                      : '01. Uninitialized AI Studio Workspace & Empty metadata.json (Fixed)'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? '此前代码仅在聊天框展示，AI Studio 工作区处于空白未保存状态（屏幕上显示 Enjoy these tips while you wait）。现在工作区已完成初始化并将 Android 模块写入 /android-module 目录。'
                      : 'Previously, code existed only in chat replies while the workspace had an empty metadata.json and no saved checkpoint. This workspace is now initialized with all module files under /android-module.'}
                  </p>
                </div>

                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '02. 目标 GitHub 仓库已存在旧提交（分支 SHA 不一致）'
                      : '02. Pushing to an Existing Non-Empty GitHub Repo (Diverged HEAD SHA)'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? '如果你关联了一个已经在网页端改过文件（例如带 README 或手动建过 workflow）的现有仓库，GitHub 会拒绝非快进覆盖并返回 Precondition check failed。使用 AI Studio 推送时必须新建一个空白仓库名称。'
                      : 'Linking AI Studio to an existing repository that already has commits on GitHub causes a branch SHA mismatch. Always create a brand-new repository name if pushing via AI Studio.'}
                  </p>
                </div>

                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '03. AI Studio 的 GitHub 授权不支持直接推送 .github/workflows/*，且根目录并非纯 Android 工程'
                      : '03. GitHub OAuth Token Cannot Push .github/workflows/* & Root Layout Is Vite'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? 'GitHub 限制无 workflow 权限的第三方应用写入 .github/workflows/ 目录，且 AI Studio 根目录是 Web 模板而非 Android 根目录。因此最稳妥的做法是点击右上角「下载完整 Android 工程 (.zip)」，解压后上传到你的新建 GitHub 仓库自动触发云编译。'
                      : 'GitHub rejects commits to .github/workflows/ from OAuth tokens lacking workflow scope, and AI Studio’s root directory is a Vite web project. Downloading the standalone .zip gives you a pure Android root layout with .github/workflows/android.yml included.'}
                  </p>
                </div>
              </div>

              {/* Recommended 3-Step Action Plan */}
              <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-5">
                <div className="flex flex-wrap items-center justify-between gap-4">
                  <div>
                    <h2 className="text-base font-semibold text-slate-900">
                      {isZh
                        ? '推荐操作：3 步完成 GitHub Actions 云端编译 APK（零报错方案）'
                        : 'Recommended Fix: 3 Steps to Cloud-Compile Your APK on GitHub'}
                    </h2>
                    <p className="mt-1 text-xs text-slate-500">
                      {isZh
                        ? '已内置 gradle/actions/setup-gradle@v4，无需本地安装 Android Studio 或生成 gradle-wrapper.jar 二进制文件'
                        : 'Uses gradle/actions/setup-gradle@v4 so no local Android Studio or binary gradle-wrapper.jar is required'}
                    </p>
                  </div>

                  <button
                    type="button"
                    onClick={handleDownloadZip}
                    className="inline-flex items-center gap-2 px-4 py-2 text-xs font-semibold text-slate-900 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors whitespace-nowrap shrink-0 cursor-pointer"
                  >
                    <Download className="w-4 h-4" />
                    <span>
                      {isZh ? '立即导出 .zip 压缩包' : 'Export .zip Archive'}
                    </span>
                  </button>
                </div>

                <ol className="space-y-3 text-sm text-slate-700 list-decimal list-inside leading-relaxed">
                  <li>
                    {isZh
                      ? '点击右上角蓝色按钮「下载完整 Android 工程 (.zip)」并在本地解压（内含 .github/workflows/android.yml、双包作用域与最新防混淆 QuotaHookEntry.kt）。'
                      : 'Click “Download Android Project (.zip)” in the top right and unzip it locally (includes .github/workflows/android.yml, dual-package scope, and R8-proof QuotaHookEntry.kt).'}
                  </li>
                  <li>
                    {isZh
                      ? '在 GitHub.com 新建一个空仓库（不要勾选 Add README），通过 Git 命令行推送解压后的文件夹，或直接在网页端点击 uploading an existing file 拖入全部文件并 Commit。'
                      : 'Create a new empty repository on GitHub.com, then push the unzipped folder via git CLI (or drag-and-drop all unzipped files via “uploading an existing file” on GitHub).'}
                  </li>
                  <li>
                    {isZh
                      ? '打开 GitHub 仓库顶部的 Actions 标签页，等待 Build LSPosed APK 变绿（约 1 分钟），下载底部 Artifacts 中的 APK；在 LSPosed 中同时勾选 Gemini 与 Google 两个应用，强行停止 Google 应用后重新打开 Gemini。'
                      : 'Open the Actions tab on GitHub, download bard-quota-hook-debug from Artifacts once green (~1 min), enable BOTH Gemini and Google in LSPosed Manager, and Force Stop the Google app.'}
                  </li>
                </ol>

                <div className="pt-2">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-medium text-slate-600">
                      {isZh
                        ? '或者使用本地 Git 一键推送解压目录到新仓库：'
                        : 'Or push the unzipped folder via Git CLI to a new empty repo:'}
                    </span>
                    <button
                      type="button"
                      onClick={() =>
                        handleCopy(
                          `git init\ngit add .\ngit commit -m "Initial Android LSPosed module"\ngit branch -M main\ngit remote add origin https://github.com/<YOUR_USER>/<NEW_REPO>.git\ngit push -u origin main`,
                          'git-cli'
                        )
                      }
                      className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-600 hover:text-slate-900 cursor-pointer"
                    >
                      {copiedPath === 'git-cli' ? (
                        <Check className="w-3.5 h-3.5 text-emerald-600" />
                      ) : (
                        <Copy className="w-3.5 h-3.5" />
                      )}
                      <span>{copiedPath === 'git-cli' ? (isZh ? '已复制' : 'Copied') : isZh ? '复制命令' : 'Copy'}</span>
                    </button>
                  </div>
                  <pre className="p-4 rounded-lg bg-slate-900 text-slate-100 text-xs font-mono overflow-x-auto leading-relaxed">
{`git init
git add .
git commit -m "Initial Android LSPosed module"
git branch -M main
git remote add origin https://github.com/<YOUR_USER>/<NEW_REPO>.git
git push -u origin main`}
                  </pre>
                </div>
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
                    {isZh ? '多源嗅探与双语悬浮窗验证' : 'Multi-Layer Sniffer & Bilingual HUD Test'}
                  </h2>
                  <p className="mt-1 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? '选择下方不同数据源模拟 Gemini Android 内部流量或界面渲染文本；在右侧手机预览框中可按住拖动悬浮窗，或单击悬浮窗在「简体中文」与「English」之间实时切换。'
                      : 'Select a payload source below to test extraction; drag the floating HUD inside the phone frame or single-tap it to toggle Simplified Chinese and English.'}
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
              <div className="lg:col-span-6 flex justify-center">
                <div className="w-full max-w-sm bg-white border border-slate-300 rounded-3xl overflow-hidden shadow-xs relative h-[520px] select-none flex flex-col">
                  {/* Mock Status Bar */}
                  <div className="px-6 py-3 flex items-center justify-between text-xs font-mono text-slate-500 border-b border-slate-100">
                    <span>09:41</span>
                    <span>Gemini · Usage Limits</span>
                  </div>

                  {/* Mock Gemini Usage Limits Page */}
                  <div className="p-6 space-y-5 flex-1 bg-slate-50/60">
                    <div>
                      <div className="text-base font-bold text-slate-900">
                        {isZh ? 'Gemini 用量与限额' : 'Gemini Usage Limits'}
                      </div>
                      <div className="mt-1 text-xs text-slate-500">
                        com.google.android.googlequicksearchbox (Robin)
                      </div>
                    </div>

                    <div className="bg-white border border-slate-200 rounded-xl p-4 space-y-3">
                      <div className="flex justify-between text-xs">
                        <span className="text-slate-500">
                          {isZh ? '5 小时对话窗口' : '5-hour window'}
                        </span>
                        <span className="font-mono font-semibold text-slate-900">
                          {activeSample.parsed.fiveHourQuota}
                        </span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-slate-500">
                          {isZh ? '每周总限额' : 'Weekly cap'}
                        </span>
                        <span className="font-mono font-semibold text-slate-900">
                          {activeSample.parsed.weeklyLimit}
                        </span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-slate-500">
                          {isZh ? '下次重置计时' : 'Next reset'}
                        </span>
                        <span className="font-mono font-semibold text-slate-900">
                          {activeSample.parsed.resetTimer}
                        </span>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 text-xs text-slate-500">
                      <Layers className="w-3.5 h-3.5 shrink-0" />
                      <span>
                        {isZh
                          ? '提示：按住深色悬浮窗可任意拖动，轻点可切换中英文'
                          : 'Tip: Drag the dark HUD badge or tap once to switch ZH/EN'}
                      </span>
                    </div>
                  </div>

                  {/* Injected Xposed Floating TextView Overlay */}
                  <button
                    type="button"
                    onPointerDown={onHudPointerDown}
                    onPointerMove={onHudPointerMove}
                    onPointerUp={onHudPointerUp}
                    style={{
                      transform: `translate(${hudOffset.x}px, ${hudOffset.y}px)`,
                    }}
                    className="absolute top-16 right-4 text-left px-3.5 py-2.5 rounded-xl bg-neutral-900/90 text-white border border-white/25 shadow-md text-[11px] font-mono leading-relaxed cursor-grab active:cursor-grabbing touch-none"
                  >
                    {hudLang === 'zh' ? (
                      <>
                        <div className="font-semibold text-amber-200">
                          用量监控 [{activeSample.parsed.source} | 点按切EN]
                        </div>
                        <div>5小时额度: {activeSample.parsed.fiveHourQuota}</div>
                        <div>每周限额: {activeSample.parsed.weeklyLimit}</div>
                        <div>重置时间: {activeSample.parsed.resetTimer}</div>
                      </>
                    ) : (
                      <>
                        <div className="font-semibold text-sky-200">
                          Usage Limits [{activeSample.parsed.source} | Tap-&gt;中文]
                        </div>
                        <div>5h Quota: {activeSample.parsed.fiveHourQuota}</div>
                        <div>Weekly Limit: {activeSample.parsed.weeklyLimit}</div>
                        <div>Reset Timer: {activeSample.parsed.resetTimer}</div>
                      </>
                    )}
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
