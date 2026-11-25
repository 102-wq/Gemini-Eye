import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  BatteryCharging,
  Check,
  ChevronDown,
  ChevronRight,
  Clock,
  Copy,
  Cpu,
  Download,
  ExternalLink,
  FileCode,
  FileText,
  FolderGit2,
  Moon,
  RefreshCw,
  RotateCcw,
  Search,
  Send,
  ShieldCheck,
  Smartphone,
  Terminal,
  Trash2,
  TrendingUp,
  UserCheck,
  Volume2,
  VolumeX,
} from 'lucide-react';
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { PROJECT_FILES } from './moduleFiles';
import { buildZipBlob } from './zipBuilder';

type UiLang = 'zh' | 'en';
type HudTheme = 'MIUIX' | 'MD3';
type HudDockEdge = 'NONE' | 'LEFT' | 'RIGHT';
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

interface EnergyTimelineEntry {
  index: number;
  timestampIso: string;
  timeHms: string;
  sessionTag: string;
  event: string;
  maWindowSize: number;
  filteredScore: number;
  rawScore: number;
  scoreDelta: number;
  spikeBypass: boolean;
  aboveHighThreshold: boolean;
  cpuMs: number;
  wallMs: number;
  fgDispatches: number;
  netReqs: number;
  estimatedMah: number;
}

interface TrendSpikePoint {
  time: string;
  score: number;
  event: string;
}

function buildTrendSpikePoints(
  samples: Array<{ ts: number; score: number; event: string }>
): TrendSpikePoint[] {
  const pad = (n: number) => String(n).padStart(2, '0');
  return samples.map((sample) => {
    const d = new Date(sample.ts);
    return {
      time: `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`,
      score: Math.round(sample.score * 100) / 100,
      event: sample.event,
    };
  });
}

function TrendScoreSparkline({
  points,
  compact = false,
  active = true,
  isZh = false,
}: {
  points: TrendSpikePoint[];
  compact?: boolean;
  active?: boolean;
  isZh?: boolean;
}) {
  const rawId = React.useId();
  const gradId = `trend-spark-${rawId.replace(/[^a-zA-Z0-9_-]/g, '')}`;

  if (!points || points.length === 0) return null;

  const width = compact ? 84 : 136;
  const height = compact ? 24 : 36;
  const padX = compact ? 4 : 6;
  const padY = compact ? 4 : 5;
  const plotW = width - padX * 2;
  const plotH = height - padY * 2;

  const scores = points.map((p) => p.score);
  const minScore = Math.min(...scores);
  const maxScore = Math.max(...scores);
  const span = maxScore - minScore;

  const coords = points.map((p, idx) => {
    const x =
      points.length === 1
        ? width / 2
        : padX + (idx / (points.length - 1)) * plotW;
    const norm = span > 0 ? (p.score - minScore) / span : 0.5;
    const y = padY + plotH - norm * plotH;
    return {
      x: Math.round(x * 10) / 10,
      y: Math.round(y * 10) / 10,
      point: p,
    };
  });

  const linePoints = coords.map((c) => `${c.x},${c.y}`).join(' ');
  const firstCoord = coords[0];
  const lastCoord = coords[coords.length - 1];
  const areaPoints =
    coords.length >= 2
      ? `${firstCoord.x},${height - padY} ${linePoints} ${lastCoord.x},${height - padY}`
      : '';

  const strokeColor = active ? '#fb7185' : '#f59e0b';
  const fillStopTop = active ? '#f43f5e' : '#f59e0b';
  const peakColor = active ? '#fda4af' : '#fcd34d';
  const tooltipText = points
    .map((p) => `${p.score.toFixed(2)} (${p.time} · ${p.event})`)
    .join(' -> ');

  return (
    <div
      title={
        isZh
          ? `功耗尖峰轨迹: ${tooltipText}`
          : `Energy Spike Trajectory: ${tooltipText}`
      }
      className={`inline-flex items-center gap-1.5 rounded border px-1.5 py-0.5 shrink-0 ${
        active
          ? 'border-rose-500/35 bg-rose-950/60'
          : 'border-slate-700/80 bg-slate-900/90'
      }`}
    >
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className={compact ? 'w-[84px] h-[24px] overflow-visible' : 'w-[136px] h-[36px] overflow-visible'}
        role="img"
        aria-label={
          isZh
            ? `功耗尖峰折线图：${minScore.toFixed(2)} 至 ${maxScore.toFixed(2)}`
            : `Energy score spike sparkline: ${minScore.toFixed(2)} to ${maxScore.toFixed(2)}`
        }
      >
        <title>{tooltipText}</title>
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={fillStopTop} stopOpacity="0.42" />
            <stop offset="100%" stopColor={fillStopTop} stopOpacity="0.02" />
          </linearGradient>
        </defs>
        <line
          x1={padX}
          y1={firstCoord.y}
          x2={width - padX}
          y2={firstCoord.y}
          stroke={active ? 'rgba(251, 113, 133, 0.25)' : 'rgba(148, 163, 184, 0.25)'}
          strokeWidth="1"
          strokeDasharray="2 2"
        />
        {areaPoints && <polygon points={areaPoints} fill={`url(#${gradId})`} />}
        {coords.length >= 2 && (
          <polyline
            fill="none"
            stroke={strokeColor}
            strokeWidth={compact ? '1.6' : '2'}
            strokeLinecap="round"
            strokeLinejoin="round"
            points={linePoints}
          />
        )}
        {coords.map((c, idx) => {
          const isLast = idx === coords.length - 1;
          const isFirst = idx === 0;
          return (
            <circle
              key={`${c.point.time}-${idx}`}
              cx={c.x}
              cy={c.y}
              r={isLast ? (compact ? 2.6 : 3.2) : isFirst ? 1.8 : 1.5}
              fill={isLast ? peakColor : strokeColor}
              stroke={isLast ? '#0f172a' : 'none'}
              strokeWidth={isLast ? '1' : '0'}
            />
          );
        })}
      </svg>
      {!compact && (
        <div className="flex flex-col leading-tight font-mono text-[9.5px] tabular-nums pr-0.5">
          <span className="text-rose-200 font-bold">{maxScore.toFixed(2)}</span>
          <span className="text-slate-400">{minScore.toFixed(2)}</span>
        </div>
      )}
    </div>
  );
}

interface TrendWindowChartPoint {
  time: string;
  ts: number;
  score: number;
  streak: number;
  deltaFromPrev: number;
  event: string;
  alertTriggered: boolean;
}

function buildTrendWindowChartSeries(
  samples: Array<{ ts: number; score: number; event: string }>,
  alertStreakThreshold = 3
): TrendWindowChartPoint[] {
  const pad = (n: number) => String(n).padStart(2, '0');
  let runningStreak = 0;
  return samples.map((sample, idx) => {
    const prev = idx > 0 ? samples[idx - 1] : null;
    const deltaFromPrev = prev ? Math.round((sample.score - prev.score) * 100) / 100 : 0;
    if (prev && sample.score > prev.score) {
      runningStreak += 1;
    } else {
      runningStreak = 0;
    }
    const d = new Date(sample.ts);
    const time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    return {
      time,
      ts: sample.ts,
      score: sample.score,
      streak: runningStreak,
      deltaFromPrev,
      event: sample.event,
      alertTriggered: runningStreak >= alertStreakThreshold,
    };
  });
}

function csvEscape(value: string | number | boolean): string {
  const s = String(value);
  if (s.includes(',') || s.includes('"') || s.includes('\n') || s.includes('\r')) {
    return `"${s.replace(/"/g, '""')}"`;
  }
  return s;
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

const DEFAULT_HIGH_BATTERY_IMPACT_THRESHOLD = 20;
const HIGH_BATTERY_THRESHOLD_MIN = 5;
const HIGH_BATTERY_THRESHOLD_MAX = 60;
const HIGH_BATTERY_THRESHOLD_STORAGE_KEY = 'gemini_eye_high_battery_threshold';

function clampHighBatteryThreshold(val: number): number {
  if (!Number.isFinite(val)) return DEFAULT_HIGH_BATTERY_IMPACT_THRESHOLD;
  return Math.min(HIGH_BATTERY_THRESHOLD_MAX, Math.max(HIGH_BATTERY_THRESHOLD_MIN, Math.round(val)));
}

function loadPersistedHighBatteryThreshold(): number {
  try {
    if (typeof window === 'undefined' || !window.localStorage) {
      return DEFAULT_HIGH_BATTERY_IMPACT_THRESHOLD;
    }
    const raw = window.localStorage.getItem(HIGH_BATTERY_THRESHOLD_STORAGE_KEY);
    if (raw === null || raw.trim() === '') {
      return DEFAULT_HIGH_BATTERY_IMPACT_THRESHOLD;
    }
    return clampHighBatteryThreshold(Number(raw));
  } catch {
    return DEFAULT_HIGH_BATTERY_IMPACT_THRESHOLD;
  }
}

const DEFAULT_TREND_CONSECUTIVE_THRESHOLD = 3;
const TREND_CONSECUTIVE_MIN = 2;
const TREND_CONSECUTIVE_MAX = 10;
const TREND_CONSECUTIVE_STORAGE_KEY = 'gemini_eye_trend_consecutive_threshold';

function clampTrendConsecutiveThreshold(val: number): number {
  if (!Number.isFinite(val)) return DEFAULT_TREND_CONSECUTIVE_THRESHOLD;
  return Math.min(TREND_CONSECUTIVE_MAX, Math.max(TREND_CONSECUTIVE_MIN, Math.round(val)));
}

function loadPersistedTrendConsecutiveThreshold(): number {
  try {
    if (typeof window === 'undefined' || !window.localStorage) {
      return DEFAULT_TREND_CONSECUTIVE_THRESHOLD;
    }
    const raw = window.localStorage.getItem(TREND_CONSECUTIVE_STORAGE_KEY);
    if (raw === null || raw.trim() === '') {
      return DEFAULT_TREND_CONSECUTIVE_THRESHOLD;
    }
    return clampTrendConsecutiveThreshold(Number(raw));
  } catch {
    return DEFAULT_TREND_CONSECUTIVE_THRESHOLD;
  }
}

const DEFAULT_TREND_AUTO_DISMISS_SEC = 15;
const TREND_AUTO_DISMISS_MIN_SEC = 5;
const TREND_AUTO_DISMISS_MAX_SEC = 60;
const TREND_AUTO_DISMISS_SEC_STORAGE_KEY = 'gemini_eye_trend_auto_dismiss_sec';
const TREND_AUTO_DISMISS_ENABLED_STORAGE_KEY = 'gemini_eye_trend_auto_dismiss_enabled';

function clampTrendAutoDismissSec(val: number): number {
  if (!Number.isFinite(val)) return DEFAULT_TREND_AUTO_DISMISS_SEC;
  return Math.min(TREND_AUTO_DISMISS_MAX_SEC, Math.max(TREND_AUTO_DISMISS_MIN_SEC, Math.round(val)));
}

function loadPersistedTrendAutoDismissSec(): number {
  try {
    if (typeof window === 'undefined' || !window.localStorage) {
      return DEFAULT_TREND_AUTO_DISMISS_SEC;
    }
    const raw = window.localStorage.getItem(TREND_AUTO_DISMISS_SEC_STORAGE_KEY);
    if (raw === null || raw.trim() === '') {
      return DEFAULT_TREND_AUTO_DISMISS_SEC;
    }
    return clampTrendAutoDismissSec(Number(raw));
  } catch {
    return DEFAULT_TREND_AUTO_DISMISS_SEC;
  }
}

function loadPersistedTrendAutoDismissEnabled(): boolean {
  try {
    if (typeof window === 'undefined' || !window.localStorage) {
      return true;
    }
    const raw = window.localStorage.getItem(TREND_AUTO_DISMISS_ENABLED_STORAGE_KEY);
    if (raw === null || raw.trim() === '') {
      return true;
    }
    return raw !== 'false';
  } catch {
    return true;
  }
}

const TREND_SOUND_ENABLED_STORAGE_KEY = 'gemini_eye_trend_sound_enabled';

function loadPersistedTrendSoundEnabled(): boolean {
  try {
    if (typeof window === 'undefined' || !window.localStorage) {
      return true;
    }
    const raw = window.localStorage.getItem(TREND_SOUND_ENABLED_STORAGE_KEY);
    if (raw === null || raw.trim() === '') {
      return true;
    }
    return raw !== 'false';
  } catch {
    return true;
  }
}

export default function App() {
  const [uiLang, setUiLang] = useState<UiLang>('zh');
  const [hudLang, setHudLang] = useState<UiLang>('zh');
  const [hudTheme, setHudTheme] = useState<HudTheme>('MIUIX');
  const [hudOpacity, setHudOpacity] = useState<number>(85);
  const [highBatteryImpactThreshold, setHighBatteryImpactThreshold] = useState<number>(() =>
    loadPersistedHighBatteryThreshold()
  );
  const [trendConsecutiveThreshold, setTrendConsecutiveThreshold] = useState<number>(() =>
    loadPersistedTrendConsecutiveThreshold()
  );
  const [trendAutoDismissEnabled, setTrendAutoDismissEnabled] = useState<boolean>(() =>
    loadPersistedTrendAutoDismissEnabled()
  );
  const [trendAutoDismissSec, setTrendAutoDismissSec] = useState<number>(() =>
    loadPersistedTrendAutoDismissSec()
  );
  const [trendSoundEnabled, setTrendSoundEnabled] = useState<boolean>(() =>
    loadPersistedTrendSoundEnabled()
  );
  const [trendAlertRemainingSec, setTrendAlertRemainingSec] = useState<number | null>(null);
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
  const [devConsoleOpen, setDevConsoleOpen] = useState<boolean>(true);
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
  const [showEnergyImpactStats, setShowEnergyImpactStats] = useState<boolean>(true);
  const [energyCpuMs, setEnergyCpuMs] = useState<number>(3.4);
  const [energyWallMs, setEnergyWallMs] = useState<number>(18.2);
  const [energyHookEvents, setEnergyHookEvents] = useState<number>(12);
  const [energyNetReqs, setEnergyNetReqs] = useState<number>(1);
  const [energyNetBytes, setEnergyNetBytes] = useState<number>(1420);
  const [energyBgSuppressed, setEnergyBgSuppressed] = useState<number>(2);
  const [energyScoreHistory, setEnergyScoreHistory] = useState<
    Array<{ time: string; score: number; rawScore: number; spikeBypass: boolean; cpuMs: number; event: string }>
  >([
    { time: '09:41:00', score: 0.08, rawScore: 0.08, spikeBypass: false, cpuMs: 0.6, event: 'INIT' },
    { time: '09:41:01', score: 0.15, rawScore: 0.21, spikeBypass: false, cpuMs: 1.4, event: 'HOOK_BIND' },
    { time: '09:41:02', score: 0.27, rawScore: 0.52, spikeBypass: false, cpuMs: 3.4, event: 'APP_OPEN' },
    { time: '09:41:05', score: 0.33, rawScore: 0.52, spikeBypass: false, cpuMs: 3.4, event: 'IDLE_SETTLE' },
    { time: '09:41:07', score: 0.38, rawScore: 0.52, spikeBypass: false, cpuMs: 3.4, event: 'IDLE_0T' },
  ]);
  const [smoothedEnergyScore, setSmoothedEnergyScore] = useState<number>(0.38);
  const [energyTimelineCount, setEnergyTimelineCount] = useState<number>(5);
  const [exportedBatteryReport, setExportedBatteryReport] = useState<string>('');
  const [batteryReportCopied, setBatteryReportCopied] = useState<boolean>(false);
  const [energySurgeAlert, setEnergySurgeAlert] = useState<{
    delta: number;
    prevScore: number;
    curScore: number;
    timeMs: string;
    sessionTag: string;
    cycleReason: string;
  } | null>(null);
  const [highUsageTrendAlert, setHighUsageTrendAlert] = useState<{
    consecutiveIncreases: number;
    startScore: number;
    curScore: number;
    delta: number;
    windowSec: number;
    timeMs: string;
    sessionTag: string;
    events: string[];
    scores: TrendSpikePoint[];
    triggeredAtMs: number;
  } | null>(null);
  const [highUsageTrendOccurrences, setHighUsageTrendOccurrences] = useState<
    Array<{
      id: string;
      consecutiveIncreases: number;
      thresholdAtTrigger: number;
      startScore: number;
      curScore: number;
      delta: number;
      windowSec: number;
      timeMs: string;
      sessionTag: string;
      events: string[];
      scores: TrendSpikePoint[];
      triggeredAtMs: number;
    }>
  >([]);
  const [trendOccurrencesExpanded, setTrendOccurrencesExpanded] = useState<boolean>(true);
  const [trendOccurrenceTimeRange, setTrendOccurrenceTimeRange] = useState<
    '15M' | '1H' | '6H' | '24H' | 'ALL'
  >('ALL');
  const [trendRangeTick, setTrendRangeTick] = useState<number>(0);
  const [consoleFilter, setConsoleFilter] = useState<'ALL' | 'ZERO_GUARD' | 'LINK_ANOMALY' | 'BATTERY_IMPACT'>('ALL');
  const bgWakeupCountRef = useRef<number>(0);
  const fgDispatchCountRef = useRef<number>(1);
  const activePoolThreadsRef = useRef<number>(0);
  const appOpenSettleTimerRef = useRef<number | null>(null);
  const lastEnergyEventTagRef = useRef<string>('APP_OPEN');
  const energyHistoryMountedRef = useRef<boolean>(false);
  const skipNextEnergyHistoryPushRef = useRef<boolean>(false);
  const heartbeatBaselineScoreRef = useRef<number>(0.52);
  const surgeAlertedInCycleRef = useRef<boolean>(false);
  const latestEnergyScoreRef = useRef<number>(0.52);
  const energyMaWindowRef = useRef<number[]>([0.08, 0.21, 0.52, 0.52]);
  const energyTrendWindowRef = useRef<Array<{ ts: number; score: number; event: string }>>([
    { ts: Date.now() - 6_000, score: 0.52, event: 'APP_OPEN' },
    { ts: Date.now() - 1_000, score: 0.52, event: 'INIT_BASELINE' },
  ]);
  const [highUsageTrendChartHistory, setHighUsageTrendChartHistory] = useState<TrendWindowChartPoint[]>(() =>
    buildTrendWindowChartSeries(energyTrendWindowRef.current, loadPersistedTrendConsecutiveThreshold())
  );
  const trendConsecutiveThresholdRef = useRef<number>(trendConsecutiveThreshold);
  trendConsecutiveThresholdRef.current = trendConsecutiveThreshold;
  const trendSoundEnabledRef = useRef<boolean>(trendSoundEnabled);
  trendSoundEnabledRef.current = trendSoundEnabled;
  const audioContextRef = useRef<AudioContext | null>(null);
  const highUsageTrendAlertedStreakRef = useRef<number>(0);
  const fullEnergyTimelineRef = useRef<EnergyTimelineEntry[]>([
    {
      index: 1,
      timestampIso: new Date(Date.now() - 25_000).toISOString(),
      timeHms: '09:41:00',
      sessionTag: 'sess_8f3a91c04b12',
      event: 'INIT',
      maWindowSize: 4,
      filteredScore: 0.08,
      rawScore: 0.08,
      scoreDelta: 0,
      spikeBypass: false,
      aboveHighThreshold: false,
      cpuMs: 0.6,
      wallMs: 3.1,
      fgDispatches: 0,
      netReqs: 0,
      estimatedMah: 0.0001,
    },
    {
      index: 2,
      timestampIso: new Date(Date.now() - 24_000).toISOString(),
      timeHms: '09:41:01',
      sessionTag: 'sess_8f3a91c04b12',
      event: 'HOOK_BIND',
      maWindowSize: 4,
      filteredScore: 0.15,
      rawScore: 0.21,
      scoreDelta: 0.13,
      spikeBypass: false,
      aboveHighThreshold: false,
      cpuMs: 1.4,
      wallMs: 7.5,
      fgDispatches: 1,
      netReqs: 0,
      estimatedMah: 0.0003,
    },
    {
      index: 3,
      timestampIso: new Date(Date.now() - 23_000).toISOString(),
      timeHms: '09:41:02',
      sessionTag: 'sess_8f3a91c04b12',
      event: 'APP_OPEN',
      maWindowSize: 4,
      filteredScore: 0.27,
      rawScore: 0.52,
      scoreDelta: 0.31,
      spikeBypass: false,
      aboveHighThreshold: false,
      cpuMs: 3.4,
      wallMs: 18.2,
      fgDispatches: 1,
      netReqs: 1,
      estimatedMah: 0.0127,
    },
    {
      index: 4,
      timestampIso: new Date(Date.now() - 20_000).toISOString(),
      timeHms: '09:41:05',
      sessionTag: 'sess_8f3a91c04b12',
      event: 'IDLE_SETTLE',
      maWindowSize: 4,
      filteredScore: 0.33,
      rawScore: 0.52,
      scoreDelta: 0,
      spikeBypass: false,
      aboveHighThreshold: false,
      cpuMs: 3.4,
      wallMs: 18.2,
      fgDispatches: 1,
      netReqs: 1,
      estimatedMah: 0.0127,
    },
    {
      index: 5,
      timestampIso: new Date(Date.now() - 18_000).toISOString(),
      timeHms: '09:41:07',
      sessionTag: 'sess_8f3a91c04b12',
      event: 'IDLE_0T',
      maWindowSize: 4,
      filteredScore: 0.38,
      rawScore: 0.52,
      scoreDelta: 0,
      spikeBypass: false,
      aboveHighThreshold: false,
      cpuMs: 3.4,
      wallMs: 18.2,
      fgDispatches: 1,
      netReqs: 1,
      estimatedMah: 0.0127,
    },
  ]);
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
  const hudRippleRef = useRef<HTMLSpanElement | null>(null);
  const hudRippleTimerRef = useRef<number | null>(null);
  const [hudTapRipple, setHudTapRipple] = useState<{
    id: number;
    x: number;
    y: number;
    size: number;
  } | null>(null);
  const bar5hRef = useRef<HTMLDivElement | null>(null);
  const barWeeklyRef = useRef<HTMLDivElement | null>(null);
  const settingsBar5hRef = useRef<HTMLDivElement | null>(null);
  const settingsBarWeeklyRef = useRef<HTMLDivElement | null>(null);

  const HUD_FRAME_SMOOTH_OFFSET_MS = 8;
  const HUD_TOUCH_JITTER_DEADBAND_PX = 1.1;
  const HUD_TOUCH_SAMPLE_WINDOW_MS = 8;
  const HUD_MAGNET_EDGE_THRESHOLD_PX = 48;

  const [hudDockedEdge, setHudDockedEdge] = useState<HudDockEdge>('NONE');
  const [hudDragEdgePreview, setHudDragEdgePreview] = useState<HudDockEdge>('NONE');
  const hudDockedEdgeRef = useRef<HudDockEdge>('NONE');
  hudDockedEdgeRef.current = hudDockedEdge;
  const prevHudDockEdgeRef = useRef<HudDockEdge>('NONE');
  const hudPosInitializedRef = useRef<boolean>(false);

  const hudPosRef = useRef<{ x: number; y: number }>({ x: 0, y: 0 });
  const hudAnimRafRef = useRef<number | null>(null);
  const dragRef = useRef<{
    startX: number;
    startY: number;
    origX: number;
    origY: number;
    rawX: number;
    pointerLocalX: number;
    intendedDock: HudDockEdge;
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
    rawX: 0,
    pointerLocalX: 0,
    intendedDock: 'NONE',
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
    lastEnergyEventTagRef.current = reasonTag;
    if (phase === 'FG_DISPATCH' || phase === 'HEARTBEAT_REPLAY') {
      const nextFg = fgDispatchCountRef.current + 1;
      fgDispatchCountRef.current = nextFg;
      setFgDispatchCount(nextFg);
      setEnergyCpuMs((v) => Math.round((v + (phase === 'HEARTBEAT_REPLAY' ? 2.1 : 1.6)) * 10) / 10);
      setEnergyWallMs((v) => Math.round((v + (phase === 'HEARTBEAT_REPLAY' ? 14.5 : 9.8)) * 10) / 10);
      setEnergyHookEvents((v) => v + 6);
      setEnergyNetReqs((v) => v + 1);
      setEnergyNetBytes((v) => v + (phase === 'HEARTBEAT_REPLAY' ? 890 : 1240));
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
      setEnergyCpuMs((v) => Math.round((v + 0.7) * 10) / 10);
      setEnergyWallMs((v) => Math.round((v + 4.2) * 10) / 10);
      setEnergyHookEvents((v) => v + 3);
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
      setEnergyBgSuppressed((v) => v + 1);
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

  const estimatedEnergyMah = useMemo(() => {
    const val = energyCpuMs * 0.00018 + energyNetReqs * 0.012 + (energyNetBytes / 1_048_576) * 0.05;
    return Math.round(val * 10000) / 10000;
  }, [energyCpuMs, energyNetReqs, energyNetBytes]);

  const energyImpactScore = useMemo(() => {
    return Math.min(
      100,
      Math.round((energyCpuMs / 50 + energyNetReqs * 0.4 + fgDispatchCount * 0.05) * 100) / 100
    );
  }, [energyCpuMs, energyNetReqs, fgDispatchCount]);

  const HEARTBEAT_ENERGY_SURGE_DELTA = 15;
  const ENERGY_TREND_WINDOW_MS = 5 * 60 * 1000;
  const ENERGY_MA_WINDOW_SIZE = 4;
  const ENERGY_SPIKE_SENSITIVITY_DELTA = 5.0;
  latestEnergyScoreRef.current = energyImpactScore;
  const highThresholdMountedRef = useRef<boolean>(false);
  const wasHighBatteryImpactRef = useRef<boolean>(false);
  const suppressNextThresholdCrossLogRef = useRef<boolean>(false);

  const computeSpikeAwareMovingAverage = (rawScore: number) => {
    const prevWindow = energyMaWindowRef.current;
    const prevFiltered =
      prevWindow.length > 0
        ? prevWindow.reduce((sum, val) => sum + val, 0) / prevWindow.length
        : rawScore;
    const jumpDelta = Math.abs(rawScore - prevFiltered);
    const spikeBypass =
      jumpDelta >= ENERGY_SPIKE_SENSITIVITY_DELTA ||
      (rawScore >= highBatteryImpactThreshold && prevFiltered < highBatteryImpactThreshold);

    const nextWindow = spikeBypass
      ? [rawScore]
      : [...prevWindow.slice(-(ENERGY_MA_WINDOW_SIZE - 1)), rawScore];
    energyMaWindowRef.current = nextWindow;

    const avg = nextWindow.reduce((sum, val) => sum + val, 0) / nextWindow.length;
    return {
      filteredScore: Math.round(avg * 100) / 100,
      spikeBypass,
    };
  };

  const energyImpactRating = useMemo(() => {
    const minimalCutoff = Math.min(5, Math.max(1, Math.round(highBatteryImpactThreshold * 0.25)));
    if (energyImpactScore < minimalCutoff) return 'Minimal';
    if (energyImpactScore < highBatteryImpactThreshold) return 'Low';
    return 'High';
  }, [energyImpactScore, highBatteryImpactThreshold]);

  const isHighBatteryImpact = energyImpactScore >= highBatteryImpactThreshold;

  useEffect(() => {
    if (!highThresholdMountedRef.current) {
      highThresholdMountedRef.current = true;
      wasHighBatteryImpactRef.current = isHighBatteryImpact;
      return;
    }
    const prev = wasHighBatteryImpactRef.current;
    wasHighBatteryImpactRef.current = isHighBatteryImpact;
    if (isHighBatteryImpact && !prev) {
      if (suppressNextThresholdCrossLogRef.current) {
        suppressNextThresholdCrossLogRef.current = false;
        return;
      }
      const sess = activeSessionRef.current.sessionTag;
      setBatteryImpactCount((c) => c + 1);
      logDevEvent(
        'BATTERY_IMPACT',
        sess,
        `[高能耗阈值告警 · >=${highBatteryImpactThreshold}] 当前 Energy Impact Score (${energyImpactScore}) 已超越用户设定阈值 (>=${highBatteryImpactThreshold})`,
        `[High Battery Impact Alert · >=${highBatteryImpactThreshold}] Current Energy Impact Score (${energyImpactScore}) crossed user threshold (>=${highBatteryImpactThreshold})`,
        'warn'
      );
    } else if (!isHighBatteryImpact) {
      suppressNextThresholdCrossLogRef.current = false;
    }
  }, [isHighBatteryImpact, highBatteryImpactThreshold, energyImpactScore]);

  useEffect(() => {
    const normalized = clampHighBatteryThreshold(highBatteryImpactThreshold);
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.setItem(HIGH_BATTERY_THRESHOLD_STORAGE_KEY, String(normalized));
      }
    } catch {
      // Ignore storage quota or sandbox restrictions
    }
  }, [highBatteryImpactThreshold]);

  useEffect(() => {
    const normalized = clampTrendConsecutiveThreshold(trendConsecutiveThreshold);
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.setItem(TREND_CONSECUTIVE_STORAGE_KEY, String(normalized));
      }
    } catch {
      // Ignore storage quota or sandbox restrictions
    }
    const nowTs = Date.now();
    const pruned = energyTrendWindowRef.current.filter(
      (sample) => nowTs - sample.ts <= ENERGY_TREND_WINDOW_MS
    );
    energyTrendWindowRef.current = pruned;
    setHighUsageTrendChartHistory(buildTrendWindowChartSeries(pruned, normalized));

    let streak = 0;
    let startIdx = Math.max(0, pruned.length - 1);
    for (let i = pruned.length - 1; i > 0; i--) {
      if (pruned[i].score > pruned[i - 1].score) {
        streak += 1;
        startIdx = i - 1;
      } else {
        break;
      }
    }
    if (streak < normalized) {
      highUsageTrendAlertedStreakRef.current = 0;
      setHighUsageTrendAlert((prev) => (prev && prev.consecutiveIncreases < normalized ? null : prev));
    } else if (pruned.length > 0) {
      const startSample = pruned[startIdx];
      const latestSample = pruned[pruned.length - 1];
      const delta = Math.round((latestSample.score - startSample.score) * 100) / 100;
      const windowSec = Math.max(1, Math.round((nowTs - startSample.ts) / 1000));
      const timeMs = formatFullTimestampMs(new Date(nowTs));
      const sess = activeSessionRef.current.sessionTag;
      const trailEvents = pruned.slice(startIdx).map((s) => s.event);
      const spikeSlice = pruned.slice(Math.max(0, startIdx - 1));
      const spikeScores = buildTrendSpikePoints(spikeSlice);
      setHighUsageTrendAlert((prev) => ({
        consecutiveIncreases: streak,
        startScore: startSample.score,
        curScore: latestSample.score,
        delta,
        windowSec,
        timeMs,
        sessionTag: sess,
        events: trailEvents,
        scores: spikeScores,
        triggeredAtMs: prev ? prev.triggeredAtMs : nowTs,
      }));
      if (streak > highUsageTrendAlertedStreakRef.current) {
        highUsageTrendAlertedStreakRef.current = streak;
        setHighUsageTrendOccurrences((prev) => [
          {
            id: `trend-${nowTs}-${Math.random().toString(36).slice(2, 7)}`,
            consecutiveIncreases: streak,
            thresholdAtTrigger: normalized,
            startScore: startSample.score,
            curScore: latestSample.score,
            delta,
            windowSec,
            timeMs,
            sessionTag: sess,
            events: trailEvents,
            scores: spikeScores,
            triggeredAtMs: nowTs,
          },
          ...prev,
        ].slice(0, 20));
      }
    }
  }, [trendConsecutiveThreshold]);

  useEffect(() => {
    const normalizedSec = clampTrendAutoDismissSec(trendAutoDismissSec);
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.setItem(
          TREND_AUTO_DISMISS_ENABLED_STORAGE_KEY,
          String(trendAutoDismissEnabled)
        );
        window.localStorage.setItem(TREND_AUTO_DISMISS_SEC_STORAGE_KEY, String(normalizedSec));
      }
    } catch {
      // Ignore storage quota or sandbox restrictions
    }
  }, [trendAutoDismissEnabled, trendAutoDismissSec]);

  useEffect(() => {
    if (!highUsageTrendAlert || !trendAutoDismissEnabled) {
      setTrendAlertRemainingSec(null);
      return;
    }
    const durationMs = clampTrendAutoDismissSec(trendAutoDismissSec) * 1000;
    const deadlineMs = highUsageTrendAlert.triggeredAtMs + durationMs;

    const tick = () => {
      const remainingMs = deadlineMs - Date.now();
      if (remainingMs <= 0) {
        setTrendAlertRemainingSec(null);
        setHighUsageTrendAlert(null);
        return false;
      }
      setTrendAlertRemainingSec(Math.ceil(remainingMs / 1000));
      return true;
    };

    if (!tick()) return;
    const intervalId = window.setInterval(() => {
      if (!tick()) {
        window.clearInterval(intervalId);
      }
    }, 250);
    return () => window.clearInterval(intervalId);
  }, [highUsageTrendAlert, trendAutoDismissEnabled, trendAutoDismissSec]);

  useEffect(() => {
    if (trendOccurrenceTimeRange === 'ALL' || highUsageTrendOccurrences.length === 0) return;
    const id = window.setInterval(() => {
      setTrendRangeTick((t) => t + 1);
    }, 15_000);
    return () => window.clearInterval(id);
  }, [trendOccurrenceTimeRange, highUsageTrendOccurrences.length]);

  const filteredTrendOccurrences = useMemo(() => {
    if (trendOccurrenceTimeRange === 'ALL') return highUsageTrendOccurrences;
    const windowMs =
      trendOccurrenceTimeRange === '15M'
        ? 15 * 60 * 1000
        : trendOccurrenceTimeRange === '1H'
          ? 60 * 60 * 1000
          : trendOccurrenceTimeRange === '6H'
            ? 6 * 60 * 60 * 1000
            : 24 * 60 * 60 * 1000;
    const cutoff = Date.now() - windowMs;
    return highUsageTrendOccurrences.filter((occ) => occ.triggeredAtMs >= cutoff);
  }, [highUsageTrendOccurrences, trendOccurrenceTimeRange, trendRangeTick]);

  useEffect(() => {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.setItem(TREND_SOUND_ENABLED_STORAGE_KEY, String(trendSoundEnabled));
      }
    } catch {
      // Ignore storage quota or sandbox restrictions
    }
  }, [trendSoundEnabled]);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const handleStorageSync = (e: StorageEvent) => {
      if (e.key === HIGH_BATTERY_THRESHOLD_STORAGE_KEY && e.newValue !== null) {
        setHighBatteryImpactThreshold(clampHighBatteryThreshold(Number(e.newValue)));
      } else if (e.key === TREND_CONSECUTIVE_STORAGE_KEY && e.newValue !== null) {
        setTrendConsecutiveThreshold(clampTrendConsecutiveThreshold(Number(e.newValue)));
      } else if (e.key === TREND_AUTO_DISMISS_SEC_STORAGE_KEY && e.newValue !== null) {
        setTrendAutoDismissSec(clampTrendAutoDismissSec(Number(e.newValue)));
      } else if (e.key === TREND_AUTO_DISMISS_ENABLED_STORAGE_KEY && e.newValue !== null) {
        setTrendAutoDismissEnabled(e.newValue !== 'false');
      } else if (e.key === TREND_SOUND_ENABLED_STORAGE_KEY && e.newValue !== null) {
        setTrendSoundEnabled(e.newValue !== 'false');
      }
    };
    window.addEventListener('storage', handleStorageSync);
    return () => window.removeEventListener('storage', handleStorageSync);
  }, []);

  const playHighUsageTrendChime = (forcePlay = false) => {
    if (!forcePlay && !trendSoundEnabledRef.current) return;
    if (typeof window === 'undefined') return;
    try {
      const AudioCtxCtor =
        window.AudioContext ||
        (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!AudioCtxCtor) return;
      if (!audioContextRef.current || audioContextRef.current.state === 'closed') {
        audioContextRef.current = new AudioCtxCtor();
      }
      const ctx = audioContextRef.current;
      if (ctx.state === 'suspended') {
        void ctx.resume().catch(() => {});
      }

      const now = ctx.currentTime;
      const playSoftNote = (freq: number, startOffsetSec: number, durationSec: number, peakGain: number, wave: OscillatorType) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = wave;
        osc.frequency.setValueAtTime(freq, now + startOffsetSec);

        gain.gain.setValueAtTime(0.0001, now + startOffsetSec);
        gain.gain.linearRampToValueAtTime(peakGain, now + startOffsetSec + 0.018);
        gain.gain.exponentialRampToValueAtTime(0.0001, now + startOffsetSec + durationSec);

        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(now + startOffsetSec);
        osc.stop(now + startOffsetSec + durationSec + 0.02);
      };

      // Subtle two-tone awareness chime (D5 -> G5), low gain to stay non-intrusive
      playSoftNote(587.33, 0, 0.14, 0.055, 'sine');
      playSoftNote(783.99, 0.09, 0.22, 0.048, 'triangle');
    } catch {
      // Ignore Web Audio API restrictions in sandboxed/headless environments
    }
  };

  const handleResetTrendAlertHistory = () => {
    const nowTs = Date.now();
    const baselineScore = Math.round(latestEnergyScoreRef.current * 100) / 100;
    const minStreak = clampTrendConsecutiveThreshold(trendConsecutiveThresholdRef.current);
    const resetBaseline = [{ ts: nowTs, score: baselineScore, event: 'STREAK_RESET' }];

    energyTrendWindowRef.current = resetBaseline;
    highUsageTrendAlertedStreakRef.current = 0;
    setHighUsageTrendChartHistory(buildTrendWindowChartSeries(resetBaseline, minStreak));
    setTrendAlertRemainingSec(null);
    setHighUsageTrendAlert(null);
    setHighUsageTrendOccurrences([]);

    const sess = activeSessionRef.current.sessionTag;
    const msgEn = `[Trend Alert History Reset] Cleared 5-min rolling streak counter at baseline ${baselineScore} without changing global thresholds (>=${minStreak}x / High >=${highBatteryImpactThreshold}).`;
    const msgZh = `[趋势告警历史已重置] 已清空 5 分钟滑动窗口连升计数（当前基准 ${baselineScore}），全局阈值 (>=${minStreak}x / 高能耗 >=${highBatteryImpactThreshold}) 保持不变。`;
    logDevEvent('BATTERY_IMPACT', sess, msgZh, msgEn, 'info');
    setLastSyncNote(isZhRef.current ? msgZh : msgEn);
  };

  const evaluateBatteryUsageTrendWindow = (curScore: number, eventName: string) => {
    const minStreak = trendConsecutiveThresholdRef.current;
    const nowTs = Date.now();
    const pruned = energyTrendWindowRef.current.filter(
      (sample) => nowTs - sample.ts <= ENERGY_TREND_WINDOW_MS
    );
    const lastSample = pruned[pruned.length - 1];

    if (lastSample && curScore === lastSample.score) {
      energyTrendWindowRef.current = pruned;
      setHighUsageTrendChartHistory(
        buildTrendWindowChartSeries(pruned, minStreak)
      );
      return;
    }

    if (lastSample && curScore < lastSample.score) {
      highUsageTrendAlertedStreakRef.current = 0;
    }

    const updated = [...pruned, { ts: nowTs, score: curScore, event: eventName }];
    energyTrendWindowRef.current = updated;
    setHighUsageTrendChartHistory(
      buildTrendWindowChartSeries(updated, minStreak)
    );

    let consecutiveIncreases = 0;
    let streakStartIdx = updated.length - 1;
    for (let i = updated.length - 1; i > 0; i--) {
      if (updated[i].score > updated[i - 1].score) {
        consecutiveIncreases += 1;
        streakStartIdx = i - 1;
      } else {
        break;
      }
    }

    if (consecutiveIncreases < minStreak) {
      return;
    }

    const startSample = updated[streakStartIdx];
    const delta = Math.round((curScore - startSample.score) * 100) / 100;
    const windowSec = Math.max(1, Math.round((nowTs - startSample.ts) / 1000));
    const timeMs = formatFullTimestampMs(new Date(nowTs));
    const sess = activeSessionRef.current.sessionTag;
    const trailEvents = updated.slice(streakStartIdx).map((s) => s.event);
    const spikeSlice = updated.slice(Math.max(0, streakStartIdx - 1));
    const spikeScores = buildTrendSpikePoints(spikeSlice);

    setHighUsageTrendAlert({
      consecutiveIncreases,
      startScore: startSample.score,
      curScore,
      delta,
      windowSec,
      timeMs,
      sessionTag: sess,
      events: trailEvents,
      scores: spikeScores,
      triggeredAtMs: nowTs,
    });

    if (consecutiveIncreases > highUsageTrendAlertedStreakRef.current) {
      highUsageTrendAlertedStreakRef.current = consecutiveIncreases;
      setHighUsageTrendOccurrences((prev) => [
        {
          id: `trend-${nowTs}-${Math.random().toString(36).slice(2, 7)}`,
          consecutiveIncreases,
          thresholdAtTrigger: minStreak,
          startScore: startSample.score,
          curScore,
          delta,
          windowSec,
          timeMs,
          sessionTag: sess,
          events: trailEvents,
          scores: spikeScores,
          triggeredAtMs: nowTs,
        },
        ...prev,
      ].slice(0, 20));
      setBatteryImpactCount((c) => c + 1);
      setDevConsoleOpen(true);
      playHighUsageTrendChime();

      const trendMsgEn = `[High Usage Trend] Detected ${consecutiveIncreases} consecutive Battery Impact Score increases within 5-min rolling window (${windowSec}s span): ${startSample.score} -> ${curScore} (+${delta.toFixed(2)} pts, session=${sess})`;
      const trendMsgZh = `[High Usage Trend · 持续高功耗趋势] 5 分钟滑动窗口内检测到连续 ${consecutiveIncreases} 次 Battery Impact Score 上升（耗时 ${windowSec}s）：${startSample.score} -> ${curScore} (+${delta.toFixed(2)} 分，会话=${sess})`;

      console.warn(trendMsgEn);
      logDevEvent('BATTERY_IMPACT', sess, trendMsgZh, trendMsgEn, 'warn');
      setLastSyncNote(isZhRef.current ? trendMsgZh : trendMsgEn);
    }
  };

  const pushHeartbeatEnergySurgeWarn = (curScore: number, cycleReason: string) => {
    const prevScore = Math.round(heartbeatBaselineScoreRef.current * 100) / 100;
    const delta = Math.round((curScore - prevScore) * 100) / 100;
    if (delta <= HEARTBEAT_ENERGY_SURGE_DELTA || surgeAlertedInCycleRef.current) return false;

    surgeAlertedInCycleRef.current = true;
    const nowMs = formatFullTimestampMs(new Date());
    const sess = activeSessionRef.current.sessionTag;

    setEnergySurgeAlert({
      delta,
      prevScore,
      curScore,
      timeMs: nowMs,
      sessionTag: sess,
      cycleReason,
    });
    setBatteryImpactCount((c) => c + 1);
    setDevConsoleOpen(true);

    const warnMsgEn = `[Energy Impact Surge WARN · +${delta.toFixed(2)} pts in 1 Heartbeat Cycle] Score jumped from ${prevScore} to ${curScore} (> +${HEARTBEAT_ENERGY_SURGE_DELTA} threshold, reason=${cycleReason}, session=${sess})`;
    const warnMsgZh = `[能耗突增告警 WARN · 单心跳周期飙升 +${delta.toFixed(2)} 分] Energy Impact Score 由 ${prevScore} 升至 ${curScore}（超过单周期 +${HEARTBEAT_ENERGY_SURGE_DELTA} 阈值，来源=${cycleReason}，会话=${sess}）`;

    console.warn(warnMsgEn);
    logDevEvent('BATTERY_IMPACT', sess, warnMsgZh, warnMsgEn, 'warn');
    setLastSyncNote(isZhRef.current ? warnMsgZh : warnMsgEn);
    return true;
  };

  useEffect(() => {
    if (!energyHistoryMountedRef.current) {
      energyHistoryMountedRef.current = true;
      return;
    }
    if (skipNextEnergyHistoryPushRef.current) {
      skipNextEnergyHistoryPushRef.current = false;
      return;
    }
    const d = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    const timeStr = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    const eventName = lastEnergyEventTagRef.current || 'SAMPLE';
    const { filteredScore, spikeBypass } = computeSpikeAwareMovingAverage(energyImpactScore);
    setSmoothedEnergyScore(filteredScore);
    const prevTimeline = fullEnergyTimelineRef.current;
    const prevRaw = prevTimeline.length > 0 ? prevTimeline[prevTimeline.length - 1].rawScore : energyImpactScore;
    const scoreDelta = Math.round((energyImpactScore - prevRaw) * 100) / 100;
    const nextEntry: EnergyTimelineEntry = {
      index: prevTimeline.length + 1,
      timestampIso: d.toISOString(),
      timeHms: timeStr,
      sessionTag: activeSessionRef.current.sessionTag,
      event: eventName,
      maWindowSize: ENERGY_MA_WINDOW_SIZE,
      filteredScore,
      rawScore: energyImpactScore,
      scoreDelta,
      spikeBypass,
      aboveHighThreshold: energyImpactScore >= highBatteryImpactThreshold || filteredScore >= highBatteryImpactThreshold,
      cpuMs: energyCpuMs,
      wallMs: energyWallMs,
      fgDispatches: fgDispatchCount,
      netReqs: energyNetReqs,
      estimatedMah: estimatedEnergyMah,
    };
    fullEnergyTimelineRef.current = [...prevTimeline, nextEntry];
    setEnergyTimelineCount(fullEnergyTimelineRef.current.length);
    setEnergyScoreHistory((prev) => [
      ...prev.slice(-23),
      {
        time: timeStr,
        score: filteredScore,
        rawScore: energyImpactScore,
        spikeBypass,
        cpuMs: energyCpuMs,
        event: eventName,
      },
    ]);
    pushHeartbeatEnergySurgeWarn(energyImpactScore, eventName);
    evaluateBatteryUsageTrendWindow(energyImpactScore, eventName);
  }, [energyImpactScore, energyCpuMs]);

  const handleSimulateEnergyJitterTest = () => {
    const sess = activeSessionRef.current.sessionTag;
    const deltas = [3.6, -3.2, 3.8, -3.4];
    deltas.forEach((reqDelta, idx) => {
      window.setTimeout(() => {
        lastEnergyEventTagRef.current = `JITTER_PROBE_${idx + 1}`;
        setEnergyCpuMs((v) => Math.max(0.5, Math.round((v + reqDelta * 12) * 10) / 10));
        setEnergyNetReqs((v) => Math.max(1, v + (reqDelta > 0 ? 4 : -4)));
        setBatteryImpactCount((c) => c + 1);
      }, idx * 140);
    });
    logDevEvent(
      'BATTERY_IMPACT',
      sess,
      `[MA(${ENERGY_MA_WINDOW_SIZE}) 抖动滤波测试] 注入 ±3.5 分瞬时抖动样本，验证均值滤波器抑制图表毛刺的同时保持 >=${ENERGY_SPIKE_SENSITIVITY_DELTA} 分真实尖峰秒级穿透`,
      `[MA(${ENERGY_MA_WINDOW_SIZE}) Jitter Filter Test] Injected +/-3.5pt transient jitter probes to verify chart smoothing while keeping >=${ENERGY_SPIKE_SENSITIVITY_DELTA}pt legitimate spikes unattenuated`,
      'info'
    );
  };

  const handleSimulateHighUsageTrend = () => {
    const sess = activeSessionRef.current.sessionTag;
    const stepCount = clampTrendConsecutiveThreshold(trendConsecutiveThresholdRef.current);
    const stepTags = Array.from({ length: stepCount }, (_, idx) => `TREND_STEP_${idx + 1}`);
    stepTags.forEach((stepTag, idx) => {
      window.setTimeout(() => {
        lastEnergyEventTagRef.current = stepTag;
        setFgDispatchCount((c) => c + 1);
        setEnergyCpuMs((v) => Math.round((v + 4.8 * (idx + 1)) * 10) / 10);
        setEnergyWallMs((v) => Math.round((v + 18.0 * (idx + 1)) * 10) / 10);
        setEnergyNetReqs((v) => v + 2);
        setEnergyNetBytes((v) => v + 2800);
        setEnergyHookEvents((v) => v + 8);
        setBatteryImpactCount((c) => c + 1);
        logDevEvent(
          'BATTERY_IMPACT',
          sess,
          `[5m 趋势采样 #${idx + 1}/${stepCount} · ${stepTag}] 前台高频交互触发能耗递进样本采集`,
          `[5m Trend Sample #${idx + 1}/${stepCount} · ${stepTag}] Foreground burst triggered progressive energy score sample`,
          'info'
        );
      }, idx * 180);
    });
  };

  const handleSimulateHighBatteryImpact = () => {
    lastEnergyEventTagRef.current = 'HIGH_LOAD_SPIKE';
    suppressNextThresholdCrossLogRef.current = true;
    const targetScore = Math.max(highBatteryImpactThreshold + 3, 24);
    const extraCpu = Math.max(380, Math.round((targetScore - energyImpactScore + 2) * 50));
    setEnergyCpuMs((v) => Math.round((Math.max(v + extraCpu, 420.0)) * 10) / 10);
    setEnergyWallMs((v) => Math.max(v + 620, 840.0));
    setEnergyNetReqs((v) => Math.max(v + 28, 32));
    setEnergyNetBytes((v) => v + 64_500);
    setEnergyHookEvents((v) => v + 45);
    setFgDispatchCount((c) => c + 12);
    setBatteryImpactCount((c) => c + 1);
    logDevEvent(
      'BATTERY_IMPACT',
      activeSession.sessionTag,
      `[高能耗阈值触发 · >=${highBatteryImpactThreshold}] 突发全量协议重放与 ProtoBuf 流解码导致 Energy Score 越过高阈值；${
        hudTheme === 'MIUIX'
          ? 'MIUIX 主题已自动激活柔和背景模糊呼吸动画 (backdrop-blur breathe)'
          : '当前为 MD3 主题（切换至 MIUIX 可预览模糊呼吸动画）'
      }`,
      `[High Energy Threshold Crossed · >=${highBatteryImpactThreshold}] Burst full-protocol replay & ProtoBuf decoding pushed Energy Score above threshold; ${
        hudTheme === 'MIUIX'
          ? 'MIUIX HUD subtle backdrop-blur animation activated'
          : 'switch HUD theme to MIUIX to preview backdrop-blur animation'
      }`,
      'warn'
    );
  };

  const handleExportBatteryReport = () => {
    const batteryLogs = devLogs.filter((l) => l.eventType === 'BATTERY_IMPACT');
    const timeline = fullEnergyTimelineRef.current;
    const spikeBypassCount = timeline.filter((e) => e.spikeBypass).length;
    const nowStr = formatFullTimestampMs(new Date());
    const headerLines = isZh
      ? [
          '=== Gemini-Eye 电池消耗影响报告 (Battery Impact Report · 模拟) ===',
          `生成时间: ${nowStr}`,
          `当前会话: ${activeSession.sessionTag} | 前台状态: ${isGeminiForeground}`,
          `Energy Impact 评级: ${energyImpactRating} (Filtered MA(${ENERGY_MA_WINDOW_SIZE})=${smoothedEnergyScore} | Raw=${energyImpactScore} | 高能耗阈值 >=${highBatteryImpactThreshold})`,
          `时间线采样全集: ${timeline.length} 条记录 (Spike Bypass 尖峰直通事件: ${spikeBypassCount} 次 · 可导出 Historical Impact CSV)`,
          `后台唤醒: ${bgWakeupCount} 次 (1ns 门控拦截: ${energyBgSuppressed} 次) | 当前线程池: ${poolStateLabel} (${activePoolThreads} 线程)`,
          `前台按需派发: ${fgDispatchCount} 次 | Hook 捕获事件: ${energyHookEvents} 次`,
          `累计 CPU / 墙钟耗时: ${energyCpuMs.toFixed(1)} ms / ${energyWallMs.toFixed(1)} ms`,
          `网络重放与拦截传输: ${energyNetReqs} 次请求 / ${energyNetBytes} Bytes`,
          `估算电池消耗: ~${estimatedEnergyMah.toFixed(4)} mAh`,
          highUsageTrendAlert
            ? `趋势告警: High Usage Trend (${highUsageTrendAlert.consecutiveIncreases}/${trendConsecutiveThreshold} 次连续上升 / 5m 窗口: ${highUsageTrendAlert.startScore} -> ${highUsageTrendAlert.curScore})`
            : `趋势告警: 正常 (5 分钟窗口阈值 >=${trendConsecutiveThreshold}x 未触发)`,
          '',
          `--- 当前能耗事件日志 (${batteryLogs.length} 条) ---`,
        ]
      : [
          '=== Gemini-Eye Battery Impact Statistics Report (Simulated) ===',
          `Generated: ${nowStr}`,
          `Session: ${activeSession.sessionTag} | ForegroundActive: ${isGeminiForeground}`,
          `Energy Rating: ${energyImpactRating} (Filtered MA(${ENERGY_MA_WINDOW_SIZE})=${smoothedEnergyScore} | Raw=${energyImpactScore} | High Threshold >=${highBatteryImpactThreshold})`,
          `Full Timeline History: ${timeline.length} samples (Spike Bypass Events: ${spikeBypassCount} · Historical Impact CSV Ready)`,
          `BG Wakeups: ${bgWakeupCount} (1ns Gate Suppressed: ${energyBgSuppressed}) | Pool State: ${poolStateLabel} (${activePoolThreads} threads)`,
          `FG On-Demand Dispatches: ${fgDispatchCount} | Hook Events: ${energyHookEvents}`,
          `Cumulative CPU / Wall Time: ${energyCpuMs.toFixed(1)} ms / ${energyWallMs.toFixed(1)} ms`,
          `Network Replays / Transferred: ${energyNetReqs} reqs / ${energyNetBytes} Bytes`,
          `Estimated Battery Draw: ~${estimatedEnergyMah.toFixed(4)} mAh`,
          highUsageTrendAlert
            ? `Trend Alert: High Usage Trend (${highUsageTrendAlert.consecutiveIncreases}/${trendConsecutiveThreshold} consecutive increases in 5m window: ${highUsageTrendAlert.startScore} -> ${highUsageTrendAlert.curScore})`
            : `Trend Alert: Normal (5m window threshold >=${trendConsecutiveThreshold}x not triggered)`,
          '',
          `--- Current Energy Usage Log (${batteryLogs.length} entries) ---`,
        ];

    const bodyLines =
      batteryLogs.length === 0
        ? [
            isZh
              ? '  (当前暂无已记录的 BATTERY_IMPACT 日志；上方的实时统计指标已汇总当前会话)'
              : '  (No BATTERY_IMPACT entries recorded yet; live counters above reflect the active session)',
          ]
        : batteryLogs.map(
            (l) => `  ${l.timeMs} [${l.sessionTag}] ${isZh ? l.detailZh : l.detailEn}`
          );

    const reportText = [...headerLines, ...bodyLines].join('\n');
    setExportedBatteryReport(reportText);
    setDevConsoleOpen(true);
  };

  const buildHistoricalImpactCsv = (): string => {
    const header = [
      'index',
      'timestamp_iso',
      'time_hms',
      'session_id',
      'event',
      'ma_window_size',
      'filtered_ma_score',
      'raw_score',
      'score_delta',
      'spike_bypass',
      'above_high_threshold',
      'cpu_ms',
      'wall_ms',
      'fg_dispatches',
      'net_reqs',
      'estimated_mah',
    ].join(',');
    const rows = fullEnergyTimelineRef.current.map((entry) =>
      [
        entry.index,
        entry.timestampIso,
        entry.timeHms,
        entry.sessionTag,
        entry.event,
        entry.maWindowSize,
        entry.filteredScore,
        entry.rawScore,
        entry.scoreDelta,
        entry.spikeBypass,
        entry.aboveHighThreshold,
        entry.cpuMs.toFixed(1),
        entry.wallMs.toFixed(1),
        entry.fgDispatches,
        entry.netReqs,
        entry.estimatedMah.toFixed(4),
      ]
        .map(csvEscape)
        .join(',')
    );
    return [header, ...rows].join('\n');
  };

  const handleDownloadHistoricalImpactCsv = () => {
    const csv = buildHistoricalImpactCsv();
    const totalSamples = fullEnergyTimelineRef.current.length;
    const spikeBypassCount = fullEnergyTimelineRef.current.filter((e) => e.spikeBypass).length;
    const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `gemini-eye-historical-impact-${activeSession.sessionTag}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    const sess = activeSessionRef.current.sessionTag;
    logDevEvent(
      'BATTERY_IMPACT',
      sess,
      `[Historical Impact CSV 已导出] 导出完整历史时间线共 ${totalSamples} 条样本 (含 Raw Score 原始值与 ${spikeBypassCount} 次 Spike Bypass 尖峰直通标记)`,
      `[Historical Impact CSV Exported] Downloaded full timeline (${totalSamples} samples including raw scores and ${spikeBypassCount} spike bypass events)`,
      'info'
    );
  };

  const handleCopyBatteryReport = async () => {
    if (!exportedBatteryReport) return;
    await navigator.clipboard.writeText(exportedBatteryReport);
    setBatteryReportCopied(true);
    window.setTimeout(() => setBatteryReportCopied(false), 1800);
  };

  const handleDownloadBatteryReportTxt = () => {
    if (!exportedBatteryReport) return;
    const blob = new Blob([exportedBatteryReport], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `gemini-eye-battery-impact-${activeSession.sessionTag}.txt`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
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

  const miuixBlurPx = useMemo(
    () => Math.round(4 + ((Math.max(20, Math.min(100, hudOpacity)) - 20) / 80) * 28),
    [hudOpacity]
  );

  // Update mini progress bar fill widths & live HUD opacity / MIUIX blur intensity via DOM refs (no inline style props)
  useEffect(() => {
    const w5 = isSessionDeepCleaned ? '0%' : `${Math.max(8, Math.min(100, activeSession.parsed.fiveHourPct))}%`;
    const wWk = isSessionDeepCleaned ? '0%' : `${Math.max(8, Math.min(100, activeSession.parsed.weeklyPct))}%`;
    if (bar5hRef.current) bar5hRef.current.style.width = w5;
    if (barWeeklyRef.current) barWeeklyRef.current.style.width = wWk;
    if (settingsBar5hRef.current) settingsBar5hRef.current.style.width = w5;
    if (settingsBarWeeklyRef.current) settingsBarWeeklyRef.current.style.width = wWk;
  }, [activeSession, activeView, isGeminiForeground, isStaleOffline, isSessionDeepCleaned]);

  useEffect(() => {
    const capsule = hudCapsuleRef.current;
    if (!capsule) return;

    const clampedOpacity = Math.max(20, Math.min(100, hudOpacity));
    const alpha = clampedOpacity / 100;
    const norm = (clampedOpacity - 20) / 80;
    const blurPx = Math.round(4 + norm * 28);
    const satVal = (1.05 + norm * 0.45).toFixed(2);

    if (hudTheme === 'MIUIX') {
      capsule.style.backgroundColor = simDarkTheme
        ? `rgba(24, 24, 27, ${alpha.toFixed(2)})`
        : `rgba(255, 255, 255, ${alpha.toFixed(2)})`;
      const filterVal = `blur(${blurPx}px) saturate(${satVal})`;
      capsule.style.backdropFilter = filterVal;
      (capsule.style as CSSStyleDeclaration & { webkitBackdropFilter?: string }).webkitBackdropFilter = filterVal;
      capsule.style.setProperty('--miuix-blur-min', `${Math.max(2, blurPx - 6)}px`);
      capsule.style.setProperty('--miuix-blur-max', `${blurPx + 8}px`);
      capsule.style.setProperty('--miuix-sat-min', (1.0 + norm * 0.25).toFixed(2));
      capsule.style.setProperty('--miuix-sat-max', (1.18 + norm * 0.42).toFixed(2));
    } else {
      capsule.style.backgroundColor = simDarkTheme
        ? `rgba(41, 37, 51, ${alpha.toFixed(2)})`
        : `rgba(245, 239, 251, ${alpha.toFixed(2)})`;
      capsule.style.backdropFilter = 'none';
      (capsule.style as CSSStyleDeclaration & { webkitBackdropFilter?: string }).webkitBackdropFilter = 'none';
    }
  }, [
    hudOpacity,
    hudTheme,
    simDarkTheme,
    isGeminiForeground,
    hudDockedEdge,
    isStaleOffline,
    isHighBatteryImpact,
    isSessionDeepCleaned,
    activeView,
  ]);

  useLayoutEffect(() => {
    const capsule = hudCapsuleRef.current;
    const host = capsule?.parentElement;
    if (!isGeminiForeground || !capsule || !host) return;

    const hostW = host.clientWidth;
    const elW = capsule.offsetWidth;
    const prevEdge = prevHudDockEdgeRef.current;
    prevHudDockEdgeRef.current = hudDockedEdge;

    if (prevEdge !== hudDockedEdge && hudAnimRafRef.current !== null) {
      cancelAnimationFrame(hudAnimRafRef.current);
      hudAnimRafRef.current = null;
    }

    let nextX = hudPosRef.current.x;
    if (!hudPosInitializedRef.current) {
      nextX = Math.max(8, hostW - elW - 10);
      hudPosInitializedRef.current = true;
    } else if (hudDockedEdge === 'LEFT') {
      nextX = 0;
    } else if (hudDockedEdge === 'RIGHT') {
      nextX = Math.max(0, hostW - elW);
    } else if (prevEdge === 'LEFT') {
      nextX = 8;
    } else if (prevEdge === 'RIGHT') {
      nextX = Math.max(8, hostW - elW - 10);
    } else {
      nextX = Math.max(4, Math.min(Math.max(4, hostW - elW - 4), nextX));
    }

    hudPosRef.current = { x: nextX, y: hudPosRef.current.y };
    if (prevEdge !== hudDockedEdge) {
      dragRef.current.filteredX = nextX;
      dragRef.current.targetX = nextX;
      dragRef.current.vx = 0;
      dragRef.current.intendedDock = 'NONE';
    }
    capsule.style.transform = `translate3d(${nextX.toFixed(2)}px, ${hudPosRef.current.y.toFixed(2)}px, 0)`;
  }, [isGeminiForeground, hudDockedEdge, activeSession, hudLang, isStaleOffline, isSessionDeepCleaned, activeView]);

  useLayoutEffect(() => {
    const el = hudRippleRef.current;
    if (!el || !hudTapRipple) return;
    el.style.left = `${hudTapRipple.x}px`;
    el.style.top = `${hudTapRipple.y}px`;
    el.style.width = `${hudTapRipple.size}px`;
    el.style.height = `${hudTapRipple.size}px`;
  }, [hudTapRipple]);

  useEffect(() => {
    return () => {
      if (hudAnimRafRef.current !== null) {
        cancelAnimationFrame(hudAnimRafRef.current);
        hudAnimRafRef.current = null;
      }
      if (hudRippleTimerRef.current !== null) {
        window.clearTimeout(hudRippleTimerRef.current);
        hudRippleTimerRef.current = null;
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
      pushHeartbeatEnergySurgeWarn(latestEnergyScoreRef.current, '30s_HEARTBEAT');
      heartbeatBaselineScoreRef.current = latestEnergyScoreRef.current;
      surgeAlertedInCycleRef.current = false;
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

  const triggerHudTapRipple = (clientX?: number, clientY?: number) => {
    const capsule = hudCapsuleRef.current;
    if (!capsule) return;
    const rect = capsule.getBoundingClientRect();
    const relX = typeof clientX === 'number' ? clientX - rect.left : rect.width * 0.5;
    const relY = typeof clientY === 'number' ? clientY - rect.top : rect.height * 0.5;
    const maxCornerDist = Math.max(
      Math.hypot(relX, relY),
      Math.hypot(rect.width - relX, relY),
      Math.hypot(relX, rect.height - relY),
      Math.hypot(rect.width - relX, rect.height - relY)
    );
    const size = Math.max(72, Math.ceil(maxCornerDist * 2.1));
    if (hudRippleTimerRef.current !== null) {
      window.clearTimeout(hudRippleTimerRef.current);
    }
    setHudTapRipple({ id: Date.now() + Math.random(), x: relX, y: relY, size });
    hudRippleTimerRef.current = window.setTimeout(() => {
      hudRippleTimerRef.current = null;
      setHudTapRipple(null);
    }, 620);
  };

  const performManualHudRefresh = (origin: 'CAPSULE_TAP' | 'REFRESH_BTN', clientX?: number, clientY?: number) => {
    if (!isGeminiForeground) return;
    triggerHudTapRipple(clientX, clientY);
    if (isRefreshingHud) return;
    setIsRefreshingHud(true);
    setLastSyncNote(
      isZh
        ? `手动前台刷新 (MANUAL_TAP · ${activeSession.sessionTag})：绕过节流强制执行全量协议快照扫描（零后台服务）`
        : `Manual foreground refresh (MANUAL_TAP · ${activeSession.sessionTag}): bypassed throttle to run foreground full-protocol snapshot scan`
    );
    logDevEvent(
      'MANUAL_TAP',
      activeSession.sessionTag,
      `HUD 胶囊点击触发手动刷新 (${origin} / MANUAL_TAP)。当前状态 usageSheetOpen=${usageSheetOpen}，已播放波纹反馈并触发 ForegroundFullProtocolSnapshotScan`,
      `HUD capsule tapped for manual refresh (${origin} / MANUAL_TAP). usageSheetOpen=${usageSheetOpen}, playing ripple feedback & invoking ForegroundFullProtocolSnapshotScan`,
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

  const triggerSimulatedRefresh = (e: React.MouseEvent) => {
    e.stopPropagation();
    performManualHudRefresh('REFRESH_BTN', e.clientX, e.clientY);
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
      const hostW = host.clientWidth;
      const isOrb = hudDockedEdgeRef.current !== 'NONE';
      const minX = isOrb ? 0 : 4;
      const maxXShift = isOrb
        ? Math.max(0, hostW - capsule.offsetWidth)
        : Math.max(minX + 8, hostW - capsule.offsetWidth - 4);
      const maxDownShift = Math.max(180, host.clientHeight - capsule.offsetHeight - 132);
      return {
        hostW,
        minX,
        maxX: maxXShift,
        minY: -6,
        maxY: maxDownShift,
      };
    }
    return { hostW: 400, minX: 4, maxX: 300, minY: -8, maxY: 260 };
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

  const resolveIntendedDockEdge = (
    rawX: number,
    pointerLocalX: number,
    vx: number,
    bounds: { hostW: number; minX: number; maxX: number },
    dxFromStart: number
  ): HudDockEdge => {
    const isCurrentlyOrb = hudDockedEdgeRef.current !== 'NONE';
    if (isCurrentlyOrb) {
      const distLeft = Math.max(0, rawX - bounds.minX);
      const distRight = Math.max(0, bounds.maxX - rawX);
      const orbZone = 72;
      if (distLeft <= orbZone && distLeft <= distRight) return 'LEFT';
      if (distRight <= orbZone) return 'RIGHT';
      if (pointerLocalX <= 64) return 'LEFT';
      if (pointerLocalX >= bounds.hostW - 64) return 'RIGHT';
      return 'NONE';
    }

    const span = bounds.maxX - bounds.minX;
    const distLeft = Math.max(0, rawX - bounds.minX);
    const distRight = Math.max(0, bounds.maxX - rawX);
    const edgeZonePx = Math.min(HUD_MAGNET_EDGE_THRESHOLD_PX, Math.max(24, span * 0.38));

    if (
      rawX <= bounds.minX - 6 ||
      (pointerLocalX <= 68 && dxFromStart < -14) ||
      (vx <= -0.32 && dxFromStart < -18)
    ) {
      return 'LEFT';
    }
    if (
      rawX >= bounds.maxX + 6 ||
      (pointerLocalX >= bounds.hostW - 68 && dxFromStart > 14) ||
      (vx >= 0.32 && dxFromStart > 18)
    ) {
      return 'RIGHT';
    }

    if (span <= 84) {
      if (dxFromStart <= -18 && distLeft <= distRight) return 'LEFT';
      if (dxFromStart >= 18 && distRight <= distLeft) return 'RIGHT';
      if (dxFromStart <= -28) return 'LEFT';
      if (dxFromStart >= 28) return 'RIGHT';
    } else {
      if (distLeft <= edgeZonePx && distLeft <= distRight) return 'LEFT';
      if (distRight <= edgeZonePx) return 'RIGHT';
    }
    return 'NONE';
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

        const lerpAlpha = 1 - Math.pow(1 - 0.52, frameScale);
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

      // Release phase: inertial glide + deterministic Left/Right edge Magnetism -> Floating Orb
      const decay = Math.pow(0.88, frameScale);
      drag.vx *= decay;
      drag.vy *= decay;

      const rawNextTargetX = drag.targetX + drag.vx * dt;
      const rawNextTargetY = drag.targetY + drag.vy * dt;
      let nextTargetX = clampHudValue(rawNextTargetX, bounds.minX, bounds.maxX);
      let nextTargetY = clampHudValue(rawNextTargetY, bounds.minY, bounds.maxY);

      if (rawNextTargetX !== nextTargetX) drag.vx *= 0.4;
      if (rawNextTargetY !== nextTargetY) drag.vy *= 0.45;

      const speed = Math.hypot(drag.vx, drag.vy);
      let xMagnetPending = false;
      const capsule = hudCapsuleRef.current;

      const activeIntendedDock: HudDockEdge =
        drag.intendedDock !== 'NONE'
          ? drag.intendedDock
          : speed < 0.18
          ? (() => {
              const dLeft = Math.abs(nextTargetX - bounds.minX);
              const dRight = Math.abs(bounds.maxX - nextTargetX);
              if (hudDockedEdgeRef.current !== 'NONE') {
                if (dLeft <= 72 && dLeft <= dRight) return 'LEFT';
                if (dRight <= 72) return 'RIGHT';
                return 'NONE';
              }
              if (dLeft <= HUD_MAGNET_EDGE_THRESHOLD_PX && dLeft <= dRight) return 'LEFT';
              if (dRight <= HUD_MAGNET_EDGE_THRESHOLD_PX) return 'RIGHT';
              return 'NONE';
            })()
          : hudDockedEdgeRef.current;

      if (capsule && (drag.intendedDock !== 'NONE' || speed < 0.18)) {
        const magnetAlpha = 1 - Math.pow(1 - 0.34, frameScale);
        if (activeIntendedDock === 'LEFT' || activeIntendedDock === 'RIGHT') {
          const snapX = activeIntendedDock === 'LEFT' ? bounds.minX : bounds.maxX;
          const deltaSnapX = snapX - nextTargetX;
          if (Math.abs(deltaSnapX) < 0.6) {
            nextTargetX = snapX;
          } else {
            nextTargetX += deltaSnapX * magnetAlpha;
            drag.vx *= 0.55;
            xMagnetPending = true;
          }
        }

        const distTop = Math.abs(nextTargetY - bounds.minY);
        const distBottom = Math.abs(bounds.maxY - nextTargetY);
        if (Math.min(distTop, distBottom) <= 28) {
          const snapY = distTop <= distBottom ? bounds.minY : bounds.maxY;
          const deltaSnapY = snapY - nextTargetY;
          if (Math.abs(deltaSnapY) < 0.35) {
            nextTargetY = snapY;
          } else {
            nextTargetY += deltaSnapY * magnetAlpha;
            drag.vy *= 0.6;
          }
        }
      }

      drag.targetX = nextTargetX;
      drag.targetY = nextTargetY;

      const curX = hudPosRef.current.x;
      const curY = hudPosRef.current.y;
      const followAlpha = 1 - Math.pow(1 - 0.34, frameScale);
      const nextX = curX + (nextTargetX - curX) * followAlpha;
      const nextY = curY + (nextTargetY - curY) * followAlpha;

      const distX = Math.abs(nextTargetX - nextX);
      const distY = Math.abs(nextTargetY - nextY);
      const readyToCommitDock =
        !xMagnetPending &&
        distX < 1.2 &&
        (activeIntendedDock !== hudDockedEdgeRef.current) &&
        (drag.intendedDock !== 'NONE' || speed < 0.12);

      if (readyToCommitDock) {
        drag.intendedDock = 'NONE';
        drag.vx = 0;
        drag.vy = 0;
        applyHudTransform(nextTargetX, nextY);
        setHudDragEdgePreview('NONE');
        setHudDockedEdge(activeIntendedDock);
        if (distY < 0.35) {
          hudAnimRafRef.current = null;
          return;
        }
        hudAnimRafRef.current = requestAnimationFrame(step);
        return;
      }

      if (!xMagnetPending && speed < 0.03 && distX < 0.25 && distY < 0.25) {
        drag.intendedDock = 'NONE';
        drag.vx = 0;
        drag.vy = 0;
        applyHudTransform(nextTargetX, nextTargetY);
        setHudDragEdgePreview('NONE');
        hudAnimRafRef.current = null;
        if (activeIntendedDock !== hudDockedEdgeRef.current) {
          setHudDockedEdge(activeIntendedDock);
        }
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
    const hostRect = e.currentTarget.parentElement?.getBoundingClientRect();
    const localX = hostRect ? e.clientX - hostRect.left : 0;
    dragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      origX: hudPosRef.current.x,
      origY: hudPosRef.current.y,
      rawX: hudPosRef.current.x,
      pointerLocalX: localX,
      intendedDock: 'NONE',
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

    if (stepDist < HUD_TOUCH_JITTER_DEADBAND_PX && sampleDt < HUD_TOUCH_SAMPLE_WINDOW_MS) {
      return;
    }

    const dt = Math.min(64, Math.max(4, now - drag.lastTimeMs));
    const instVx = stepDx / dt;
    const instVy = stepDy / dt;

    drag.vx = drag.vx * 0.55 + clampHudValue(instVx, -1.8, 1.8) * 0.45;
    drag.vy = drag.vy * 0.55 + clampHudValue(instVy, -1.8, 1.8) * 0.45;
    drag.lastClientX = e.clientX;
    drag.lastClientY = e.clientY;
    drag.lastTimeMs = now;
    drag.lastSampleTimeMs = now;

    const speed = Math.hypot(drag.vx, drag.vy);
    const posAlpha = clampHudValue(0.48 + speed * 0.44, 0.48, 0.96);
    const rawX = drag.origX + dx;
    const rawY = drag.origY + dy;
    drag.rawX = rawX;
    const hostRect = e.currentTarget.parentElement?.getBoundingClientRect();
    const pointerLocalX = hostRect ? e.clientX - hostRect.left : drag.pointerLocalX;
    drag.pointerLocalX = pointerLocalX;

    drag.filteredX = drag.filteredX + (rawX - drag.filteredX) * posAlpha;
    drag.filteredY = drag.filteredY + (rawY - drag.filteredY) * posAlpha;

    const bounds = getHudDragBounds();
    const leadX = clampHudValue(drag.vx * HUD_FRAME_SMOOTH_OFFSET_MS, -14, 14);
    const leadY = clampHudValue(drag.vy * HUD_FRAME_SMOOTH_OFFSET_MS, -14, 14);
    drag.targetX = rubberBandHudValue(drag.filteredX + leadX, bounds.minX, bounds.maxX);
    drag.targetY = rubberBandHudValue(drag.filteredY + leadY, bounds.minY, bounds.maxY);

    const previewDock = resolveIntendedDockEdge(rawX, pointerLocalX, drag.vx, bounds, dx);
    drag.intendedDock = previewDock;
    setHudDragEdgePreview((prev) => (prev === previewDock ? prev : previewDock));

    ensureHudAnimationLoop();
  };

  const onHudPointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag.pointerDown) return;
    drag.pointerDown = false;
    setHudDragEdgePreview('NONE');
    try {
      if (e.currentTarget.hasPointerCapture(e.pointerId)) {
        e.currentTarget.releasePointerCapture(e.pointerId);
      }
    } catch {
      // ignore if capture already released
    }
    if (drag.moved) {
      const bounds = getHudDragBounds();
      const dx = e.clientX - drag.startX;
      const hostRect = e.currentTarget.parentElement?.getBoundingClientRect();
      const pointerLocalX = hostRect ? e.clientX - hostRect.left : drag.pointerLocalX;
      const intended = resolveIntendedDockEdge(drag.rawX, pointerLocalX, drag.vx, bounds, dx);
      drag.intendedDock = intended;
      drag.targetX =
        intended === 'LEFT'
          ? bounds.minX
          : intended === 'RIGHT'
          ? bounds.maxX
          : clampHudValue(drag.filteredX, bounds.minX, bounds.maxX);
      drag.targetY = clampHudValue(drag.filteredY, bounds.minY, bounds.maxY);
      if (performance.now() - drag.lastSampleTimeMs > 64) {
        drag.vx = 0;
        drag.vy = 0;
      }
      ensureHudAnimationLoop();
    } else if (hudDockedEdgeRef.current !== 'NONE') {
      triggerHudTapRipple(e.clientX, e.clientY);
      setHudDockedEdge('NONE');
    } else {
      performManualHudRefresh('CAPSULE_TAP', e.clientX, e.clientY);
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
    const miuixEnergyBlurClass =
      hudTheme === 'MIUIX' && isHighBatteryImpact && !isSessionDeepCleaned
        ? 'animate-miuix-energy-blur'
        : '';

    if (isStaleOffline) {
      if (hudTheme === 'MIUIX') {
        const base = simDarkTheme
          ? 'bg-zinc-900/80 backdrop-blur-xl border-slate-500/40 text-slate-400 shadow-lg shadow-black/40'
          : 'bg-white/85 backdrop-blur-xl border-slate-400/40 text-slate-500 shadow-lg shadow-slate-900/10';
        return miuixEnergyBlurClass ? `${base} ${miuixEnergyBlurClass}` : base;
      }
      return simDarkTheme
        ? 'bg-[#292533]/95 border-gray-500/45 text-gray-400 shadow-md shadow-black/40'
        : 'bg-[#F5EFFB]/95 border-gray-400/50 text-gray-500 shadow-md shadow-indigo-950/10';
    }
    if (hudTheme === 'MIUIX') {
      const base = simDarkTheme
        ? 'bg-zinc-900/80 backdrop-blur-xl border-white/20 text-slate-50 shadow-lg shadow-black/40'
        : 'bg-white/85 backdrop-blur-xl border-slate-900/12 text-slate-900 shadow-lg shadow-slate-900/12';
      return [base, highLoadClass, miuixEnergyBlurClass].filter(Boolean).join(' ');
    }
    const base = simDarkTheme
      ? 'bg-[#292533]/95 border-[#938F99]/40 text-[#E6E1E5] shadow-md shadow-black/40'
      : 'bg-[#F5EFFB]/95 border-[#CAC4D0]/70 text-[#1D1B20] shadow-md shadow-indigo-950/10';
    return highLoadClass ? `${base} ${highLoadClass}` : base;
  }, [hudTheme, simDarkTheme, isStaleOffline, isHighLoadQuota, isHighBatteryImpact, isSessionDeepCleaned]);

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

                  {/* Real-time HUD Opacity & MIUIX Blur Intensity */}
                  <div className="pt-2 border-t border-slate-100 space-y-2">
                    <div className="flex items-center justify-between gap-2 text-xs">
                      <span className="font-medium text-slate-700">
                        {isZh ? 'HUD 不透明度 & MIUIX 模糊强度' : 'HUD Opacity & MIUIX Blur Intensity'}
                      </span>
                      <div className="flex items-center gap-2 font-mono text-[11px]">
                        <span className="px-1.5 py-0.5 rounded bg-sky-50 text-sky-700 border border-sky-200 font-semibold">
                          {hudOpacity}%
                        </span>
                        {hudTheme === 'MIUIX' && (
                          <span className="px-1.5 py-0.5 rounded bg-slate-100 text-slate-700 border border-slate-200">
                            Blur {miuixBlurPx}px
                          </span>
                        )}
                        <button
                          type="button"
                          onClick={() => {
                            setIsGeminiForeground(true);
                            setUsageSheetOpen(true);
                          }}
                          className="px-2 py-0.5 rounded bg-slate-900 text-white hover:bg-slate-800 transition-colors cursor-pointer"
                        >
                          {isZh ? '打开设置弹窗' : 'Open Settings Modal'}
                        </button>
                      </div>
                    </div>
                    <input
                      type="range"
                      min={20}
                      max={100}
                      step={1}
                      value={hudOpacity}
                      onChange={(e) => setHudOpacity(Number(e.target.value))}
                      aria-label={isZh ? 'HUD 不透明度滑块' : 'HUD Opacity Slider'}
                      className="w-full h-1.5 bg-slate-200 rounded-lg appearance-none cursor-pointer accent-sky-600"
                    />
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
                  <div className="flex flex-wrap items-center gap-1.5">
                    <div className="inline-flex items-center gap-1 p-0.5 bg-slate-200/80 rounded-lg text-[11px]">
                      <button
                        type="button"
                        onClick={() => setHudDockedEdge('LEFT')}
                        className={`px-2 py-0.5 rounded-md font-medium transition-colors cursor-pointer ${
                          hudDockedEdge === 'LEFT'
                            ? 'bg-slate-900 text-white'
                            : 'text-slate-600 hover:text-slate-900'
                        }`}
                      >
                        {isZh ? '左贴边球' : 'Left Orb'}
                      </button>
                      <button
                        type="button"
                        onClick={() => setHudDockedEdge('NONE')}
                        className={`px-2 py-0.5 rounded-md font-medium transition-colors cursor-pointer ${
                          hudDockedEdge === 'NONE'
                            ? 'bg-slate-900 text-white'
                            : 'text-slate-600 hover:text-slate-900'
                        }`}
                      >
                        {isZh ? '展开胶囊' : 'Capsule'}
                      </button>
                      <button
                        type="button"
                        onClick={() => setHudDockedEdge('RIGHT')}
                        className={`px-2 py-0.5 rounded-md font-medium transition-colors cursor-pointer ${
                          hudDockedEdge === 'RIGHT'
                            ? 'bg-slate-900 text-white'
                            : 'text-slate-600 hover:text-slate-900'
                        }`}
                      >
                        {isZh ? '右贴边球' : 'Right Orb'}
                      </button>
                    </div>

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

                  {/* Subtle ambient Gemini mesh behind HUD so real-time MIUIX blur & opacity changes are clearly visible */}
                  <div className="pointer-events-none absolute inset-x-0 top-10 h-32 overflow-hidden">
                    <div
                      className={`absolute -top-6 left-10 w-44 h-28 rounded-full blur-xl ${
                        simDarkTheme
                          ? 'bg-gradient-to-r from-sky-500/35 via-indigo-500/30 to-teal-400/30'
                          : 'bg-gradient-to-r from-sky-400/40 via-indigo-400/30 to-teal-300/35'
                      }`}
                    />
                    <div
                      className={`absolute top-2 right-8 w-36 h-20 rounded-full blur-lg ${
                        simDarkTheme ? 'bg-violet-500/25' : 'bg-blue-400/30'
                      }`}
                    />
                  </div>

                  {/* Simulated Gemini Body */}
                  <div className="relative p-6 pt-16 space-y-4 flex-1 flex flex-col justify-between">
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
                          className={`p-3.5 rounded-2xl border space-y-2 max-h-[340px] overflow-y-auto ${
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

                          {/* HUD Settings Section */}
                          <div
                            className={`pt-2 border-t space-y-2 ${
                              simDarkTheme ? 'border-zinc-700/70' : 'border-slate-200/80'
                            }`}
                          >
                            <div className="flex items-center justify-between gap-2">
                              <span className="text-[11px] font-semibold tracking-wide uppercase text-sky-500">
                                {isZh ? 'HUD 设置 · HUD Settings' : 'HUD Settings'}
                              </span>
                              <div className="flex items-center gap-1">
                                <button
                                  type="button"
                                  onClick={() => setHudTheme('MIUIX')}
                                  className={`px-1.5 py-0.5 rounded text-[10px] font-medium transition-colors cursor-pointer ${
                                    hudTheme === 'MIUIX'
                                      ? 'bg-sky-600 text-white'
                                      : simDarkTheme
                                      ? 'bg-zinc-800 text-zinc-400 hover:text-zinc-200'
                                      : 'bg-slate-100 text-slate-600 hover:text-slate-900'
                                  }`}
                                >
                                  MIUIX
                                </button>
                                <button
                                  type="button"
                                  onClick={() => setHudTheme('MD3')}
                                  className={`px-1.5 py-0.5 rounded text-[10px] font-medium transition-colors cursor-pointer ${
                                    hudTheme === 'MD3'
                                      ? 'bg-violet-600 text-white'
                                      : simDarkTheme
                                      ? 'bg-zinc-800 text-zinc-400 hover:text-zinc-200'
                                      : 'bg-slate-100 text-slate-600 hover:text-slate-900'
                                  }`}
                                >
                                  MD3
                                </button>
                              </div>
                            </div>

                            <div className="space-y-1">
                              <div className="flex items-center justify-between gap-2 text-[11px]">
                                <span className="font-medium">
                                  {isZh ? 'HUD 不透明度 (MIUIX 模糊强度)' : 'HUD Opacity (MIUIX Blur Intensity)'}
                                </span>
                                <span className="font-mono text-[10px] text-sky-500 font-semibold">
                                  {hudOpacity}%{hudTheme === 'MIUIX' ? ` · ${miuixBlurPx}px` : ''}
                                </span>
                              </div>
                              <div className="flex items-center gap-2">
                                <input
                                  type="range"
                                  min={20}
                                  max={100}
                                  step={1}
                                  value={hudOpacity}
                                  onChange={(e) => setHudOpacity(Number(e.target.value))}
                                  aria-label="HUD Opacity"
                                  className="w-full h-1.5 bg-slate-200 dark:bg-zinc-700 rounded-lg appearance-none cursor-pointer accent-sky-500"
                                />
                                  {hudOpacity !== 85 && (
                                  <button
                                    type="button"
                                    onClick={() => setHudOpacity(85)}
                                    className={`px-1.5 py-0.5 rounded text-[9px] font-mono border shrink-0 cursor-pointer ${
                                      simDarkTheme
                                        ? 'border-zinc-700 bg-zinc-800 text-zinc-300 hover:bg-zinc-700'
                                        : 'border-slate-200 bg-slate-100 text-slate-600 hover:bg-slate-200'
                                    }`}
                                  >
                                    {isZh ? '重置' : 'Reset'}
                                  </button>
                                )}
                              </div>
                            </div>

                            <div className="space-y-1 pt-1">
                              <div className="flex items-center justify-between gap-2 text-[11px]">
                                <span className="font-medium">
                                  {isZh
                                    ? 'High Usage Trend 灵敏度 (连续上升次数)'
                                    : 'High Usage Trend Sensitivity (Consecutive Increases)'}
                                </span>
                                <span className="font-mono text-[10px] text-rose-500 font-semibold">
                                  &gt;= {trendConsecutiveThreshold}x / 5m
                                </span>
                              </div>
                              <div className="flex items-center gap-2">
                                <span className="text-[9px] font-mono opacity-60 shrink-0">{TREND_CONSECUTIVE_MIN}</span>
                                <input
                                  type="range"
                                  min={TREND_CONSECUTIVE_MIN}
                                  max={TREND_CONSECUTIVE_MAX}
                                  step={1}
                                  value={trendConsecutiveThreshold}
                                  onChange={(e) =>
                                    setTrendConsecutiveThreshold(
                                      clampTrendConsecutiveThreshold(Number(e.target.value))
                                    )
                                  }
                                  aria-label="High Usage Trend Consecutive Increases Threshold"
                                  className="w-full h-1.5 bg-slate-200 dark:bg-zinc-700 rounded-lg appearance-none cursor-pointer accent-rose-500"
                                />
                                <span className="text-[9px] font-mono opacity-60 shrink-0">{TREND_CONSECUTIVE_MAX}</span>
                                {trendConsecutiveThreshold !== DEFAULT_TREND_CONSECUTIVE_THRESHOLD && (
                                  <button
                                    type="button"
                                    onClick={() =>
                                      setTrendConsecutiveThreshold(DEFAULT_TREND_CONSECUTIVE_THRESHOLD)
                                    }
                                    className={`px-1.5 py-0.5 rounded text-[9px] font-mono border shrink-0 cursor-pointer ${
                                      simDarkTheme
                                        ? 'border-zinc-700 bg-zinc-800 text-zinc-300 hover:bg-zinc-700'
                                        : 'border-slate-200 bg-slate-100 text-slate-600 hover:bg-slate-200'
                                    }`}
                                  >
                                    {isZh ? '重置' : 'Reset'}
                                  </button>
                                )}
                              </div>
                              <div className="text-[9.5px] opacity-65 font-mono">
                                {isZh
                                  ? '5 分钟滑动窗口连续上升触发阈值 · 已自动保存至 localStorage'
                                  : '5-min rolling window trigger streak · Saved to localStorage'}
                              </div>
                            </div>

                            <div className="space-y-1 pt-1.5 border-t border-slate-200/50 dark:border-zinc-700/60">
                              <div className="flex items-center justify-between gap-2 text-[11px]">
                                <span className="font-medium">
                                  {isZh
                                    ? 'Trend 通知自动消失计时'
                                    : 'Trend Alert Auto-Dismiss Timer'}
                                </span>
                                <div className="flex items-center gap-1.5">
                                  <span
                                    className={`font-mono text-[10px] font-semibold ${
                                      trendAutoDismissEnabled ? 'text-amber-500' : 'opacity-60'
                                    }`}
                                  >
                                    {trendAutoDismissEnabled
                                      ? `${trendAutoDismissSec}s`
                                      : isZh
                                        ? '已关闭'
                                        : 'OFF'}
                                  </span>
                                  <button
                                    type="button"
                                    onClick={() => setTrendAutoDismissEnabled((v) => !v)}
                                    className={`px-1.5 py-0.5 rounded font-mono text-[9.5px] border transition-colors cursor-pointer ${
                                      trendAutoDismissEnabled
                                        ? 'bg-amber-600 border-amber-500 text-white'
                                        : 'bg-slate-200 dark:bg-zinc-800 border-slate-300 dark:border-zinc-700 text-slate-600 dark:text-zinc-300'
                                    }`}
                                  >
                                    {trendAutoDismissEnabled ? 'AUTO' : 'OFF'}
                                  </button>
                                </div>
                              </div>
                              <div className="flex items-center gap-2">
                                <span className="text-[9px] font-mono opacity-60 shrink-0">
                                  {TREND_AUTO_DISMISS_MIN_SEC}s
                                </span>
                                <input
                                  type="range"
                                  min={TREND_AUTO_DISMISS_MIN_SEC}
                                  max={TREND_AUTO_DISMISS_MAX_SEC}
                                  step={1}
                                  disabled={!trendAutoDismissEnabled}
                                  value={trendAutoDismissSec}
                                  onChange={(e) =>
                                    setTrendAutoDismissSec(
                                      clampTrendAutoDismissSec(Number(e.target.value))
                                    )
                                  }
                                  aria-label="High Usage Trend Auto-Dismiss Timer Seconds"
                                  className={`w-full h-1.5 bg-slate-200 dark:bg-zinc-700 rounded-lg appearance-none accent-amber-500 ${
                                    trendAutoDismissEnabled
                                      ? 'cursor-pointer'
                                      : 'opacity-45 cursor-not-allowed'
                                  }`}
                                />
                                <span className="text-[9px] font-mono opacity-60 shrink-0">
                                  {TREND_AUTO_DISMISS_MAX_SEC}s
                                </span>
                                {trendAutoDismissSec !== DEFAULT_TREND_AUTO_DISMISS_SEC && (
                                  <button
                                    type="button"
                                    onClick={() =>
                                      setTrendAutoDismissSec(DEFAULT_TREND_AUTO_DISMISS_SEC)
                                    }
                                    className={`px-1.5 py-0.5 rounded text-[9px] font-mono border shrink-0 cursor-pointer ${
                                      simDarkTheme
                                        ? 'border-zinc-700 bg-zinc-800 text-zinc-300 hover:bg-zinc-700'
                                        : 'border-slate-200 bg-slate-100 text-slate-600 hover:bg-slate-200'
                                    }`}
                                  >
                                    {isZh ? '重置' : 'Reset'}
                                  </button>
                                )}
                              </div>
                              <div className="text-[9.5px] opacity-65 font-mono">
                                {isZh
                                  ? '触发后超时自动从控制台移除 · 已保存至 localStorage'
                                  : 'Auto-removes console alert after timeout · Saved to localStorage'}
                              </div>
                            </div>

                            <div className="space-y-1 pt-1.5 border-t border-slate-200/50 dark:border-zinc-700/60">
                              <div className="flex items-center justify-between gap-2 text-[11px]">
                                <span className="font-medium inline-flex items-center gap-1">
                                  {trendSoundEnabled ? (
                                    <Volume2 className="w-3.5 h-3.5 text-emerald-500 shrink-0" />
                                  ) : (
                                    <VolumeX className="w-3.5 h-3.5 opacity-60 shrink-0" />
                                  )}
                                  <span>
                                    {isZh
                                      ? 'Trend 告警提示音 (Web Audio)'
                                      : 'Trend Alert Sound (Web Audio)'}
                                  </span>
                                </span>
                                <div className="flex items-center gap-1.5">
                                  <button
                                    type="button"
                                    onClick={() => playHighUsageTrendChime(true)}
                                    className={`px-1.5 py-0.5 rounded font-mono text-[9.5px] border transition-colors cursor-pointer ${
                                      simDarkTheme
                                        ? 'border-zinc-700 bg-zinc-800 text-zinc-200 hover:bg-zinc-700'
                                        : 'border-slate-200 bg-slate-100 text-slate-700 hover:bg-slate-200'
                                    }`}
                                  >
                                    {isZh ? '试听' : 'Test'}
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => setTrendSoundEnabled((v) => !v)}
                                    className={`px-1.5 py-0.5 rounded font-mono text-[9.5px] border transition-colors cursor-pointer ${
                                      trendSoundEnabled
                                        ? 'bg-emerald-600 border-emerald-500 text-white'
                                        : 'bg-slate-200 dark:bg-zinc-800 border-slate-300 dark:border-zinc-700 text-slate-600 dark:text-zinc-300'
                                    }`}
                                  >
                                    {trendSoundEnabled ? 'ON' : 'MUTED'}
                                  </button>
                                </div>
                              </div>
                              <div className="text-[9.5px] opacity-65 font-mono">
                                {isZh
                                  ? '触发 High Usage Trend 时播放柔和双音提示 · 已保存至 localStorage'
                                  : 'Subtle Web Audio chime on High Usage Trend · Saved to localStorage'}
                              </div>
                            </div>
                          </div>

                          <div className="pt-1.5 border-t border-slate-200/60 dark:border-zinc-700/70 space-y-1.5">
                            <div className="flex items-center justify-between gap-2 text-[11px]">
                              <span className="font-medium">
                                {isZh ? '显示 Energy Impact 统计' : 'Show Energy Impact Stats'}
                              </span>
                              <button
                                type="button"
                                onClick={() => setShowEnergyImpactStats((v) => !v)}
                                className={`px-2 py-0.5 rounded font-mono text-[10px] border transition-colors cursor-pointer ${
                                  showEnergyImpactStats
                                    ? 'bg-teal-600 border-teal-500 text-white'
                                    : 'bg-slate-200 dark:bg-zinc-800 border-slate-300 dark:border-zinc-700 text-slate-600 dark:text-zinc-300'
                                }`}
                              >
                                {showEnergyImpactStats ? 'ON' : 'OFF'}
                              </button>
                            </div>

                            {showEnergyImpactStats && (
                              <div className="p-2 rounded-lg bg-teal-950/80 border border-teal-800/70 text-teal-200 text-[10px] font-mono leading-snug">
                                Energy Impact: {energyImpactRating} | CPU={energyCpuMs.toFixed(1)}ms | FG={fgDispatchCount} | BG={bgWakeupCount} | Est=~{estimatedEnergyMah.toFixed(4)}mAh
                              </div>
                            )}

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

                  {/* Edge-dock snap preview strips on left / right bezels while dragging */}
                  {isGeminiForeground && hudDragEdgePreview === 'LEFT' && (
                    <div
                      aria-hidden="true"
                      className="pointer-events-none absolute left-0 top-14 w-1.5 h-16 rounded-r-full bg-sky-400/90 shadow-[0_0_12px_rgba(56,189,248,0.85)] animate-pulse z-20"
                    />
                  )}
                  {isGeminiForeground && hudDragEdgePreview === 'RIGHT' && (
                    <div
                      aria-hidden="true"
                      className="pointer-events-none absolute right-0 top-14 w-1.5 h-16 rounded-l-full bg-sky-400/90 shadow-[0_0_12px_rgba(56,189,248,0.85)] animate-pulse z-20"
                    />
                  )}

                  {/* EXACT SINGLE-LINE CAPSULE / EDGE-DOCKED FLOATING ORB HUD (`QuotaHudView.kt` Spec — Foreground Only) */}
                  {isGeminiForeground && (
                    <div
                      ref={hudCapsuleRef}
                      onPointerDown={onHudPointerDown}
                      onPointerMove={onHudPointerMove}
                      onPointerUp={onHudPointerUp}
                      onPointerCancel={onHudPointerUp}
                      title={
                        hudDockedEdge !== 'NONE'
                          ? isZh
                            ? '点击悬浮球展开胶囊，或拖拽至左右边缘贴边切换'
                            : 'Tap floating orb to expand capsule, or drag to left/right edge'
                          : isZh
                          ? '轻点胶囊触发波纹并手动刷新，或拖拽至左右边缘贴边收起'
                          : 'Tap capsule for ripple & manual refresh, or drag to left/right edge'
                      }
                      className={`absolute top-12 left-0 border text-[11px] leading-none whitespace-nowrap cursor-grab active:cursor-grabbing touch-none will-change-transform transition-colors ${
                        hudDockedEdge !== 'NONE'
                          ? `w-11 h-11 p-0.5 flex items-center justify-center ${
                              hudDockedEdge === 'LEFT'
                                ? 'rounded-r-full rounded-l-xl border-l-2 border-l-sky-400/80'
                                : 'rounded-l-full rounded-r-xl border-r-2 border-r-sky-400/80'
                            }`
                          : `max-w-[96%] inline-flex flex-col items-stretch rounded-full ${
                              isStaleOffline ? 'px-3 py-1.5' : 'px-2.5 py-1.5'
                            }`
                      } ${capsuleContainerClass}`}
                    >
                      {/* Clipped radial tap ripple layer */}
                      <span
                        aria-hidden="true"
                        className="pointer-events-none absolute inset-0 rounded-full overflow-hidden"
                      >
                        {hudTapRipple && (
                          <span
                            key={hudTapRipple.id}
                            ref={hudRippleRef}
                            className={`absolute rounded-full pointer-events-none animate-hud-tap-ripple ${
                              hudTheme === 'MD3'
                                ? simDarkTheme
                                  ? 'bg-violet-300/40'
                                  : 'bg-violet-600/25'
                                : simDarkTheme
                                ? 'bg-sky-300/40'
                                : 'bg-sky-500/30'
                            }`}
                          />
                        )}
                      </span>

                      {hudDockedEdge !== 'NONE' ? (() => {
                        const pct5 = isSessionDeepCleaned ? 0 : activeSession.parsed.fiveHourPct;
                        const pctWk = isSessionDeepCleaned ? 0 : activeSession.parsed.weeklyPct;
                        const rOuter = 16;
                        const cOuter = 2 * Math.PI * rOuter;
                        const rInner = 12;
                        const cInner = 2 * Math.PI * rInner;
                        const ringStrokeClass = isStaleOffline
                          ? 'stroke-slate-400'
                          : pct5 >= 90
                          ? simDarkTheme
                            ? 'stroke-red-400'
                            : 'stroke-red-500'
                          : pct5 >= 75
                          ? simDarkTheme
                            ? 'stroke-amber-400'
                            : 'stroke-amber-500'
                          : hudTheme === 'MD3'
                          ? simDarkTheme
                            ? 'stroke-violet-300'
                            : 'stroke-violet-600'
                          : simDarkTheme
                          ? 'stroke-sky-400'
                          : 'stroke-blue-500';
                        const trackStrokeClass =
                          hudTheme === 'MIUIX'
                            ? simDarkTheme
                              ? 'stroke-white/15'
                              : 'stroke-slate-900/12'
                            : simDarkTheme
                            ? 'stroke-[#4A4458]'
                            : 'stroke-[#E8DEF8]';
                        return (
                          <div className="relative w-9 h-9 flex items-center justify-center">
                            <svg viewBox="0 0 38 38" className="w-9 h-9 -rotate-90">
                              <circle
                                cx="19"
                                cy="19"
                                r={rOuter}
                                fill="none"
                                strokeWidth="3"
                                className={trackStrokeClass}
                              />
                              {pct5 > 0 && (
                                <circle
                                  cx="19"
                                  cy="19"
                                  r={rOuter}
                                  fill="none"
                                  strokeWidth="3"
                                  strokeLinecap="round"
                                  strokeDasharray={cOuter}
                                  strokeDashoffset={cOuter * (1 - pct5 / 100)}
                                  className={ringStrokeClass}
                                />
                              )}
                              {pctWk > 0 && (
                                <circle
                                  cx="19"
                                  cy="19"
                                  r={rInner}
                                  fill="none"
                                  strokeWidth="1.5"
                                  strokeLinecap="round"
                                  strokeDasharray={cInner}
                                  strokeDashoffset={cInner * (1 - pctWk / 100)}
                                  className="stroke-teal-400/70"
                                />
                              )}
                            </svg>
                            <span className="absolute inset-0 flex items-center justify-center text-[9.5px] font-bold tabular-nums leading-none">
                              {isSessionDeepCleaned ? '--' : activeSession.parsed.fiveHourQuota}
                            </span>
                            {isStaleOffline && (
                              <span
                                className={`absolute top-0 ${
                                  hudDockedEdge === 'LEFT' ? 'right-0' : 'left-0'
                                } w-2 h-2 rounded-full bg-orange-400 animate-pulse`}
                              />
                            )}
                          </div>
                        );
                      })() : (
                      <>
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
                      </>
                      )}
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
                      {highUsageTrendAlert && (
                        <span className="inline-flex items-center gap-1 text-[10px] font-mono font-semibold px-1.5 py-0.5 rounded bg-rose-500/20 text-rose-300 border border-rose-500/50 animate-pulse">
                          <TrendingUp className="w-3 h-3" />
                          <span>
                            High Usage Trend ({highUsageTrendAlert.consecutiveIncreases}x / 5m)
                          </span>
                        </span>
                      )}
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
                  {/* Developer Console Automated High Usage Trend Notification & Recent Trigger History */}
                  {(highUsageTrendAlert || highUsageTrendOccurrences.length > 0) && (
                    <div
                      role="alert"
                      className={`rounded-xl border px-3.5 py-2.5 space-y-2.5 shadow-sm ${
                        highUsageTrendAlert
                          ? 'border-rose-500/55 bg-gradient-to-r from-rose-950/75 via-red-950/60 to-amber-950/50'
                          : 'border-rose-500/30 bg-slate-950/85'
                      }`}
                    >
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div className="flex items-start gap-2.5 min-w-0">
                          <div
                            className={`w-7 h-7 rounded-lg border flex items-center justify-center shrink-0 mt-0.5 ${
                              highUsageTrendAlert
                                ? 'bg-rose-500/20 border-rose-500/40 text-rose-300'
                                : 'bg-rose-500/10 border-rose-500/25 text-rose-300/80'
                            }`}
                          >
                            <TrendingUp className="w-4 h-4" />
                          </div>
                          <div className="space-y-0.5 font-mono min-w-0">
                            <div className="text-xs font-bold text-rose-200 flex flex-wrap items-center gap-2">
                              <span>
                                {highUsageTrendAlert
                                  ? 'High Usage Trend'
                                  : isZh
                                    ? 'High Usage Trend · 近期触发历史'
                                    : 'High Usage Trend · Recent Alert History'}
                              </span>
                              {highUsageTrendAlert ? (
                                <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded bg-rose-500/20 text-rose-200 border border-rose-400/40">
                                  {isZh
                                    ? `5分钟窗口连续上升 ${highUsageTrendAlert.consecutiveIncreases} 次`
                                    : `${highUsageTrendAlert.consecutiveIncreases} consecutive increases in 5m window`}
                                </span>
                              ) : (
                                <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 border border-slate-700">
                                  {isZh
                                    ? `已记录 ${highUsageTrendOccurrences.length} 次触发`
                                    : `${highUsageTrendOccurrences.length} recorded trigger${highUsageTrendOccurrences.length === 1 ? '' : 's'}`}
                                </span>
                              )}
                              {highUsageTrendAlert && trendAutoDismissEnabled && trendAlertRemainingSec !== null && (
                                <span className="inline-flex items-center gap-1 text-[10px] font-semibold px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-200 border border-amber-400/40 tabular-nums">
                                  <Clock className="w-3 h-3" />
                                  <span>
                                    {isZh
                                      ? `${trendAlertRemainingSec}s 后自动消失`
                                      : `Auto-dismiss in ${trendAlertRemainingSec}s`}
                                  </span>
                                </span>
                              )}
                            </div>
                            <div className="text-[11px] text-rose-100/90 break-words">
                              {highUsageTrendAlert
                                ? isZh
                                  ? `${highUsageTrendAlert.timeMs} · [${highUsageTrendAlert.sessionTag}] Energy Impact Score 在 ${highUsageTrendAlert.windowSec}s 内持续递增：${highUsageTrendAlert.startScore} -> ${highUsageTrendAlert.curScore} (+${highUsageTrendAlert.delta.toFixed(2)} 分)`
                                  : `${highUsageTrendAlert.timeMs} · [${highUsageTrendAlert.sessionTag}] Consecutive Battery Impact Score climb over ${highUsageTrendAlert.windowSec}s: ${highUsageTrendAlert.startScore} -> ${highUsageTrendAlert.curScore} (+${highUsageTrendAlert.delta.toFixed(2)} pts)`
                                : isZh
                                  ? '当前实时告警已静默或自动消失；下方保留最近触发记录以便追溯。'
                                  : 'Active alert banner dismissed; recent High Usage Trend trigger occurrences remain listed below.'}
                            </div>
                          </div>
                        </div>
                        <div className="flex flex-wrap items-center gap-2 shrink-0">
                          {highUsageTrendAlert && highUsageTrendAlert.scores.length > 0 && (
                            <TrendScoreSparkline
                              points={highUsageTrendAlert.scores}
                              active
                              isZh={isZh}
                            />
                          )}
                          <button
                            type="button"
                            onClick={() => setTrendSoundEnabled((v) => !v)}
                            title={
                              isZh
                                ? trendSoundEnabled
                                  ? '点击静音 High Usage Trend 提示音'
                                  : '点击开启 High Usage Trend 提示音'
                                : trendSoundEnabled
                                  ? 'Mute High Usage Trend chime'
                                  : 'Unmute High Usage Trend chime'
                            }
                            className={`inline-flex items-center gap-1 px-2 py-1 text-[10px] font-medium rounded-md border shrink-0 cursor-pointer transition-colors ${
                              trendSoundEnabled
                                ? 'bg-emerald-950/60 hover:bg-emerald-900/70 text-emerald-200 border-emerald-700/60'
                                : 'bg-slate-900/70 hover:bg-slate-800 text-slate-300 border-slate-700/70'
                            }`}
                          >
                            {trendSoundEnabled ? (
                              <Volume2 className="w-3 h-3" />
                            ) : (
                              <VolumeX className="w-3 h-3" />
                            )}
                            <span>{trendSoundEnabled ? (isZh ? '提示音开' : 'Sound On') : (isZh ? '已静音' : 'Muted')}</span>
                          </button>
                          <button
                            type="button"
                            onClick={handleResetTrendAlertHistory}
                            title={
                              isZh
                                ? '清空连升计数及触发历史并静默当前告警（不修改全局阈值）'
                                : 'Clear streak counter and occurrence history without modifying global thresholds'
                            }
                            className="inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-medium rounded-md bg-amber-900/65 hover:bg-amber-800/80 text-amber-100 border border-amber-600/60 shrink-0 cursor-pointer transition-colors"
                          >
                            <RotateCcw className="w-3 h-3" />
                            <span>{isZh ? '重置告警历史' : 'Reset Alert History'}</span>
                          </button>
                          {highUsageTrendAlert && (
                            <button
                              type="button"
                              onClick={() => setHighUsageTrendAlert(null)}
                              className="px-2.5 py-1 text-[10px] font-medium rounded-md bg-rose-900/60 hover:bg-rose-800/80 text-rose-100 border border-rose-700/60 shrink-0 cursor-pointer transition-colors"
                            >
                              {isZh ? '确认忽略' : 'Dismiss'}
                            </button>
                          )}
                        </div>
                      </div>

                      {highUsageTrendOccurrences.length > 0 && (
                        <div className="pt-2 border-t border-rose-500/25 space-y-1.5">
                          <div className="flex flex-wrap items-center justify-between gap-2 text-[10px] font-mono">
                            <button
                              type="button"
                              onClick={() => setTrendOccurrencesExpanded((v) => !v)}
                              className="inline-flex items-center gap-1.5 text-rose-200/90 hover:text-rose-100 font-semibold cursor-pointer"
                            >
                              {trendOccurrencesExpanded ? (
                                <ChevronDown className="w-3 h-3" />
                              ) : (
                                <ChevronRight className="w-3 h-3" />
                              )}
                              <span>
                                {isZh
                                  ? trendOccurrenceTimeRange === 'ALL'
                                    ? `最近触发历史 (${highUsageTrendOccurrences.length})`
                                    : `最近触发历史 (${filteredTrendOccurrences.length}/${highUsageTrendOccurrences.length})`
                                  : trendOccurrenceTimeRange === 'ALL'
                                    ? `Recent Alert Occurrences (${highUsageTrendOccurrences.length})`
                                    : `Recent Alert Occurrences (${filteredTrendOccurrences.length}/${highUsageTrendOccurrences.length})`}
                              </span>
                            </button>
                            <div className="flex flex-wrap items-center gap-2">
                              <label className="inline-flex items-center gap-1.5 text-[9.5px] text-slate-300">
                                <span className="text-slate-400">
                                  {isZh ? '时间范围:' : 'Time Range:'}
                                </span>
                                <select
                                  aria-label={isZh ? '触发历史时间范围筛选' : 'High Usage Trend Occurrence Time Range Filter'}
                                  value={trendOccurrenceTimeRange}
                                  onChange={(e) =>
                                    setTrendOccurrenceTimeRange(
                                      e.target.value as '15M' | '1H' | '6H' | '24H' | 'ALL'
                                    )
                                  }
                                  className="rounded border border-rose-500/35 bg-slate-900 px-1.5 py-0.5 text-[10px] font-mono text-rose-100 focus:outline-none focus:border-rose-400 cursor-pointer"
                                >
                                  <option value="15M">{isZh ? '最近 15 分钟' : 'Last 15m'}</option>
                                  <option value="1H">{isZh ? '最近 1 小时' : 'Last 1h'}</option>
                                  <option value="6H">{isZh ? '最近 6 小时' : 'Last 6h'}</option>
                                  <option value="24H">{isZh ? '最近 24 小时' : 'Last 24h'}</option>
                                  <option value="ALL">{isZh ? '全部时间' : 'All Time'}</option>
                                </select>
                              </label>
                              <span className="text-[9.5px] text-slate-400 hidden sm:inline">
                                {isZh ? '按时间倒序 · 最多 20 条' : 'Newest first · Max 20'}
                              </span>
                            </div>
                          </div>

                          {trendOccurrencesExpanded && (
                            <div className="max-h-36 overflow-y-auto space-y-1 pr-1">
                              {filteredTrendOccurrences.length === 0 ? (
                                <div className="rounded-lg border border-slate-800 bg-slate-950/75 px-3 py-2 text-[10px] font-mono text-slate-400 flex flex-wrap items-center justify-between gap-2">
                                  <span>
                                    {isZh
                                      ? `所选时间范围内暂无 High Usage Trend 触发记录（历史共 ${highUsageTrendOccurrences.length} 条）。`
                                      : `No High Usage Trend occurrences in selected window (${highUsageTrendOccurrences.length} total recorded).`}
                                  </span>
                                  <button
                                    type="button"
                                    onClick={() => setTrendOccurrenceTimeRange('ALL')}
                                    className="px-2 py-0.5 rounded bg-slate-800 hover:bg-slate-700 text-rose-200 border border-slate-700 cursor-pointer"
                                  >
                                    {isZh ? '显示全部时间' : 'Show All Time'}
                                  </button>
                                </div>
                              ) : (
                                filteredTrendOccurrences.map((occ) => {
                                  const isCurrentLatest =
                                    Boolean(highUsageTrendAlert) &&
                                    occ.triggeredAtMs === highUsageTrendAlert?.triggeredAtMs &&
                                    occ.id === highUsageTrendOccurrences[0]?.id;
                                  return (
                                    <div
                                      key={occ.id}
                                      className={`rounded-lg border px-2.5 py-1.5 font-mono text-[10px] flex flex-wrap items-center justify-between gap-2 ${
                                        isCurrentLatest
                                          ? 'border-rose-400/45 bg-rose-950/60 text-rose-100'
                                          : 'border-slate-800 bg-slate-950/75 text-slate-300'
                                      }`}
                                    >
                                      <div className="flex flex-wrap items-center gap-2 min-w-0">
                                        <span className="text-slate-400 tabular-nums shrink-0">
                                          {occ.timeMs}
                                        </span>
                                        <span
                                          className={`px-1.5 py-0.2 rounded border font-semibold shrink-0 ${
                                            isCurrentLatest
                                              ? 'bg-rose-500/25 border-rose-400/50 text-rose-200'
                                              : 'bg-slate-800 border-slate-700 text-rose-300'
                                          }`}
                                        >
                                          {occ.consecutiveIncreases}x &gt;= {occ.thresholdAtTrigger}x
                                        </span>
                                        <span className="text-amber-300 tabular-nums shrink-0">
                                          {occ.startScore} -&gt; {occ.curScore} (+{occ.delta.toFixed(2)})
                                        </span>
                                        <span className="text-slate-400 shrink-0">
                                          {occ.windowSec}s · {occ.sessionTag}
                                        </span>
                                        {occ.events.length > 0 && (
                                          <span className="text-slate-500 truncate max-w-[220px]">
                                            [{occ.events[occ.events.length - 1]}]
                                          </span>
                                        )}
                                      </div>
                                      <div className="flex items-center gap-2 shrink-0">
                                        {occ.scores.length > 0 && (
                                          <TrendScoreSparkline
                                            points={occ.scores}
                                            compact
                                            active={isCurrentLatest}
                                            isZh={isZh}
                                          />
                                        )}
                                        <span
                                          className={`text-[9px] px-1.5 py-0.5 rounded shrink-0 ${
                                            isCurrentLatest
                                              ? 'bg-rose-500/25 text-rose-200 border border-rose-400/40'
                                              : 'bg-slate-900 text-slate-400 border border-slate-800'
                                          }`}
                                        >
                                          {isCurrentLatest
                                            ? isZh ? '当前激活' : 'ACTIVE'
                                            : isZh ? '历史记录' : 'HISTORY'}
                                        </span>
                                      </div>
                                    </div>
                                  );
                                })
                              )}
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  )}

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
                          setEnergyCpuMs(0);
                          setEnergyWallMs(0);
                          setEnergyHookEvents(0);
                          setEnergyNetReqs(0);
                          setEnergyNetBytes(0);
                          setEnergyBgSuppressed(0);
                          skipNextEnergyHistoryPushRef.current = true;
                          heartbeatBaselineScoreRef.current = 0;
                          surgeAlertedInCycleRef.current = false;
                          energyMaWindowRef.current = [0];
                          setSmoothedEnergyScore(0);
                          const resetTs = Date.now();
                          const resetSamples = [{ ts: resetTs, score: 0, event: 'RESET' }];
                          energyTrendWindowRef.current = resetSamples;
                          setHighUsageTrendChartHistory(
                            buildTrendWindowChartSeries(resetSamples, trendConsecutiveThresholdRef.current)
                          );
                          highUsageTrendAlertedStreakRef.current = 0;
                          setEnergySurgeAlert(null);
                          setHighUsageTrendAlert(null);
                          setHighUsageTrendOccurrences([]);
                          const d = new Date();
                          const pad = (n: number) => String(n).padStart(2, '0');
                          const resetTimeStr = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
                          fullEnergyTimelineRef.current = [
                            {
                              index: 1,
                              timestampIso: d.toISOString(),
                              timeHms: resetTimeStr,
                              sessionTag: activeSessionRef.current.sessionTag,
                              event: 'RESET',
                              maWindowSize: ENERGY_MA_WINDOW_SIZE,
                              filteredScore: 0,
                              rawScore: 0,
                              scoreDelta: 0,
                              spikeBypass: false,
                              aboveHighThreshold: false,
                              cpuMs: 0,
                              wallMs: 0,
                              fgDispatches: 0,
                              netReqs: 0,
                              estimatedMah: 0,
                            },
                          ];
                          setEnergyTimelineCount(1);
                          setEnergyScoreHistory([
                            {
                              time: resetTimeStr,
                              score: 0,
                              rawScore: 0,
                              spikeBypass: false,
                              cpuMs: 0,
                              event: 'RESET',
                            },
                          ]);
                          setExportedBatteryReport('');
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

                  {/* Battery Impact & Energy Statistics Section */}
                  <div className="rounded-xl border border-teal-500/30 bg-teal-950/20 px-3.5 py-3 space-y-2.5">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <BatteryCharging className="w-4 h-4 text-teal-400" />
                        <span className="text-xs font-semibold text-slate-100">
                          {isZh ? '电池消耗影响 · Battery Impact' : 'Battery Impact'}
                        </span>
                        <span
                          className={`text-[10px] font-mono px-1.5 py-0.5 rounded border ${
                            isHighBatteryImpact
                              ? 'bg-amber-500/20 text-amber-300 border-amber-500/40'
                              : 'bg-teal-500/15 text-teal-300 border-teal-500/30'
                          }`}
                        >
                          Energy Impact: {energyImpactRating} · MA({ENERGY_MA_WINDOW_SIZE})={smoothedEnergyScore} (Raw {energyImpactScore})
                        </span>
                        {isHighBatteryImpact && hudTheme === 'MIUIX' && (
                          <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-cyan-500/20 text-cyan-200 border border-cyan-500/40 animate-pulse">
                            {isZh ? 'MIUIX 毛玻璃呼吸动画已激活' : 'MIUIX Blur Animation Active'}
                          </span>
                        )}
                      </div>

                      <div className="flex flex-wrap items-center gap-2">
                        <button
                          type="button"
                          onClick={handleSimulateHighBatteryImpact}
                          className="inline-flex items-center gap-1 px-2.5 py-1 text-[11px] rounded bg-amber-950/60 hover:bg-amber-900/60 text-amber-200 border border-amber-700/60 transition-colors cursor-pointer"
                        >
                          <span>
                            {isZh
                              ? `模拟高能耗 (>=${highBatteryImpactThreshold})`
                              : `Simulate High Load (>=${highBatteryImpactThreshold})`}
                          </span>
                        </button>

                        <button
                          type="button"
                          onClick={handleSimulateEnergyJitterTest}
                          className="inline-flex items-center gap-1 px-2.5 py-1 text-[11px] rounded bg-cyan-950/60 hover:bg-cyan-900/60 text-cyan-200 border border-cyan-700/60 transition-colors cursor-pointer"
                        >
                          <span>{isZh ? '抖动滤波测试' : 'Jitter Filter Test'}</span>
                        </button>

                        <button
                          type="button"
                          onClick={handleSimulateHighUsageTrend}
                          className="inline-flex items-center gap-1 px-2.5 py-1 text-[11px] rounded bg-rose-950/60 hover:bg-rose-900/60 text-rose-200 border border-rose-700/60 transition-colors cursor-pointer"
                        >
                          <TrendingUp className="w-3 h-3" />
                          <span>{isZh ? '模拟 5m 连续上升趋势' : 'Simulate 5m Trend'}</span>
                        </button>

                        <button
                          type="button"
                          onClick={() => triggerSilentHeartbeatGetUserQuotaReplay()}
                          disabled={!isGeminiForeground}
                          className="inline-flex items-center gap-1 px-2.5 py-1 text-[11px] rounded bg-slate-800 hover:bg-slate-700 disabled:opacity-40 disabled:cursor-not-allowed text-slate-200 border border-slate-700 transition-colors cursor-pointer"
                        >
                          <span>{isZh ? '心跳周期检测' : 'Heartbeat Check'}</span>
                        </button>

                        <button
                          type="button"
                          onClick={() => setShowEnergyImpactStats((v) => !v)}
                          className={`inline-flex items-center gap-1.5 px-2.5 py-1 text-[11px] rounded border transition-colors cursor-pointer ${
                            showEnergyImpactStats
                              ? 'bg-teal-500/20 text-teal-200 border-teal-500/40 font-semibold'
                              : 'bg-slate-800 text-slate-300 border-slate-700 hover:bg-slate-700'
                          }`}
                        >
                          <span>
                            {isZh
                              ? showEnergyImpactStats
                                ? '隐藏 Energy Impact 统计'
                                : '显示 Energy Impact 统计'
                              : showEnergyImpactStats
                              ? 'Hide Energy Impact'
                              : 'Show Energy Impact'}
                          </span>
                        </button>

                        <button
                          type="button"
                          onClick={handleExportBatteryReport}
                          className="inline-flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-semibold rounded bg-teal-600 hover:bg-teal-500 text-white border border-teal-400/40 transition-colors cursor-pointer"
                        >
                          <FileText className="w-3 h-3" />
                          <span>Export Report</span>
                        </button>

                        <button
                          type="button"
                          onClick={handleDownloadHistoricalImpactCsv}
                          className="inline-flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-semibold rounded bg-emerald-700 hover:bg-emerald-600 text-white border border-emerald-400/40 transition-colors cursor-pointer"
                          title={
                            isZh
                              ? `下载完整历史能耗时间线 CSV（${energyTimelineCount} 条样本，含 Raw 值与 Spike Bypass 事件）`
                              : `Download complete energy impact timeline CSV (${energyTimelineCount} samples including raw scores & spike bypass events)`
                          }
                        >
                          <Download className="w-3 h-3" />
                          <span>Historical Impact CSV ({energyTimelineCount})</span>
                        </button>
                      </div>
                    </div>

                    {/* Dynamic High Battery Impact Threshold Settings Slider */}
                    <div className="rounded-lg border border-teal-500/25 bg-slate-950/65 px-3 py-2 space-y-1.5">
                      <div className="flex flex-wrap items-center justify-between gap-2 text-[11px]">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-semibold text-teal-200">
                            {isZh
                              ? '高能耗告警阈值设置 (High Battery Impact Threshold)'
                              : 'High Battery Impact Threshold'}
                          </span>
                          <span
                            className={`text-[10px] font-mono px-1.5 py-0.5 rounded border ${
                              isHighBatteryImpact
                                ? 'bg-amber-500/20 text-amber-300 border-amber-500/40'
                                : 'bg-slate-800 text-slate-300 border-slate-700'
                            }`}
                          >
                            {isHighBatteryImpact
                              ? isZh
                                ? `已超过阈值 (${energyImpactScore} >= ${highBatteryImpactThreshold})`
                                : `ALERT ACTIVE (${energyImpactScore} >= ${highBatteryImpactThreshold})`
                              : isZh
                              ? `监控中 (${energyImpactScore} < ${highBatteryImpactThreshold})`
                              : `MONITORING (${energyImpactScore} < ${highBatteryImpactThreshold})`}
                          </span>
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="font-mono text-[11px] font-semibold text-amber-300">
                            &gt;= {highBatteryImpactThreshold} pts
                          </span>
                          {highBatteryImpactThreshold !== DEFAULT_HIGH_BATTERY_IMPACT_THRESHOLD && (
                            <button
                              type="button"
                              onClick={() => setHighBatteryImpactThreshold(DEFAULT_HIGH_BATTERY_IMPACT_THRESHOLD)}
                              className="px-1.5 py-0.5 rounded text-[10px] font-mono bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700 transition-colors cursor-pointer"
                            >
                              {isZh
                                ? `重置 (${DEFAULT_HIGH_BATTERY_IMPACT_THRESHOLD})`
                                : `Reset (${DEFAULT_HIGH_BATTERY_IMPACT_THRESHOLD})`}
                            </button>
                          )}
                        </div>
                      </div>
                      <div className="flex items-center gap-3">
                        <span className="text-[10px] font-mono text-slate-500 shrink-0">{HIGH_BATTERY_THRESHOLD_MIN}</span>
                        <input
                          type="range"
                          min={HIGH_BATTERY_THRESHOLD_MIN}
                          max={HIGH_BATTERY_THRESHOLD_MAX}
                          step={1}
                          value={highBatteryImpactThreshold}
                          onChange={(e) =>
                            setHighBatteryImpactThreshold(clampHighBatteryThreshold(Number(e.target.value)))
                          }
                          aria-label="High Battery Impact Threshold"
                          className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-amber-400"
                        />
                        <span className="text-[10px] font-mono text-slate-500 shrink-0">{HIGH_BATTERY_THRESHOLD_MAX}</span>
                      </div>
                      <div className="text-[10px] text-slate-400 font-mono">
                        {isZh
                          ? '已自动持久化至 localStorage（刷新保留）；实时联动警戒参考线、Spike Bypass、高能耗告警及 MIUIX 呼吸模糊动画'
                          : 'Persisted automatically to localStorage across refreshes; updates chart line, spike bypass, alerts & MIUIX blur live'}
                      </div>
                    </div>

                    {energySurgeAlert && (
                      <div
                        role="alert"
                        className="rounded-lg border border-amber-500/50 bg-amber-950/50 px-3 py-2 flex flex-wrap items-start justify-between gap-2 text-[11px]"
                      >
                        <div className="flex items-start gap-2 min-w-0">
                          <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
                          <div className="space-y-0.5 font-mono">
                            <div className="font-semibold text-amber-200">
                              {isZh
                                ? `能耗突增告警 (Console WARN) · 单心跳周期内上升 +${energySurgeAlert.delta.toFixed(2)} 分 (> 15)`
                                : `Energy Surge Notification (Console WARN) · +${energySurgeAlert.delta.toFixed(2)} pts in 1 Heartbeat Cycle (> 15)`}
                            </div>
                            <div className="text-[10px] text-amber-300/90">
                              {energySurgeAlert.timeMs} · [{energySurgeAlert.sessionTag}] {energySurgeAlert.prevScore} {'->'} {energySurgeAlert.curScore} ({energySurgeAlert.cycleReason})
                            </div>
                          </div>
                        </div>
                        <button
                          type="button"
                          onClick={() => setEnergySurgeAlert(null)}
                          className="px-2 py-0.5 text-[10px] rounded bg-amber-900/60 hover:bg-amber-800/70 text-amber-200 border border-amber-700/60 shrink-0 cursor-pointer"
                        >
                          {isZh ? '忽略' : 'Dismiss'}
                        </button>
                      </div>
                    )}

                    {showEnergyImpactStats && (
                      <div className="space-y-2.5">
                        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-[11px] font-mono">
                          <div className="rounded-lg bg-slate-950/70 border border-slate-800 px-2.5 py-1.5">
                            <div className="text-[10px] text-slate-400">
                              {isZh ? '后台唤醒 / 门控拦截' : 'BG Wakeups / Gated'}
                            </div>
                            <div className="text-emerald-300 font-semibold mt-0.5">
                              {bgWakeupCount} / {energyBgSuppressed} skipped
                            </div>
                          </div>

                          <div className="rounded-lg bg-slate-950/70 border border-slate-800 px-2.5 py-1.5">
                            <div className="text-[10px] text-slate-400">
                              {isZh ? 'CPU / 墙钟耗时' : 'CPU / Wall Time'}
                            </div>
                            <div className="text-teal-200 font-semibold mt-0.5">
                              {energyCpuMs.toFixed(1)}ms / {energyWallMs.toFixed(1)}ms
                            </div>
                          </div>

                          <div className="rounded-lg bg-slate-950/70 border border-slate-800 px-2.5 py-1.5">
                            <div className="text-[10px] text-slate-400">
                              {isZh ? '前台派发 / Hook 事件' : 'FG Dispatch / Hooks'}
                            </div>
                            <div className="text-sky-300 font-semibold mt-0.5">
                              {fgDispatchCount} / {energyHookEvents}
                            </div>
                          </div>

                          <div className="rounded-lg bg-slate-950/70 border border-slate-800 px-2.5 py-1.5">
                            <div className="text-[10px] text-slate-400">
                              {isZh ? '网络负载 / 估算耗电' : 'Net Transfer / Est. Draw'}
                            </div>
                            <div className="text-amber-300 font-semibold mt-0.5">
                              {energyNetReqs}r · ~{estimatedEnergyMah.toFixed(4)}mAh
                            </div>
                          </div>
                        </div>

                        {/* Dual Time-Series Charts: Primary Energy Impact (MA) + Secondary 5-Min Rolling High Usage Trend */}
                        <div className="grid grid-cols-1 xl:grid-cols-2 gap-2.5">
                          <div className="rounded-lg bg-slate-950/80 border border-slate-800/90 p-3 space-y-2">
                            <div className="flex flex-wrap items-center justify-between gap-2 text-[11px]">
                              <span className="font-mono font-semibold text-teal-300">
                                {isZh
                                  ? `Energy Impact Score 移动平均滤波曲线 · MA(${ENERGY_MA_WINDOW_SIZE})`
                                  : `Energy Impact Score · Moving Average Filter MA(${ENERGY_MA_WINDOW_SIZE})`}
                              </span>
                              <div className="flex flex-wrap items-center gap-3 text-[10px] font-mono text-slate-400">
                                <span className="inline-flex items-center gap-1">
                                  <span className="w-2.5 h-0.5 bg-teal-400" />
                                  {isZh ? '滤波平滑曲线 (MA)' : 'Filtered (MA)'}
                                </span>
                                <span className="inline-flex items-center gap-1">
                                  <span className="w-2.5 h-0 border-t border-dashed border-slate-400" />
                                  {isZh ? '原始采样 (Raw)' : 'Raw Sample'}
                                </span>
                                <span className="inline-flex items-center gap-1">
                                  <span className="w-2 h-2 rounded-full bg-amber-400" />
                                  {isZh ? `尖峰直通 (>=${highBatteryImpactThreshold})` : `Spike Bypass (>=${highBatteryImpactThreshold})`}
                                </span>
                              </div>
                            </div>

                            <div className="w-full h-44">
                              <ResponsiveContainer width="100%" height="100%">
                                <LineChart
                                  data={energyScoreHistory}
                                  margin={{ top: 8, right: 14, left: -14, bottom: 2 }}
                                >
                                  <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" />
                                  <XAxis
                                    dataKey="time"
                                    tick={{ fill: '#64748b', fontSize: 10 }}
                                    stroke="#334155"
                                  />
                                  <YAxis
                                    domain={[
                                      0,
                                      (dataMax: number) => Math.max(highBatteryImpactThreshold + 5, 25, Math.ceil(dataMax * 1.15)),
                                    ]}
                                    tick={{ fill: '#64748b', fontSize: 10 }}
                                    stroke="#334155"
                                  />
                                  <Tooltip
                                    content={({ active, payload, label }) => {
                                      if (!active || !payload || payload.length === 0) return null;
                                      const pt = payload[0]?.payload as
                                        | { time: string; score: number; rawScore: number; spikeBypass: boolean; cpuMs: number; event: string }
                                        | undefined;
                                      if (!pt) return null;
                                      const spike = pt.score >= highBatteryImpactThreshold || pt.spikeBypass;
                                      return (
                                        <div className="rounded-lg border border-slate-700 bg-slate-950 px-2.5 py-1.5 text-[11px] font-mono shadow-lg">
                                          <div className="text-slate-400">
                                            {label} · {pt.event}
                                          </div>
                                          <div className={spike ? 'text-amber-300 font-bold' : 'text-teal-300 font-semibold'}>
                                            MA({ENERGY_MA_WINDOW_SIZE}) Score: {pt.score} {pt.spikeBypass ? '(SPIKE BYPASS)' : ''}
                                          </div>
                                          <div className="text-slate-400 text-[10px]">
                                            Raw: {pt.rawScore} · CPU: {pt.cpuMs.toFixed(1)} ms
                                          </div>
                                        </div>
                                      );
                                    }}
                                  />
                                  <ReferenceLine
                                    y={highBatteryImpactThreshold}
                                    stroke="#f59e0b"
                                    strokeDasharray="4 4"
                                    label={{
                                      value: `High >= ${highBatteryImpactThreshold}`,
                                      position: 'insideTopRight',
                                      fill: '#fbbf24',
                                      fontSize: 10,
                                    }}
                                  />
                                  <Line
                                    type="linear"
                                    dataKey="rawScore"
                                    name={isZh ? '原始采样' : 'Raw Score'}
                                    stroke="#64748b"
                                    strokeWidth={1.25}
                                    strokeDasharray="3 3"
                                    dot={false}
                                    isAnimationActive={false}
                                  />
                                  <Line
                                    type="monotone"
                                    dataKey="score"
                                    name={isZh ? `移动平均 MA(${ENERGY_MA_WINDOW_SIZE})` : `Moving Avg MA(${ENERGY_MA_WINDOW_SIZE})`}
                                    stroke={isHighBatteryImpact ? '#f59e0b' : '#2dd4bf'}
                                    strokeWidth={2.4}
                                    isAnimationActive={false}
                                    dot={(dotProps) => {
                                      const { cx, cy, index, payload } = dotProps;
                                      if (typeof cx !== 'number' || typeof cy !== 'number') {
                                        return <g key={`empty-${index ?? 0}`} />;
                                      }
                                      const pt = payload as { score?: number; spikeBypass?: boolean } | undefined;
                                      const isSpike = (pt?.score ?? 0) >= highBatteryImpactThreshold || Boolean(pt?.spikeBypass);
                                      return (
                                        <circle
                                          key={`dot-${index ?? cx}`}
                                          cx={cx}
                                          cy={cy}
                                          r={isSpike ? 4.5 : 3}
                                          fill={isSpike ? '#f59e0b' : '#2dd4bf'}
                                          stroke="#0f172a"
                                          strokeWidth={1.5}
                                        />
                                      );
                                    }}
                                    activeDot={{ r: 5.5, fill: '#fbbf24', stroke: '#0f172a', strokeWidth: 1.5 }}
                                  />
                                </LineChart>
                              </ResponsiveContainer>
                            </div>
                          </div>

                          {/* Secondary Line Chart: 5-Minute Rolling Window High Usage Trend Streak */}
                          {(() => {
                            const latestTrendPoint =
                              highUsageTrendChartHistory[highUsageTrendChartHistory.length - 1];
                            const currentStreak = latestTrendPoint?.streak ?? 0;
                            const isStreakAlert = currentStreak >= trendConsecutiveThreshold;
                            return (
                              <div className="rounded-lg bg-slate-950/80 border border-rose-500/25 p-3 space-y-2">
                                <div className="flex flex-wrap items-center justify-between gap-2 text-[11px]">
                                  <div className="flex items-center gap-2">
                                    <span className="font-mono font-semibold text-rose-300">
                                      {isZh
                                        ? 'High Usage Trend · 5分钟滑动窗口连续上升'
                                        : 'High Usage Trend · 5m Rolling Streak'}
                                    </span>
                                    <span
                                      className={`text-[10px] font-mono px-1.5 py-0.5 rounded border ${
                                        isStreakAlert
                                          ? 'bg-rose-500/20 text-rose-200 border-rose-400/50 font-semibold'
                                          : 'bg-slate-800 text-slate-300 border-slate-700'
                                      }`}
                                    >
                                      {isZh
                                        ? `连升 ${currentStreak}/${trendConsecutiveThreshold} 次`
                                        : `Streak ${currentStreak}/${trendConsecutiveThreshold}x`}
                                    </span>
                                  </div>
                                  <div className="flex flex-wrap items-center gap-3 text-[10px] font-mono text-slate-400">
                                    <span className="inline-flex items-center gap-1">
                                      <span className="w-2.5 h-0.5 bg-rose-400" />
                                      {isZh ? '连续上升计数' : 'Increase Streak'}
                                    </span>
                                    <span className="inline-flex items-center gap-1">
                                      <span className="w-2.5 h-0 border-t border-dashed border-sky-400" />
                                      {isZh ? '原始功耗分' : 'Energy Score'}
                                    </span>
                                  </div>
                                </div>

                                <div className="w-full h-44">
                                  <ResponsiveContainer width="100%" height="100%">
                                    <LineChart
                                      data={highUsageTrendChartHistory}
                                      margin={{ top: 8, right: 4, left: -18, bottom: 2 }}
                                    >
                                      <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" />
                                      <XAxis
                                        dataKey="time"
                                        tick={{ fill: '#64748b', fontSize: 10 }}
                                        stroke="#334155"
                                      />
                                      <YAxis
                                        yAxisId="streak"
                                        allowDecimals={false}
                                        domain={[
                                          0,
                                          (dataMax: number) => Math.max(trendConsecutiveThreshold + 1, 4, Math.ceil(dataMax + 1)),
                                        ]}
                                        tick={{ fill: '#fda4af', fontSize: 10 }}
                                        stroke="#4c0519"
                                      />
                                      <YAxis
                                        yAxisId="score"
                                        orientation="right"
                                        domain={[0, (dataMax: number) => Math.max(10, Math.ceil(dataMax * 1.15))]}
                                        tick={{ fill: '#7dd3fc', fontSize: 10 }}
                                        stroke="#1e293b"
                                      />
                                      <Tooltip
                                        content={({ active, payload, label }) => {
                                          if (!active || !payload || payload.length === 0) return null;
                                          const pt = payload[0]?.payload as TrendWindowChartPoint | undefined;
                                          if (!pt) return null;
                                          return (
                                            <div className="rounded-lg border border-rose-500/40 bg-slate-950 px-2.5 py-1.5 text-[11px] font-mono shadow-lg">
                                              <div className="text-slate-400">
                                                {label} · {pt.event}
                                              </div>
                                              <div
                                                className={
                                                  pt.alertTriggered
                                                    ? 'text-rose-300 font-bold'
                                                    : 'text-rose-200 font-semibold'
                                                }
                                              >
                                                Consecutive Streak: {pt.streak}/{trendConsecutiveThreshold}x{' '}
                                                {pt.alertTriggered ? '(HIGH USAGE TREND)' : ''}
                                              </div>
                                              <div className="text-sky-300 text-[10px]">
                                                Energy Score: {pt.score} ({pt.deltaFromPrev >= 0 ? '+' : ''}
                                                {pt.deltaFromPrev.toFixed(2)} pts)
                                              </div>
                                            </div>
                                          );
                                        }}
                                      />
                                      <ReferenceLine
                                        yAxisId="streak"
                                        y={trendConsecutiveThreshold}
                                        stroke="#fb7185"
                                        strokeDasharray="4 4"
                                        label={{
                                          value: `Alert >= ${trendConsecutiveThreshold}x`,
                                          position: 'insideTopRight',
                                          fill: '#fda4af',
                                          fontSize: 10,
                                        }}
                                      />
                                      <Line
                                        yAxisId="score"
                                        type="monotone"
                                        dataKey="score"
                                        name={isZh ? '能耗评分' : 'Energy Score'}
                                        stroke="#38bdf8"
                                        strokeWidth={1.5}
                                        strokeDasharray="3 3"
                                        dot={false}
                                        isAnimationActive={false}
                                      />
                                      <Line
                                        yAxisId="streak"
                                        type="linear"
                                        dataKey="streak"
                                        name={isZh ? '连续上升次数' : 'Increase Streak'}
                                        stroke="#fb7185"
                                        strokeWidth={2.4}
                                        isAnimationActive={false}
                                        dot={(dotProps) => {
                                          const { cx, cy, index, payload } = dotProps;
                                          if (typeof cx !== 'number' || typeof cy !== 'number') {
                                            return <g key={`trend-empty-${index ?? 0}`} />;
                                          }
                                          const pt = payload as TrendWindowChartPoint | undefined;
                                          const isAlert = Boolean(pt?.alertTriggered);
                                          return (
                                            <circle
                                              key={`trend-dot-${index ?? cx}`}
                                              cx={cx}
                                              cy={cy}
                                              r={isAlert ? 4.5 : 3}
                                              fill={isAlert ? '#f43f5e' : '#fb7185'}
                                              stroke="#0f172a"
                                              strokeWidth={1.5}
                                            />
                                          );
                                        }}
                                        activeDot={{ r: 5.5, fill: '#f43f5e', stroke: '#0f172a', strokeWidth: 1.5 }}
                                      />
                                    </LineChart>
                                  </ResponsiveContainer>
                                </div>
                              </div>
                            );
                          })()}
                        </div>
                      </div>
                    )}

                    {exportedBatteryReport && (
                      <div className="rounded-lg border border-teal-700/50 bg-slate-950 p-2.5 space-y-2">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-[11px] font-mono font-semibold text-teal-300">
                            {isZh ? '能耗统计纯文本报告 (Battery Impact Report)' : 'Energy Usage Statistics Report (.txt)'}
                          </span>
                          <div className="flex flex-wrap items-center gap-1.5">
                            <button
                              type="button"
                              onClick={handleCopyBatteryReport}
                              className="inline-flex items-center gap-1 px-2 py-0.5 text-[10px] rounded bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 transition-colors cursor-pointer"
                            >
                              {batteryReportCopied ? (
                                <Check className="w-3 h-3 text-emerald-400" />
                              ) : (
                                <Copy className="w-3 h-3" />
                              )}
                              <span>{batteryReportCopied ? (isZh ? '已复制' : 'Copied') : isZh ? '复制报告' : 'Copy'}</span>
                            </button>
                            <button
                              type="button"
                              onClick={handleDownloadBatteryReportTxt}
                              className="inline-flex items-center gap-1 px-2 py-0.5 text-[10px] rounded bg-teal-900/60 hover:bg-teal-800/70 text-teal-200 border border-teal-700/60 transition-colors cursor-pointer"
                            >
                              <Download className="w-3 h-3" />
                              <span>.txt</span>
                            </button>
                            <button
                              type="button"
                              onClick={handleDownloadHistoricalImpactCsv}
                              className="inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-semibold rounded bg-emerald-900/70 hover:bg-emerald-800/80 text-emerald-200 border border-emerald-600/60 transition-colors cursor-pointer"
                            >
                              <Download className="w-3 h-3" />
                              <span>Historical Impact CSV ({energyTimelineCount})</span>
                            </button>
                            <button
                              type="button"
                              onClick={() => setExportedBatteryReport('')}
                              className="px-2 py-0.5 text-[10px] rounded bg-slate-800 hover:bg-slate-700 text-slate-400 border border-slate-700 transition-colors cursor-pointer"
                            >
                              {isZh ? '关闭' : 'Close'}
                            </button>
                          </div>
                        </div>
                        <pre className="text-[10.5px] font-mono text-teal-100 whitespace-pre-wrap break-words max-h-48 overflow-y-auto leading-relaxed">
                          {exportedBatteryReport}
                        </pre>
                      </div>
                    )}
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
