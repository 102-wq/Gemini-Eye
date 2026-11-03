import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Check,
  Copy,
  Download,
  ExternalLink,
  FileCode,
  FolderGit2,
  RefreshCw,
  Search,
  ShieldCheck,
  Smartphone,
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

const SAMPLE_PAYLOADS: Array<{
  id: string;
  labelZh: string;
  labelEn: string;
  source: string;
  raw: string;
  parsed: HudSnapshot;
}> = [
  {
    id: 'reference-spec',
    labelZh: '基准胶囊规范示例 · 15% (1h52m) / 29% (23h52m)',
    labelEn: 'Reference Capsule Spec · 15% (1h52m) / 29% (23h52m)',
    source: 'UsageWeb',
    raw: 'Usage limits\n5-hour window\n15% used · Resets in 1 hr 52 min\nWeekly limit\n29% used · Resets in 23 hr 52 min',
    parsed: {
      fiveHourPct: 15,
      fiveHourQuota: '15%',
      fiveHourReset: '1h52m',
      weeklyPct: 29,
      weeklyLimit: '29%',
      weeklyReset: '23h52m',
      source: 'UsageWeb',
    },
  },
  {
    id: 'compose-cn',
    labelZh: '中文用量页嗅探 · 42% (3h18m) / 64% (4d12h)',
    labelEn: 'CN Usage Sniff · 42% (3h18m) / 64% (4d12h)',
    source: 'Compose',
    raw: '用量与限额\n当前 5 小时窗口\n已使用 42%\n3 小时 18 分钟后重置\n每周限额\n已使用 64%\n4 天 12 小时后重置',
    parsed: {
      fiveHourPct: 42,
      fiveHourQuota: '42%',
      fiveHourReset: '3h18m',
      weeklyPct: 64,
      weeklyLimit: '64%',
      weeklyReset: '4d12h',
      source: 'Compose',
    },
  },
  {
    id: 'high-load',
    labelZh: '高负载预警阈值测试 · 84% (28m) / 92% (11h05m)',
    labelEn: 'High-Usage Threshold · 84% (28m) / 92% (11h05m)',
    source: 'SharedPrefs',
    raw: '{"five_hour_quota":"84%","five_reset":"28m","weekly_limit":"92%","weekly_reset":"11h05m"}',
    parsed: {
      fiveHourPct: 84,
      fiveHourQuota: '84%',
      fiveHourReset: '28m',
      weeklyPct: 92,
      weeklyLimit: '92%',
      weeklyReset: '11h05m',
      source: 'SharedPrefs',
    },
  },
];

export default function App() {
  const [uiLang, setUiLang] = useState<UiLang>('zh');
  const [hudLang, setHudLang] = useState<UiLang>('zh');
  const [hudTheme, setHudTheme] = useState<HudTheme>('MIUIX');
  const [activeView, setActiveView] = useState<ActiveView>('simulator');
  const [selectedPath, setSelectedPath] = useState<string>(
    'app/src/main/java/com/example/bardquotahook/QuotaHudView.kt'
  );
  const [fileQuery, setFileQuery] = useState('');
  const [copiedPath, setCopiedPath] = useState<string | null>(null);
  const [zipDownloaded, setZipDownloaded] = useState(false);

  const [selectedSampleId, setSelectedSampleId] = useState<string>('reference-spec');
  const [simDarkTheme, setSimDarkTheme] = useState<boolean>(false);
  const [xposedBound, setXposedBound] = useState<boolean>(true);
  const [systemOverlayEnabled, setSystemOverlayEnabled] = useState<boolean>(false);
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

  const activeSample = useMemo(
    () => SAMPLE_PAYLOADS.find((s) => s.id === selectedSampleId) ?? SAMPLE_PAYLOADS[0],
    [selectedSampleId]
  );

  // Enforce Single-HUD Exclusion when ModernQuotaModule (API 102) binds to Gemini
  useEffect(() => {
    if (xposedBound && systemOverlayEnabled) {
      setSystemOverlayEnabled(false);
    }
  }, [xposedBound, systemOverlayEnabled]);

  // Update mini progress bar fill widths via DOM refs (no inline style props)
  useEffect(() => {
    const w5 = `${Math.max(8, Math.min(100, activeSample.parsed.fiveHourPct))}%`;
    const wWk = `${Math.max(8, Math.min(100, activeSample.parsed.weeklyPct))}%`;
    if (bar5hRef.current) bar5hRef.current.style.width = w5;
    if (barWeeklyRef.current) barWeeklyRef.current.style.width = wWk;
    if (settingsBar5hRef.current) settingsBar5hRef.current.style.width = w5;
    if (settingsBarWeeklyRef.current) settingsBarWeeklyRef.current.style.width = wWk;
  }, [activeSample, activeView, xposedBound, systemOverlayEnabled]);

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
    link.download = 'Gemini-Eye-v1.9.1-Android-Project.zip';
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

  const triggerSimulatedRefresh = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (isRefreshingHud) return;
    setIsRefreshingHud(true);
    setLastSyncNote(
      isZh
        ? '已调用 QuotaSyncProvider.requestBackgroundRefresh -> 后台同步 gemini.google.com/usage 并通过 ContentObserver 实时推送到所有 HUD'
        : 'Invoked QuotaSyncProvider.requestBackgroundRefresh -> synced gemini.google.com/usage and notified all HUD observers'
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
    // MD3 Material You Dynamic Tonal Surface
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

  const showAnyHudInSim = xposedBound || systemOverlayEnabled;

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 flex flex-col lg:flex-row">
      {/* Sidebar Navigation */}
      <aside className="w-full lg:w-64 shrink-0 bg-white border-b lg:border-b-0 lg:border-r border-slate-200 flex flex-col">
        <div className="px-5 py-4 border-b border-slate-200">
          <div className="text-base font-bold tracking-tight text-slate-900 whitespace-nowrap">
            Gemini-Eye
          </div>
          <div className="mt-1 text-xs text-slate-500">
            <span>QuotaHudView · API 102 + 82</span>
            <span aria-hidden="true"> · </span>
            <span>v1.9.1</span>
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
              {isZh ? '单行胶囊交互预览 (MIUIX/MD3)' : 'Capsule HUD Simulator'}
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
              {isZh ? 'v1.9.1 统一数据源与修复说明' : 'v1.9.1 Unified Sync & Fixes'}
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
              {isZh ? 'Gemini 单行胶囊监控模块' : 'Gemini Capsule HUD Module'}
            </span>
            <span aria-hidden="true">/</span>
            <span className="truncate">
              {activeView === 'simulator'
                ? isZh
                  ? 'QuotaHudView 单行胶囊 · 双语 i18n · 单 HUD 互斥 · MIUIX vs MD3'
                  : 'QuotaHudView Single-Row Pill · i18n · Single-HUD Exclusion · MIUIX vs MD3'
                : activeView === 'files'
                ? currentFile.path
                : isZh
                ? 'v1.9.1 统一数据源与资源本地化规范'
                : 'v1.9.1 Unified Sync & Localization Spec'}
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
                  ? '已导出 v1.9.1 工程 .zip'
                  : 'v1.9.1 .zip Downloaded'
                : isZh
                ? '导出 v1.9.1 Android 工程 (.zip)'
                : 'Export v1.9.1 Project (.zip)'}
            </span>
          </button>
        </header>

        {/* Workspace Toolbar */}
        <div className="px-6 py-2.5 bg-slate-50 border-b border-slate-200 flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-wrap items-center gap-2 text-xs text-slate-600">
            <button
              type="button"
              onClick={() => {
                setSelectedPath('app/src/main/java/com/example/bardquotahook/QuotaHudView.kt');
                setActiveView('files');
              }}
              className="font-mono text-slate-800 hover:text-blue-600 underline underline-offset-4 cursor-pointer"
            >
              QuotaHudView.kt
            </button>
            <span aria-hidden="true">·</span>
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
                setSelectedPath('app/src/main/java/com/example/bardquotahook/QuotaSyncProvider.kt');
                setActiveView('files');
              }}
              className="font-mono text-slate-800 hover:text-blue-600 underline underline-offset-4 cursor-pointer"
            >
              QuotaSyncProvider.kt
            </button>
            <span aria-hidden="true">·</span>
            <button
              type="button"
              onClick={() => {
                setSelectedPath('app/src/main/res/values/strings.xml');
                setActiveView('files');
              }}
              className="font-mono text-slate-800 hover:text-blue-600 underline underline-offset-4 cursor-pointer"
            >
              strings.xml
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
              {/* Left Column: Controls & SharedPreferences Sync State */}
              <div className="lg:col-span-6 space-y-5">
                <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-4">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '1. 双语标签 (i18n) 与双主题引擎 (MIUIX vs MD3)'
                      : '1. Dual Language (i18n) & Theme Engine (MIUIX vs MD3)'}
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
                </div>

                {/* Single-HUD Exclusion Control */}
                <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-3">
                  <div className="flex items-center justify-between gap-3">
                    <div className="text-sm font-semibold text-slate-900">
                      {isZh
                        ? '2. 单 HUD 互斥机制 (ModernQuotaModule API 102)'
                        : '2. Single-HUD Exclusion (ModernQuotaModule API 102)'}
                    </div>
                    <ShieldCheck className="w-4 h-4 text-emerald-600 shrink-0" />
                  </div>

                  <p className="text-xs text-slate-600 leading-relaxed">
                    {isZh
                      ? '当 ModernQuotaModule (Xposed API 102) 成功绑定 Gemini 并发送心跳时，自动关闭并锁定 App 系统全局悬浮窗，确保仅渲染唯一的 Gemini 应用内单行胶囊。'
                      : 'When ModernQuotaModule (Xposed API 102) binds to Gemini, OverlayHudController automatically disables the App System Overlay so only one in-app HUD renders.'}
                  </p>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 pt-1">
                    <button
                      type="button"
                      onClick={() => setXposedBound((v) => !v)}
                      className={`px-3 py-2 text-xs font-medium rounded-lg border transition-colors text-left cursor-pointer ${
                        xposedBound
                          ? 'bg-emerald-50 border-emerald-300 text-emerald-900'
                          : 'bg-slate-50 border-slate-200 text-slate-700'
                      }`}
                    >
                      <div className="font-semibold">
                        {xposedBound
                          ? isZh
                            ? 'ModernQuotaModule: 已绑定'
                            : 'ModernQuotaModule: Bound'
                          : isZh
                          ? 'ModernQuotaModule: 未绑定'
                          : 'ModernQuotaModule: Unbound'}
                      </div>
                      <div className="text-[11px] opacity-80 mt-0.5">
                        {xposedBound
                          ? isZh
                            ? '应用内单胶囊已接管'
                            : 'In-app single HUD active'
                          : isZh
                          ? '点击模拟 Xposed 注入绑定'
                          : 'Click to simulate Xposed bind'}
                      </div>
                    </button>

                    <button
                      type="button"
                      disabled={xposedBound}
                      onClick={() => setSystemOverlayEnabled((v) => !v)}
                      className={`px-3 py-2 text-xs font-medium rounded-lg border transition-colors text-left ${
                        xposedBound
                          ? 'bg-slate-100 border-slate-200 text-slate-400 cursor-not-allowed'
                          : systemOverlayEnabled
                          ? 'bg-blue-50 border-blue-300 text-blue-900 cursor-pointer'
                          : 'bg-white border-slate-200 text-slate-700 hover:bg-slate-50 cursor-pointer'
                      }`}
                    >
                      <div className="font-semibold">
                        {xposedBound
                          ? isZh
                            ? '系统悬浮窗：已自动互斥关闭'
                            : 'System Overlay: Auto-Disabled'
                          : systemOverlayEnabled
                          ? isZh
                            ? '系统悬浮窗：开启中'
                            : 'System Overlay: Enabled'
                          : isZh
                          ? '系统悬浮窗：已关闭'
                          : 'System Overlay: Off'}
                      </div>
                      <div className="text-[11px] opacity-80 mt-0.5">
                        {xposedBound
                          ? isZh
                            ? '防止重影双胶囊'
                            : 'Prevents duplicate HUDs'
                          : isZh
                          ? '仅在未 Hook 时允许开启'
                          : 'Fallback when unhooked'}
                      </div>
                    </button>
                  </div>
                </div>

                {/* SharedPreferences Sample Selector */}
                <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-3">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '3. 单一权威跨进程数据源 (QuotaSyncProvider)'
                      : '3. Single Unified Data Source (QuotaSyncProvider)'}
                  </div>

                  <div className="space-y-2">
                    {SAMPLE_PAYLOADS.map((sample) => {
                      const selected = sample.id === activeSample.id;
                      return (
                        <button
                          key={sample.id}
                          type="button"
                          onClick={() => setSelectedSampleId(sample.id)}
                          className={`w-full text-left p-3 rounded-lg border transition-colors cursor-pointer ${
                            selected
                              ? 'bg-slate-900 text-white border-slate-900'
                              : 'bg-slate-50 text-slate-800 border-slate-200 hover:border-slate-300'
                          }`}
                        >
                          <div className="flex items-center justify-between gap-2 text-xs font-medium">
                            <span>{isZh ? sample.labelZh : sample.labelEn}</span>
                            <span className="font-mono opacity-75">{sample.source}</span>
                          </div>
                        </button>
                      );
                    })}
                  </div>

                  {/* Identical Settings App Mirror */}
                  <div className="pt-2 border-t border-slate-100">
                    <div className="text-[11px] text-slate-500 mb-2">
                      {isZh
                        ? 'MainActivity 设置页共享数值镜像 (与 Gemini 内置 HUD 100% 实时一致)：'
                        : 'MainActivity Settings Mirror (Identical SharedPreferences values):'}
                    </div>
                    <div className="px-3 py-2 rounded-lg bg-slate-900 text-slate-100 font-mono text-xs flex flex-wrap items-center justify-between gap-2">
                      <span className="inline-flex items-center gap-1.5">
                        <span className="text-slate-400">5h</span>
                        <span className="w-6 h-1.5 rounded-full bg-white/20 overflow-hidden inline-block">
                          <span ref={settingsBar5hRef} className="block h-full rounded-full bg-sky-400" />
                        </span>
                        <span>
                          {activeSample.parsed.fiveHourQuota} ({activeSample.parsed.fiveHourReset})
                        </span>
                      </span>
                      <span className="inline-flex items-center gap-1.5">
                        <span className="text-slate-400">{hudLang === 'zh' ? '每周' : 'Weekly'}</span>
                        <span className="w-6 h-1.5 rounded-full bg-white/20 overflow-hidden inline-block">
                          <span ref={settingsBarWeeklyRef} className="block h-full rounded-full bg-sky-400" />
                        </span>
                        <span>
                          {activeSample.parsed.weeklyLimit} ({activeSample.parsed.weeklyReset})
                        </span>
                      </span>
                    </div>
                  </div>
                </div>
              </div>

              {/* Right Column: Simulated Gemini Host Screen with Exact Single-Line Capsule */}
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
                      {xposedBound ? 'Gemini · In-App Xposed HUD' : 'Gemini · Unhooked'}
                    </span>
                  </div>

                  {/* Simulated Gemini Body */}
                  <div className="p-6 pt-16 space-y-4 flex-1">
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
                          ? '可按住上方单行胶囊任意拖拽，或点击胶囊右侧「↻ 刷新」与「↗ 用量网页」图标按钮。'
                          : 'Drag the single-line capsule above, or click its right-side Refresh (↻) and External Link (↗) buttons.'}
                      </div>
                    </div>

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

                    {usageSheetOpen && (
                      <div
                        className={`p-4 rounded-2xl border space-y-2.5 ${
                          simDarkTheme
                            ? 'bg-zinc-900 border-zinc-700 text-zinc-100'
                            : 'bg-white border-slate-200 text-slate-900 shadow-xs'
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <span className="text-xs font-mono font-semibold text-blue-500">
                            https://gemini.google.com/usage
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
                          <div>
                            5-hour quota: <strong>{activeSample.parsed.fiveHourQuota}</strong> (resets in{' '}
                            {activeSample.parsed.fiveHourReset})
                          </div>
                          <div>
                            Weekly quota: <strong>{activeSample.parsed.weeklyLimit}</strong> (resets in{' '}
                            {activeSample.parsed.weeklyReset})
                          </div>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* EXACT SINGLE-LINE CAPSULE HUD (`QuotaHudView.kt` Spec) */}
                  {showAnyHudInSim && (
                    <div
                      ref={hudCapsuleRef}
                      onPointerDown={onHudPointerDown}
                      onPointerMove={onHudPointerMove}
                      onPointerUp={onHudPointerUp}
                      className={`absolute top-12 right-2.5 max-w-[96%] inline-flex items-center px-2.5 py-1.5 rounded-full border text-[11px] leading-none whitespace-nowrap cursor-grab active:cursor-grabbing touch-none transition-colors ${capsuleContainerClass}`}
                    >
                      {/* Left Section: 5h + Mini Progress Bar + "15% (1h52m)" */}
                      <span className={`font-semibold ${capsuleLabelClass}`}>5h</span>
                      <span
                        className={`w-6 h-[5px] mx-1.5 rounded-full overflow-hidden shrink-0 ${capsuleTrackClass}`}
                      >
                        <span
                          ref={bar5hRef}
                          className={`block h-full rounded-full transition-all duration-300 ${pickBarColorClass(
                            activeSample.parsed.fiveHourPct
                          )}`}
                        />
                      </span>
                      <span className="font-medium tabular-nums">
                        {activeSample.parsed.fiveHourQuota} ({activeSample.parsed.fiveHourReset})
                      </span>

                      {/* Subtle Divider 1 */}
                      <span
                        className={`w-px h-3 mx-2 shrink-0 ${
                          simDarkTheme ? 'bg-white/15' : 'bg-slate-900/12'
                        }`}
                      />

                      {/* Middle Section: "每周" / "Weekly" + Mini Progress Bar + "29% (23h52m)" */}
                      <span className={`font-semibold ${capsuleLabelClass}`}>
                        {hudLang === 'zh' ? '每周' : 'Weekly'}
                      </span>
                      <span
                        className={`w-6 h-[5px] mx-1.5 rounded-full overflow-hidden shrink-0 ${capsuleTrackClass}`}
                      >
                        <span
                          ref={barWeeklyRef}
                          className={`block h-full rounded-full transition-all duration-300 ${pickBarColorClass(
                            activeSample.parsed.weeklyPct
                          )}`}
                        />
                      </span>
                      <span className="font-medium tabular-nums">
                        {activeSample.parsed.weeklyLimit} ({activeSample.parsed.weeklyReset})
                      </span>

                      {/* Subtle Divider 2 */}
                      <span
                        className={`w-px h-3 ml-2 mr-1 shrink-0 ${
                          simDarkTheme ? 'bg-white/15' : 'bg-slate-900/12'
                        }`}
                      />

                      {/* Right Section: Refresh Icon Button + External Link Icon Button */}
                      <button
                        type="button"
                        data-hud-action="true"
                        onClick={triggerSimulatedRefresh}
                        title={isZh ? '触发后台 API 同步' : 'Trigger Background API Sync'}
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
                        title={isZh ? '打开用量网页' : 'Open Usage Webpage'}
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
                    placeholder={isZh ? '搜索 QuotaHudView / ModernQuotaModule...' : 'Filter files...'}
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
                    ? 'v1.9.1 单一统一数据源与本地化字符串修复说明'
                    : 'v1.9.1 Unified Data Source & Localized String Resources'}
                </h1>
                <p className="mt-1.5 text-sm text-slate-600 max-w-3xl leading-relaxed">
                  {isZh
                    ? '通过导出型 QuotaSyncProvider + forceQueryable 解决 Android 11–15 跨进程可见性与广播冻结导致的额度不同步问题，并引入严格数值清洗与 Android 标准中英字符串资源。'
                    : 'Resolves cross-process quota sync via an exported QuotaSyncProvider with forceQueryable package visibility, strict metric sanitization, and localized values/strings.xml + values-zh/strings.xml.'}
                </p>
              </div>

              <div className="bg-white border border-slate-200 rounded-xl divide-y divide-slate-200">
                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '01. 单一权威跨进程数据源 (QuotaSyncProvider.kt + forceQueryable)'
                      : '01. Single Authoritative Cross-Process Store (QuotaSyncProvider.kt)'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? 'Android 11+ 默认限制宿主反向查询模块包名，导致 Gemini 内发出的广播被系统静默拦截，且各自私有 SharedPreferences 处于隔离沙盒。v1.9.1 在 AndroidManifest.xml 开启 android:forceQueryable="true" 并注册 QuotaSyncProvider，使 Gemini 应用内 HUD、设置页实时预览与后台同步引擎读写同一个权威数据源，并通过 ContentObserver 毫秒级实时推送变更。'
                      : 'Android 11+ asymmetric package visibility previously blocked broadcasts from Gemini to the module. Adding android:forceQueryable="true" and QuotaSyncProvider unifies all HUD views onto one authoritative store with live ContentObserver notifications.'}
                  </p>
                </div>

                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '02. 严格额度白名单清洗与防对话文本污染 (QuotaTextParser.kt)'
                      : '02. Strict Quota Sanitizer & Zero Chat Text Contamination (QuotaTextParser.kt)'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? '彻底移除 QuotaCoreEngine 对聊天正文布局的宽泛抓取与 normalizeValue 原文截断回退逻辑；新增 sanitizeQuotaMetric 与 sanitizeResetToken 严格白名单校验（仅允许合法百分比、比例及重置倒计时），并在读取旧缓存时自动清除任何历史残留的配置描述文本。'
                      : 'Removed broad chat-text layout scraping and raw string truncation fallbacks; added strict whitelist validators (sanitizeQuotaMetric & sanitizeResetToken) and automatic cache sanitization so chat or config text can never appear in HUD TextViews.'}
                  </p>
                </div>

                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900">
                    {isZh
                      ? '03. 标准 Android 本地化双语资源 (res/values & res/values-zh)'
                      : '03. Proper Localized String Resources (values/strings.xml & values-zh/strings.xml)'}
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? '新增完整的英文默认资源 res/values/strings.xml 与简体中文资源 res/values-zh/strings.xml，通过 createConfigurationContext 动态切换 Locale，规范渲染 "5h" / "每周" (CN) 与 "5h" / "Weekly" (EN) 及设置界面文案。'
                      : 'Added complete English (res/values/strings.xml) and Chinese (res/values-zh/strings.xml) string resources resolved via Configuration locale contexts for clean EN/CN toggling.'}
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
