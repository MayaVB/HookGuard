import { useEffect, useRef, useState } from 'react';

import type { Overview, ReceivedRequest, ReceiverControl } from '../api';

type EdgeId = 'producer-api' | 'api-queue' | 'queue-workers' | 'workers-receiver' | 'api-db' | 'workers-db';
type PulseTone = 'neutral' | 'success' | 'danger' | 'info';

interface Pulse {
  key: number;
  edge: EdgeId;
  tone: PulseTone;
  delayMs: number;
}

interface Props {
  overview: Overview | undefined;
  receiverLog: ReceivedRequest[] | undefined;
  receiverControl: ReceiverControl | undefined;
  eventsSent: number;
}

const MAX_PULSES_PER_EDGE = 8;

/**
 * The architecture as a live diagram. Every pulse corresponds to a change observed between two
 * polls of real state (new deliveries, new claims, new requests logged by the receiver).
 */
export function Pipeline({ overview, receiverLog, receiverControl, eventsSent }: Props) {
  const [pulses, setPulses] = useState<Pulse[]>([]);
  const previous = useRef<{ total?: number; claims?: number; attempts?: number; seen?: Set<string> }>({});
  const nextKey = useRef(0);

  useEffect(() => {
    if (!overview) return;
    const prev = previous.current;
    const total = overview.deliveries.total;
    const claims = overview.attemptsRecorded + overview.deliveries.byStatus.IN_PROGRESS;
    const added: Pulse[] = [];
    const add = (edge: EdgeId, count: number, tone: PulseTone, baseDelay = 0) => {
      for (let i = 0; i < Math.min(count, MAX_PULSES_PER_EDGE); i++) {
        added.push({ key: nextKey.current++, edge, tone, delayMs: baseDelay + i * 110 });
      }
    };
    if (prev.total !== undefined && total > prev.total) {
      const n = total - prev.total;
      add('producer-api', n, 'neutral');
      add('api-db', n, 'neutral', 250);
      add('api-queue', n, 'neutral', 450);
    }
    if (prev.claims !== undefined && claims > prev.claims) {
      add('queue-workers', claims - prev.claims, 'info');
      add('workers-db', claims - prev.claims, 'info', 200);
    }
    if (prev.attempts !== undefined && overview.attemptsRecorded > prev.attempts) {
      add('workers-db', overview.attemptsRecorded - prev.attempts, 'success');
    }
    prev.total = total;
    prev.claims = claims;
    prev.attempts = overview.attemptsRecorded;
    if (added.length) setPulses((current) => [...current, ...added]);
  }, [overview]);

  useEffect(() => {
    if (!receiverLog) return;
    const prev = previous.current;
    const keys = receiverLog.map((r) => `${r.receivedAt}|${r.deliveryId}|${r.attempt}|${r.path}`);
    if (prev.seen) {
      const fresh = receiverLog.filter((_, i) => !prev.seen!.has(keys[i] as string));
      const added = fresh.slice(0, MAX_PULSES_PER_EDGE).map((r, i) => ({
        key: nextKey.current++,
        edge: 'workers-receiver' as const,
        tone: (r.respondedStatus < 300 ? 'success' : 'danger') as PulseTone,
        delayMs: i * 110,
      }));
      if (added.length) setPulses((current) => [...current, ...added]);
    }
    prev.seen = new Set(keys);
  }, [receiverLog]);

  const done = (key: number) => setPulses((current) => current.filter((p) => p.key !== key));
  const edge = (id: EdgeId, vertical = false) => (
    <Connector vertical={vertical} pulses={pulses.filter((p) => p.edge === id)} onDone={done} />
  );

  const stats = overview?.deliveries.byStatus;
  const queue = overview?.workQueue;
  const dlq = overview?.deadLetterQueue;
  const brokerDown = queue !== undefined && !queue.reachable;
  const expired = overview?.workers.reduce((sum, w) => sum + w.expiredLeases, 0) ?? 0;
  const lastRequest = receiverLog?.[0];

  return (
    <div className="pipeline-scroll">
      <div className="pipeline">
        <Node className="n-producer" title="Producer" subtitle="You · scenario buttons">
          <Metric label="events sent (this page)" value={eventsSent} />
        </Node>
        {edge('producer-api')}
        <Node className="n-api" title="API" subtitle="Spring Boot · profile api">
          <Metric label="deliveries accepted" value={overview?.deliveries.total} />
        </Node>
        {edge('api-queue')}
        <Node className="n-queue" title="RabbitMQ" subtitle="quorum queue + DLQ" state={brokerDown ? 'down' : undefined}>
          {brokerDown ? (
            <div className="node-alert">Unreachable</div>
          ) : (
            <>
              <Metric label="ready messages" value={queue?.readyMessages ?? undefined} />
              <Metric label="consumers" value={queue?.consumers ?? undefined} />
              <Metric label="dead-lettered" value={dlq?.readyMessages ?? undefined} warn={(dlq?.readyMessages ?? 0) > 0} />
            </>
          )}
        </Node>
        {edge('queue-workers')}
        <Node className="n-workers" title="Workers" subtitle="profile worker · active in last 10 min">
          {overview && overview.workers.length === 0 && <div className="muted small">No activity yet</div>}
          <ul className="worker-list">
            {overview?.workers.map((w) => (
              <li key={w.workerId} className={w.expiredLeases > 0 ? 'worker-stuck' : ''}>
                <span className="mono">{w.workerId}</span>
                <span className="worker-stats">
                  {w.inFlight - w.expiredLeases > 0 && <span className="chip chip-info">{w.inFlight - w.expiredLeases} in flight</span>}
                  {w.expiredLeases > 0 && <span className="chip chip-warning">{w.expiredLeases} lease expired</span>}
                  <span className="muted">{w.attemptsLast10Minutes} attempts</span>
                </span>
              </li>
            ))}
          </ul>
        </Node>
        {edge('workers-receiver')}
        <Node className="n-receiver" title="Demo receiver" subtitle="failure simulator" state={receiverControl?.outage ? 'down' : undefined}>
          {receiverControl?.outage ? <div className="node-alert">Outage: answering 503</div> : <Metric label="status" value="up" />}
          <Metric label="requests logged" value={receiverControl?.loggedRequests} />
          {lastRequest && (
            <Metric
              label="last response"
              value={`HTTP ${lastRequest.respondedStatus}`}
              warn={lastRequest.respondedStatus >= 300}
            />
          )}
        </Node>

        <div className="v-api">{edge('api-db', true)}</div>
        <div className="v-workers">{edge('workers-db', true)}</div>

        <Node className="n-db" title="PostgreSQL" subtitle="source of truth · atomic claims · attempt history">
          <div className="db-counts">
            <span className="chip chip-neutral">Pending {stats?.PENDING ?? '—'}</span>
            <span className="chip chip-info">In progress {stats?.IN_PROGRESS ?? '—'}</span>
            <span className="chip chip-success">Delivered {stats?.DELIVERED ?? '—'}</span>
            <span className="chip chip-danger">Failed {stats?.FAILED ?? '—'}</span>
            {expired > 0 && <span className="chip chip-warning">{expired} expired lease{expired > 1 ? 's' : ''}</span>}
            <span className="muted small">{overview?.attemptsRecorded ?? '—'} attempts recorded</span>
          </div>
        </Node>
      </div>
    </div>
  );
}

function Node(props: { className: string; title: string; subtitle: string; state?: 'down'; children: React.ReactNode }) {
  return (
    <div className={`node ${props.className} ${props.state === 'down' ? 'node-down' : ''}`}>
      <div className="node-title">{props.title}</div>
      <div className="node-subtitle">{props.subtitle}</div>
      <div className="node-body">{props.children}</div>
    </div>
  );
}

function Metric({ label, value, warn }: { label: string; value: number | string | undefined; warn?: boolean }) {
  return (
    <div className="metric">
      <span className="metric-label">{label}</span>
      <span className={`metric-value ${warn ? 'text-danger' : ''}`}>{value ?? '—'}</span>
    </div>
  );
}

function Connector({ vertical, pulses, onDone }: { vertical: boolean; pulses: Pulse[]; onDone: (key: number) => void }) {
  return (
    <div className={`connector ${vertical ? 'connector-v' : 'connector-h'}`} aria-hidden="true">
      <span className="connector-line" />
      {pulses.map((p) => (
        <span
          key={p.key}
          className={`pulse-dot dot-${p.tone}`}
          style={{ animationDelay: `${p.delayMs}ms` }}
          onAnimationEnd={() => onDone(p.key)}
        />
      ))}
    </div>
  );
}
