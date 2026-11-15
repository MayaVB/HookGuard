import { useCallback, useEffect, useRef, useState } from 'react';

import { api, ApiError, receiver } from '../api';
import { DeliveryBoard } from '../components/DeliveryBoard';
import { LiveIndicator } from '../components/LiveIndicator';
import { Pipeline } from '../components/Pipeline';
import { ReceiverLog } from '../components/ReceiverLog';
import { SCENARIOS, type Scenario, type Tone } from '../demo/scenarios';
import { usePolling } from '../usePolling';

const POLL_MS = 1000;

interface LogEntry {
  id: number;
  at: Date;
  text: string;
  tone: Tone;
  result?: string[];
}

type DemoAvailability = { state: 'loading' } | { state: 'enabled'; receiverBaseUrl: string } | { state: 'disabled'; reason: string };

export function LivePage() {
  const [demo, setDemo] = useState<DemoAvailability>({ state: 'loading' });
  const [running, setRunning] = useState<Set<string>>(new Set());
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [eventsSent, setEventsSent] = useState(0);
  const [sessionOnly, setSessionOnly] = useState(true);
  const [outageBusy, setOutageBusy] = useState(false);
  const sessionStart = useRef<string | undefined>(undefined);
  // Caches the in-flight promise (not the ID) so concurrent scenarios never register the same endpoint twice.
  const endpointCache = useRef(new Map<string, Promise<string>>());
  const nextId = useRef(0);

  useEffect(() => {
    api
      .demoInfo()
      .then((info) => setDemo({ state: 'enabled', receiverBaseUrl: info.receiverBaseUrl }))
      .catch((error: unknown) =>
        setDemo({
          state: 'disabled',
          reason:
            error instanceof ApiError && error.status === 404
              ? 'Demo controls are off. Start the API with HOOKGUARD_DEMO_ENABLED=true (docker compose does).'
              : `Could not reach the API: ${(error as Error).message}`,
        }),
      );
  }, []);

  const overview = usePolling((signal) => api.overview(signal), POLL_MS, 'overview');
  const deliveries = usePolling((signal) => api.deliveries({ page: 0, size: 100 }, signal), POLL_MS, 'live-deliveries');
  const receiverState = usePolling(
    async (signal) => {
      const [log, control] = await Promise.all([receiver.received(60, signal), receiver.control(signal)]);
      return { log, control };
    },
    POLL_MS,
    'receiver',
  );

  if (overview.data && sessionStart.current === undefined) {
    sessionStart.current = overview.data.serverTime;
  }

  const log = useCallback((text: string, tone: Tone = 'neutral', result?: string[]) => {
    setEntries((current) => [{ id: nextId.current++, at: new Date(), text, tone, result }, ...current].slice(0, 40));
  }, []);

  const ensureEndpoint = useCallback(
    async (path: string, description: string) => {
      if (demo.state !== 'enabled') throw new Error('Demo controls are disabled');
      const url = demo.receiverBaseUrl + path;
      const cached = endpointCache.current.get(url);
      if (cached) return cached;
      const pending = (async () => {
        const existing = (await api.endpoints()).find((e) => e.url === url);
        if (existing) return existing.id;
        const created = await api.registerEndpoint(url, description);
        log(`Registered endpoint ${path} (allowlist check passed)`);
        return created.id;
      })();
      endpointCache.current.set(url, pending);
      pending.catch(() => endpointCache.current.delete(url)); // allow a retry after a failure
      return pending;
    },
    [demo, log],
  );

  const run = async (scenario: Scenario) => {
    setRunning((current) => new Set(current).add(scenario.id));
    log(`▶ ${scenario.title} — watch: ${scenario.watch}`, 'info');
    try {
      const result = await scenario.run({
        ensureEndpoint,
        log: (text, tone) => log(text, tone),
        countSent: (n) => setEventsSent((c) => c + n),
      });
      log(`✓ ${scenario.title} finished`, scenario.tone, result);
    } catch (error) {
      log(`✗ ${scenario.title}: ${(error as Error).message}`, 'danger');
    } finally {
      setRunning((current) => {
        const next = new Set(current);
        next.delete(scenario.id);
        return next;
      });
    }
  };

  const outage = receiverState.data?.control.outage ?? false;
  const toggleOutage = async () => {
    setOutageBusy(true);
    try {
      await receiver.setOutage(!outage);
      log(outage ? 'Receiver back up' : 'Receiver outage ON: every webhook now gets 503', outage ? 'success' : 'danger');
    } catch (error) {
      log(`Could not toggle the outage: ${(error as Error).message}`, 'danger');
    } finally {
      setOutageBusy(false);
    }
  };

  const allDeliveries = deliveries.data?.items ?? [];
  const start = sessionStart.current;
  const shown = sessionOnly && start ? allDeliveries.filter((d) => d.createdAt >= start) : allDeliveries;
  const receiverBaseUrl = demo.state === 'enabled' ? demo.receiverBaseUrl : undefined;
  const error = overview.error ?? deliveries.error;

  return (
    <>
      <div className="page-header">
        <div>
          <h1>Live demo</h1>
          <p className="muted">
            Everything here is live state read from PostgreSQL, RabbitMQ and the demo receiver once a second. Nothing is
            simulated in the browser.
          </p>
        </div>
        <LiveIndicator updatedAt={overview.updatedAt} error={error} />
      </div>

      <section className="panel pipeline-panel">
        <Pipeline
          overview={overview.data}
          receiverLog={receiverState.data?.log}
          receiverControl={receiverState.data?.control}
          eventsSent={eventsSent}
        />
      </section>

      <div className="live-grid">
        <aside className="side">
          <section className="panel">
            <h2>Scenarios</h2>
            {demo.state === 'disabled' && <p className="text-warning small">{demo.reason}</p>}
            <div className="scenarios">
              {SCENARIOS.map((s) => (
                <button
                  key={s.id}
                  className={`scenario scenario-${s.tone}`}
                  disabled={demo.state !== 'enabled' || running.has(s.id)}
                  onClick={() => void run(s)}
                >
                  <span className="scenario-title">
                    {s.title}
                    {running.has(s.id) && <span className="spinner" aria-label="running" />}
                  </span>
                  <span className="scenario-description">{s.description}</span>
                </button>
              ))}
            </div>
            <label className={`switch ${outage ? 'switch-on' : ''}`}>
              <input type="checkbox" checked={outage} disabled={outageBusy || !receiverState.data} onChange={() => void toggleOutage()} />
              <span className="switch-track" aria-hidden="true">
                <span className="switch-thumb" />
              </span>
              <span>
                <strong>Receiver outage</strong>
                <span className="muted small block">Every webhook gets 503 while on</span>
              </span>
            </label>
          </section>

          <section className="panel">
            <h2>Break it from a terminal</h2>
            <Recipe
              title="Kill a worker mid-delivery"
              command="docker compose kill worker && docker compose up -d worker"
              text="Run Timeout first. The card stays In progress and turns amber once its 30 s lease expires. Phase 2 recovers it."
            />
            <Recipe
              title="Stop the broker"
              command="docker compose stop rabbitmq"
              text="RabbitMQ turns red; new events are stored but stay Pending, even after it restarts. Phase 2's relay re-publishes them."
            />
            <Recipe
              title="Add workers"
              command="docker compose up -d --scale worker=3"
              text="Then run Burst: three worker lanes share the work, still with zero duplicates."
            />
          </section>
        </aside>

        <section className="panel board-panel">
          <div className="panel-heading">
            <h2>Deliveries</h2>
            <label className="checkbox small">
              <input type="checkbox" checked={sessionOnly} onChange={(e) => setSessionOnly(e.target.checked)} />
              Only since this page was opened
            </label>
          </div>
          {shown.length === 0 ? (
            <div className="column-empty board-empty">Run a scenario to see deliveries flow through the system.</div>
          ) : (
            <DeliveryBoard deliveries={shown} receiverBaseUrl={receiverBaseUrl} />
          )}
        </section>
      </div>

      <div className="live-bottom">
        <section className="panel">
          <h2>What happened</h2>
          {entries.length === 0 ? (
            <div className="column-empty">Scenario steps and verified results appear here.</div>
          ) : (
            <ul className="narration">
              {entries.map((e) => (
                <li key={e.id} className={`narration-${e.tone}`}>
                  <span className="mono muted">{e.at.toLocaleTimeString('en', { hour12: false })}</span>
                  <div>
                    <div>{e.text}</div>
                    {e.result && (
                      <ul className="result">
                        {e.result.map((line) => (
                          <li key={line}>{line}</li>
                        ))}
                      </ul>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
        <ReceiverLog
          requests={receiverState.data?.log}
          onClear={() => void receiver.clear().then(() => log('Receiver log cleared'))}
        />
      </div>
    </>
  );
}

function Recipe({ title, command, text }: { title: string; command: string; text: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(command);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard unavailable; the command is still visible to copy by hand
    }
  };
  return (
    <div className="recipe">
      <div className="recipe-title">{title}</div>
      <div className="recipe-command">
        <code>{command}</code>
        <button className="button button-small" onClick={() => void copy()}>
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <p className="muted small">{text}</p>
    </div>
  );
}
