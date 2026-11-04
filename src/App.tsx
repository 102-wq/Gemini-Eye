import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Check,
  Copy,
  Download,
  ExternalLink,
  FileCode,
  FolderGit2,
  Moon,
  RefreshCw,
  Search,
  Send,
  ShieldCheck,
  Smartphone,
  UserCheck,
} from 'lucide-react';
import { PROJECT_FILES } from './moduleFiles';
import { buildZipBlob } from './zipBuilder';

type UiLang = 'zh' | 'en';
type HudTheme = 'MIUIX' | 'MD3';
type ActiveView = 'simulator' | 'files' | 'spec';

interface HudSnapshot {
  fiveHourPct: number;
  fiveHourQuota: string;
  fiveHourReset: string;
  weeklyPct: number;
  weeklyLimit: string;
  weeklyReset: string;
  source: string;
}

interface SimulatedAccountSession {
  id: string;
  sessionTag: string;
  accountLabelZh: string;
  accountLabelEn: string;
  headerSummary: string;
  parsed: HudSnapshot;
}

const ACCOUNT_SESSIONS: SimulatedAccountSession[] = [
  {
    id: 'account-primary',
    sessionTag: 'sess_8f3a91c04b12',
    accountLabelZh: '主账号 · Google Pro (X-Goog-AuthUser: 0)',
    accountLabelEn: 'Primary Account · Google Pro (X-Goog-AuthUser: 0)',
    headerSummary: 'Cronet UrlRequest.Builder.addHeader("Cookie", "__Secure-1PSID=...") -> sess_8f3a91c04b12',
    parsed: {
      fiveHourPct: 15,
      fiveHourQuota: '15%',
      fiveHourReset: '1h52m',
      weeklyPct: 29,
      weeklyLimit: '29%',
      weeklyReset: '23h52m',
      source: 'Cronet-PB',
    },
  },
  {
    id: 'account-workspace',
    sessionTag: 'sess_2d7e44b910ca',
    accountLabelZh: '切换账号 · Workspace 账号 (X-Goog-AuthUser: 1)',
    accountLabelEn: 'Switched Account · Workspace (X-Goog-AuthUser: 1)',
    headerSummary: 'gRPC Metadata.put("authorization", "Bearer ya29...") -> sess_2d7e44b910ca',
    parsed: {
      fiveHourPct: 42,
      fiveHourQuota: '42%',
      fiveHourReset: '3h18m',
      weeklyPct: 64,
      weeklyLimit: '64%',
      weeklyReset: '4d12h',
      source: 'gRPC-PB',
    },
  },
  {
    id: 'account-heavy',
    sessionTag: 'sess_c91b05e781f4',
    accountLabelZh: '备用账号 · 高负载会话 (X-Goog-AuthUser: 2)',
    accountLabelEn: 'Secondary Account · High Usage (X-Goog-AuthUser: 2)',
    headerSummary: 'OkHttp Request.Builder.header("Authorization", "Bearer ya29...") -> sess_c91b05e781f4',
    parsed: {
      fiveHourPct: 84,
      fiveHourQuota: '84%',
      fiveHourReset: '28m',
      weeklyPct: 92,
      weeklyLimit: '92%',
      weeklyReset: '11h05m',
      source: 'StateFlow',
    },
  },
];

export default function App() {
  const [uiLang, setUiLang] = useState<UiLang>('zh');
  const [hudLang, setHudLang] = useState<UiLang>('zh');
  const [hudTheme, setHudTheme] = useState<HudTheme>('MIUIX');
  const [activeView, setActiveView] = useState<ActiveView>('simulator');
  const [selectedPath, setSelectedPath] = useState<string>(
    'app/src/main/java/com/example/bardquotahook/QuotaCoreEngine.kt'
  );
  const [fileQuery, setFileQuery] = useState('');
  const [copiedPath, setCopiedPath] = useState<string | null>(null);
  const [zipDownloaded, setZipDownloaded] = useState(false);

  const [selectedAccountId, setSelectedAccountId] = useState<string>('account-primary');
  const [simDarkTheme, setSimDarkTheme] = useState<boolean>(false);
  const [isGeminiForeground, setIsGeminiForeground] = useState<boolean>(true);
  const [isRefreshingHud, setIsRefreshingHud] = useState<boolean>(false);
  const [usageSheetOpen, setUsageSheetOpen] = useState<boolean>(false);
  const [lastSyncNote, setLastSyncNote] = useState<string>('');

  const hudCapsuleRef = useRef<HTMLDivElement | null>(null);
  const bar5hRef = useRef<HTMLDivElement | null>(null);
  const barWeeklyRef = useRef<HTMLDivElement | null>(null);
  const settingsBar5hRef = useRef<HTMLDivElement | null>(null);
  const settingsBarWeeklyRef = useRef<HTMLDivElement | null>(null);

  const hudPosRef = useRef<{ x: number; y: number }>({ x: 0, y: 0 });
  const dragRef = useRef<{
    startX: number;
    startY: number;
    origX: number;
    origY: number;
    moved: boolean;
    pointerDown: boolean;
  }>({ startX: 0, startY: 0, origX: 0, origY: 0, moved: false, pointerDown: false });

  const isZh = uiLang === 'zh';

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

  const activeSession = useMemo(
    () => ACCOUNT_SESSIONS.find((s) => s.id === selectedAccountId) ?? ACCOUNT_SESSIONS[0],
    [selectedAccountId]
  );

  // Update mini progress bar fill widths via DOM refs (no inline style props)
  useEffect(() => {
    const w5 = `${Math.max(8, Math.min(100, activeSession.parsed.fiveHourPct))}%`;
    const wWk = `${Math.max(8, Math.min(100, activeSession.parsed.weeklyPct))}%`;
    if (bar5hRef.current) bar5hRef.current.style.width = w5;
    if (barWeeklyRef.current) barWeeklyRef.current.style.width = wWk;
    if (settingsBar5hRef.current) settingsBar5hRef.current.style.width = w5;
    if (settingsBarWeeklyRef.current) settingsBarWeeklyRef.current.style.width = wWk;
  }, [activeSession, activeView, isGeminiForeground]);

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
    link.download = 'Gemini-Eye-v2.1.0-Android-Project.zip';
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

  const handleSwitchAccount = (acct: SimulatedAccountSession) => {
    setSelectedAccountId(acct.id);
    setIsRefreshingHud(true);
    setLastSyncNote(
      isZh
        ? `检测到出站动态请求头变更 (${acct.sessionTag})：已自动隔离旧账号缓存并切换至当前 Google 账号额度`
        : `Dynamic outgoing auth/cookie header changed (${acct.sessionTag}): isolated previous cache & switched to active Google account quota`
    );
    setTimeout(() => setIsRefreshingHud(false), 550);
  };

  const handleToggleForeground = (fg: boolean) => {
    setIsGeminiForeground(fg);
    setUsageSheetOpen(false);
    setLastSyncNote(
      fg
        ? isZh
          ? 'Gemini 进入前台 (APP_OPEN)：HUD 已挂载并触发一次轻量级前台额度刷新'
          : 'Gemini entered foreground (APP_OPEN): HUD attached & triggered single lightweight refresh'
        : isZh
        ? 'Gemini 退至后台 (TRIM_MEMORY_UI_HIDDEN)：HUD 已卸载、注销监听、清空临时凭证缓冲，工作线程降至 0'
        : 'Gemini moved to background: HUD detached, observers unregistered, buffers wiped, 0 active threads'
    );
  };

  const handleSimulateQuerySend = () => {
    if (!isGeminiForeground) return;
    setIsRefreshingHud(true);
    setLastSyncNote(
      isZh
        ? `发送提问事件 (QUERY_SENT · ${activeSession.sessionTag})：实时携带当前账号 Authorization/Cookie，流结束后触发单次轻量额度同步`
        : `User query sent (QUERY_SENT · ${activeSession.sessionTag}): intercepted live Authorization/Cookie header & triggered lightweight post-query sync`
    );
    setTimeout(() => setIsRefreshingHud(false), 600);
  };

  const triggerSimulatedRefresh = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (isRefreshingHud || !isGeminiForeground) return;
    setIsRefreshingHud(true);
    setLastSyncNote(
      isZh
        ? `手动前台刷新 (MANUAL_TAP · ${activeSession.sessionTag})：扫描当前前台 Activity ViewModel / StateFlow 状态（零网页轮询 / 零常驻服务）`
        : `Manual foreground refresh (MANUAL_TAP · ${activeSession.sessionTag}): scanned foreground Activity ViewModel / StateFlow holders (zero web polling)`
    );
    setTimeout(() => {
      setIsRefreshingHud(false);
    }, 650);
  };

  const triggerOpenUsageWebpage = (e: React.MouseEvent) => {
    e.stopPropagation();
    setUsageSheetOpen(true);
  };

  const onHudPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    const target = e.target as HTMLElement;
    if (target.closest('[data-hud-action="true"]')) {
      return;
    }
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

  const onHudPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragRef.current.pointerDown) return;
    const dx = e.clientX - dragRef.current.startX;
    const dy = e.clientY - dragRef.current.startY;
    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
      dragRef.current.moved = true;
    }
    if (dragRef.current.moved) {
      const nextX = Math.max(-48, Math.min(24, dragRef.current.origX + dx));
      const nextY = Math.max(-24, Math.min(240, dragRef.current.origY + dy));
      hudPosRef.current = { x: nextX, y: nextY };
      if (hudCapsuleRef.current) {
        hudCapsuleRef.current.style.translate = `${nextX}px ${nextY}px`;
      }
    }
  };

  const onHudPointerUp = () => {
    dragRef.current.pointerDown = false;
  };

  const pickBarColorClass = (pct: number) => {
    if (pct >= 90) return simDarkTheme ? 'bg-red-400' : 'bg-red-500';
    if (pct >= 75) return simDarkTheme ? 'bg-amber-400' : 'bg-amber-500';
    if (hudTheme === 'MD3') {
      return simDarkTheme ? 'bg-violet-300' : 'bg-violet-600';
    }
    return simDarkTheme ? 'bg-sky-400' : 'bg-blue-500';
  };

  const capsuleContainerClass = useMemo(() => {
    if (hudTheme === 'MIUIX') {
      return simDarkTheme
        ? 'bg-zinc-900/80 backdrop-blur-xl border-white/20 text-slate-50 shadow-lg shadow-black/40'
        : 'bg-white/85 backdrop-blur-xl border-slate-900/12 text-slate-900 shadow-lg shadow-slate-900/12';
    }
    return simDarkTheme
      ? 'bg-[#292533]/95 border-[#938F99]/40 text-[#E6E1E5] shadow-md shadow-black/40'
      : 'bg-[#F5EFFB]/95 border-[#CAC4D0]/70 text-[#1D1B20] shadow-md shadow-indigo-950/10';
  }, [hudTheme, simDarkTheme]);

  const capsuleLabelClass = useMemo(() => {
    if (hudTheme === 'MIUIX') {
      return simDarkTheme ? 'text-slate-400' : 'text-slate-500';
    }
    return simDarkTheme ? 'text-[#CAC4D0]' : 'text-[#49454F]';
  }, [hudTheme, simDarkTheme]);

  const capsuleTrackClass = useMemo(() => {
    if (hudTheme === 'MIUIX') {
      return simDarkTheme ? 'bg-white/20' : 'bg-slate-900/15';
    }
    return simDarkTheme ? 'bg-[#4A4458]' : 'bg-[#E8DEF8]';
  }, [hudTheme, simDarkTheme]);

  const capsuleIconClass = useMemo(() => {
    if (hudTheme === 'MIUIX') {
      return simDarkTheme
        ? 'text-slate-300 hover:bg-white/15 active:bg-white/25'
        : 'text-slate-600 hover:bg-slate-900/10 active:bg-slate-900/15';
    }
    return simDarkTheme
      ? 'text-violet-300 hover:bg-violet-300/15 active:bg-violet-300/25'
      : 'text-violet-700 hover:bg-violet-700/10 active:bg-violet-700/20';
  }, [hudTheme, simDarkTheme]);

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 flex flex-col lg:flex-row">
      {/* Sidebar Navigation */}
      <aside className="w-full lg:w-64 shrink-0 bg-white border-b lg:border-b-0 lg:border-r border-slate-200 flex flex-col">
        <div className="px-5 py-4 border-b border-slate-200">
          <div className="text-base font-bold tracking-tight text-slate-900 whitespace-nowrap">
            Gemini-Eye
          </div>
          <div className="mt-1 text-xs text-slate-500">
            <span>Zero-Background · API 102 + 82</span>
            <span aria-hidden="true"> · </span>
            <span>v2.1.0</span>
          </div>
        </div>

        <nav className="p-3 space-y-1 border-b border-slate-200">
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
              {isZh ? '动态切号与纯前台预览' : 'Session & Foreground Simulator'}
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
            onClick={() => setActiveView('spec')}
            className={`w-full flex items-center gap-2.5 px-3 py-2 text-sm font-medium rounded-lg transition-colors whitespace-nowrap cursor-pointer ${
              activeView === 'spec'
                ? 'bg-slate-900 text-white'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            }`}
          >
            <FolderGit2 className="w-4 h-4 shrink-0" />
            <span className="truncate">
              {isZh ? 'v2.1.0 零后台动态会话架构' : 'v2.1.0 Zero-Background Spec'}
            </span>
          </button>
        </nav>

        <div className="p-3 flex-1 overflow-y-auto hidden lg:block">
          <div className="px-2 pb-2 text-xs font-medium text-slate-500">
            {isZh ? '核心文件直达' : 'Project File Tree'}
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
        <header className="flex items-center justify-between gap-6 px-6 py-3.5 bg-white border-b border-slate-200">
          <div className="flex items-center gap-2 text-sm text-slate-600 min-w-0">
            <span className="font-semibold text-slate-900 whitespace-nowrap">
              {isZh ? 'Gemini 零后台胶囊监控模块' : 'Gemini Zero-Background HUD'}
            </span>
            <span aria-hidden="true">/</span>
            <span className="truncate">
              {activeView === 'simulator'
                ? isZh
                  ? '动态 Authorization/Cookie 切号隔离 · 前台挂载 / 后台完全休眠'
                  : 'Dynamic Session Interception · Foreground-Only Lifecycle'
                : activeView === 'files'
                ? currentFile.path
                : isZh
                ? 'v2.1.0 零后台与动态多账号架构说明'
                : 'v2.1.0 Zero-Background & Dynamic Session Spec'}
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
                  ? '已导出 v2.1.0 工程 .zip'
                  : 'v2.1.0 .zip Downloaded'
                : isZh
                ? '导出 v2.1.0 Android 工程 (.zip)'
                : 'Export v2.1.0 Project (.zip)'}
            </span>
          </button>
        </header>

        {/* Workspace Toolbar */}
        <div className="px-6 py-2.5 bg-slate-50 border-b border-slate-200 flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-wrap items-center gap-2 text-xs text-slate-600">
            <button
              type="button"
              onClick={() => {
                setSelectedPath('app/src/main/java/com/example/bardquotahook/ModernQuotaModule.kt');
                setActiveView('files');
              }}
              className="font-mono text-slate-800 hover:text-blue-600 underline underline-offset-4 cursor-pointer"
            >
              ModernQuotaModule.kt
            </button>
            <span aria-hidden="true">·</span>
            <button
              type="button"
              onClick={() => {
                setSelectedPath('app/src/main/java/com/example/bardquotahook/QuotaCoreEngine.kt');
                setActiveView('files');
              }}
              className="font-mono text-slate-800 hover:text-blue-600 underline underline-offset-4 cursor-pointer"
            >
              QuotaCoreEngine.kt
            </button>
            <span aria-hidden="true">·</span>
            <button
              type="button"
              onClick={() => {
                setSelectedPath('app/src/main/java/com/example/bardquotahook/QuotaHookEntry.kt');
                setActiveView('files');
              }}
              className="font-mono text-slate-800 hover:text-blue-600 underline underline-offset-4 cursor-pointer"
            >
              QuotaHookEntry.kt
            </button>
            <span aria-hidden="true">·</span>
            <button
              type="button"
              onClick={() => {
                setSelectedPath('app/proguard-rules.pro');
                setActiveView('files');
              }}
              className="font-mono text-slate-800 hover:text-blue-600 underline underline-offset-4 cursor-pointer"
            >
              proguard-rules.pro
            </button>
            <span aria-hidden="true">·</span>
            <button
              type="button"
              onClick={() => {
                setSelectedPath('app/src/main/AndroidManifest.xml');
                setActiveView('files');
              }}
              className="font-mono text-slate-800 hover:text-blue-600 underline underline-offset-4 cursor-pointer"
            >
              AndroidManifest.xml
            </button>
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
              中文工作台
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

        {/* Main Workspace Body */}
        <main className="p-6 max-w-6xl w-full mx-auto flex-1">
          {activeView === 'simulator' && (
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
              {/* Left Column: Lifecycle, Account Session & Theme Controls */}
              <div className="lg:col-span-6 space-y-5">
                {/* 1. Zero-Background Lifecycle Gate */}
                <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-3">
                  <div className="flex items-center justify-between gap-3">
                    <div className="text-sm font-semibold text-slate-900">
                      {isZh
                        ? '1. 零后台应用内生命周期 (Zero-Background Lifecycle)'
                        : '1. Zero-Background In-App Lifecycle'}
                    </div>
                    <ShieldCheck className="w-4 h-4 text-emerald-600 shrink-0" />
                  </div>

                  <p className="text-xs text-slate-600 leading-relaxed">
                    {isZh
                      ? '无任何常驻 Service 或轮询定时器。仅当 Gemini Activity 进入前台时挂载 HUD；切回桌面或锁屏时立即卸载 HUD、注销监听并将线程池缩减至 0 线程。'
                      : 'Zero background or foreground services. HUD attaches strictly when Gemini enters foreground and detaches + spins down to 0 threads on background.'}
                  </p>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 pt-1">
                    <button
                      type="button"
                      onClick={() => handleToggleForeground(true)}
                      className={`px-3 py-2 text-xs font-medium rounded-lg border transition-colors text-left cursor-pointer ${
                        isGeminiForeground
                          ? 'bg-emerald-50 border-emerald-300 text-emerald-900'
                          : 'bg-white border-slate-200 text-slate-700 hover:bg-slate-50'
                      }`}
                    >
                      <div className="font-semibold">
                        {isZh ? 'Gemini 前台运行中 (Awake)' : 'Gemini Foreground (Awake)'}
                      </div>
                      <div className="text-[11px] opacity-80 mt-0.5">
                        {isZh ? 'HUD 已挂载 · 按需事件触发' : 'HUD attached · Event-driven'}
                      </div>
                    </button>

                    <button
                      type="button"
                      onClick={() => handleToggleForeground(false)}
                      className={`px-3 py-2 text-xs font-medium rounded-lg border transition-colors text-left cursor-pointer ${
                        !isGeminiForeground
                          ? 'bg-slate-900 border-slate-900 text-white'
                          : 'bg-white border-slate-200 text-slate-700 hover:bg-slate-50'
                      }`}
                    >
                      <div className="font-semibold flex items-center gap-1.5">
                        <Moon className="w-3.5 h-3.5" />
                        <span>
                          {isZh ? '退至后台休眠 (Sleep)' : 'Background Sleep (0 Threads)'}
                        </span>
                      </div>
                      <div className="text-[11px] opacity-80 mt-0.5">
                        {isZh ? 'HUD 已卸载 · 0 后台线程耗电' : 'HUD detached · 0 idle CPU/threads'}
                      </div>
                    </button>
                  </div>
                </div>

                {/* 2. Dynamic Session & Google Account Switcher */}
                <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-3">
                  <div className="flex items-center justify-between gap-3">
                    <div className="text-sm font-semibold text-slate-900">
                      {isZh
                        ? '2. 动态 Authorization / Cookie 切号拦截'
                        : '2. Dynamic Session & Cookie Account Switching'}
                    </div>
                    <UserCheck className="w-4 h-4 text-blue-600 shrink-0" />
                  </div>

                  <p className="text-xs text-slate-600 leading-relaxed">
                    {isZh
                      ? '绝不硬编码静态 Cookie 或 Token。实时从 Cronet / gRPC Metadata / OkHttp 出站请求头提取稳定会话指纹，应用内切换 Google 账号时立即隔离旧缓存并无缝刷新：'
                      : 'Never uses hardcoded cookies/tokens. Dynamically intercepts outgoing Cronet/gRPC headers to isolate per-account quota states on Google account switch:'}
                  </p>

                  <div className="space-y-2">
                    {ACCOUNT_SESSIONS.map((acct) => {
                      const selected = acct.id === activeSession.id;
                      return (
                        <button
                          key={acct.id}
                          type="button"
                          onClick={() => handleSwitchAccount(acct)}
                          className={`w-full text-left p-3 rounded-lg border transition-colors cursor-pointer ${
                            selected
                              ? 'bg-slate-900 text-white border-slate-900'
                              : 'bg-slate-50 text-slate-800 border-slate-200 hover:border-slate-300'
                          }`}
                        >
                          <div className="flex items-center justify-between gap-2 text-xs font-medium">
                            <span>{isZh ? acct.accountLabelZh : acct.accountLabelEn}</span>
                            <span className="font-mono opacity-80">{acct.sessionTag}</span>
                          </div>
                          <div className="mt-1 text-[11px] font-mono opacity-75 truncate">
                            {acct.headerSummary}
                          </div>
                        </button>
                      );
                    })}
                  </div>
                </div>

                {/* 3. Dual Language (i18n) & Theme Engine */}
                <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-4">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '3. 双语标签 (i18n) 与双主题引擎 (MIUIX vs MD3)'
                      : '3. Dual Language (i18n) & Theme Engine (MIUIX vs MD3)'}
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <div className="text-xs text-slate-500 mb-1.5">
                        {isZh ? 'HUD 语言切换 (i18n)' : 'HUD Language (i18n)'}
                      </div>
                      <div className="grid grid-cols-2 gap-1.5 p-1 bg-slate-100 rounded-lg">
                        <button
                          type="button"
                          onClick={() => setHudLang('zh')}
                          className={`py-1.5 text-xs font-medium rounded-md transition-colors cursor-pointer ${
                            hudLang === 'zh'
                              ? 'bg-slate-900 text-white'
                              : 'text-slate-600 hover:text-slate-900'
                          }`}
                        >
                          5h / 每周 (CN)
                        </button>
                        <button
                          type="button"
                          onClick={() => setHudLang('en')}
                          className={`py-1.5 text-xs font-medium rounded-md transition-colors cursor-pointer ${
                            hudLang === 'en'
                              ? 'bg-slate-900 text-white'
                              : 'text-slate-600 hover:text-slate-900'
                          }`}
                        >
                          5h / Weekly (EN)
                        </button>
                      </div>
                    </div>

                    <div>
                      <div className="text-xs text-slate-500 mb-1.5">
                        {isZh ? '胶囊视觉引擎' : 'Capsule Theme Engine'}
                      </div>
                      <div className="grid grid-cols-2 gap-1.5 p-1 bg-slate-100 rounded-lg">
                        <button
                          type="button"
                          onClick={() => setHudTheme('MIUIX')}
                          className={`py-1.5 text-xs font-medium rounded-md transition-colors cursor-pointer ${
                            hudTheme === 'MIUIX'
                              ? 'bg-sky-600 text-white'
                              : 'text-slate-600 hover:text-slate-900'
                          }`}
                        >
                          MIUIX 毛玻璃
                        </button>
                        <button
                          type="button"
                          onClick={() => setHudTheme('MD3')}
                          className={`py-1.5 text-xs font-medium rounded-md transition-colors cursor-pointer ${
                            hudTheme === 'MD3'
                              ? 'bg-violet-600 text-white'
                              : 'text-slate-600 hover:text-slate-900'
                          }`}
                        >
                          MD3 动态取色
                        </button>
                      </div>
                    </div>
                  </div>

                  {/* Identical Settings App Mirror */}
                  <div className="pt-2 border-t border-slate-100">
                    <div className="text-[11px] text-slate-500 mb-2">
                      {isZh
                        ? `MainActivity 同步镜像 (当前会话 ${activeSession.sessionTag})：`
                        : `MainActivity Mirror (Session ${activeSession.sessionTag}):`}
                    </div>
                    <div className="px-3 py-2 rounded-lg bg-slate-900 text-slate-100 font-mono text-xs flex flex-wrap items-center justify-between gap-2">
                      <span className="inline-flex items-center gap-1.5">
                        <span className="text-slate-400">5h</span>
                        <span className="w-6 h-1.5 rounded-full bg-white/20 overflow-hidden inline-block">
                          <span ref={settingsBar5hRef} className="block h-full rounded-full bg-sky-400" />
                        </span>
                        <span>
                          {activeSession.parsed.fiveHourQuota} ({activeSession.parsed.fiveHourReset})
                        </span>
                      </span>
                      <span className="inline-flex items-center gap-1.5">
                        <span className="text-slate-400">{hudLang === 'zh' ? '每周' : 'Weekly'}</span>
                        <span className="w-6 h-1.5 rounded-full bg-white/20 overflow-hidden inline-block">
                          <span ref={settingsBarWeeklyRef} className="block h-full rounded-full bg-sky-400" />
                        </span>
                        <span>
                          {activeSession.parsed.weeklyLimit} ({activeSession.parsed.weeklyReset})
                        </span>
                      </span>
                    </div>
                  </div>
                </div>
              </div>

              {/* Right Column: Simulated Gemini Host Screen */}
              <div className="lg:col-span-6 flex flex-col items-center gap-3">
                <div className="w-full max-w-md flex items-center justify-between px-1">
                  <span className="text-xs font-medium text-slate-600">
                    {isZh ? 'Gemini 宿主深浅色自适应测试' : 'Gemini Host Light / Dark Mode'}
                  </span>
                  <button
                    type="button"
                    onClick={() => setSimDarkTheme((d) => !d)}
                    className="px-3 py-1 text-xs font-medium rounded-lg border border-slate-300 bg-white text-slate-800 hover:bg-slate-100 transition-colors cursor-pointer"
                  >
                    {simDarkTheme
                      ? isZh
                        ? '当前：深色宿主 (切换浅色)'
                        : 'Host: Dark (Switch to Light)'
                      : isZh
                      ? '当前：浅色宿主 (切换深色)'
                      : 'Host: Light (Switch to Dark)'}
                  </button>
                </div>

                <div
                  className={`w-full max-w-md border rounded-3xl overflow-hidden shadow-sm relative h-[520px] select-none flex flex-col transition-colors ${
                    simDarkTheme
                      ? 'bg-zinc-950 border-zinc-800 text-zinc-100'
                      : 'bg-[#F8FAFC] border-slate-300 text-slate-900'
                  }`}
                >
                  {/* Simulated Android Status Bar */}
                  <div
                    className={`px-6 py-2.5 flex items-center justify-between text-xs font-mono border-b ${
                      simDarkTheme
                        ? 'text-zinc-400 border-zinc-800/80 bg-zinc-950'
                        : 'text-slate-500 border-slate-200/80 bg-white'
                    }`}
                  >
                    <span>09:41</span>
                    <span>
                      {isGeminiForeground
                        ? `Gemini · ${activeSession.sessionTag}`
                        : isZh
                        ? 'Android 桌面 · Gemini 已后台休眠'
                        : 'Home · Gemini Sleeping (0 Threads)'}
                    </span>
                  </div>

                  {/* Simulated Gemini Body */}
                  <div className="p-6 pt-16 space-y-4 flex-1 flex flex-col justify-between">
                    <div className="space-y-4">
                      {isGeminiForeground ? (
                        <div>
                          <div className="text-base font-bold">
                            {isZh ? '你好，有什么可以帮你？' : 'Hello, how can I help today?'}
                          </div>
                          <div
                            className={`mt-1 text-xs ${
                              simDarkTheme ? 'text-zinc-400' : 'text-slate-500'
                            }`}
                          >
                            {isZh
                              ? '仅在打开 Gemini 或点击下方「发送提问」时触发轻量刷新；切换左侧 Google 账号将实时拦截新会话请求头。'
                              : 'Lightweight quota refresh runs only on Gemini foreground open or query send below.'}
                          </div>
                        </div>
                      ) : (
                        <div className="py-12 text-center space-y-2">
                          <Moon className="w-7 h-7 mx-auto opacity-50" />
                          <div className="text-sm font-semibold">
                            {isZh
                              ? 'Gemini 已进入后台完全休眠态'
                              : 'Gemini Process in Zero-Background Sleep'}
                          </div>
                          <div
                            className={`text-xs max-w-xs mx-auto leading-relaxed ${
                              simDarkTheme ? 'text-zinc-400' : 'text-slate-500'
                            }`}
                          >
                            {isZh
                              ? 'QuotaHudView 已从窗口移除，ContentObserver 已注销，工作线程池已缩减至 0 线程。点击左侧「Gemini 前台运行中」重新唤醒。'
                              : 'QuotaHudView detached, ContentObserver unregistered, and worker pool spun down to 0 threads.'}
                          </div>
                        </div>
                      )}

                      {lastSyncNote && (
                        <div
                          className={`p-3 rounded-xl border text-xs leading-relaxed ${
                            simDarkTheme
                              ? 'bg-emerald-950/50 border-emerald-800/60 text-emerald-200'
                              : 'bg-emerald-50 border-emerald-200 text-emerald-900'
                          }`}
                        >
                          {lastSyncNote}
                        </div>
                      )}

                      {usageSheetOpen && isGeminiForeground && (
                        <div
                          className={`p-4 rounded-2xl border space-y-2.5 ${
                            simDarkTheme
                              ? 'bg-zinc-900 border-zinc-700 text-zinc-100'
                              : 'bg-white border-slate-200 text-slate-900 shadow-xs'
                          }`}
                        >
                          <div className="flex items-center justify-between">
                            <span className="text-xs font-mono font-semibold text-blue-500">
                              com.example.bardquotahook.MainActivity
                            </span>
                            <button
                              type="button"
                              onClick={() => setUsageSheetOpen(false)}
                              className="text-xs text-slate-400 hover:text-slate-600 cursor-pointer"
                            >
                              {isZh ? '关闭' : 'Close'}
                            </button>
                          </div>
                          <div className="text-xs space-y-1">
                            <div className="font-mono text-[11px] opacity-75">
                              Session: {activeSession.sessionTag}
                            </div>
                            <div>
                              5h ({activeSession.parsed.source}):{' '}
                              <strong>{activeSession.parsed.fiveHourQuota}</strong> (
                              {activeSession.parsed.fiveHourReset})
                            </div>
                            <div>
                              Weekly ({activeSession.parsed.source}):{' '}
                              <strong>{activeSession.parsed.weeklyLimit}</strong> (
                              {activeSession.parsed.weeklyReset})
                            </div>
                          </div>
                        </div>
                      )}
                    </div>

                    {/* Simulated Gemini Prompt Bar to trigger QUERY_SENT */}
                    {isGeminiForeground && (
                      <div
                        className={`p-2 rounded-2xl border flex items-center justify-between gap-2 ${
                          simDarkTheme
                            ? 'bg-zinc-900 border-zinc-800'
                            : 'bg-white border-slate-200 shadow-xs'
                        }`}
                      >
                        <span
                          className={`px-2.5 text-xs truncate ${
                            simDarkTheme ? 'text-zinc-400' : 'text-slate-500'
                          }`}
                        >
                          {isZh
                            ? '模拟发送一条 Gemini 提问以触发按需刷新...'
                            : 'Simulate sending a Gemini query...'}
                        </span>
                        <button
                          type="button"
                          onClick={handleSimulateQuerySend}
                          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold bg-blue-600 text-white hover:bg-blue-700 transition-colors shrink-0 cursor-pointer"
                        >
                          <Send className="w-3.5 h-3.5" />
                          <span>{isZh ? '发送提问' : 'Send Query'}</span>
                        </button>
                      </div>
                    )}
                  </div>

                  {/* EXACT SINGLE-LINE CAPSULE HUD (`QuotaHudView.kt` Spec — Foreground Only) */}
                  {isGeminiForeground && (
                    <div
                      ref={hudCapsuleRef}
                      onPointerDown={onHudPointerDown}
                      onPointerMove={onHudPointerMove}
                      onPointerUp={onHudPointerUp}
                      className={`absolute top-12 right-2.5 max-w-[96%] inline-flex items-center px-2.5 py-1.5 rounded-full border text-[11px] leading-none whitespace-nowrap cursor-grab active:cursor-grabbing touch-none transition-colors ${capsuleContainerClass}`}
                    >
                      <span className={`font-semibold ${capsuleLabelClass}`}>5h</span>
                      <span
                        className={`w-6 h-[5px] mx-1.5 rounded-full overflow-hidden shrink-0 ${capsuleTrackClass}`}
                      >
                        <span
                          ref={bar5hRef}
                          className={`block h-full rounded-full transition-all duration-300 ${pickBarColorClass(
                            activeSession.parsed.fiveHourPct
                          )}`}
                        />
                      </span>
                      <span className="font-medium tabular-nums">
                        {activeSession.parsed.fiveHourQuota} ({activeSession.parsed.fiveHourReset})
                      </span>

                      <span
                        className={`w-px h-3 mx-2 shrink-0 ${
                          simDarkTheme ? 'bg-white/15' : 'bg-slate-900/12'
                        }`}
                      />

                      <span className={`font-semibold ${capsuleLabelClass}`}>
                        {hudLang === 'zh' ? '每周' : 'Weekly'}
                      </span>
                      <span
                        className={`w-6 h-[5px] mx-1.5 rounded-full overflow-hidden shrink-0 ${capsuleTrackClass}`}
                      >
                        <span
                          ref={barWeeklyRef}
                          className={`block h-full rounded-full transition-all duration-300 ${pickBarColorClass(
                            activeSession.parsed.weeklyPct
                          )}`}
                        />
                      </span>
                      <span className="font-medium tabular-nums">
                        {activeSession.parsed.weeklyLimit} ({activeSession.parsed.weeklyReset})
                      </span>

                      <span
                        className={`w-px h-3 ml-2 mr-1 shrink-0 ${
                          simDarkTheme ? 'bg-white/15' : 'bg-slate-900/12'
                        }`}
                      />

                      <button
                        type="button"
                        data-hud-action="true"
                        onClick={triggerSimulatedRefresh}
                        title={isZh ? '手动刷新当前前台额度' : 'Refresh foreground quota'}
                        className={`w-5 h-5 inline-flex items-center justify-center rounded-full transition-colors shrink-0 cursor-pointer ${capsuleIconClass}`}
                      >
                        <RefreshCw
                          className={`w-3 h-3 ${isRefreshingHud ? 'animate-spin' : ''}`}
                        />
                      </button>

                      <button
                        type="button"
                        data-hud-action="true"
                        onClick={triggerOpenUsageWebpage}
                        title={isZh ? '打开模块设置' : 'Open Module Settings'}
                        className={`w-5 h-5 ml-0.5 inline-flex items-center justify-center rounded-full transition-colors shrink-0 cursor-pointer ${capsuleIconClass}`}
                      >
                        <ExternalLink className="w-3 h-3" />
                      </button>
                    </div>
                  )}
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
                    placeholder={isZh ? '搜索 QuotaCoreEngine / ModernQuotaModule...' : 'Filter files...'}
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

                <pre className="p-5 bg-slate-950 text-slate-100 text-xs font-mono overflow-x-auto max-h-[640px] leading-relaxed">
                  {currentFile.content}
                </pre>
              </div>
            </div>
          )}

          {activeView === 'spec' && (
            <div className="space-y-6">
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-slate-900">
                  {isZh
                    ? 'v2.1.0 零后台生命周期与动态 Authorization/Cookie 会话拦截架构'
                    : 'v2.1.0 Zero-Background Lifecycle & Dynamic Session Interception'}
                </h1>
                <p className="mt-1.5 text-sm text-slate-600 max-w-3xl leading-relaxed">
                  {isZh
                    ? '彻底移除所有常驻后台服务、全局系统悬浮窗、静态广播唤醒与硬编码凭证；APK 声明 0 权限、0 Service、0 Receiver，严格跟随 Gemini 前台生命周期按需运行。'
                    : 'Eliminates all background services, persistent system overlays, manifest wake-up receivers, and static tokens—running with 0 permissions and sleeping completely when Gemini is backgrounded.'}
                </p>
              </div>

              <div className="bg-white border border-slate-200 rounded-xl divide-y divide-slate-200">
                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '01. 动态出站 Authorization / Cookie / X-Goog-AuthUser 实时拦截（无感切号）'
                      : '01. Dynamic Outgoing Authorization & Cookie Interception (Account-Switch Safe)'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? '钩挂 Cronet UrlRequest.Builder.addHeader / BidirectionalStream.Builder.addHeader、gRPC io.grpc.Metadata.put / ClientCall.start 以及 OkHttp Request.Builder，实时提取稳定会话 Cookie（__Secure-1PSID / SAPISID / SID，过滤旋转的 __Secure-1PSIDTS）与 OAuth2 Bearer 指纹（sess_<12hex>）。用户在应用内切换 Google 账号时立即归档旧账号快照并切换至新账号独立槽位，彻底杜绝跨账号额度串号。'
                      : 'Intercepts Cronet UrlRequest.Builder.addHeader, gRPC Metadata.put / ClientCall.start, and OkHttp Request.Builder on the fly to derive stable per-account SHA-256 session tags—automatically isolating and refreshing quota whenever the user switches Google accounts.'}
                  </p>
                </div>

                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '02. 零后台应用内生命周期（前台挂载 · 后台 0 线程完全休眠）'
                      : '02. Zero-Background In-App Lifecycle (Awake on Foreground · 0 Threads on Sleep)'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? 'ModernQuotaModule 入口直接过滤 :interactor、:crash_report、:background 等非 UI 守护进程；QuotaHudView 仅在 Gemini Activity 进入前台时初始化并挂载，当全部 Activity 停止 (startedActivityCount == 0) 或触发 TRIM_MEMORY_UI_HIDDEN 时立即卸载 HUD、注销 ContentObserver、清空内存凭证缓冲，并通过 allowCoreThreadTimeOut(true) 将工作线程彻底归零。'
                      : 'ModernQuotaModule skips non-UI background daemons (:interactor, :background, etc.); QuotaHudView initializes strictly when a Gemini Activity enters foreground and detaches + unregisters observers + spins worker threads down to 0 via allowCoreThreadTimeOut(true) on background.'}
                  </p>
                </div>

                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '03. 严格事件驱动轻量刷新（仅在打开 Gemini 或发送提问时触发）'
                      : '03. Strictly Event-Driven Refresh (Only on App Open or Query Dispatch)'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? '无任何定时轮询循环；仅在打开 Gemini (APP_OPEN)、出站提问发送 (Cronet UrlRequest.start / gRPC ClientCall.sendMessage -> QUERY_SENT)、账号切换 (ACCOUNT_SWITCH) 或手动点击胶囊刷新图标 (MANUAL_TAP) 时执行轻量级状态采集。'
                      : 'Zero polling timers: lightweight quota refresh runs strictly on APP_OPEN, outgoing query dispatch (Cronet UrlRequest.start / gRPC ClientCall.sendMessage -> QUERY_SENT), ACCOUNT_SWITCH, or manual refresh tap.'}
                  </p>
                </div>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
