import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  Check,
  ChevronDown,
  ChevronRight,
  Copy,
  Cpu,
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
  Terminal,
  Trash2,
  UserCheck,
} from 'lucide-react';
import { PROJECT_FILES } from './moduleFiles';
import { buildZipBlob } from './zipBuilder';

type UiLang = 'zh' | 'en';
type HudTheme = 'MIUIX' | 'MD3';
type ActiveView = 'simulator' | 'files' | 'spec';
type DevEventType =
  | 'APP_OPEN'
  | 'QUERY_SENT'
  | 'ACCOUNT_SWITCH'
  | 'MANUAL_TAP'
  | 'BACKGROUND_SLEEP'
  | 'PROTO_SCAN'
  | 'FULL_PROTOCOL_SCAN'
  | 'BATTERY_IMPACT'
  | 'ZERO_GUARD'
  | 'LINK_ANOMALY';

interface CachedSnapshotEntry {
  cachedAtStr: string;
  fiveHourQuota: string;
  fiveHourReset: string;
  weeklyLimit: string;
  weeklyReset: string;
  source: string;
}

function formatFullTimestampMs(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  const padMs = (n: number) => String(n).padStart(3, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${padMs(date.getMilliseconds())}`;
}

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

function formatWeeklyResetLabel(rawToken: string, lang: UiLang): string {
  const clean = rawToken.replace(/\s+/g, '').trim();
  if (!clean || clean === '--') return '--';
  const unit = lang === 'zh' ? '天' : 'day';
  const lower = clean.toLowerCase();

  const dhMatch = lower.match(/^(\d{1,3})d(\d{1,2})h$/);
  if (dhMatch) {
    const d = parseInt(dhMatch[1], 10);
    const h = parseInt(dhMatch[2], 10);
    const extraDays = Math.floor(h / 24);
    const remH = h % 24;
    return `${d + extraDays}${unit}${remH}h`;
  }

  const intDayMatch = lower.match(/^(\d{1,3})d$/);
  if (intDayMatch) {
    const d = parseInt(intDayMatch[1], 10);
    return `${d}${unit}0h`;
  }

  const decDayMatch = lower.match(/^(\d{1,3}\.\d+)d$/);
  if (decDayMatch) {
    const decDays = parseFloat(decDayMatch[1]);
    const totalHours = Math.max(0, Math.round(decDays * 24));
    const d = Math.floor(totalHours / 24);
    const h = totalHours % 24;
    return `${d}${unit}${h}h`;
  }

  const hmMatch = lower.match(/^(?:(\d{1,3})h)?(?:(\d{1,2})m)?$/);
  if (hmMatch && (hmMatch[1] || hmMatch[2])) {
    const h = hmMatch[1] ? parseInt(hmMatch[1], 10) : 0;
    const d = Math.floor(h / 24);
    const remH = h % 24;
    return `${d}${unit}${remH}h`;
  }

  return clean;
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
  const [lastDiagTraceSummary, setLastDiagTraceSummary] = useState<string>('');
  const [devConsoleOpen, setDevConsoleOpen] = useState<boolean>(false);
  const [isStaleOffline, setIsStaleOffline] = useState<boolean>(false);
  const [lastCachedTimestamp, setLastCachedTimestamp] = useState<string>('');
  const [isSessionDeepCleaned, setIsSessionDeepCleaned] = useState<boolean>(false);
  const [zeroGuardCount, setZeroGuardCount] = useState<number>(0);
  const [linkAnomalyCount, setLinkAnomalyCount] = useState<number>(0);
  const [bgWakeupCount, setBgWakeupCount] = useState<number>(0);
  const [fgDispatchCount, setFgDispatchCount] = useState<number>(1);
  const [activePoolThreads, setActivePoolThreads] = useState<number>(0);
  const [poolStateLabel, setPoolStateLabel] = useState<'IDLE_ZERO_THREADS' | 'FG_ON_DEMAND_ACTIVE' | 'SLEEP_PURGED'>('IDLE_ZERO_THREADS');
  const [batteryImpactCount, setBatteryImpactCount] = useState<number>(1);
  const [consoleFilter, setConsoleFilter] = useState<'ALL' | 'ZERO_GUARD' | 'LINK_ANOMALY' | 'BATTERY_IMPACT'>('ALL');
  const bgWakeupCountRef = useRef<number>(0);
  const fgDispatchCountRef = useRef<number>(1);
  const activePoolThreadsRef = useRef<number>(0);
  const appOpenSettleTimerRef = useRef<number | null>(null);
  bgWakeupCountRef.current = bgWakeupCount;
  fgDispatchCountRef.current = fgDispatchCount;
  const cachedSnapshotsRef = useRef<Record<string, CachedSnapshotEntry>>({
    sess_8f3a91c04b12: {
      cachedAtStr: formatFullTimestampMs(new Date(Date.now() - 18_500)),
      fiveHourQuota: '15%',
      fiveHourReset: '1h52m',
      weeklyLimit: '29%',
      weeklyReset: '23h52m',
      source: 'Cronet-PB',
    },
    sess_2d7e44b910ca: {
      cachedAtStr: formatFullTimestampMs(new Date(Date.now() - 42_000)),
      fiveHourQuota: '42%',
      fiveHourReset: '3h18m',
      weeklyLimit: '64%',
      weeklyReset: '4d12h',
      source: 'gRPC-PB',
    },
    sess_c91b05e781f4: {
      cachedAtStr: formatFullTimestampMs(new Date(Date.now() - 65_000)),
      fiveHourQuota: '84%',
      fiveHourReset: '28m',
      weeklyLimit: '92%',
      weeklyReset: '11h05m',
      source: 'StateFlow',
    },
  });

  const [devLogs, setDevLogs] = useState<Array<{
    id: string;
    timestamp: string;
    timeMs: string;
    eventType: DevEventType;
    sessionTag: string;
    detailZh: string;
    detailEn: string;
    level: 'info' | 'success' | 'warn' | 'event';
  }>>([
    {
      id: 'init-1',
      timestamp: '09:41:02',
      timeMs: '09:41:02.104',
      eventType: 'APP_OPEN',
      sessionTag: 'sess_8f3a91c04b12',
      detailZh: 'Gemini Activity.onCreate / onResume 前台激活。isForegroundActive=true，挂载 QuotaHudView(translationZ=12dp)，初始化轻量级事件刷新',
      detailEn: 'Gemini Activity.onCreate / onResume foreground wake. isForegroundActive=true, attached QuotaHudView(translationZ=12dp), triggered initial refresh',
      level: 'success',
    },
    {
      id: 'init-2',
      timestamp: '09:41:02',
      timeMs: '09:41:02.350',
      eventType: 'PROTO_SCAN',
      sessionTag: 'sess_8f3a91c04b12',
      detailZh: 'Cronet UrlRequest.onReadCompleted 出站头捕获: [__Secure-1PSID, X-Goog-AuthUser:0]。Protobuf 扫描命中双窗口: 5h=15% (1h52m), 每周=29% (0天23h)',
      detailEn: 'Cronet UrlRequest.onReadCompleted header captured: [__Secure-1PSID, X-Goog-AuthUser:0]. Protobuf scanned dual windows: 5h=15% (1h52m), Weekly=29% (0day23h)',
      level: 'info',
    },
    {
      id: 'init-3',
      timestamp: '09:41:07',
      timeMs: '09:41:07.355',
      eventType: 'BATTERY_IMPACT',
      sessionTag: 'sess_8f3a91c04b12',
      detailZh: '[零后台功耗基线] 累计后台线程唤醒=0 | 前台按需派发=1 | 线程池状态: FG_ON_DEMAND_ACTIVE(1) -> allowCoreThreadTimeOut(5s) -> IDLE_ZERO_THREADS(0 活跃线程)',
      detailEn: '[Zero-Background Baseline] Cumulative BG Thread Wakeups=0 | FG On-Demand Dispatches=1 | Pool State: FG_ON_DEMAND_ACTIVE(1) -> allowCoreThreadTimeOut(5s) -> IDLE_ZERO_THREADS(0 active threads)',
      level: 'info',
    },
  ]);

  const FOREGROUND_HEARTBEAT_INTERVAL_MS = 30_000;
  const lastQuotaUpdateAtRef = useRef<number>(Date.now());
  const heartbeatTimerRef = useRef<number | null>(null);
  const heartbeatReplayTimerRef = useRef<number | null>(null);
  const isGeminiForegroundRef = useRef<boolean>(isGeminiForeground);
  const isZhRef = useRef<boolean>(uiLang === 'zh');

  const logDevEvent = (
    eventType: DevEventType,
    sessionTag: string,
    detailZh: string,
    detailEn: string,
    level: 'info' | 'success' | 'warn' | 'event' = 'event'
  ) => {
    const d = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    const padMs = (n: number) => String(n).padStart(3, '0');
    const timeStr = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    const timeMsStr = `${timeStr}.${padMs(d.getMilliseconds())}`;
    setDevLogs((prev) => [
      {
        id: `log-${Date.now()}-${Math.random().toString(36).substring(2, 6)}`,
        timestamp: timeStr,
        timeMs: timeMsStr,
        eventType,
        sessionTag,
        detailZh,
        detailEn,
        level,
      },
      ...prev.slice(0, 49),
    ]);
  };

  const hudCapsuleRef = useRef<HTMLDivElement | null>(null);
  const bar5hRef = useRef<HTMLDivElement | null>(null);
  const barWeeklyRef = useRef<HTMLDivElement | null>(null);
  const settingsBar5hRef = useRef<HTMLDivElement | null>(null);
  const settingsBarWeeklyRef = useRef<HTMLDivElement | null>(null);

  const HUD_FRAME_SMOOTH_OFFSET_MS = 8;
  const HUD_TOUCH_JITTER_DEADBAND_PX = 1.1;
  const HUD_TOUCH_SAMPLE_WINDOW_MS = 8;
  const HUD_MAGNET_EDGE_THRESHOLD_PX = 40;

  const hudPosRef = useRef<{ x: number; y: number }>({ x: 0, y: 0 });
  const hudAnimRafRef = useRef<number | null>(null);
  const dragRef = useRef<{
    startX: number;
    startY: number;
    origX: number;
    origY: number;
    filteredX: number;
    filteredY: number;
    targetX: number;
    targetY: number;
    lastClientX: number;
    lastClientY: number;
    lastTimeMs: number;
    lastSampleTimeMs: number;
    vx: number;
    vy: number;
    moved: boolean;
    pointerDown: boolean;
  }>({
    startX: 0,
    startY: 0,
    origX: 0,
    origY: 0,
    filteredX: 0,
    filteredY: 0,
    targetX: 0,
    targetY: 0,
    lastClientX: 0,
    lastClientY: 0,
    lastTimeMs: 0,
    lastSampleTimeMs: 0,
    vx: 0,
    vy: 0,
    moved: false,
    pointerDown: false,
  });

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
  const activeSessionRef = useRef<SimulatedAccountSession>(activeSession);
  isGeminiForegroundRef.current = isGeminiForeground;
  isZhRef.current = isZh;
  activeSessionRef.current = activeSession;

  const visibleDevLogs = useMemo(() => {
    if (consoleFilter === 'ZERO_GUARD') {
      return devLogs.filter((l) => l.eventType === 'ZERO_GUARD');
    }
    if (consoleFilter === 'LINK_ANOMALY') {
      return devLogs.filter((l) => l.eventType === 'LINK_ANOMALY');
    }
    if (consoleFilter === 'BATTERY_IMPACT') {
      return devLogs.filter((l) => l.eventType === 'BATTERY_IMPACT');
    }
    return devLogs;
  }, [devLogs, consoleFilter]);

  const recordBatteryImpactEvent = (
    sessionTag: string,
    phase: 'FG_DISPATCH' | 'FG_SETTLE_IDLE' | 'BG_SLEEP_PURGE' | 'HEARTBEAT_REPLAY',
    reasonTag: string
  ) => {
    if (phase === 'FG_DISPATCH' || phase === 'HEARTBEAT_REPLAY') {
      const nextFg = fgDispatchCountRef.current + 1;
      fgDispatchCountRef.current = nextFg;
      setFgDispatchCount(nextFg);
      const prevThreads = activePoolThreadsRef.current;
      const nextThreads = prevThreads + 1;
      activePoolThreadsRef.current = nextThreads;
      setActivePoolThreads(nextThreads);
      setPoolStateLabel('FG_ON_DEMAND_ACTIVE');
      setBatteryImpactCount((c) => c + 1);
      logDevEvent(
        'BATTERY_IMPACT',
        sessionTag,
        `[线程池按需唤醒 · ${reasonTag}] 累计后台线程唤醒=${bgWakeupCountRef.current} | 前台按需派发=${nextFg} | 活跃线程 ${prevThreads} -> ${nextThreads} (${
          nextThreads > 1 ? '并发突发 MULTI_THREAD_BURST' : 'FG_ON_DEMAND_ACTIVE, keepAlive=5s'
        })`,
        `[On-Demand Pool Wake · ${reasonTag}] Cumulative BG Wakeups=${bgWakeupCountRef.current} | FG Dispatches=${nextFg} | Active Threads ${prevThreads} -> ${nextThreads} (${
          nextThreads > 1 ? 'MULTI_THREAD_BURST' : 'FG_ON_DEMAND_ACTIVE daemon, keepAlive=5s'
        })`,
        nextThreads > 1 ? 'warn' : 'info'
      );
    } else if (phase === 'FG_SETTLE_IDLE') {
      const prevThreads = activePoolThreadsRef.current;
      const nextThreads = Math.max(0, prevThreads - 1);
      activePoolThreadsRef.current = nextThreads;
      setActivePoolThreads(nextThreads);
      if (nextThreads === 0) {
        setPoolStateLabel('IDLE_ZERO_THREADS');
      }
      setBatteryImpactCount((c) => c + 1);
      logDevEvent(
        'BATTERY_IMPACT',
        sessionTag,
        `[按需任务完成 · ${reasonTag}] 累计后台线程唤醒=${bgWakeupCountRef.current} | 前台按需派发=${fgDispatchCountRef.current} | 活跃线程 ${prevThreads} -> ${nextThreads} (${
          nextThreads === 0 ? 'allowCoreThreadTimeOut(5s) -> IDLE_ZERO_THREADS' : '仍有并发任务运行中'
        })`,
        `[On-Demand Task Settled · ${reasonTag}] Cumulative BG Wakeups=${bgWakeupCountRef.current} | FG Dispatches=${fgDispatchCountRef.current} | Active Threads ${prevThreads} -> ${nextThreads} (${
          nextThreads === 0 ? 'allowCoreThreadTimeOut(5s) -> IDLE_ZERO_THREADS' : 'concurrent tasks still draining'
        })`,
        nextThreads === 0 ? 'success' : 'info'
      );
    } else {
      activePoolThreadsRef.current = 0;
      setActivePoolThreads(0);
      setPoolStateLabel('SLEEP_PURGED');
      setBatteryImpactCount((c) => c + 1);
      logDevEvent(
        'BATTERY_IMPACT',
        sessionTag,
        `[零后台休眠清零 · ${reasonTag}] 累计后台线程唤醒=${bgWakeupCountRef.current} (拦截非UI守护进程 :interactor/:gms) | 队列已清空并调用 purge() -> SLEEP_PURGED(0 活跃线程, 0 后台定时器)`,
        `[Zero-Background Sleep Purge · ${reasonTag}] Cumulative BG Thread Wakeups=${bgWakeupCountRef.current} (:interactor/:gms daemons skipped) | Queue drained & purge() -> SLEEP_PURGED(0 active threads, 0 bg timers)`,
        'success'
      );
    }
  };

  const handleSimulateThreadBurst = () => {
    const tag = activeSessionRef.current.sessionTag;
    recordBatteryImpactEvent(tag, 'FG_DISPATCH', 'BURST_SCAN_W1');
    window.setTimeout(() => {
      if (!isGeminiForegroundRef.current) return;
      recordBatteryImpactEvent(activeSessionRef.current.sessionTag, 'FG_DISPATCH', 'BURST_GRPC_W2');
    }, 140);
    window.setTimeout(() => {
      if (!isGeminiForegroundRef.current) return;
      recordBatteryImpactEvent(activeSessionRef.current.sessionTag, 'FG_SETTLE_IDLE', 'BURST_SCAN_W1');
    }, 1050);
    window.setTimeout(() => {
      if (!isGeminiForegroundRef.current) return;
      recordBatteryImpactEvent(activeSessionRef.current.sessionTag, 'FG_SETTLE_IDLE', 'BURST_GRPC_W2');
    }, 1550);
  };

  // Update mini progress bar fill widths via DOM refs (no inline style props)
  useEffect(() => {
    const w5 = isSessionDeepCleaned ? '0%' : `${Math.max(8, Math.min(100, activeSession.parsed.fiveHourPct))}%`;
    const wWk = isSessionDeepCleaned ? '0%' : `${Math.max(8, Math.min(100, activeSession.parsed.weeklyPct))}%`;
    if (bar5hRef.current) bar5hRef.current.style.width = w5;
    if (barWeeklyRef.current) barWeeklyRef.current.style.width = wWk;
    if (settingsBar5hRef.current) settingsBar5hRef.current.style.width = w5;
    if (settingsBarWeeklyRef.current) settingsBarWeeklyRef.current.style.width = wWk;
  }, [activeSession, activeView, isGeminiForeground, isStaleOffline, isSessionDeepCleaned]);

  useEffect(() => {
    if (isGeminiForeground && hudCapsuleRef.current) {
      const { x, y } = hudPosRef.current;
      hudCapsuleRef.current.style.transform = `translate3d(${x.toFixed(2)}px, ${y.toFixed(2)}px, 0)`;
    }
  }, [isGeminiForeground]);

  useEffect(() => {
    return () => {
      if (hudAnimRafRef.current !== null) {
        cancelAnimationFrame(hudAnimRafRef.current);
        hudAnimRafRef.current = null;
      }
    };
  }, []);

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

  const clearHeartbeatTimers = () => {
    if (heartbeatTimerRef.current !== null) {
      window.clearTimeout(heartbeatTimerRef.current);
      heartbeatTimerRef.current = null;
    }
    if (heartbeatReplayTimerRef.current !== null) {
      window.clearTimeout(heartbeatReplayTimerRef.current);
      heartbeatReplayTimerRef.current = null;
    }
  };

  const triggerSilentHeartbeatGetUserQuotaReplay = () => {
    if (!isGeminiForegroundRef.current) return;
    const acct = activeSessionRef.current;
    const elapsedSec = Math.max(
      30,
      Math.round((Date.now() - lastQuotaUpdateAtRef.current) / 1000)
    );
    lastQuotaUpdateAtRef.current = Date.now();

    logDevEvent(
      'FULL_PROTOCOL_SCAN',
      acct.sessionTag,
      `[前台心跳检查 · ${elapsedSec}s 无更新] 前台持续活跃满 30 秒未收到新额度快照，强制静默重放 gRPC batchExecute 'GetUserQuota' (robinandroid-pa / BardChatUi · hOoQ4d) 以保证数据鲜活`,
      `[Foreground Heartbeat Check · ${elapsedSec}s Idle] No quota updates received after 30s of foreground activity; forcing silent gRPC batchExecute 'GetUserQuota' replay (robinandroid-pa / BardChatUi · hOoQ4d)`,
      'event'
    );
    recordBatteryImpactEvent(acct.sessionTag, 'HEARTBEAT_REPLAY', '30s_HEARTBEAT');

    if (heartbeatReplayTimerRef.current !== null) {
      window.clearTimeout(heartbeatReplayTimerRef.current);
    }
    heartbeatReplayTimerRef.current = window.setTimeout(() => {
      heartbeatReplayTimerRef.current = null;
      if (!isGeminiForegroundRef.current) return;
      const latestAcct = activeSessionRef.current;
      const cachedAtStr = formatFullTimestampMs(new Date());
      lastQuotaUpdateAtRef.current = Date.now();
      cachedSnapshotsRef.current[latestAcct.sessionTag] = {
        cachedAtStr,
        fiveHourQuota: latestAcct.parsed.fiveHourQuota,
        fiveHourReset: latestAcct.parsed.fiveHourReset,
        weeklyLimit: latestAcct.parsed.weeklyLimit,
        weeklyReset: latestAcct.parsed.weeklyReset,
        source: 'gRPC-GetUserQuota',
      };
      setLastCachedTimestamp(cachedAtStr);
      setIsStaleOffline(false);
      setIsSessionDeepCleaned(false);
      setLastSyncNote(
        isZhRef.current
          ? `前台 30s 心跳静默校验完成 (${latestAcct.sessionTag})：已通过 gRPC batchExecute 'GetUserQuota' 刷新最新额度快照`
          : `30s foreground heartbeat verified (${latestAcct.sessionTag}): silently replayed gRPC batchExecute 'GetUserQuota' for freshness`
      );
      logDevEvent(
        'PROTO_SCAN',
        latestAcct.sessionTag,
        `[静默 GetUserQuota 重放完成] 无感校验成功 [5h=${latestAcct.parsed.fiveHourQuota} (${latestAcct.parsed.fiveHourReset}), 每周=${latestAcct.parsed.weeklyLimit} (${formatWeeklyResetLabel(latestAcct.parsed.weeklyReset, 'zh')})]，已重置 30s 前台心跳计时器`,
        `[Silent GetUserQuota Replay Complete] Freshness verified without UI interruption [5h=${latestAcct.parsed.fiveHourQuota} (${latestAcct.parsed.fiveHourReset}), Weekly=${latestAcct.parsed.weeklyLimit} (${formatWeeklyResetLabel(latestAcct.parsed.weeklyReset, 'en')})]; 30s heartbeat reset`,
        'success'
      );
      recordBatteryImpactEvent(latestAcct.sessionTag, 'FG_SETTLE_IDLE', '30s_HEARTBEAT');
      scheduleForegroundHeartbeat();
    }, 420);
  };

  const scheduleForegroundHeartbeat = () => {
    if (heartbeatTimerRef.current !== null) {
      window.clearTimeout(heartbeatTimerRef.current);
      heartbeatTimerRef.current = null;
    }
    if (!isGeminiForegroundRef.current) return;
    const elapsed = Date.now() - lastQuotaUpdateAtRef.current;
    const waitMs = Math.max(250, FOREGROUND_HEARTBEAT_INTERVAL_MS - elapsed);
    heartbeatTimerRef.current = window.setTimeout(() => {
      heartbeatTimerRef.current = null;
      if (!isGeminiForegroundRef.current) return;
      if (Date.now() - lastQuotaUpdateAtRef.current >= FOREGROUND_HEARTBEAT_INTERVAL_MS - 100) {
        triggerSilentHeartbeatGetUserQuotaReplay();
      } else {
        scheduleForegroundHeartbeat();
      }
    }, waitMs);
  };

  useEffect(() => {
    if (!isGeminiForeground) {
      clearHeartbeatTimers();
      return;
    }
    scheduleForegroundHeartbeat();
    return () => {
      clearHeartbeatTimers();
    };
  }, [isGeminiForeground, selectedAccountId]);

  const recordCachedNonZeroSnapshot = (acct: SimulatedAccountSession) => {
    const now = Date.now();
    lastQuotaUpdateAtRef.current = now;
    const cachedAtStr = formatFullTimestampMs(new Date(now));
    cachedSnapshotsRef.current[acct.sessionTag] = {
      cachedAtStr,
      fiveHourQuota: acct.parsed.fiveHourQuota,
      fiveHourReset: acct.parsed.fiveHourReset,
      weeklyLimit: acct.parsed.weeklyLimit,
      weeklyReset: acct.parsed.weeklyReset,
      source: acct.parsed.source,
    };
    setLastCachedTimestamp(cachedAtStr);
    scheduleForegroundHeartbeat();
  };

  const handleSimulateProtobufZeroGuard = () => {
    const cached = cachedSnapshotsRef.current[activeSession.sessionTag] ?? {
      cachedAtStr: formatFullTimestampMs(new Date(Date.now() - 12_000)),
      fiveHourQuota: activeSession.parsed.fiveHourQuota,
      fiveHourReset: activeSession.parsed.fiveHourReset,
      weeklyLimit: activeSession.parsed.weeklyLimit,
      weeklyReset: activeSession.parsed.weeklyReset,
      source: activeSession.parsed.source,
    };
    cachedSnapshotsRef.current[activeSession.sessionTag] = cached;
    setLastCachedTimestamp(cached.cachedAtStr);
    const nextHit = zeroGuardCount + 1;
    setZeroGuardCount(nextHit);
    setIsSessionDeepCleaned(false);
    setIsStaleOffline(true);

    const wkZh = formatWeeklyResetLabel(cached.weeklyReset, 'zh');
    const wkEn = formatWeeklyResetLabel(cached.weeklyReset, 'en');
    const restoredZh = `5h=${cached.fiveHourQuota} (${cached.fiveHourReset}), 每周=${cached.weeklyLimit} (${wkZh})`;
    const restoredEn = `5h=${cached.fiveHourQuota} (${cached.fiveHourReset}), Weekly=${cached.weeklyLimit} (${wkEn})`;

    setLastSyncNote(
      isZh
        ? `归零防护已触发 (第 ${nextHit} 次 · ${activeSession.sessionTag})：Protobuf 返回 0%，已从缓存 (${cached.cachedAtStr}) 恢复非零数值，HUD 切换为灰色「离线」提示`
        : `Zero-Guard Triggered (#${nextHit} · ${activeSession.sessionTag}): Protobuf returned 0%, restored cached non-zero snapshot from ${cached.cachedAtStr} in HUD Offline/Gray mode`
    );

    logDevEvent(
      'ZERO_GUARD',
      activeSession.sessionTag,
      `归零防护已触发 (第 ${nextHit} 次)：Cronet-PB 返回 0 [5h=0%, 每周=0%]，成功调用缓存补偿 | 缓存快照时间戳: ${cached.cachedAtStr} | 已恢复的数值: ${restoredZh}`,
      `Zero-Guard Triggered (#${nextHit}): Cronet-PB returned 0 [5h=0%, Weekly=0%], cache compensation applied | Cached Snapshot Timestamp: ${cached.cachedAtStr} | Restored Values: ${restoredEn}`,
      'warn'
    );
  };

  const handleSimulateHealthAnomaly = () => {
    const nextAnomaly = linkAnomalyCount + 1;
    setLinkAnomalyCount(nextAnomaly);
    setIsStaleOffline(false);
    setIsSessionDeepCleaned(true);
    delete cachedSnapshotsRef.current[activeSession.sessionTag];

    const reasonZh =
      nextAnomaly % 2 === 1
        ? 'Protobuf 解析失败 (WireType corruption in quota stream)'
        : '连续 3 次返回空数据 (Cronet-PB / Active-Fetch 3 consecutive nulls)';
    const reasonEn =
      nextAnomaly % 2 === 1
        ? 'Protobuf parse failure (WireType corruption in quota stream)'
        : '3 consecutive empty responses (Cronet-PB / Active-Fetch)';
    const cleanedSlotInfo = `Session槽位=${activeSession.sessionTag}(live=true, cache=true), 端点缓存=2, 流/WIZ令牌已重置`;

    setLastSyncNote(
      isZh
        ? `QuotaHealthMonitor 触发「链路异常」(第 ${nextAnomaly} 次)：${reasonZh}，已自动清除会话 ${activeSession.sessionTag} 缓存槽位`
        : `QuotaHealthMonitor LINK_ANOMALY (#${nextAnomaly}): ${reasonEn}, executed deep cleanup of session slot ${activeSession.sessionTag}`
    );

    logDevEvent(
      'LINK_ANOMALY',
      activeSession.sessionTag,
      `链路异常 (第 ${nextAnomaly} 次)：${reasonZh} [Cronet-PB]，已自动触发深层清理 -> ${cleanedSlotInfo}`,
      `Link Anomaly (#${nextAnomaly}): ${reasonEn} [Cronet-PB], triggered deep cleanup -> ${cleanedSlotInfo}`,
      'warn'
    );
  };

  const buildOutboundProtocolFrameTrace = (acct: SimulatedAccountSession) => {
    const authUserIdx =
      acct.id === 'account-workspace' ? '1' : acct.id === 'account-heavy' ? '2' : '0';
    const quotaHexLen = (24 + (acct.parsed.fiveHourPct % 15)).toString(16).toUpperCase().padStart(2, '0');
    const streamHexLen = (68 + (acct.parsed.weeklyPct % 24)).toString(16).toUpperCase().padStart(2, '0');

    const h2TrailersLines = [
      `H2 Outbound #1 -> :method=POST :authority=robinandroid-pa.googleapis.com :path=/$rpc/google.internal.robin.v1.RobinService/GetUserQuota | TE: trailers | content-type=application/grpc | grpc-encoding=identity | grpc-accept-encoding=gzip,identity | x-goog-authuser=${authUserIdx}`,
      `H2 Outbound #2 -> :method=POST :authority=gemini.google.com :path=/_/BardChatUi/data/batchexecute?rpcids=hOoQ4d,o4g7id | TE: trailers | x-same-domain=1 | x-goog-authuser=${authUserIdx}`,
    ];
    const grpc5bFrames = [
      `gRPC-RPC 5B Frame #1 -> Header[00 00 00 00 ${quotaHexLen}] (Flag=0x00 Identity, PayloadLen=${parseInt(quotaHexLen, 16)}B) -> GetUserQuota [${acct.sessionTag}]`,
      `gRPC-RPC 5B Frame #2 -> Header[01 00 00 00 ${streamHexLen}] (Flag=0x01 GZIP, PayloadLen=${parseInt(streamHexLen, 16)}B) -> StreamGenerate [${acct.sessionTag}]`,
      `gRPC-RPC 5B Frame #3 -> Header[00 00 00 00 0E] (Flag=0x00 Identity, PayloadLen=14B) -> BardChatUi/batchexecute(hOoQ4d)`,
    ];
    return { h2TrailersLines, grpc5bFrames };
  };

  const runForegroundFullProtocolDiagnostic = (
    trigger: 'ACCOUNT_SWITCH' | 'MANUAL_TAP' | 'FORCE_SETTINGS_SCAN',
    acct: SimulatedAccountSession,
    usageUiOpened: boolean
  ) => {
    const { h2TrailersLines, grpc5bFrames } = buildOutboundProtocolFrameTrace(acct);
    const compactTrace = `${h2TrailersLines[0]}\n${grpc5bFrames[0]}`;
    setLastDiagTraceSummary(compactTrace);

    if (trigger === 'FORCE_SETTINGS_SCAN') {
      logDevEvent(
        'FULL_PROTOCOL_SCAN',
        acct.sessionTag,
        `[HUD 设置弹窗 · 强制全量协议扫描] 手动调用 runForegroundFullProtocolDiagnostic()，正在采集并打印出站 HTTP/2 TE: trailers 与 gRPC-RPC 5B 帧头...`,
        `[HUD Settings Modal · Force Full-Protocol Scan] Manually invoked runForegroundFullProtocolDiagnostic(); dumping outbound HTTP/2 TE: trailers & gRPC-RPC 5B frame headers...`,
        'event'
      );
      h2TrailersLines.forEach((line) => {
        logDevEvent(
          'FULL_PROTOCOL_SCAN',
          acct.sessionTag,
          `[出站 HTTP/2 头] ${line}`,
          `[Outbound H2 Header] ${line}`,
          'info'
        );
      });
      grpc5bFrames.forEach((frameLine) => {
        logDevEvent(
          'FULL_PROTOCOL_SCAN',
          acct.sessionTag,
          `[出站帧头] ${frameLine}`,
          `[Outbound Frame] ${frameLine}`,
          'info'
        );
      });
      return;
    }

    if (!usageUiOpened) {
      logDevEvent(
        'LINK_ANOMALY',
        acct.sessionTag,
        `[被动拦截诊断 · 未打开额度页] ${trigger}: 首页仅发射 StreamGenerate/Clearcut 懒加载未触发 GetUserQuota；若无全量扫描易出现：①APP_OPEN 首轮冷却抢跑错过 Cookie(__Secure-1PSID)/Bearer 抵达窗口；②Telemetry/Play 作用域覆盖单一 Auth 槽位引发 401；③gRPC start(listenerId) 与 sendMessage(callId) 请求体脱节或 https://grpc/ 虚拟域名被正则拦截`,
        `[Passive Hook Diagnosis · Usage UI Closed] ${trigger}: Home screen emits StreamGenerate/Clearcut without calling GetUserQuota; passive capture misses due to (1) initial throttle outrunning delayed Cookie/Bearer headers, (2) cross-service Auth token overwrite, (3) gRPC listenerId/callId body mismatch & virtual host filter`,
        'warn'
      );
      setTimeout(() => {
        logDevEvent(
          'FULL_PROTOCOL_SCAN',
          acct.sessionTag,
          `[前台全量协议快照监听器激活] Activity.onWindowFocusChanged(hasFocus=true) 触发四栈强扫：①DataStore PB (${acct.sessionTag}) -> ②perHostAuthorizations 按域隔离鉴权 + CookieManager -> ③DI Repo/内嵌 WebView XHR 探针 -> ④Cronet H2(TE:trailers)/gRPC-RPC(5B帧头)/batchexecute(动态刷新 SNlM0e at=) 主动重放\n${h2TrailersLines[0]}\n${grpc5bFrames[0]}`,
          `[Foreground Full-Protocol Snapshot Listener] Activity.onWindowFocusChanged(hasFocus=true) forced 4-stack sweep: (1) DataStore PB -> (2) Scoped perHostAuthorizations + CookieManager -> (3) DI Repo/Attached WebView probe -> (4) Cronet H2(TE:trailers)/gRPC-RPC(5B frame)/batchexecute(SNlM0e refresh) replay\n${h2TrailersLines[0]}\n${grpc5bFrames[0]}`,
          'event'
        );
      }, 240);
    } else {
      logDevEvent(
        'FULL_PROTOCOL_SCAN',
        acct.sessionTag,
        `[前台全量协议快照监听器 · 额度页已打开] 捕获直连 ${acct.headerSummary}，已学习并持久化去凭据端点模板与 5B gRPC/XHR 请求体供后续冷启无感直连复用\n${h2TrailersLines[0]}\n${grpc5bFrames[0]}`,
        `[Foreground Full-Protocol Snapshot Listener · Usage UI Open] Direct hit via ${acct.headerSummary}; persisted credential-free endpoint & framed request payload for zero-touch cold-start replay\n${h2TrailersLines[0]}\n${grpc5bFrames[0]}`,
        'info'
      );
    }
  };

  const handleSwitchAccount = (acct: SimulatedAccountSession) => {
    setSelectedAccountId(acct.id);
    setIsStaleOffline(false);
    setIsSessionDeepCleaned(false);
    setIsRefreshingHud(true);
    setLastSyncNote(
      isZh
        ? `检测到出站动态请求头变更 (${acct.sessionTag})：已隔离旧账号凭证/端点，并触发前台全量协议快照扫描`
        : `Dynamic outgoing auth/cookie header changed (${acct.sessionTag}): isolated previous credentials & triggered foreground full-protocol snapshot scan`
    );
    logDevEvent(
      'ACCOUNT_SWITCH',
      acct.sessionTag,
      `动态出站头变更 [${acct.accountLabelZh}]。清空旧 perHostAuthorizations 作用域缓存并切换至独立数据槽 [5h=${acct.parsed.fiveHourQuota}, Weekly=${acct.parsed.weeklyLimit}]`,
      `Dynamic header change [${acct.accountLabelEn}]. Flushed scoped perHostAuthorizations & switched to isolated slot [5h=${acct.parsed.fiveHourQuota}, Weekly=${acct.parsed.weeklyLimit}]`,
      'warn'
    );
    runForegroundFullProtocolDiagnostic('ACCOUNT_SWITCH', acct, usageSheetOpen);
    recordBatteryImpactEvent(acct.sessionTag, 'FG_DISPATCH', 'ACCOUNT_SWITCH');
    setTimeout(() => {
      setIsRefreshingHud(false);
      recordCachedNonZeroSnapshot(acct);
      logDevEvent(
        'PROTO_SCAN',
        acct.sessionTag,
        `全量协议重放与出站流拦截完成 (${acct.headerSummary})。QuotaSyncProvider(replaceSession=true) 广播已同步`,
        `Full-protocol replay & outbound stream intercepted (${acct.headerSummary}). QuotaSyncProvider(replaceSession=true) broadcast synchronized`,
        'success'
      );
      recordBatteryImpactEvent(acct.sessionTag, 'FG_SETTLE_IDLE', 'ACCOUNT_SWITCH');
    }, 620);
  };

  const handleToggleForeground = (fg: boolean) => {
    setIsGeminiForeground(fg);
    setUsageSheetOpen(false);
    setLastSyncNote(
      fg
        ? isZh
          ? 'Gemini 进入前台 (APP_OPEN)：HUD 已挂载并激活前台全量协议快照监听器'
          : 'Gemini entered foreground (APP_OPEN): HUD attached & armed foreground full-protocol snapshot listener'
        : isZh
        ? 'Gemini 退至后台 (TRIM_MEMORY_UI_HIDDEN)：HUD 已卸载、注销监听、清空临时凭证缓冲，工作线程降至 0'
        : 'Gemini moved to background: HUD detached, observers unregistered, buffers wiped, 0 active threads'
    );
    if (appOpenSettleTimerRef.current !== null) {
      window.clearTimeout(appOpenSettleTimerRef.current);
      appOpenSettleTimerRef.current = null;
    }
    if (fg) {
      lastQuotaUpdateAtRef.current = Date.now();
      scheduleForegroundHeartbeat();
      logDevEvent(
        'APP_OPEN',
        activeSession.sessionTag,
        `Gemini Activity.onResume / onWindowFocusChanged 前台激活。startedActivityCount=1, 挂载 QuotaHudView, 注册前台全量协议快照监听器（含 30s 无更新静默心跳）`,
        `Gemini Activity.onResume / onWindowFocusChanged foreground wake. startedActivityCount=1, attached QuotaHudView, armed full-protocol snapshot listener (30s idle heartbeat active)`,
        'success'
      );
      recordBatteryImpactEvent(activeSession.sessionTag, 'FG_DISPATCH', 'APP_OPEN');
      appOpenSettleTimerRef.current = window.setTimeout(() => {
        appOpenSettleTimerRef.current = null;
        if (!isGeminiForegroundRef.current) return;
        recordBatteryImpactEvent(activeSessionRef.current.sessionTag, 'FG_SETTLE_IDLE', 'APP_OPEN');
      }, 480);
    } else {
      clearHeartbeatTimers();
      logDevEvent(
        'BACKGROUND_SLEEP',
        activeSession.sessionTag,
        `Gemini 退至后台 (TRIM_MEMORY_UI_HIDDEN)。startedActivityCount=0, isForegroundActive=false, 移除 HUD, 注销 30s 前台心跳检测, 清理临时请求头缓冲, 线程池缩减至 0`,
        `Gemini left foreground (TRIM_MEMORY_UI_HIDDEN). startedActivityCount=0, isForegroundActive=false, detached HUD, cleared 30s foreground heartbeat, wiped buffers, 0 active threads`,
        'warn'
      );
      recordBatteryImpactEvent(activeSession.sessionTag, 'BG_SLEEP_PURGE', 'TRIM_MEMORY_UI_HIDDEN');
    }
  };

  const handleSimulateQuerySend = () => {
    if (!isGeminiForeground) return;
    setIsRefreshingHud(true);
    setLastSyncNote(
      isZh
        ? `发送提问事件 (QUERY_SENT · ${activeSession.sessionTag})：实时携带当前账号 Authorization/Cookie，流结束后触发单次轻量额度同步`
        : `User query sent (QUERY_SENT · ${activeSession.sessionTag}): intercepted live Authorization/Cookie header & triggered lightweight post-query sync`
    );
    logDevEvent(
      'QUERY_SENT',
      activeSession.sessionTag,
      `用户提问流出站 (Cronet.start / gRPC ClientCall.sendMessage)。捕获当前账号动态凭据，流结束后触发 1 次防抖轻量刷新`,
      `User query stream initiated (Cronet.start / gRPC ClientCall.sendMessage). Captured dynamic credentials, debounced post-query refresh queued`,
      'event'
    );
    recordBatteryImpactEvent(activeSession.sessionTag, 'FG_DISPATCH', 'QUERY_SENT');
    setTimeout(() => {
      setIsRefreshingHud(false);
      setIsStaleOffline(false);
      setIsSessionDeepCleaned(false);
      recordCachedNonZeroSnapshot(activeSession);
      logDevEvent(
        'PROTO_SCAN',
        activeSession.sessionTag,
        `提问响应 ProtoBuf 流解析完毕。获取最新额度: 5h=${activeSession.parsed.fiveHourQuota} (${activeSession.parsed.fiveHourReset}), 每周=${activeSession.parsed.weeklyLimit} (${formatWeeklyResetLabel(activeSession.parsed.weeklyReset, 'zh')})`,
        `Query response ProtoBuf parsed. Current quota: 5h=${activeSession.parsed.fiveHourQuota} (${activeSession.parsed.fiveHourReset}), Weekly=${activeSession.parsed.weeklyLimit} (${formatWeeklyResetLabel(activeSession.parsed.weeklyReset, 'en')})`,
        'info'
      );
      recordBatteryImpactEvent(activeSession.sessionTag, 'FG_SETTLE_IDLE', 'QUERY_SENT');
    }, 600);
  };

  const triggerSimulatedRefresh = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (isRefreshingHud || !isGeminiForeground) return;
    setIsRefreshingHud(true);
    setLastSyncNote(
      isZh
        ? `手动前台刷新 (MANUAL_TAP · ${activeSession.sessionTag})：绕过节流强制执行全量协议快照扫描（零后台服务）`
        : `Manual foreground refresh (MANUAL_TAP · ${activeSession.sessionTag}): bypassed throttle to run foreground full-protocol snapshot scan`
    );
    logDevEvent(
      'MANUAL_TAP',
      activeSession.sessionTag,
      `HUD 胶囊刷新图标点击 (MANUAL_TAP)。当前状态 usageSheetOpen=${usageSheetOpen}，绕过冷启动节流并触发 ForegroundFullProtocolSnapshotScan`,
      `HUD capsule refresh clicked (MANUAL_TAP). usageSheetOpen=${usageSheetOpen}, bypassing cooldown & invoking ForegroundFullProtocolSnapshotScan`,
      'event'
    );
    runForegroundFullProtocolDiagnostic('MANUAL_TAP', activeSession, usageSheetOpen);
    recordBatteryImpactEvent(activeSession.sessionTag, 'FG_DISPATCH', 'MANUAL_TAP');
    setTimeout(() => {
      setIsRefreshingHud(false);
      setIsStaleOffline(false);
      setIsSessionDeepCleaned(false);
      recordCachedNonZeroSnapshot(activeSession);
      logDevEvent(
        'PROTO_SCAN',
        activeSession.sessionTag,
        `全量协议快照扫描完毕 [5h=${activeSession.parsed.fiveHourQuota}, Weekly=${formatWeeklyResetLabel(activeSession.parsed.weeklyReset, 'zh')}]。HUD 重绘完成 [Source: ${activeSession.parsed.source}]`,
        `Full-protocol snapshot scan finished [5h=${activeSession.parsed.fiveHourQuota}, Weekly=${formatWeeklyResetLabel(activeSession.parsed.weeklyReset, 'en')}]. HUD redrawn [Source: ${activeSession.parsed.source}]`,
        'success'
      );
      recordBatteryImpactEvent(activeSession.sessionTag, 'FG_SETTLE_IDLE', 'MANUAL_TAP');
    }, 680);
  };

  const triggerOpenUsageWebpage = (e: React.MouseEvent) => {
    e.stopPropagation();
    setUsageSheetOpen(true);
    setIsStaleOffline(false);
    setIsSessionDeepCleaned(false);
    recordCachedNonZeroSnapshot(activeSession);
  };

  const handleForceFullProtocolScanFromSettings = () => {
    if (isRefreshingHud || !isGeminiForeground) return;
    setIsRefreshingHud(true);
    setDevConsoleOpen(true);
    setLastSyncNote(
      isZh
        ? `HUD 设置弹窗手动触发「强制全量协议扫描」(${activeSession.sessionTag})：已打印出站 HTTP/2 TE: trailers 与 gRPC-RPC 5B 帧头并强制重拉额度`
        : `Manual "Force Full-Protocol Scan" triggered from HUD Settings (${activeSession.sessionTag}): dumped outbound H2 TE: trailers & gRPC-RPC 5B frames and forced fresh pull`
    );
    runForegroundFullProtocolDiagnostic('FORCE_SETTINGS_SCAN', activeSession, usageSheetOpen);
    recordBatteryImpactEvent(activeSession.sessionTag, 'FG_DISPATCH', 'FORCE_SETTINGS_SCAN');
    setTimeout(() => {
      setIsRefreshingHud(false);
      setIsStaleOffline(false);
      setIsSessionDeepCleaned(false);
      recordCachedNonZeroSnapshot(activeSession);
      logDevEvent(
        'PROTO_SCAN',
        activeSession.sessionTag,
        `强制全量协议扫描重拉完成 [5h=${activeSession.parsed.fiveHourQuota} (${activeSession.parsed.fiveHourReset}), 每周=${activeSession.parsed.weeklyLimit} (${formatWeeklyResetLabel(activeSession.parsed.weeklyReset, 'zh')})] · Source: ${activeSession.parsed.source}`,
        `Force full-protocol pull complete [5h=${activeSession.parsed.fiveHourQuota} (${activeSession.parsed.fiveHourReset}), Weekly=${activeSession.parsed.weeklyLimit} (${formatWeeklyResetLabel(activeSession.parsed.weeklyReset, 'en')})] · Source: ${activeSession.parsed.source}`,
        'success'
      );
      recordBatteryImpactEvent(activeSession.sessionTag, 'FG_SETTLE_IDLE', 'FORCE_SETTINGS_SCAN');
    }, 520);
  };

  const getHudDragBounds = () => {
    const capsule = hudCapsuleRef.current;
    const host = capsule?.parentElement;
    if (capsule && host) {
      const maxLeftShift = Math.max(24, host.clientWidth - capsule.offsetWidth - 20);
      const maxDownShift = Math.max(180, host.clientHeight - capsule.offsetHeight - 132);
      return {
        minX: -maxLeftShift,
        maxX: 4,
        minY: -6,
        maxY: maxDownShift,
      };
    }
    return { minX: -64, maxX: 8, minY: -8, maxY: 260 };
  };

  const clampHudValue = (val: number, min: number, max: number) =>
    Math.max(min, Math.min(max, val));

  const rubberBandHudValue = (val: number, min: number, max: number, maxOvershoot = 18) => {
    if (val < min) {
      const over = min - val;
      return min - maxOvershoot * (1 - Math.exp(-over / 42));
    }
    if (val > max) {
      const over = val - max;
      return max + maxOvershoot * (1 - Math.exp(-over / 42));
    }
    return val;
  };

  const applyHudTransform = (x: number, y: number) => {
    hudPosRef.current = { x, y };
    if (hudCapsuleRef.current) {
      hudCapsuleRef.current.style.transform = `translate3d(${x.toFixed(2)}px, ${y.toFixed(2)}px, 0)`;
    }
  };

  const ensureHudAnimationLoop = () => {
    if (hudAnimRafRef.current !== null) return;
    let lastFrameTime = performance.now();

    const step = (now: number) => {
      const dt = Math.min(34, Math.max(4, now - lastFrameTime));
      lastFrameTime = now;
      const frameScale = dt / 16.67;

      const drag = dragRef.current;
      const bounds = getHudDragBounds();

      if (drag.pointerDown) {
        // Decay residual velocity if finger pauses while held so the 8ms lead offset settles to exact finger position
        const idleSinceSample = now - drag.lastSampleTimeMs;
        if (idleSinceSample > 24) {
          const holdDecay = Math.pow(0.72, frameScale);
          drag.vx *= holdDecay;
          drag.vy *= holdDecay;
        }

        const leadX = clampHudValue(drag.vx * HUD_FRAME_SMOOTH_OFFSET_MS, -14, 14);
        const leadY = clampHudValue(drag.vy * HUD_FRAME_SMOOTH_OFFSET_MS, -14, 14);
        drag.targetX = rubberBandHudValue(drag.filteredX + leadX, bounds.minX, bounds.maxX);
        drag.targetY = rubberBandHudValue(drag.filteredY + leadY, bounds.minY, bounds.maxY);

        const lerpAlpha = 1 - Math.pow(1 - 0.48, frameScale);
        const curX = hudPosRef.current.x;
        const curY = hudPosRef.current.y;
        const dx = drag.targetX - curX;
        const dy = drag.targetY - curY;

        if (Math.abs(dx) < 0.08 && Math.abs(dy) < 0.08 && Math.hypot(drag.vx, drag.vy) < 0.015) {
          applyHudTransform(drag.targetX, drag.targetY);
          hudAnimRafRef.current = null;
          return;
        }
        applyHudTransform(curX + dx * lerpAlpha, curY + dy * lerpAlpha);
        hudAnimRafRef.current = requestAnimationFrame(step);
        return;
      }

      // Release phase: inertial glide + smooth elastic settle + 40px edge Magnetism
      const decay = Math.pow(0.9, frameScale);
      drag.vx *= decay;
      drag.vy *= decay;

      const rawNextTargetX = drag.targetX + drag.vx * dt;
      const rawNextTargetY = drag.targetY + drag.vy * dt;
      let nextTargetX = clampHudValue(rawNextTargetX, bounds.minX, bounds.maxX);
      let nextTargetY = clampHudValue(rawNextTargetY, bounds.minY, bounds.maxY);

      if (rawNextTargetX !== nextTargetX) drag.vx *= 0.45;
      if (rawNextTargetY !== nextTargetY) drag.vy *= 0.45;

      const speed = Math.hypot(drag.vx, drag.vy);
      let magnetPending = false;
      const capsule = hudCapsuleRef.current;

      // Magnetism: when drag stops/settles and HUD center is within 40px of any usable screen edge,
      // find the nearest boundary and smoothly interpolate drag.targetX/Y flush to that edge.
      if (capsule && speed < 0.18) {
        const halfW = capsule.offsetWidth / 2;
        const halfH = capsule.offsetHeight / 2;
        const baseLeft = capsule.offsetLeft;
        const baseTop = capsule.offsetTop;

        const centerX = baseLeft + nextTargetX + halfW;
        const centerY = baseTop + nextTargetY + halfH;

        const minCenterX = baseLeft + bounds.minX + halfW;
        const maxCenterX = baseLeft + bounds.maxX + halfW;
        const minCenterY = baseTop + bounds.minY + halfH;
        const maxCenterY = baseTop + bounds.maxY + halfH;

        const distLeft = Math.abs(centerX - minCenterX);
        const distRight = Math.abs(maxCenterX - centerX);
        const distTop = Math.abs(centerY - minCenterY);
        const distBottom = Math.abs(maxCenterY - centerY);

        const nearestEdgeXDist = Math.min(distLeft, distRight);
        const nearestEdgeYDist = Math.min(distTop, distBottom);
        const magnetAlpha = 1 - Math.pow(1 - 0.24, frameScale);

        if (nearestEdgeXDist <= HUD_MAGNET_EDGE_THRESHOLD_PX) {
          const snapX = distLeft <= distRight ? bounds.minX : bounds.maxX;
          const deltaSnapX = snapX - nextTargetX;
          if (Math.abs(deltaSnapX) < 0.12) {
            nextTargetX = snapX;
          } else {
            nextTargetX += deltaSnapX * magnetAlpha;
            drag.vx *= 0.6;
            magnetPending = true;
          }
        }

        if (nearestEdgeYDist <= HUD_MAGNET_EDGE_THRESHOLD_PX) {
          const snapY = distTop <= distBottom ? bounds.minY : bounds.maxY;
          const deltaSnapY = snapY - nextTargetY;
          if (Math.abs(deltaSnapY) < 0.12) {
            nextTargetY = snapY;
          } else {
            nextTargetY += deltaSnapY * magnetAlpha;
            drag.vy *= 0.6;
            magnetPending = true;
          }
        }
      }

      drag.targetX = nextTargetX;
      drag.targetY = nextTargetY;

      const curX = hudPosRef.current.x;
      const curY = hudPosRef.current.y;
      const followAlpha = 1 - Math.pow(1 - 0.28, frameScale);
      const nextX = curX + (nextTargetX - curX) * followAlpha;
      const nextY = curY + (nextTargetY - curY) * followAlpha;

      const dist = Math.hypot(nextTargetX - nextX, nextTargetY - nextY);
      if (!magnetPending && speed < 0.025 && dist < 0.12) {
        drag.vx = 0;
        drag.vy = 0;
        applyHudTransform(nextTargetX, nextTargetY);
        hudAnimRafRef.current = null;
        return;
      }

      applyHudTransform(nextX, nextY);
      hudAnimRafRef.current = requestAnimationFrame(step);
    };

    hudAnimRafRef.current = requestAnimationFrame(step);
  };

  const onHudPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    const target = e.target as HTMLElement;
    if (target.closest('[data-hud-action="true"]')) {
      return;
    }
    e.currentTarget.setPointerCapture(e.pointerId);
    const now = performance.now();
    dragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      origX: hudPosRef.current.x,
      origY: hudPosRef.current.y,
      filteredX: hudPosRef.current.x,
      filteredY: hudPosRef.current.y,
      targetX: hudPosRef.current.x,
      targetY: hudPosRef.current.y,
      lastClientX: e.clientX,
      lastClientY: e.clientY,
      lastTimeMs: now,
      lastSampleTimeMs: now,
      vx: 0,
      vy: 0,
      moved: false,
      pointerDown: true,
    };
  };

  const onHudPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag.pointerDown) return;

    const dx = e.clientX - drag.startX;
    const dy = e.clientY - drag.startY;
    if (!drag.moved && (Math.abs(dx) > 3 || Math.abs(dy) > 3)) {
      drag.moved = true;
    }
    if (!drag.moved) return;

    const now = performance.now();
    const stepDx = e.clientX - drag.lastClientX;
    const stepDy = e.clientY - drag.lastClientY;
    const stepDist = Math.hypot(stepDx, stepDy);
    const sampleDt = now - drag.lastSampleTimeMs;

    // Anti-jitter touch sampling: drop sub-pixel digitizer tremor bursts within 8ms window
    if (stepDist < HUD_TOUCH_JITTER_DEADBAND_PX && sampleDt < HUD_TOUCH_SAMPLE_WINDOW_MS) {
      return;
    }

    const dt = Math.min(64, Math.max(4, now - drag.lastTimeMs));
    const instVx = stepDx / dt;
    const instVy = stepDy / dt;

    // Exponential velocity smoothing prevents fast-swipe spike jitter
    drag.vx = drag.vx * 0.55 + clampHudValue(instVx, -1.8, 1.8) * 0.45;
    drag.vy = drag.vy * 0.55 + clampHudValue(instVy, -1.8, 1.8) * 0.45;
    drag.lastClientX = e.clientX;
    drag.lastClientY = e.clientY;
    drag.lastTimeMs = now;
    drag.lastSampleTimeMs = now;

    // Adaptive speed-coupled position smoothing (suppresses slow micro-jitter while preserving fast swipe tracking)
    const speed = Math.hypot(drag.vx, drag.vy);
    const posAlpha = clampHudValue(0.48 + speed * 0.44, 0.48, 0.96);
    const rawX = drag.origX + dx;
    const rawY = drag.origY + dy;
    drag.filteredX = drag.filteredX + (rawX - drag.filteredX) * posAlpha;
    drag.filteredY = drag.filteredY + (rawY - drag.filteredY) * posAlpha;

    // Apply 8ms predictive frame-smoothing offset to reduce touch-to-display drag latency on Android
    const bounds = getHudDragBounds();
    const leadX = clampHudValue(drag.vx * HUD_FRAME_SMOOTH_OFFSET_MS, -14, 14);
    const leadY = clampHudValue(drag.vy * HUD_FRAME_SMOOTH_OFFSET_MS, -14, 14);
    drag.targetX = rubberBandHudValue(drag.filteredX + leadX, bounds.minX, bounds.maxX);
    drag.targetY = rubberBandHudValue(drag.filteredY + leadY, bounds.minY, bounds.maxY);

    ensureHudAnimationLoop();
  };

  const onHudPointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag.pointerDown) return;
    drag.pointerDown = false;
    try {
      if (e.currentTarget.hasPointerCapture(e.pointerId)) {
        e.currentTarget.releasePointerCapture(e.pointerId);
      }
    } catch {
      // ignore if capture already released
    }
    if (drag.moved) {
      const bounds = getHudDragBounds();
      drag.targetX = clampHudValue(drag.filteredX, bounds.minX, bounds.maxX);
      drag.targetY = clampHudValue(drag.filteredY, bounds.minY, bounds.maxY);
      if (performance.now() - drag.lastSampleTimeMs > 64) {
        drag.vx = 0;
        drag.vy = 0;
      }
      ensureHudAnimationLoop();
    }
  };

  const pickBarColorClass = (pct: number) => {
    if (isStaleOffline) return simDarkTheme ? 'bg-slate-500' : 'bg-slate-400';
    if (pct >= 90) return simDarkTheme ? 'bg-red-400' : 'bg-red-500';
    if (pct >= 75) return simDarkTheme ? 'bg-amber-400' : 'bg-amber-500';
    if (hudTheme === 'MD3') {
      return simDarkTheme ? 'bg-violet-300' : 'bg-violet-600';
    }
    return simDarkTheme ? 'bg-sky-400' : 'bg-blue-500';
  };

  const isHighLoadQuota =
    !isStaleOffline &&
    !isSessionDeepCleaned &&
    (activeSession.parsed.fiveHourPct > 90 || activeSession.parsed.weeklyPct > 90);

  const capsuleContainerClass = useMemo(() => {
    const highLoadClass = isHighLoadQuota
      ? simDarkTheme
        ? 'animate-high-load-shadow ring-1 ring-red-400/35'
        : 'animate-high-load-shadow ring-1 ring-red-500/30'
      : '';

    if (isStaleOffline) {
      if (hudTheme === 'MIUIX') {
        return simDarkTheme
          ? 'bg-zinc-900/80 backdrop-blur-xl border-slate-500/40 text-slate-400 shadow-lg shadow-black/40'
          : 'bg-white/85 backdrop-blur-xl border-slate-400/40 text-slate-500 shadow-lg shadow-slate-900/10';
      }
      return simDarkTheme
        ? 'bg-[#292533]/95 border-gray-500/45 text-gray-400 shadow-md shadow-black/40'
        : 'bg-[#F5EFFB]/95 border-gray-400/50 text-gray-500 shadow-md shadow-indigo-950/10';
    }
    if (hudTheme === 'MIUIX') {
      const base = simDarkTheme
        ? 'bg-zinc-900/80 backdrop-blur-xl border-white/20 text-slate-50 shadow-lg shadow-black/40'
        : 'bg-white/85 backdrop-blur-xl border-slate-900/12 text-slate-900 shadow-lg shadow-slate-900/12';
      return highLoadClass ? `${base} ${highLoadClass}` : base;
    }
    const base = simDarkTheme
      ? 'bg-[#292533]/95 border-[#938F99]/40 text-[#E6E1E5] shadow-md shadow-black/40'
      : 'bg-[#F5EFFB]/95 border-[#CAC4D0]/70 text-[#1D1B20] shadow-md shadow-indigo-950/10';
    return highLoadClass ? `${base} ${highLoadClass}` : base;
  }, [hudTheme, simDarkTheme, isStaleOffline, isHighLoadQuota]);

  const capsuleLabelClass = useMemo(() => {
    if (isStaleOffline) {
      return simDarkTheme ? 'text-slate-400' : 'text-slate-500';
    }
    if (hudTheme === 'MIUIX') {
      return simDarkTheme ? 'text-slate-400' : 'text-slate-500';
    }
    return simDarkTheme ? 'text-[#CAC4D0]' : 'text-[#49454F]';
  }, [hudTheme, simDarkTheme, isStaleOffline]);

  const capsuleTrackClass = useMemo(() => {
    if (hudTheme === 'MIUIX') {
      return simDarkTheme ? 'bg-white/20' : 'bg-slate-900/15';
    }
    return simDarkTheme ? 'bg-[#4A4458]' : 'bg-[#E8DEF8]';
  }, [hudTheme, simDarkTheme]);

  const capsuleIconClass = useMemo(() => {
    if (isStaleOffline) {
      return simDarkTheme
        ? 'text-slate-400 hover:bg-white/10 active:bg-white/20'
        : 'text-slate-500 hover:bg-slate-900/10 active:bg-slate-900/15';
    }
    if (hudTheme === 'MIUIX') {
      return simDarkTheme
        ? 'text-slate-300 hover:bg-white/15 active:bg-white/25'
        : 'text-slate-600 hover:bg-slate-900/10 active:bg-slate-900/15';
    }
    return simDarkTheme
      ? 'text-violet-300 hover:bg-violet-300/15 active:bg-violet-300/25'
      : 'text-violet-700 hover:bg-violet-700/10 active:bg-violet-700/20';
  }, [hudTheme, simDarkTheme, isStaleOffline]);

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
              onClick={() => {
                setUiLang('zh');
                setHudLang('zh');
              }}
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
              onClick={() => {
                setUiLang('en');
                setHudLang('en');
              }}
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
                          {activeSession.parsed.weeklyLimit} ({formatWeeklyResetLabel(activeSession.parsed.weeklyReset, hudLang)})
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
                          className={`p-3.5 rounded-2xl border space-y-2 max-h-[250px] overflow-y-auto ${
                            simDarkTheme
                              ? 'bg-zinc-900 border-zinc-700 text-zinc-100'
                              : 'bg-white border-slate-200 text-slate-900 shadow-xs'
                          }`}
                        >
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-xs font-mono font-semibold text-blue-500 truncate">
                              com.example.bardquotahook.MainActivity
                            </span>
                            <button
                              type="button"
                              onClick={() => setUsageSheetOpen(false)}
                              className="text-xs text-slate-400 hover:text-slate-600 shrink-0 cursor-pointer"
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
                              {hudLang === 'zh' ? '每周' : 'Weekly'} ({activeSession.parsed.source}):{' '}
                              <strong>{activeSession.parsed.weeklyLimit}</strong> (
                              {formatWeeklyResetLabel(activeSession.parsed.weeklyReset, hudLang)})
                            </div>
                          </div>

                          <div className="pt-1.5 border-t border-slate-200/60 dark:border-zinc-700/70 space-y-1.5">
                            <button
                              type="button"
                              onClick={handleForceFullProtocolScanFromSettings}
                              disabled={isRefreshingHud}
                              className={`w-full inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors cursor-pointer ${
                                isRefreshingHud
                                  ? 'bg-cyan-700/70 text-cyan-100 cursor-wait'
                                  : 'bg-cyan-600 hover:bg-cyan-700 text-white'
                              }`}
                            >
                              <RefreshCw className={`w-3.5 h-3.5 ${isRefreshingHud ? 'animate-spin' : ''}`} />
                              <span>
                                {isZh ? '强制全量协议扫描' : 'Force Full-Protocol Scan'}
                              </span>
                            </button>

                            {lastDiagTraceSummary && (
                              <pre
                                className={`p-2 rounded-lg text-[10px] leading-snug font-mono whitespace-pre-wrap break-all border ${
                                  simDarkTheme
                                    ? 'bg-zinc-950 border-zinc-800 text-cyan-300'
                                    : 'bg-slate-900 border-slate-800 text-cyan-300'
                                }`}
                              >
                                {lastDiagTraceSummary}
                              </pre>
                            )}
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
                      onPointerCancel={onHudPointerUp}
                      className={`absolute top-12 right-2.5 max-w-[96%] inline-flex flex-col items-stretch rounded-full border text-[11px] leading-none whitespace-nowrap cursor-grab active:cursor-grabbing touch-none will-change-transform transition-colors ${
                        isStaleOffline ? 'px-3 py-1.5' : 'px-2.5 py-1.5'
                      } ${capsuleContainerClass}`}
                    >
                      <div className="inline-flex items-center">
                        {isStaleOffline && (
                          <span
                            title={hudLang === 'zh' ? '当前数据为离线缓存状态' : 'Cached offline state'}
                            aria-label={hudLang === 'zh' ? '离线缓存状态' : 'Offline cached'}
                            className="relative flex items-center justify-center w-2 h-2 mr-1.5 shrink-0"
                          >
                            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-orange-400 opacity-75" />
                            <span
                              className={`relative inline-flex rounded-full w-1.5 h-1.5 animate-pulse ${
                                simDarkTheme
                                  ? 'bg-orange-400 shadow-[0_0_6px_rgba(251,146,60,0.85)]'
                                  : 'bg-orange-500 shadow-[0_0_5px_rgba(249,115,22,0.7)]'
                              }`}
                            />
                          </span>
                        )}
                        <span className={`font-semibold ${capsuleLabelClass}`}>5h</span>
                        <span
                          className={`relative mx-1.5 rounded-full overflow-hidden shrink-0 transition-all duration-200 ${capsuleTrackClass} ${
                            isHighLoadQuota ? 'w-7 h-[13px]' : 'w-6 h-[5px]'
                          }`}
                        >
                          <span
                            ref={bar5hRef}
                            className={`block h-full rounded-full transition-all duration-300 ${pickBarColorClass(
                              activeSession.parsed.fiveHourPct
                            )}`}
                          />
                          {isHighLoadQuota && (
                            <span
                              className={`pointer-events-none absolute inset-0 flex items-center justify-center text-[8px] font-bold leading-none tabular-nums ${
                                activeSession.parsed.fiveHourPct >= 55 || simDarkTheme
                                  ? 'text-white drop-shadow-[0_1px_1px_rgba(0,0,0,0.65)]'
                                  : 'text-slate-900/85'
                              }`}
                            >
                              {activeSession.parsed.fiveHourPct}%
                            </span>
                          )}
                        </span>
                        <span className="font-medium tabular-nums">
                          {isSessionDeepCleaned
                            ? '-- (--)'
                            : `${activeSession.parsed.fiveHourQuota} (${activeSession.parsed.fiveHourReset})`}
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
                          className={`relative mx-1.5 rounded-full overflow-hidden shrink-0 transition-all duration-200 ${capsuleTrackClass} ${
                            isHighLoadQuota ? 'w-7 h-[13px]' : 'w-6 h-[5px]'
                          }`}
                        >
                          <span
                            ref={barWeeklyRef}
                            className={`block h-full rounded-full transition-all duration-300 ${pickBarColorClass(
                              activeSession.parsed.weeklyPct
                            )}`}
                          />
                          {isHighLoadQuota && (
                            <span
                              className={`pointer-events-none absolute inset-0 flex items-center justify-center text-[8px] font-bold leading-none tabular-nums ${
                                activeSession.parsed.weeklyPct >= 55 || simDarkTheme
                                  ? 'text-white drop-shadow-[0_1px_1px_rgba(0,0,0,0.65)]'
                                  : 'text-slate-900/85'
                              }`}
                            >
                              {activeSession.parsed.weeklyPct}%
                            </span>
                          )}
                        </span>
                        <span className="font-medium tabular-nums">
                          {isSessionDeepCleaned
                            ? '-- (--)'
                            : `${activeSession.parsed.weeklyLimit} (${formatWeeklyResetLabel(activeSession.parsed.weeklyReset, hudLang)})`}
                        </span>

                        {isStaleOffline && (
                          <span
                            className={`ml-1.5 px-1.5 py-0.5 rounded text-[9px] font-semibold border shrink-0 ${
                              simDarkTheme
                                ? 'bg-slate-500/15 text-slate-400 border-slate-500/35'
                                : 'bg-slate-500/10 text-slate-500 border-slate-400/40'
                            }`}
                          >
                            {hudLang === 'zh' ? '离线' : 'Offline'}
                          </span>
                        )}

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

                      {isStaleOffline && (() => {
                        const cachedAt =
                          cachedSnapshotsRef.current[activeSession.sessionTag]?.cachedAtStr ||
                          lastCachedTimestamp ||
                          '';
                        if (!cachedAt) return null;
                        return (
                          <div
                            title={`Last Cached: ${cachedAt}`}
                            className={`mt-1 pt-0.5 border-t flex items-center gap-1 pl-3.5 text-[9px] leading-tight font-mono tabular-nums ${
                              simDarkTheme
                                ? 'border-white/10 text-slate-400/90'
                                : 'border-slate-900/10 text-slate-500/95'
                            }`}
                          >
                            <span className="w-1 h-1 rounded-full bg-orange-400/80 shrink-0" />
                            <span>
                              {hudLang === 'zh' ? '最近缓存' : 'Last Cached'}: {cachedAt.slice(0, 19)}
                            </span>
                          </div>
                        );
                      })()}
                    </div>
                  )}
              </div>
            </div>

            {/* Developer Console (Collapsible Hook Event Stream with Timestamps) */}
            <div className="mt-8 bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden shadow-sm">
              <button
                type="button"
                onClick={() => setDevConsoleOpen((open) => !open)}
                className="w-full px-5 py-3.5 flex items-center justify-between text-left bg-slate-900/90 hover:bg-slate-800/80 transition-colors border-b border-slate-800 cursor-pointer"
              >
                <div className="flex items-center gap-2.5">
                  <div className="w-6 h-6 rounded-md bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center text-emerald-400">
                    <Terminal className="w-3.5 h-3.5" />
                  </div>
                  <div>
                    <span className="text-xs font-semibold text-slate-100 flex items-center gap-2">
                      <span>{isZh ? '开发者控制台 · 模拟事件流' : 'Developer Console · Simulated Event Stream'}</span>
                      <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                        {devLogs.length} {isZh ? '条事件' : 'events'}
                      </span>
                      <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-orange-500/15 text-orange-300 border border-orange-500/30">
                        {isZh ? `归零防护: ${zeroGuardCount} 次` : `Zero-Guard: ${zeroGuardCount}`}
                      </span>
                      <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-red-500/15 text-red-300 border border-red-500/30">
                        {isZh ? `链路异常: ${linkAnomalyCount} 次` : `Link Anomaly: ${linkAnomalyCount}`}
                      </span>
                      <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-teal-500/15 text-teal-300 border border-teal-500/30">
                        {isZh ? `后台唤醒: ${bgWakeupCount} 次` : `BG Wakeups: ${bgWakeupCount}`}
                      </span>
                    </span>
                    <span className="text-[11px] text-slate-400 block mt-0.5">
                      {isZh
                        ? '记录出站头拦截、归零防护已触发（含缓存时间戳与恢复值）、QuotaHealthMonitor 链路异常（含深层清理记录）与毫秒时间戳'
                        : 'Logs APP_OPEN, QUERY_SENT, ZERO_GUARD_TRIGGERED, QuotaHealthMonitor LINK_ANOMALY (deep cleanup), and ProtoBuf scans'}
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-2 text-slate-400">
                  <span className="text-[11px] font-mono text-slate-500 hidden sm:inline">
                    {devConsoleOpen
                      ? isZh ? '点击收起' : 'Click to collapse'
                      : isZh ? '展开日志控制台' : 'Expand event console'}
                  </span>
                  {devConsoleOpen ? (
                    <ChevronDown className="w-4 h-4 text-slate-300" />
                  ) : (
                    <ChevronRight className="w-4 h-4 text-slate-300" />
                  )}
                </div>
              </button>

              {devConsoleOpen && (
                <div className="p-4 space-y-3">
                  {/* Console Header Bar */}
                  <div className="flex flex-wrap items-center justify-between gap-2 px-1 text-xs">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="inline-flex items-center gap-1.5 font-mono text-[11px] text-slate-400">
                        <Activity className="w-3.5 h-3.5 text-emerald-400" />
                        <span>Active Session:</span>
                        <span className="text-emerald-400 font-semibold">{activeSession.sessionTag}</span>
                      </span>
                      <span className="text-slate-700">|</span>
                      <span className="text-[11px] text-slate-400 font-mono">
                        Foreground: <strong className={isGeminiForeground ? 'text-emerald-400' : 'text-amber-400'}>{isGeminiForeground ? 'true' : 'false (0 threads)'}</strong>
                      </span>
                      <span className="text-slate-700">|</span>
                      <span className="text-[11px] text-slate-400 font-mono">
                        Pool: <strong className="text-teal-300">{poolStateLabel} ({activePoolThreads}t · FG {fgDispatchCount} / BG {bgWakeupCount})</strong>
                      </span>
                      <span className="text-slate-700">|</span>
                      <div className="inline-flex items-center gap-1 bg-slate-950 p-0.5 rounded border border-slate-800">
                        <button
                          type="button"
                          onClick={() => setConsoleFilter('ALL')}
                          className={`px-2 py-0.5 text-[10px] rounded transition-colors cursor-pointer ${
                            consoleFilter === 'ALL'
                              ? 'bg-slate-800 text-slate-100 font-semibold'
                              : 'text-slate-400 hover:text-slate-200'
                          }`}
                        >
                          {isZh ? '全部日志' : 'All'}
                        </button>
                        <button
                          type="button"
                          onClick={() => setConsoleFilter('ZERO_GUARD')}
                          className={`px-2 py-0.5 text-[10px] rounded transition-colors cursor-pointer ${
                            consoleFilter === 'ZERO_GUARD'
                              ? 'bg-orange-500/20 text-orange-300 font-semibold'
                              : 'text-slate-400 hover:text-slate-200'
                          }`}
                        >
                          {isZh ? `仅归零防护 (${zeroGuardCount})` : `Zero-Guard (${zeroGuardCount})`}
                        </button>
                        <button
                          type="button"
                          onClick={() => setConsoleFilter('LINK_ANOMALY')}
                          className={`px-2 py-0.5 text-[10px] rounded transition-colors cursor-pointer ${
                            consoleFilter === 'LINK_ANOMALY'
                              ? 'bg-red-500/20 text-red-300 font-semibold'
                              : 'text-slate-400 hover:text-slate-200'
                          }`}
                        >
                          {isZh ? `仅链路异常 (${linkAnomalyCount})` : `Link Anomaly (${linkAnomalyCount})`}
                        </button>
                        <button
                          type="button"
                          onClick={() => setConsoleFilter('BATTERY_IMPACT')}
                          className={`px-2 py-0.5 text-[10px] rounded transition-colors cursor-pointer ${
                            consoleFilter === 'BATTERY_IMPACT'
                              ? 'bg-teal-500/20 text-teal-300 font-semibold'
                              : 'text-slate-400 hover:text-slate-200'
                          }`}
                        >
                          {isZh ? `功耗与线程 (${batteryImpactCount})` : `Battery Impact (${batteryImpactCount})`}
                        </button>
                      </div>
                    </div>

                    <div className="flex flex-wrap items-center gap-2">
                      <button
                        type="button"
                        onClick={handleSimulateProtobufZeroGuard}
                        className="px-2.5 py-1 text-[11px] rounded bg-orange-950/60 hover:bg-orange-900/60 text-orange-200 border border-orange-700/60 transition-colors cursor-pointer"
                      >
                        {isZh ? '模拟 Protobuf 归零防护' : 'Simulate Protobuf 0% Guard'}
                      </button>

                      <button
                        type="button"
                        onClick={handleSimulateHealthAnomaly}
                        className="px-2.5 py-1 text-[11px] rounded bg-red-950/60 hover:bg-red-900/60 text-red-200 border border-red-700/60 transition-colors cursor-pointer"
                      >
                        {isZh ? '模拟链路异常 (PB失败/3次空)' : 'Simulate Link Anomaly'}
                      </button>

                      <button
                        type="button"
                        onClick={() =>
                          logDevEvent(
                            'PROTO_SCAN',
                            activeSession.sessionTag,
                            `开发控制台诊断心跳测试: QuotaCoreEngine (v2.1.0) 桥接正常，当前会话指纹 [${activeSession.sessionTag}]`,
                            `Dev console diagnostic ping: QuotaCoreEngine (v2.1.0) bridge verified, session fingerprint [${activeSession.sessionTag}]`,
                            'info'
                          )
                        }
                        className="px-2.5 py-1 text-[11px] rounded bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700 transition-colors cursor-pointer"
                      >
                        {isZh ? '发送测试日志' : 'Send Test Event'}
                      </button>

                      <button
                        type="button"
                        onClick={() => {
                          setDevLogs([]);
                          setZeroGuardCount(0);
                          setLinkAnomalyCount(0);
                          setBatteryImpactCount(0);
                          setFgDispatchCount(0);
                          setBgWakeupCount(0);
                          setIsStaleOffline(false);
                          setIsSessionDeepCleaned(false);
                        }}
                        className="inline-flex items-center gap-1 px-2.5 py-1 text-[11px] rounded bg-slate-800 hover:bg-red-950/40 text-slate-300 hover:text-red-300 border border-slate-700 hover:border-red-800 transition-colors cursor-pointer"
                        title={isZh ? '清空控制台日志' : 'Clear console logs'}
                      >
                        <Trash2 className="w-3 h-3" />
                        <span>{isZh ? '清空' : 'Clear'}</span>
                      </button>
                    </div>
                  </div>

                  {/* Real-Time Thread Pool Visualization Bar (0: Green, 1: Amber, >1: Red) */}
                  {(() => {
                    const maxSlots = 4;
                    const poolTier =
                      activePoolThreads === 0 ? 'idle' : activePoolThreads === 1 ? 'single' : 'burst';
                    const cardTone =
                      poolTier === 'idle'
                        ? 'border-emerald-500/30 bg-emerald-950/20'
                        : poolTier === 'single'
                        ? 'border-amber-500/40 bg-amber-950/25'
                        : 'border-red-500/50 bg-red-950/30';
                    const barFillTone =
                      poolTier === 'idle'
                        ? 'bg-emerald-500 w-full opacity-35'
                        : poolTier === 'single'
                        ? 'bg-amber-500 w-1/4 animate-pulse'
                        : activePoolThreads === 2
                        ? 'bg-red-500 w-2/4 animate-pulse'
                        : activePoolThreads === 3
                        ? 'bg-red-500 w-3/4 animate-pulse'
                        : 'bg-red-500 w-full animate-pulse';
                    const badgeTone =
                      poolTier === 'idle'
                        ? 'bg-emerald-500/15 text-emerald-300 border-emerald-500/40'
                        : poolTier === 'single'
                        ? 'bg-amber-500/20 text-amber-300 border-amber-500/40'
                        : 'bg-red-500/20 text-red-300 border-red-500/50';
                    const dotTone =
                      poolTier === 'idle'
                        ? 'bg-emerald-400'
                        : poolTier === 'single'
                        ? 'bg-amber-400 animate-ping'
                        : 'bg-red-400 animate-ping';
                    const statusLabel =
                      poolTier === 'idle'
                        ? isZh
                          ? '0 活跃线程 · 零后台静默空闲'
                          : '0 threads · Zero-Idle'
                        : poolTier === 'single'
                        ? isZh
                          ? '1 活跃线程 · 单线程按需执行'
                          : '1 thread · On-Demand Active'
                        : isZh
                        ? `${activePoolThreads} 活跃线程 · 并发突发负载`
                        : `${activePoolThreads} threads · Concurrent Burst`;

                    return (
                      <div className={`rounded-xl border px-3 py-2.5 transition-colors duration-200 ${cardTone}`}>
                        <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
                          <div className="flex items-center gap-2">
                            <Cpu
                              className={`w-3.5 h-3.5 ${
                                poolTier === 'idle'
                                  ? 'text-emerald-400'
                                  : poolTier === 'single'
                                  ? 'text-amber-400'
                                  : 'text-red-400'
                              }`}
                            />
                            <span className="text-[11px] font-semibold text-slate-200 font-mono">
                              {isZh ? 'Thread Pool · 实时线程池负载' : 'Thread Pool · Real-Time Activity'}
                            </span>
                            <span
                              className={`inline-flex items-center gap-1.5 text-[10px] font-mono font-semibold px-2 py-0.5 rounded border transition-colors ${badgeTone}`}
                            >
                              <span className="relative flex h-1.5 w-1.5">
                                <span className={`absolute inline-flex h-full w-full rounded-full opacity-75 ${dotTone}`} />
                                <span
                                  className={`relative inline-flex rounded-full h-1.5 w-1.5 ${
                                    poolTier === 'idle'
                                      ? 'bg-emerald-400'
                                      : poolTier === 'single'
                                      ? 'bg-amber-400'
                                      : 'bg-red-400'
                                  }`}
                                />
                              </span>
                              {statusLabel}
                            </span>
                          </div>

                          <div className="flex items-center gap-2.5 text-[10px] font-mono text-slate-400">
                            <span className="hidden sm:inline-flex items-center gap-2">
                              <span className="inline-flex items-center gap-1">
                                <span className="w-2 h-2 rounded-xs bg-emerald-500" />0
                              </span>
                              <span className="inline-flex items-center gap-1">
                                <span className="w-2 h-2 rounded-xs bg-amber-500" />1
                              </span>
                              <span className="inline-flex items-center gap-1">
                                <span className="w-2 h-2 rounded-xs bg-red-500" />&gt;1
                              </span>
                            </span>
                            <button
                              type="button"
                              disabled={!isGeminiForeground}
                              onClick={handleSimulateThreadBurst}
                              className="px-2 py-0.5 rounded bg-slate-800 hover:bg-slate-700 disabled:opacity-40 disabled:cursor-not-allowed text-slate-200 border border-slate-700 transition-colors cursor-pointer"
                            >
                              {isZh ? '并发测试 (2t)' : 'Burst Test (2t)'}
                            </button>
                          </div>
                        </div>

                        {/* Continuous Dynamic Load Bar + Discrete Worker Slots */}
                        <div className="space-y-1.5">
                          <div className="w-full h-2.5 bg-slate-950/90 rounded-md overflow-hidden border border-slate-800/90 p-0.5">
                            <div className={`h-full rounded-sm transition-all duration-200 ${barFillTone}`} />
                          </div>

                          <div className="grid grid-cols-4 gap-1.5">
                            {Array.from({ length: maxSlots }).map((_, idx) => {
                              const isSlotBusy = idx < activePoolThreads;
                              const slotClass = !isSlotBusy
                                ? poolTier === 'idle'
                                  ? 'bg-emerald-500/15 border-emerald-500/35 text-emerald-400'
                                  : 'bg-slate-900/90 border-slate-800 text-slate-500'
                                : poolTier === 'single'
                                ? 'bg-amber-500/85 border-amber-400 text-slate-950 font-bold'
                                : 'bg-red-500/85 border-red-400 text-white font-bold';
                              return (
                                <div
                                  key={idx}
                                  className={`h-4 rounded border flex items-center justify-between px-1.5 text-[9px] font-mono transition-all duration-200 ${slotClass}`}
                                >
                                  <span>W{idx + 1}</span>
                                  <span>{isSlotBusy ? 'RUN' : 'IDLE'}</span>
                                </div>
                              );
                            })}
                          </div>
                        </div>
                      </div>
                    );
                  })()}

                  {/* Terminal Log Stream Area */}
                  <div className="bg-slate-950 border border-slate-800/80 rounded-xl p-3 max-h-72 overflow-y-auto font-mono text-xs space-y-1.5 shadow-inner">
                    {visibleDevLogs.length === 0 ? (
                      <div className="text-center py-8 text-slate-500 text-xs">
                        {consoleFilter === 'ZERO_GUARD'
                          ? isZh
                            ? '暂无「归零防护已触发」事件记录。点击右上角「模拟 Protobuf 归零防护」即可验证缓存快照时间戳与数值恢复。'
                            : 'No ZERO_GUARD_TRIGGERED events yet. Click "Simulate Protobuf 0% Guard" to inspect cache timestamp & value recovery.'
                          : consoleFilter === 'LINK_ANOMALY'
                          ? isZh
                            ? '暂无「链路异常」事件记录。点击「模拟链路异常 (PB失败/3次空)」即可验证 QuotaHealthMonitor 自动深层清理 Session 缓存槽位。'
                            : 'No LINK_ANOMALY events yet. Click "Simulate Link Anomaly" to inspect QuotaHealthMonitor deep session cleanup.'
                          : consoleFilter === 'BATTERY_IMPACT'
                          ? isZh
                            ? '暂无「功耗与线程 (BATTERY_IMPACT)」事件记录。切换前后台、切号或发送提问即可追踪累计后台唤醒次数与线程池状态迁移。'
                            : 'No BATTERY_IMPACT events yet. Toggle foreground/background, switch accounts, or send a query to monitor cumulative background wakeups & thread-pool states.'
                          : isZh
                          ? '控制台暂无事件记录。在上方切换账号、发送提问或触发模拟按钮以产生事件。'
                          : 'No events logged. Switch accounts, send queries, or click simulation buttons to capture events.'}
                      </div>
                    ) : (
                      visibleDevLogs.map((log) => {
                        const badgeColor =
                          log.eventType === 'LINK_ANOMALY'
                            ? 'bg-red-500/20 text-red-300 border-red-500/40'
                            : log.eventType === 'ZERO_GUARD'
                            ? 'bg-orange-500/20 text-orange-300 border-orange-500/40'
                            : log.eventType === 'APP_OPEN'
                            ? 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30'
                            : log.eventType === 'QUERY_SENT'
                            ? 'bg-blue-500/15 text-blue-300 border-blue-500/30'
                            : log.eventType === 'ACCOUNT_SWITCH'
                            ? 'bg-amber-500/15 text-amber-300 border-amber-500/30'
                            : log.eventType === 'BACKGROUND_SLEEP'
                            ? 'bg-purple-500/15 text-purple-300 border-purple-500/30'
                            : log.eventType === 'MANUAL_TAP'
                            ? 'bg-sky-500/15 text-sky-300 border-sky-500/30'
                            : log.eventType === 'FULL_PROTOCOL_SCAN'
                            ? 'bg-cyan-500/20 text-cyan-300 border-cyan-500/40'
                            : log.eventType === 'BATTERY_IMPACT'
                            ? 'bg-teal-500/20 text-teal-300 border-teal-500/40'
                            : 'bg-zinc-700/40 text-zinc-300 border-zinc-600/40';

                        const eventLabel =
                          log.eventType === 'LINK_ANOMALY'
                            ? isZh
                              ? '链路异常'
                              : 'LINK_ANOMALY'
                            : log.eventType === 'ZERO_GUARD'
                            ? isZh
                              ? '归零防护已触发'
                              : 'ZERO_GUARD_TRIGGERED'
                            : log.eventType === 'FULL_PROTOCOL_SCAN'
                            ? isZh
                              ? '全量协议扫描'
                              : 'FULL_PROTOCOL_SCAN'
                            : log.eventType === 'BATTERY_IMPACT'
                            ? isZh
                              ? '功耗与线程'
                              : 'BATTERY_IMPACT'
                            : log.eventType;

                        return (
                          <div
                            key={log.id}
                            className={`flex items-start gap-2.5 py-1 px-1.5 rounded transition-colors ${
                              log.eventType === 'LINK_ANOMALY'
                                ? 'bg-red-950/25 hover:bg-red-950/40'
                                : log.eventType === 'ZERO_GUARD'
                                ? 'bg-orange-950/20 hover:bg-orange-950/35'
                                : log.eventType === 'BATTERY_IMPACT'
                                ? 'bg-teal-950/20 hover:bg-teal-950/35'
                                : 'hover:bg-slate-900/60'
                            }`}
                          >
                            <span className="text-[11px] text-slate-500 shrink-0 select-none">
                              {log.timeMs}
                            </span>

                            <span
                              className={`text-[10px] font-semibold px-1.5 py-0.5 rounded border shrink-0 ${badgeColor}`}
                            >
                              {eventLabel}
                            </span>

                            <span className="text-[11px] text-slate-400 shrink-0 font-mono">
                              [{log.sessionTag}]
                            </span>

                            <span className="text-[11px] text-slate-200 break-words flex-1 leading-relaxed">
                              {isZh ? log.detailZh : log.detailEn}
                            </span>
                          </div>
                        );
                      })
                    )}
                  </div>

                  <div className="px-1 text-[11px] text-slate-500 flex items-center justify-between">
                    <span>
                      {isZh
                        ? '提示：点击上方模拟器的「发送提问」或切换账号，此处将实时展示对应的出站 Hook 拦截流'
                        : 'Tip: Click "Send Query" or switch Google accounts above to see real-time outbound hook interceptions'}
                    </span>
                    <span className="font-mono">QuotaCoreEngine v2.1.0</span>
                  </div>
                </div>
              )}
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

                <div className="p-5">
                  <div className="text-sm font-semibold text-slate-900 flex items-center justify-between">
                    <span>
                      {isZh
                        ? '04. 持久化应用签名保护（保留原始应用签名 · 解决安装覆盖不兼容）'
                        : '04. Persistent App Signature (Keep App Signature & Seamless Overwrite Updates)'}
                    </span>
                    <span className="text-[11px] font-mono text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">
                      v1 + v2 Signing
                    </span>
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 leading-relaxed">
                    {isZh
                      ? '模块仓库内置并固化持久化 release 签名库，并在 GitHub Actions 中支持自动继承仓库密钥。杜绝 CI 每次运行重新生成随机密钥导致签名不一致（解决 INSTALL_FAILED_UPDATE_INCOMPATIBLE 签名冲突错误）。如需使用您手机上原有安装包的专属签名，只需在 GitHub 仓库 Settings -> Secrets 中添加 RELEASE_KEYSTORE_BASE64，CI 即可无缝沿用您原本的签名证书。'
                      : 'Includes persistent repository signing keystore and supports GitHub Repository Secret RELEASE_KEYSTORE_BASE64 to guarantee that every CI and local build produces APKs with the exact same cryptographic signature, eliminating signature mismatch (INSTALL_FAILED_UPDATE_INCOMPATIBLE) errors on APK updates.'}
                  </p>
                  <div className="mt-3 bg-slate-50 border border-slate-200 rounded-lg p-3 font-mono text-xs text-slate-700 space-y-1">
                    <div><span className="text-slate-400"># GitHub Secrets (Optional Custom Keystore):</span></div>
                    <div><span className="text-indigo-600">RELEASE_KEYSTORE_BASE64</span>: &lt;base64-encoded-keystore&gt;</div>
                    <div><span className="text-indigo-600">RELEASE_KEYSTORE_PASSWORD</span>: android <span className="text-slate-400">(default)</span></div>
                    <div><span className="text-indigo-600">RELEASE_KEY_ALIAS</span>: release <span className="text-slate-400">(default)</span></div>
                  </div>
                </div>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
