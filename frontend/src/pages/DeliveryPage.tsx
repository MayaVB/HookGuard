import { Link, useParams } from 'react-router';

import { api, ApiError, isTerminal, type Attempt, type DeliveryDetail } from '../api';
import { OutcomeBadge, StatusBadge } from '../components/Badges';
import { LiveIndicator } from '../components/LiveIndicator';
import { dateTime, duration, timeAgo } from '../format';
import { usePolling } from '../usePolling';

export function DeliveryPage() {
  const { id = '' } = useParams();
  // Keep polling until the delivery reaches a terminal state, then stop.
  const { data, error, updatedAt } = usePolling(
    (signal) => api.delivery(id, signal),
    2000,
    id,
    (d) => !isTerminal(d.status),
  );

  if (error instanceof ApiError && error.status === 404) {
    return (
      <>
        <BackLink />
        <div className="panel empty">Delivery {id} was not found.</div>
      </>
    );
  }
  if (!data) {
    return (
      <>
        <BackLink />
        <p className="empty">{error ? `Could not load delivery: ${error.message}` : 'Loading…'}</p>
      </>
    );
  }

  return (
    <>
      <BackLink />
      <div className="page-header">
        <div>
          <div className="title-row">
            <h1>{data.event.eventType}</h1>
            <StatusBadge status={data.status} />
          </div>
          <p className="muted mono">Delivery {data.id}</p>
        </div>
        {isTerminal(data.status) ? (
          <span className="live live-done">Final state · polling stopped</span>
        ) : (
          <LiveIndicator updatedAt={updatedAt} error={error} />
        )}
      </div>

      <div className="detail-grid">
        <section className="panel">
          <h2>Lifecycle</h2>
          <Timeline delivery={data} />
        </section>

        <aside className="side">
          <section className="panel">
            <h2>Details</h2>
            <dl className="facts">
              <Fact label="Endpoint">
                <span className="mono break">{data.endpoint.url}</span>
              </Fact>
              <Fact label="Event ID">
                <span className="mono break">{data.event.id}</span>
              </Fact>
              <Fact label="Attempts">{data.attemptCount}</Fact>
              <Fact label="Created">{dateTime(data.createdAt)}</Fact>
              <Fact label="Completed">{dateTime(data.completedAt)}</Fact>
              {data.lastError && (
                <Fact label="Last error">
                  <span className="text-danger">{data.lastError}</span>
                </Fact>
              )}
            </dl>
          </section>
          <section className="panel">
            <h2>Payload</h2>
            <pre className="code">{JSON.stringify(data.event.payload, null, 2)}</pre>
          </section>
        </aside>
      </div>
    </>
  );
}

function BackLink() {
  return (
    <Link to="/" className="back">
      ← All deliveries
    </Link>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  );
}

function Timeline({ delivery }: { delivery: DeliveryDetail }) {
  return (
    <ol className="timeline">
      <li className="step">
        <span className="step-marker neutral" />
        <div className="step-body">
          <div className="step-title">Event accepted, delivery created</div>
          <div className="step-meta">{dateTime(delivery.createdAt)}</div>
        </div>
      </li>

      {delivery.attempts.map((attempt) => (
        <AttemptStep key={attempt.attemptNumber} attempt={attempt} />
      ))}

      {delivery.status === 'PENDING' && (
        <li className="step">
          <span className="step-marker neutral pending" />
          <div className="step-body">
            <div className="step-title">Waiting for a worker</div>
            <div className="step-meta">Queued {timeAgo(delivery.createdAt)}</div>
          </div>
        </li>
      )}
      {delivery.status === 'IN_PROGRESS' && !delivery.leaseExpired && (
        <li className="step">
          <span className="step-marker info pending" />
          <div className="step-body">
            <div className="step-title">Attempt {delivery.attemptCount} in progress</div>
            <div className="step-meta">
              Claimed by <span className="mono">{delivery.claimedBy}</span> · lease expires {timeAgo(delivery.claimExpiresAt)}
            </div>
          </div>
        </li>
      )}
      {delivery.status === 'IN_PROGRESS' && delivery.leaseExpired && (
        <li className="step">
          <span className="step-marker warning" />
          <div className="step-body">
            <div className="step-title">Stuck: claim lease expired {timeAgo(delivery.claimExpiresAt)}</div>
            <div className="step-meta">
              Worker <span className="mono">{delivery.claimedBy}</span> claimed attempt {delivery.attemptCount} and never
              reported back (it most likely crashed). The receiver may or may not have received the request.
            </div>
            <div className="step-error">Phase 1 has no recovery for this; Phase 2 reclaims expired leases.</div>
          </div>
        </li>
      )}
      {delivery.status === 'DELIVERED' && (
        <li className="step">
          <span className="step-marker success" />
          <div className="step-body">
            <div className="step-title">Delivered</div>
            <div className="step-meta">{dateTime(delivery.completedAt)}</div>
          </div>
        </li>
      )}
      {delivery.status === 'FAILED' && (
        <li className="step">
          <span className="step-marker danger" />
          <div className="step-body">
            <div className="step-title">Failed permanently</div>
            <div className="step-meta">{dateTime(delivery.completedAt)} · no further attempts will be made</div>
          </div>
        </li>
      )}
    </ol>
  );
}

function AttemptStep({ attempt }: { attempt: Attempt }) {
  const tone = attempt.outcome === 'SUCCESS' ? 'success' : attempt.outcome === 'TIMEOUT' ? 'warning' : 'danger';
  return (
    <li className="step">
      <span className={`step-marker ${tone}`} />
      <div className="step-body">
        <div className="step-title">
          Attempt {attempt.attemptNumber} <OutcomeBadge outcome={attempt.outcome} />
          {attempt.httpStatus !== null && <span className="mono http">HTTP {attempt.httpStatus}</span>}
        </div>
        <div className="step-meta">
          {dateTime(attempt.startedAt)} · {duration(attempt.durationMs)} · worker <span className="mono">{attempt.workerId}</span>
        </div>
        {attempt.errorMessage && <div className="step-error">{attempt.errorMessage}</div>}
        {attempt.responseBody && (
          <details className="response">
            <summary>
              Response body{attempt.responseTruncated ? ' (truncated)' : ''}
            </summary>
            <pre className="code">{attempt.responseBody}</pre>
          </details>
        )}
      </div>
    </li>
  );
}
