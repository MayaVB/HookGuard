import type { ReceivedRequest } from '../api';
import { shortId } from '../format';

const time = new Intl.DateTimeFormat('en', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });

export function ReceiverLog({ requests, onClear }: { requests: ReceivedRequest[] | undefined; onClear: () => void }) {
  return (
    <section className="panel">
      <div className="panel-heading">
        <h2>Demo receiver log</h2>
        <button className="button button-small" onClick={onClear}>
          Clear
        </button>
      </div>
      <p className="muted small">
        What the receiver actually got. A <span className="chip chip-warning">duplicate</span> is a delivery it had already
        acknowledged with 2xx — the case receivers must handle using <span className="mono">X-HookGuard-Delivery-Id</span>.
      </p>
      {!requests || requests.length === 0 ? (
        <div className="column-empty">No requests yet</div>
      ) : (
        <ul className="log">
          {requests.slice(0, 14).map((r, i) => (
            <li key={`${r.receivedAt}-${r.deliveryId}-${i}`}>
              <span className="mono muted">{time.format(new Date(r.receivedAt))}</span>
              <span className={`chip ${r.respondedStatus < 300 ? 'chip-success' : 'chip-danger'}`}>{r.respondedStatus}</span>
              <span className="mono log-path">{r.path}</span>
              <span className="mono muted">{r.deliveryId ? shortId(r.deliveryId) : '—'}</span>
              {r.duplicate && <span className="chip chip-warning">duplicate</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
