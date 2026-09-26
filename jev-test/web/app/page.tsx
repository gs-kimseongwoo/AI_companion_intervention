'use client';

import { useEffect, useMemo, useRef, useState } from 'react';

type Metric = 'context_shift' | 'natural_breakpoint' | 'engagement_drop';
type ViewMode = 'compare' | 'jev' | 'qwen';
type Scores = Record<Metric, number>;
type Turn = {
  index: number;
  speaker: 'human' | 'assistant';
  text: string;
  scores: Scores | null;
  latencyMs: number | null;
};
type Conversation = {
  number: number;
  id: string;
  botName: string;
  categories: string[];
  totalTurns: number;
  evaluatedTurns: number;
  averageLatencyMs: number;
  modelReturned: string;
  turns: Turn[];
};
type Results = {
  generatedAt: string;
  modelRequested: string;
  modelLabel: string;
  questionsSha256: string;
  conversationCount: number;
  evaluationCount: number;
  conversations: Conversation[];
};

const metrics: { key: Metric; label: string; color: string }[] = [
  { key: 'context_shift', label: 'Context shift', color: '#2563eb' },
  { key: 'natural_breakpoint', label: 'Natural breakpoint', color: '#16a34a' },
  { key: 'engagement_drop', label: 'Engagement drop', color: '#dc2626' },
];

const conversationLabel = (number: number) => `대화 ${String(number).padStart(2, '0')}`;
const formatLatency = (milliseconds: number) =>
  milliseconds >= 1000 ? `${(milliseconds / 1000).toFixed(2)} s` : `${milliseconds.toFixed(0)} ms`;

function TrajectoryChart({
  conversation,
  comparison,
  selectedTurn,
  onSelectTurn,
}: {
  conversation: Conversation;
  comparison?: Conversation;
  selectedTurn: number;
  onSelectTurn: (turn: number) => void;
}) {
  const evaluated = conversation.turns.filter((turn) => turn.scores !== null);
  const width = 920;
  const height = 365;
  const margin = { left: 58, right: 22, top: 24, bottom: 54 };
  const plotWidth = width - margin.left - margin.right;
  const plotHeight = height - margin.top - margin.bottom;
  const minTurn = evaluated[0]?.index ?? 1;
  const maxTurn = evaluated.at(-1)?.index ?? conversation.totalTurns;
  const x = (turn: number) =>
    margin.left + ((turn - minTurn) / Math.max(1, maxTurn - minTurn)) * plotWidth;
  const y = (value: number) => margin.top + (1 - value) * plotHeight;
  const tickStep = Math.max(1, Math.ceil((maxTurn - minTurn + 1) / 12));
  const turnTicks = evaluated
    .map((turn) => turn.index)
    .filter((turn) => (turn - minTurn) % tickStep === 0 || turn === maxTurn);

  return (
    <div className="chart-wrap">
      <svg
        className="trajectory-chart"
        viewBox={`0 0 ${width} ${height}`}
        aria-label={`${conversationLabel(conversation.number)} score trajectory`}
      >
        <rect
          className="plot-frame"
          x={margin.left}
          y={margin.top}
          width={plotWidth}
          height={plotHeight}
        />
        {[0, 0.25, 0.5, 0.75, 1].map((tick) => (
          <g key={tick}>
            <line
              className={tick === 0.75 ? 'threshold-line' : 'grid-line'}
              x1={margin.left}
              x2={width - margin.right}
              y1={y(tick)}
              y2={y(tick)}
            />
            <text className="axis-tick" x={margin.left - 11} y={y(tick) + 4} textAnchor="end">
              {tick.toFixed(2)}
            </text>
          </g>
        ))}
        <text className="threshold-label" x={width - margin.right - 5} y={y(0.75) - 7} textAnchor="end">
          high 0.75
        </text>
        {turnTicks.map((turn) => (
          <g key={turn}>
            <line
              className="x-tick"
              x1={x(turn)}
              x2={x(turn)}
              y1={height - margin.bottom}
              y2={height - margin.bottom + 5}
            />
            <text className="axis-tick" x={x(turn)} y={height - margin.bottom + 21} textAnchor="middle">
              {turn}
            </text>
          </g>
        ))}
        <text className="axis-title" x={margin.left + plotWidth / 2} y={height - 9} textAnchor="middle">
          Turn index
        </text>
        <text
          className="axis-title"
          x={15}
          y={margin.top + plotHeight / 2}
          textAnchor="middle"
          transform={`rotate(-90 15 ${margin.top + plotHeight / 2})`}
        >
          Jev score
        </text>

        {metrics.map((metric) => {
          const points = evaluated
            .map((turn) => `${x(turn.index)},${y(turn.scores![metric.key])}`)
            .join(' ');
          const comparisonPoints = comparison?.turns
            .filter((turn) => turn.scores !== null)
            .map((turn) => `${x(turn.index)},${y(turn.scores![metric.key])}`)
            .join(' ');
          return (
            <g key={metric.key}>
              <polyline points={points} fill="none" stroke={metric.color} strokeWidth="2.5" />
              {comparisonPoints && (
                <polyline
                  points={comparisonPoints}
                  fill="none"
                  stroke={metric.color}
                  strokeWidth="2.2"
                  strokeDasharray="7 5"
                  opacity="0.72"
                />
              )}
              {evaluated.map((turn) => (
                <circle
                  key={turn.index}
                  cx={x(turn.index)}
                  cy={y(turn.scores![metric.key])}
                  r={turn.index === selectedTurn ? 5.5 : 3.2}
                  fill={metric.color}
                  stroke="white"
                  strokeWidth="1.5"
                  pointerEvents="none"
                />
              ))}
              {comparison?.turns.filter((turn) => turn.scores).map((turn) => (
                <rect
                  key={`compare-${turn.index}`}
                  x={x(turn.index) - (turn.index === selectedTurn ? 4.5 : 3)}
                  y={y(turn.scores![metric.key]) - (turn.index === selectedTurn ? 4.5 : 3)}
                  width={turn.index === selectedTurn ? 9 : 6}
                  height={turn.index === selectedTurn ? 9 : 6}
                  fill="white"
                  stroke={metric.color}
                  strokeWidth="1.7"
                  pointerEvents="none"
                />
              ))}
            </g>
          );
        })}

        {evaluated.map((turn, index) => {
          const next = evaluated[index + 1];
          const previous = evaluated[index - 1];
          const left = previous ? (x(previous.index) + x(turn.index)) / 2 : margin.left;
          const right = next ? (x(turn.index) + x(next.index)) / 2 : width - margin.right;
          return (
            <a
              key={turn.index}
              href={`#turn-${turn.index}`}
              aria-label={`Turn ${turn.index} 선택`}
              onClick={(event) => {
                event.preventDefault();
                onSelectTurn(turn.index);
              }}
            >
              <title>{`Turn ${turn.index}`}</title>
              <rect
                className="turn-hit-area"
                x={left}
                y={margin.top}
                width={right - left}
                height={plotHeight}
              />
            </a>
          );
        })}

        {selectedTurn >= minTurn && selectedTurn <= maxTurn && (
          <line
            className="selected-guide"
            x1={x(selectedTurn)}
            x2={x(selectedTurn)}
            y1={margin.top}
            y2={height - margin.bottom}
            pointerEvents="none"
          />
        )}
      </svg>
    </div>
  );
}

function DialogueLog({
  conversation,
  comparison,
  selectedTurn,
}: {
  conversation: Conversation;
  comparison?: Conversation;
  selectedTurn: number;
}) {
  const rowRefs = useRef<Record<number, HTMLLIElement | null>>({});

  useEffect(() => {
    rowRefs.current[selectedTurn]?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [conversation.id, selectedTurn]);

  return (
    <section className="log-panel" aria-label="전체 대화 로그">
      <div className="panel-heading">
        <div>
          <span className="eyebrow">Full dialogue</span>
          <h2>전체 대화 로그</h2>
        </div>
        <span className="turn-count">{conversation.totalTurns} turns</span>
      </div>
      <ol className="dialogue-list">
        {conversation.turns.map((turn) => {
          const selected = turn.index === selectedTurn;
          const comparisonTurn = comparison?.turns[turn.index - 1];
          return (
            <li
              key={turn.index}
              id={`turn-${turn.index}`}
              ref={(node) => {
                rowRefs.current[turn.index] = node;
              }}
              className={`dialogue-turn ${turn.speaker} ${selected ? 'selected' : ''}`}
            >
              <div className="turn-meta">
                <span>Turn {turn.index}</span>
                <span>{turn.speaker === 'human' ? 'Human' : conversation.botName || 'Assistant'}</span>
                {turn.latencyMs !== null && (
                  <span>
                    {comparisonTurn?.latencyMs !== null && comparisonTurn?.latencyMs !== undefined
                      ? `J ${turn.latencyMs.toFixed(0)} · Q ${comparisonTurn.latencyMs.toFixed(0)} ms`
                      : `${turn.latencyMs.toFixed(0)} ms`}
                  </span>
                )}
              </div>
              <p>{turn.text}</p>
              {selected && turn.scores && (
                <div className="selected-scores">
                  {metrics.map((metric) => (
                    <span key={metric.key} style={{ color: metric.color }}>
                      {metric.label}{' '}
                      <strong>
                        {comparisonTurn?.scores
                          ? `J ${turn.scores![metric.key].toFixed(2)} · Q ${comparisonTurn.scores[metric.key].toFixed(2)}`
                          : turn.scores![metric.key].toFixed(2)}
                      </strong>
                    </span>
                  ))}
                </div>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}

export default function Home() {
  const [datasets, setDatasets] = useState<{ jev: Results; qwen: Results } | null>(null);
  const [error, setError] = useState('');
  const [selectedConversationId, setSelectedConversationId] = useState('');
  const [selectedTurn, setSelectedTurn] = useState(3);
  const [viewMode, setViewMode] = useState<ViewMode>('compare');

  useEffect(() => {
    Promise.all(
      ['/jev-results.json', '/qwen-results.json'].map((path) =>
        fetch(path).then((response) => {
          if (!response.ok) throw new Error(`결과 파일을 읽을 수 없습니다 (${response.status})`);
          return response.json() as Promise<Results>;
        }),
      ),
    )
      .then(([jev, qwen]) => {
        setDatasets({ jev, qwen });
        const first = jev.conversations[0];
        if (first) {
          setSelectedConversationId(first.id);
          setSelectedTurn(first.turns.find((turn) => turn.scores)?.index ?? 1);
        }
      })
      .catch((reason: Error) => setError(reason.message));
  }, []);

  const results = viewMode === 'qwen' ? datasets?.qwen : datasets?.jev;
  const conversation = useMemo(
    () => results?.conversations.find((item) => item.id === selectedConversationId),
    [results, selectedConversationId],
  );
  const comparisonConversation = useMemo(
    () => viewMode === 'compare'
      ? datasets?.qwen.conversations.find((item) => item.id === selectedConversationId)
      : undefined,
    [datasets, selectedConversationId, viewMode],
  );

  if (error) return <main className="state-message">{error}</main>;
  if (!datasets || !results || !conversation) return <main className="state-message">결과를 불러오는 중…</main>;

  const selected = conversation.turns[selectedTurn - 1];
  const comparisonSelected = comparisonConversation?.turns[selectedTurn - 1];
  const peaks = metrics.map((metric) => {
    const peak = conversation.turns
      .filter((turn) => turn.scores)
      .reduce((best, turn) =>
        !best || turn.scores![metric.key] > best.scores![metric.key] ? turn : best,
      null as Turn | null);
    return { ...metric, turn: peak?.index ?? 0, value: peak?.scores?.[metric.key] ?? 0 };
  });
  const comparisonPeaks = comparisonConversation
    ? metrics.map((metric) => {
        const peak = comparisonConversation.turns
          .filter((turn) => turn.scores)
          .reduce((best, turn) =>
            !best || turn.scores![metric.key] > best.scores![metric.key] ? turn : best,
          null as Turn | null);
        return { key: metric.key, turn: peak?.index ?? 0, value: peak?.scores?.[metric.key] ?? 0 };
      })
    : [];
  const transitionGroups = metrics.map((metric) => {
    const evaluated = conversation.turns.filter((turn) => turn.scores);
    const changes = evaluated.slice(1).map((turn, index) => ({
      turn: turn.index,
      value: turn.scores![metric.key],
      delta: turn.scores![metric.key] - evaluated[index].scores![metric.key],
    }));
    const rise = changes.reduce((best, item) => item.delta > best.delta ? item : best, changes[0]);
    const drop = changes.reduce((best, item) => item.delta < best.delta ? item : best, changes[0]);
    const peak = peaks.find((item) => item.key === metric.key)!;
    return {
      ...metric,
      points: [
        { label: '최고점', turn: peak.turn, value: peak.value, detail: `score ${peak.value.toFixed(2)}` },
        { label: '최대 상승', turn: rise?.turn ?? peak.turn, value: rise?.value ?? peak.value, detail: `${rise && rise.delta >= 0 ? '+' : ''}${(rise?.delta ?? 0).toFixed(2)}` },
        { label: '최대 하락', turn: drop?.turn ?? peak.turn, value: drop?.value ?? peak.value, detail: `${drop && drop.delta >= 0 ? '+' : ''}${(drop?.delta ?? 0).toFixed(2)}` },
      ],
    };
  });
  const selectConversation = (next: Conversation) => {
    setSelectedConversationId(next.id);
    setSelectedTurn(next.turns.find((turn) => turn.scores)?.index ?? 1);
  };

  return (
    <main className="app-shell">
      <aside className="conversation-sidebar">
        <div className="sidebar-header">
          <span className="eyebrow">Jev × Qwen × PIPPA</span>
          <h1>Conversation explorer</h1>
          <p>{results.conversationCount} conversations · {datasets.jev.evaluationCount + datasets.qwen.evaluationCount} paired evaluations</p>
        </div>
        <nav className="conversation-list" aria-label="대화 목록">
          {results.conversations.map((item) => {
            const qwenItem = datasets.qwen.conversations.find((candidate) => candidate.id === item.id);
            return (
              <button
                type="button"
                key={item.id}
                className={`conversation-item ${item.id === conversation.id ? 'active' : ''}`}
                onClick={() => selectConversation(item)}
              >
                <span className="conversation-number">{conversationLabel(item.number)}</span>
                <strong>{item.botName || 'Unnamed bot'}</strong>
                <code>{item.id}</code>
                <span className="conversation-stats">
                  {item.totalTurns} turns · J {formatLatency(datasets.jev.conversations[item.number - 1].averageLatencyMs)}
                  {qwenItem ? ` · Q ${formatLatency(qwenItem.averageLatencyMs)}` : ''}
                </span>
              </button>
            );
          })}
        </nav>
      </aside>

      <section className="analysis-panel">
        <header className="analysis-header">
          <div>
            <span className="eyebrow">{conversationLabel(conversation.number)}</span>
            <h2>{conversation.botName || 'Unnamed bot'}</h2>
            <code>{conversation.id}</code>
          </div>
          <div className="header-actions">
            <fieldset className="model-switch">
              <legend className="visually-hidden">표시할 모델</legend>
              {([
                ['compare', 'Compare'],
                ['jev', 'Jev'],
                ['qwen', 'Qwen 27B'],
              ] as const).map(([mode, label]) => (
                <button
                  type="button"
                  key={mode}
                  className={viewMode === mode ? 'active' : ''}
                  aria-pressed={viewMode === mode}
                  onClick={() => setViewMode(mode)}
                >
                  {label}
                </button>
              ))}
            </fieldset>
            <div className="conversation-position">
              <strong>{conversation.number}</strong>
              <span>/ {results.conversationCount}</span>
            </div>
          </div>
        </header>

        <section className="overview-strip" aria-label="대화 요약">
          <div className="summary-stat latency-stat">
            <span>평균 latency</span>
            <strong className="latency-comparison">
              {comparisonConversation ? (
                <>
                  <span><small>J</small> {formatLatency(conversation.averageLatencyMs)}</span>
                  <span><small>Q</small> {formatLatency(comparisonConversation.averageLatencyMs)}</span>
                </>
              ) : formatLatency(conversation.averageLatencyMs)}
            </strong>
            <em>{comparisonConversation ? `${conversation.evaluatedTurns} paired turns` : `${results.modelLabel} · ${conversation.evaluatedTurns} turns`}</em>
          </div>
          {peaks.map((peak, index) => {
            const comparisonPeak = comparisonPeaks[index];
            return <div className="summary-stat" key={peak.key} style={{ borderTopColor: peak.color }}>
              <span>{peak.label} 최고점</span>
              <strong>{comparisonPeak ? `J ${peak.value.toFixed(2)} · Q ${comparisonPeak.value.toFixed(2)}` : peak.value.toFixed(2)}</strong>
              <em>{comparisonPeak ? `turn ${peak.turn} / ${comparisonPeak.turn}` : `turn ${peak.turn}`}</em>
            </div>;
          })}
        </section>

        <section className="chart-panel" aria-label="Score trajectory">
          <div className="chart-heading">
            <div>
              <h3>Score trajectory</h3>
              <p>그래프의 turn을 클릭하면 오른쪽 전체 로그에서 해당 지점으로 이동합니다.</p>
            </div>
            <div className="legend" aria-label="그래프 범례">
              {metrics.map((metric) => (
                <span key={metric.key}>
                  <i style={{ background: metric.color }} />
                  {metric.label}
                </span>
              ))}
              {comparisonConversation && (
                <span className="model-line-key"><b className="solid-line" /> Jev <b className="dash-line" /> Qwen</span>
              )}
            </div>
          </div>
          <TrajectoryChart
            conversation={conversation}
            comparison={comparisonConversation}
            selectedTurn={selectedTurn}
            onSelectTurn={setSelectedTurn}
          />
          <div className="selected-turn-summary">
            <div>
              <span className="eyebrow">Selected point</span>
              <strong>Turn {selectedTurn} · {selected.speaker}</strong>
            </div>
            {selected.scores ? (
              <div className="score-values">
                {metrics.map((metric) => (
                  <span key={metric.key} style={{ borderColor: metric.color }}>
                    {metric.label}
                    <strong>
                      {comparisonSelected?.scores
                        ? `J ${selected.scores![metric.key].toFixed(2)} · Q ${comparisonSelected.scores[metric.key].toFixed(2)}`
                        : selected.scores![metric.key].toFixed(2)}
                    </strong>
                    {comparisonSelected?.scores && (
                      <small>Δ {(comparisonSelected.scores[metric.key] - selected.scores![metric.key]).toFixed(2)}</small>
                    )}
                  </span>
                ))}
              </div>
            ) : (
              <span className="not-evaluated">평가 시작 전 turn</span>
            )}
          </div>
        </section>

        <section className="transitions-panel" aria-label="주요 transition">
          <div className="section-heading">
            <div>
              <span className="eyebrow">Quick inspection</span>
              <h3>눈에 띄는 지점</h3>
            </div>
            <p>{comparisonConversation ? 'Jev 기준 · ' : ''}항목을 클릭하면 해당 turn의 원문으로 이동합니다.</p>
          </div>
          <div className="transition-grid">
            {transitionGroups.map((group) => (
              <article className="transition-group" key={group.key} style={{ borderTopColor: group.color }}>
                <h4>{group.label}</h4>
                {group.points.map((point) => (
                  <button type="button" key={point.label} onClick={() => setSelectedTurn(point.turn)}>
                    <span>{point.label}</span>
                    <strong>Turn {point.turn}</strong>
                    <em>{point.detail}</em>
                  </button>
                ))}
              </article>
            ))}
          </div>
        </section>
      </section>

      <DialogueLog conversation={conversation} comparison={comparisonConversation} selectedTurn={selectedTurn} />
    </main>
  );
}
