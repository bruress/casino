import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  ChevronLeft,
  Coins,
  History,
  Info,
  Play,
  RotateCcw,
  Settings,
  ShieldCheck,
  Sparkles,
  Trophy,
  Volume2,
  VolumeX,
  X,
} from 'lucide-react';
import './styles.css';

const API = import.meta.env.VITE_API_URL ?? '';

type Mode = 'red' | 'green';
type Screen = 'mode' | 'stake' | 'game' | 'result';

type Config = {
  stakes: number[];
  redLevels: number;
  greenLevels: number;
  pointsPerLevel: number;
  pointsCashoutBonus: number;
  pointsBoosterBonus: number;
  minWinOfferAmount: number;
  popupTimeoutSeconds: number;
  redGrowthPerSecond: number;
  greenGrowthPerSecond: number;
  maxMultiplier: number;
};

type Player = {
  id: string;
  name: string;
  bonusBalance: number;
  gamePoints: number;
  boosters: Record<string, number>;
  tickets: number;
};

type Reward = {
  type: string;
  label: string;
  amount: number;
};

type Round = {
  id: string;
  mode: Mode;
  stake: number;
  booster: number;
  boosterLevel: number;
  boosterActive: boolean;
  levels: number;
  status: 'RUNNING' | 'CASHED_OUT' | 'CRASHED' | 'FINISHED';
  currentLevel: number;
  currentMultiplier: number;
  potentialPayout: number;
  cashoutMultiplier: number;
  lockedPayout: number;
  points: number;
  cashoutAvailable: boolean;
  fairnessHash: string;
  crashMultiplier?: number;
  serverSeed?: string;
  reward?: Reward | null;
};

type HistoryRow = {
  id: string;
  finishedAt: string;
  mode: Mode;
  stake: number;
  result: 'win' | 'loss';
  crashMultiplier: number;
  cashoutMultiplier: number;
  payout: number;
  points: number;
  booster: string;
  reward: Reward | null;
};

type LeaderboardRow = {
  position: number;
  player: string;
  points: number;
  current: boolean;
};

async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options?.headers ?? {}) },
    ...options,
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(data?.error ?? 'Ошибка запроса');
  }
  return data as T;
}

function format(value: number): string {
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(value);
}

function App() {
  const [screen, setScreen] = useState<Screen>('mode');
  const [mode, setMode] = useState<Mode>('green');
  const [selectedStake, setSelectedStake] = useState(50);
  const [selectedBooster, setSelectedBooster] = useState(1);
  const [config, setConfig] = useState<Config | null>(null);
  const [player, setPlayer] = useState<Player | null>(null);
  const [history, setHistory] = useState<HistoryRow[]>([]);
  const [leaderboard, setLeaderboard] = useState<LeaderboardRow[]>([]);
  const [round, setRound] = useState<Round | null>(null);
  const [error, setError] = useState('');
  const [rulesOpen, setRulesOpen] = useState(false);
  const [adminOpen, setAdminOpen] = useState(false);
  const [sound, setSound] = useState(false);
  const [offerDismissed, setOfferDismissed] = useState(false);

  async function loadAll() {
    const [cfg, me, hist, leaders] = await Promise.all([
      api<Config>('/api/config'),
      api<Player>('/api/player'),
      api<HistoryRow[]>('/api/history'),
      api<LeaderboardRow[]>('/api/leaderboard'),
    ]);
    setConfig(cfg);
    setPlayer(me);
    setHistory(hist);
    setLeaderboard(leaders);
    setSelectedStake((current) => (cfg.stakes.includes(current) ? current : cfg.stakes[0]));
  }

  useEffect(() => {
    loadAll().catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    if (!round || screen !== 'game') return;
    const timer = window.setInterval(async () => {
      try {
        const next = await api<Round>(`/api/rounds/${round.id}`);
        setRound(next);
        const leaders = await api<LeaderboardRow[]>('/api/leaderboard');
        setLeaderboard(leaders);
        if (next.status === 'CRASHED' || next.status === 'FINISHED') {
          window.clearInterval(timer);
          await loadAll();
          setRound(next);
          setScreen('result');
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Ошибка обновления раунда');
      }
    }, 550);
    return () => window.clearInterval(timer);
  }, [round?.id, screen]);

  useEffect(() => {
    if (screen !== 'result') return;
    const timer = window.setTimeout(() => {
      setScreen('mode');
    }, 10000);
    return () => window.clearTimeout(timer);
  }, [screen, round?.id]);

  const levels = mode === 'red' ? config?.redLevels ?? 12 : config?.greenLevels ?? 9;
  const canAfford = !!player && player.bonusBalance >= selectedStake;

  async function startRound(stake = selectedStake, booster = selectedBooster) {
    if (!canAfford && stake === selectedStake) {
      setError('Не хватает бонусов');
      return;
    }
    setError('');
    const idempotencyKey = crypto.randomUUID();
    try {
      const next = await api<Round>('/api/rounds/start', {
        method: 'POST',
        body: JSON.stringify({ mode, stake, booster, idempotencyKey }),
      });
      setRound(next);
      await loadAll();
      setScreen('game');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Не удалось начать раунд');
    }
  }

  async function cashout() {
    if (!round) return;
    try {
      const next = await api<Round>(`/api/rounds/${round.id}/cashout`, { method: 'POST' });
      setRound(next);
      await loadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Cashout отклонен сервером');
    }
  }

  async function activateOffer(cost: number, tickets: number) {
    await api('/api/offers/activate', {
      method: 'POST',
      body: JSON.stringify({ cost, tickets }),
    });
    setOfferDismissed(true);
    await loadAll();
  }

  if (!config || !player) {
    return <Shell error={error}><div className="loading">Поднимаем шар...</div></Shell>;
  }

  return (
    <Shell error={error}>
      <TopBar
        player={player}
        mode={mode}
        onRules={() => setRulesOpen(true)}
        onAdmin={() => setAdminOpen(true)}
        sound={sound}
        onSound={() => setSound((value) => !value)}
      />

      {screen === 'mode' && (
        <ModeScreen
          selected={mode}
          onSelect={(value) => {
            setMode(value);
            setScreen('stake');
          }}
          redLevels={config.redLevels}
          greenLevels={config.greenLevels}
        />
      )}

      {screen === 'stake' && (
        <StakeScreen
          mode={mode}
          setMode={setMode}
          levels={levels}
          stakes={config.stakes}
          balance={player.bonusBalance}
          selectedStake={selectedStake}
          selectedBooster={selectedBooster}
          onStake={setSelectedStake}
          onBooster={setSelectedBooster}
          canAfford={canAfford}
          onStart={() => startRound()}
          onBack={() => setScreen('mode')}
          history={history}
          leaderboard={leaderboard}
          onRules={() => setRulesOpen(true)}
        />
      )}

      {screen === 'game' && round && (
        <GameScreen round={round} leaderboard={leaderboard} onCashout={cashout} onBack={() => setScreen('stake')} />
      )}

      {screen === 'result' && round && (
        <ResultScreen
          round={round}
          mode={mode}
          config={config}
          offerDismissed={offerDismissed}
          onAgain={() => setScreen('stake')}
          onRepeat={() => startRound(round.stake, round.booster)}
          onOffer={activateOffer}
          onDismissOffer={() => setOfferDismissed(true)}
        />
      )}

      {rulesOpen && <RulesModal onClose={() => setRulesOpen(false)} />}
      {adminOpen && <AdminModal config={config} onClose={() => setAdminOpen(false)} onSaved={loadAll} />}
    </Shell>
  );
}

function Shell({ children, error }: { children: React.ReactNode; error: string }) {
  return (
    <main className="min-h-screen overflow-hidden bg-[#071927] text-white">
      <div className="sky-gradient" />
      <div className="cloud cloud-a" />
      <div className="cloud cloud-b" />
      <div className="cloud cloud-c" />
      <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-[1440px] flex-col px-4 py-4 sm:px-6 lg:px-8">
        {error && <div className="mb-3 rounded-md border border-amber-300/40 bg-amber-500/15 px-4 py-2 text-sm text-amber-100">{error}</div>}
        {children}
      </div>
    </main>
  );
}

function TopBar({
  player,
  mode,
  onRules,
  onAdmin,
  sound,
  onSound,
}: {
  player: Player;
  mode: Mode;
  onRules: () => void;
  onAdmin: () => void;
  sound: boolean;
  onSound: () => void;
}) {
  return (
    <header className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-md border border-white/10 bg-white/10 px-4 py-3 backdrop-blur">
      <div>
        <p className="text-xs uppercase tracking-wide text-white/60">Воздушный шар</p>
        <h1 className="text-xl font-bold sm:text-2xl">Бонусная crash-игра</h1>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Badge icon={<Coins size={16} />} label={`${format(player.bonusBalance)} бонусов`} />
        <Badge icon={<Trophy size={16} />} label={`${player.gamePoints} очков`} />
        <Badge icon={<Sparkles size={16} />} label={mode === 'red' ? 'Красный риск' : 'Зеленая стабильность'} tone={mode} />
        <IconButton label="Правила" onClick={onRules}><Info size={18} /></IconButton>
        <IconButton label="Админ" onClick={onAdmin}><Settings size={18} /></IconButton>
        <IconButton label="Звук" onClick={onSound}>{sound ? <Volume2 size={18} /> : <VolumeX size={18} />}</IconButton>
      </div>
    </header>
  );
}

function ModeScreen({
  selected,
  onSelect,
  redLevels,
  greenLevels,
}: {
  selected: Mode;
  onSelect: (mode: Mode) => void;
  redLevels: number;
  greenLevels: number;
}) {
  return (
    <section className="grid flex-1 items-center gap-5 lg:grid-cols-2">
      <ModeCard
        mode="red"
        selected={selected === 'red'}
        title="Красный шар"
        subtitle={`${redLevels} уровней, выше награда, сильнее риск`}
        onClick={() => onSelect('red')}
      />
      <ModeCard
        mode="green"
        selected={selected === 'green'}
        title="Зеленый шар"
        subtitle={`${greenLevels} уровней, стабильнее полет, спокойнее риск`}
        onClick={() => onSelect('green')}
      />
    </section>
  );
}

function ModeCard({ mode, selected, title, subtitle, onClick }: { mode: Mode; selected: boolean; title: string; subtitle: string; onClick: () => void }) {
  return (
    <button
      className={`mode-card ${mode} ${selected ? 'selected' : ''}`}
      onClick={onClick}
    >
      <Balloon mode={mode} size="large" />
      <div className="relative z-10 text-left">
        <p className="text-sm uppercase text-white/70">Выбор темы</p>
        <h2 className="mt-2 text-4xl font-black sm:text-5xl">{title}</h2>
        <p className="mt-3 max-w-md text-lg text-white/80">{subtitle}</p>
        <span className="mt-6 inline-flex items-center gap-2 rounded-md bg-white px-4 py-3 font-bold text-slate-950">
          <Play size={18} /> Выбрать
        </span>
      </div>
    </button>
  );
}

function StakeScreen(props: {
  mode: Mode;
  setMode: (mode: Mode) => void;
  levels: number;
  stakes: number[];
  balance: number;
  selectedStake: number;
  selectedBooster: number;
  onStake: (stake: number) => void;
  onBooster: (booster: number) => void;
  canAfford: boolean;
  onStart: () => void;
  onBack: () => void;
  history: HistoryRow[];
  leaderboard: LeaderboardRow[];
  onRules: () => void;
}) {
  const boosterFor = (index: number) => index + 1;
  return (
    <section className="grid flex-1 gap-5 lg:grid-cols-[minmax(0,1.1fr)_380px]">
      <div className="space-y-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <button className="ghost-button" onClick={props.onBack}><ChevronLeft size={18} /> К темам</button>
          <div className="segmented">
            <button className={props.mode === 'green' ? 'active green' : ''} onClick={() => props.setMode('green')}>Зеленый</button>
            <button className={props.mode === 'red' ? 'active red' : ''} onClick={() => props.setMode('red')}>Красный</button>
          </div>
        </div>

        <div className={`game-panel ${props.mode}`}>
          <div>
            <p className="text-sm uppercase text-white/60">Ставка и бустер</p>
            <h2 className="mt-1 text-3xl font-black">Выберите фрагмент</h2>
            <p className="mt-2 text-white/70">{props.levels} уровней. Cashout откроется после первого уровня.</p>
          </div>
          <div className="stake-grid">
            {props.stakes.map((stake, index) => {
              const booster = boosterFor(index);
              const disabled = props.balance < stake;
              const selected = props.selectedStake === stake && props.selectedBooster === booster;
              return (
                <button
                  key={stake}
                  className={`stake-piece ${selected ? 'selected' : ''} ${disabled ? 'disabled' : ''}`}
                  onClick={() => {
                    props.onStake(stake);
                    props.onBooster(booster);
                  }}
                >
                  <span className="text-sm text-white/60">Ставка</span>
                  <strong>{stake}</strong>
                  <span className="booster-chip">x{booster}</span>
                  {disabled && <small>Не хватает бонусов</small>}
                </button>
              );
            })}
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <button className="primary-button" disabled={!props.canAfford} onClick={props.onStart}>
              <Play size={18} /> Начать
            </button>
            <button className="ghost-button" onClick={props.onRules}><Info size={18} /> Правила</button>
            <p className="text-sm text-white/65">
              Будет списано: <b>{props.selectedStake}</b>. Бустер активируется на серверном уровне до cashout.
            </p>
          </div>
        </div>
      </div>

      <aside className="space-y-5">
        <Leaderboard rows={props.leaderboard} />
        <HistoryPanel history={props.history} />
      </aside>
    </section>
  );
}

function GameScreen({ round, leaderboard, onCashout, onBack }: { round: Round; leaderboard: LeaderboardRow[]; onCashout: () => void; onBack: () => void }) {
  const progress = Math.min(100, (round.currentLevel / round.levels) * 100);
  const cashed = round.status === 'CASHED_OUT';
  return (
    <section className={`gameplay ${round.mode}`}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <button className="ghost-button" onClick={onBack}><ChevronLeft size={18} /> Ставки</button>
        <div className="flex flex-wrap gap-2">
          <Badge icon={<ShieldCheck size={16} />} label={`Hash ${round.fairnessHash.slice(0, 10)}...`} />
          <Badge icon={<Sparkles size={16} />} label={`Бустер x${round.booster}${round.boosterActive ? ' активен' : ''}`} />
        </div>
      </div>

      <Leaderboard rows={leaderboard} compact />

      <div className="gameplay-stage">
        <div className="altitude">
          {Array.from({ length: round.levels }, (_, index) => {
            const level = round.levels - index;
            const reached = round.currentLevel >= level;
            const booster = round.boosterLevel === level;
            return (
              <div className={`level-line ${reached ? 'reached' : ''}`} key={level}>
                <span>Ур. {level}</span>
                {booster && <b className={round.boosterActive ? 'active' : ''}>x{round.booster}</b>}
              </div>
            );
          })}
        </div>
        <div className="flight-column">
          <div className="balloon-track">
            <div className="balloon-position" style={{ bottom: `${Math.max(8, progress)}%` }}>
              <Balloon mode={round.mode} size="game" popped={round.status === 'CRASHED' || round.status === 'FINISHED'} />
            </div>
          </div>
        </div>
        <div className="hud">
          <p className="text-sm uppercase text-white/60">{cashed ? 'Выигрыш зафиксирован' : 'Текущий коэффициент'}</p>
          <div className="multiplier">x{format(round.currentMultiplier)}</div>
          <div className="metric-grid">
            <Metric label="Ставка" value={round.stake} />
            <Metric label={cashed ? 'Зафиксировано' : 'Потенциал'} value={format(round.potentialPayout)} />
            <Metric label="Уровень" value={`${round.currentLevel}/${round.levels}`} />
            <Metric label="Очки" value={round.points} />
          </div>
          {cashed && <div className="secured">Награда уже защищена. Шар летит дальше до crash.</div>}
          <button className="cashout-button" disabled={!round.cashoutAvailable || cashed} onClick={onCashout}>
            Забрать
          </button>
          {!round.cashoutAvailable && !cashed && <p className="text-sm text-white/55">Cashout откроется после первого уровня.</p>}
        </div>
      </div>
    </section>
  );
}

function ResultScreen({
  round,
  mode,
  config,
  offerDismissed,
  onAgain,
  onRepeat,
  onOffer,
  onDismissOffer,
}: {
  round: Round;
  mode: Mode;
  config: Config;
  offerDismissed: boolean;
  onAgain: () => void;
  onRepeat: () => void;
  onOffer: (cost: number, tickets: number) => void;
  onDismissOffer: () => void;
}) {
  const won = round.lockedPayout > 0;
  const showOffer = won && round.lockedPayout >= config.minWinOfferAmount && !offerDismissed;
  const offerCost = Math.max(50, Math.round(round.lockedPayout * 0.2));
  const tickets = Math.max(1, Math.round(round.lockedPayout / 100));
  return (
    <section className={`result ${mode}`}>
      <div className="result-card">
        <Balloon mode={mode} size="result" popped />
        <p className="text-sm uppercase text-white/60">{won ? 'Успешный cashout' : 'Crash'}</p>
        <h2>{won ? 'Вы забрали выигрыш' : 'Шар лопнул'}</h2>
        <p className="text-white/70">
          {won ? 'Могли бы забрать больше, но выплата уже была защищена.' : 'Ставка потеряна, очки и награда все равно учтены.'}
        </p>
        <div className="result-grid">
          <Metric label="Ставка" value={round.stake} />
          <Metric label="Cashout" value={won ? `x${format(round.cashoutMultiplier)}` : '-'} />
          <Metric label="Crash" value={`x${format(round.crashMultiplier ?? round.currentMultiplier)}`} />
          <Metric label="Выплата" value={format(round.lockedPayout)} />
          <Metric label="Очки" value={round.points} />
          <Metric label="Награда" value={round.reward ? `${round.reward.label} +${round.reward.amount}` : '-'} />
        </div>
        <div className="hash-box">
          <span>Provably fair hash</span>
          <code>{round.fairnessHash}</code>
          {round.serverSeed && <small>Seed раскрыт после завершения: {round.serverSeed.slice(0, 34)}...</small>}
        </div>
        <div className="flex flex-wrap justify-center gap-3">
          <button className="primary-button" onClick={onAgain}><RotateCcw size={18} /> Играть снова</button>
          <button className="ghost-button" onClick={onRepeat}><Play size={18} /> Повторить ставку</button>
        </div>
      </div>

      {showOffer && (
        <div className="offer">
          <button className="absolute right-3 top-3 text-white/70" onClick={onDismissOffer}><X size={18} /></button>
          <h3>Закрепи успех</h3>
          <p>Можно обменять {offerCost} бонусов на {tickets} условн. билета. Покупка имитационная.</p>
          <button className="primary-button" onClick={() => onOffer(offerCost, tickets)}>Активировать</button>
        </div>
      )}
    </section>
  );
}

function RulesModal({ onClose }: { onClose: () => void }) {
  const [items, setItems] = useState<string[]>([]);
  useEffect(() => {
    api<{ items: string[] }>('/api/rules').then((data) => setItems(data.items)).catch(() => setItems([]));
  }, []);
  return (
    <Modal title="Правила игры" onClose={onClose}>
      <div className="space-y-3">
        {items.map((item) => <p key={item} className="rounded-md bg-white/8 p-3 text-sm text-white/80">{item}</p>)}
      </div>
    </Modal>
  );
}

function AdminModal({ config, onClose, onSaved }: { config: Config; onClose: () => void; onSaved: () => void }) {
  const [pointsPerLevel, setPointsPerLevel] = useState(config.pointsPerLevel);
  const [redGrowthPerSecond, setRedGrowthPerSecond] = useState(config.redGrowthPerSecond);
  const [greenGrowthPerSecond, setGreenGrowthPerSecond] = useState(config.greenGrowthPerSecond);
  const [saving, setSaving] = useState(false);

  async function save() {
    setSaving(true);
    await api('/api/admin/config', {
      method: 'PUT',
      body: JSON.stringify({ pointsPerLevel, redGrowthPerSecond, greenGrowthPerSecond }),
    });
    await onSaved();
    setSaving(false);
    onClose();
  }

  return (
    <Modal title="Управление параметрами" onClose={onClose}>
      <div className="space-y-5">
        <label className="admin-field">
          <span>Очки за уровень</span>
          <input type="number" value={pointsPerLevel} onChange={(event) => setPointsPerLevel(Number(event.target.value))} />
          <small>Обязательный проверочный параметр: эксперт меняет значение и видит новое начисление в новом раунде.</small>
        </label>
        <label className="admin-field">
          <span>Скорость роста красного шара</span>
          <input type="number" step="0.01" value={redGrowthPerSecond} onChange={(event) => setRedGrowthPerSecond(Number(event.target.value))} />
        </label>
        <label className="admin-field">
          <span>Скорость роста зеленого шара</span>
          <input type="number" step="0.01" value={greenGrowthPerSecond} onChange={(event) => setGreenGrowthPerSecond(Number(event.target.value))} />
        </label>
        <button className="primary-button" disabled={saving} onClick={save}>
          <Settings size={18} /> Сохранить
        </button>
      </div>
    </Modal>
  );
}

function Modal({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(event) => event.stopPropagation()}>
        <div className="mb-5 flex items-center justify-between gap-3">
          <h2 className="text-2xl font-black">{title}</h2>
          <IconButton label="Закрыть" onClick={onClose}><X size={18} /></IconButton>
        </div>
        {children}
      </div>
    </div>
  );
}

function Leaderboard({ rows, compact = false }: { rows: LeaderboardRow[]; compact?: boolean }) {
  return (
    <section className={`leaderboard ${compact ? 'compact' : ''}`}>
      <div className="mb-3 flex items-center gap-2">
        <Trophy size={18} className="text-amber-200" />
        <h3 className="font-bold">Живой рейтинг</h3>
      </div>
      <div className={compact ? 'flex flex-wrap gap-2' : 'space-y-2'}>
        {rows.map((row) => (
          <div className={`leader-row ${row.current ? 'current' : ''}`} key={row.player}>
            <span>#{row.position}</span>
            <b>{row.player}</b>
            <em>{row.points}</em>
          </div>
        ))}
      </div>
    </section>
  );
}

function HistoryPanel({ history }: { history: HistoryRow[] }) {
  return (
    <section className="history-panel">
      <div className="mb-3 flex items-center gap-2">
        <History size={18} className="text-cyan-200" />
        <h3 className="font-bold">История</h3>
      </div>
      <div className="space-y-2">
        {history.length === 0 && <p className="text-sm text-white/55">Раунды появятся после завершения игры.</p>}
        {history.slice(0, 6).map((row) => (
          <div className="history-row" key={row.id}>
            <span className={row.result === 'win' ? 'text-emerald-200' : 'text-rose-200'}>{row.result === 'win' ? 'win' : 'loss'}</span>
            <b>{row.mode === 'red' ? 'Красный' : 'Зеленый'}</b>
            <em>x{format(row.crashMultiplier)}</em>
            <small>{format(row.payout)} бонусов</small>
          </div>
        ))}
      </div>
    </section>
  );
}

function Balloon({ mode, size, popped = false }: { mode: Mode; size: 'large' | 'game' | 'result'; popped?: boolean }) {
  return (
    <div className={`balloon ${mode} ${size} ${popped ? 'popped' : ''}`}>
      <div className="balloon-body" />
      <div className="balloon-knot" />
      <div className="balloon-string" />
    </div>
  );
}

function Metric({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <b>{value}</b>
    </div>
  );
}

function Badge({ icon, label, tone }: { icon: React.ReactNode; label: string; tone?: Mode }) {
  return <span className={`badge ${tone ?? ''}`}>{icon}{label}</span>;
}

function IconButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return <button className="icon-button" title={label} aria-label={label} onClick={onClick}>{children}</button>;
}

createRoot(document.getElementById('root')!).render(<App />);
