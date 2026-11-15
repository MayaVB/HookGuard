import { useNavigate, useSearchParams } from 'react-router';

import { api, DELIVERY_STATUSES, type DeliveryStatus, type DeliverySummary, type Stats } from '../api';
import { StatusBadge } from '../components/Badges';
import { LiveIndicator } from '../components/LiveIndicator';
import { shortId, statusLabel, timeAgo, dateTime } from '../format';
import { usePolling } from '../usePolling';

const PAGE_SIZE = 20;
const POLL_MS = 2000;

export function DashboardPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const statusParam = searchParams.get('status');
  const status = DELIVERY_STATUSES.find((s) => s === statusParam);
  const page = Math.max(0, Number(searchParams.get('page') ?? 0) || 0);

  const stats = usePolling((signal) => api.stats(signal), POLL_MS, 'stats');
  const deliveries = usePolling(
    (signal) => api.deliveries({ status, page, size: PAGE_SIZE }, signal),
    POLL_MS,
    `deliveries:${status ?? 'all'}:${page}`,
  );

  const setFilter = (next: DeliveryStatus | undefined) => {
    setSearchParams(next ? { status: next } : {});
  };
  const setPage = (next: number) => {
    const params: Record<string, string> = { page: String(next) };
    if (status) params.status = status;
    setSearchParams(params);
  };

  return (
    <>
      <div className="page-header">
        <div>
          <h1>Deliveries</h1>
          <p className="muted">Every event accepted by HookGuard and what happened when it was delivered.</p>
        </div>
        <LiveIndicator updatedAt={deliveries.updatedAt} error={deliveries.error ?? stats.error} />
      </div>

      <StatCards stats={stats.data} />

      <section className="panel">
        <div className="panel-toolbar">
          <div className="tabs" role="tablist" aria-label="Filter by status">
            <FilterTab label="All" active={!status} onClick={() => setFilter(undefined)} count={stats.data?.total} />
            {DELIVERY_STATUSES.map((s) => (
              <FilterTab
                key={s}
                label={statusLabel(s)}
                active={status === s}
                onClick={() => setFilter(s)}
                count={stats.data?.byStatus[s]}
              />
            ))}
          </div>
        </div>
        <DeliveriesTable rows={deliveries.data?.items} loading={!deliveries.data && !deliveries.error} />
        {deliveries.data && deliveries.data.totalPages > 1 && (
          <div className="pagination">
            <button className="button" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Previous
            </button>
            <span className="muted">
              Page {page + 1} of {deliveries.data.totalPages}
            </span>
            <button
              className="button"
              disabled={page + 1 >= deliveries.data.totalPages}
              onClick={() => setPage(page + 1)}
            >
              Next
            </button>
          </div>
        )}
      </section>
    </>
  );
}

function StatCards({ stats }: { stats: Stats | undefined }) {
  const value = (n: number | undefined) => (stats === undefined ? '—' : String(n ?? 0));
  const inFlight = stats ? stats.byStatus.PENDING + stats.byStatus.IN_PROGRESS : undefined;
  return (
    <div className="stats">
      <StatCard label="Total deliveries" value={value(stats?.total)} />
      <StatCard label="Delivered" value={value(stats?.byStatus.DELIVERED)} tone="success" />
      <StatCard label="In flight" value={value(inFlight)} tone="info" hint="Pending or in progress" />
      <StatCard label="Failed" value={value(stats?.byStatus.FAILED)} tone="danger" />
    </div>
  );
}

function StatCard({ label, value, tone, hint }: { label: string; value: string; tone?: string; hint?: string }) {
  return (
    <div className={`stat ${tone ? `stat-${tone}` : ''}`}>
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
      {hint && <span className="stat-hint">{hint}</span>}
    </div>
  );
}

function FilterTab(props: { label: string; active: boolean; onClick: () => void; count: number | undefined }) {
  return (
    <button role="tab" aria-selected={props.active} className={`tab ${props.active ? 'tab-active' : ''}`} onClick={props.onClick}>
      {props.label}
      {props.count !== undefined && <span className="tab-count">{props.count}</span>}
    </button>
  );
}

function DeliveriesTable({ rows, loading }: { rows: DeliverySummary[] | undefined; loading: boolean }) {
  const navigate = useNavigate();
  if (loading) return <p className="empty">Loading deliveries…</p>;
  if (!rows || rows.length === 0) {
    return (
      <div className="empty">
        <p>No deliveries yet.</p>
        <p className="muted">Register an endpoint and submit an event (see README → Quick demo).</p>
      </div>
    );
  }
  return (
    <div className="table-scroll">
      <table className="table">
        <thead>
          <tr>
            <th>Status</th>
            <th>Event</th>
            <th>Endpoint</th>
            <th className="num">Attempts</th>
            <th>Last result</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((d) => (
            <tr
              key={d.id}
              className="clickable"
              tabIndex={0}
              onClick={() => navigate(`/deliveries/${d.id}`)}
              onKeyDown={(e) => e.key === 'Enter' && navigate(`/deliveries/${d.id}`)}
            >
              <td>
                <StatusBadge status={d.status} />
              </td>
              <td>
                <div className="cell-main">{d.eventType}</div>
                <div className="cell-sub mono">{shortId(d.id)}</div>
              </td>
              <td className="cell-url mono" title={d.endpointUrl}>
                {d.endpointUrl}
              </td>
              <td className="num">{d.attemptCount}</td>
              <td>
                <LastResult delivery={d} />
              </td>
              <td title={dateTime(d.createdAt)}>{timeAgo(d.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function LastResult({ delivery }: { delivery: DeliverySummary }) {
  if (delivery.attemptCount === 0) return <span className="muted">Not attempted yet</span>;
  if (delivery.status === 'IN_PROGRESS' && delivery.leaseExpired) {
    return <span className="text-warning">Lease expired: worker gone, stuck until recovery</span>;
  }
  if (delivery.status === 'IN_PROGRESS') return <span className="muted">Attempt {delivery.attemptCount} running…</span>;
  if (delivery.lastError) {
    return (
      <span className="text-danger" title={delivery.lastError}>
        {delivery.lastError}
      </span>
    );
  }
  return delivery.lastHttpStatus ? <span className="mono">HTTP {delivery.lastHttpStatus}</span> : <span className="muted">—</span>;
}
