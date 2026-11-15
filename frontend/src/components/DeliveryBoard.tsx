import { useLayoutEffect, useRef } from 'react';
import { useNavigate } from 'react-router';

import type { DeliveryStatus, DeliverySummary } from '../api';
import { shortId } from '../format';

const CARDS_PER_COLUMN = 10;

interface Props {
  deliveries: DeliverySummary[];
  receiverBaseUrl: string | undefined;
}

/** Deliveries as cards in status columns; cards animate between columns as database state changes. */
export function DeliveryBoard({ deliveries, receiverBaseUrl }: Props) {
  const boardRef = useRef<HTMLDivElement>(null);
  useFlipAnimation(boardRef);

  const byStatus = (status: DeliveryStatus) => deliveries.filter((d) => d.status === status);
  const inProgress = byStatus('IN_PROGRESS');
  const lanes = new Map<string, DeliverySummary[]>();
  for (const d of inProgress) {
    const worker = d.claimedBy ?? 'unknown';
    lanes.set(worker, [...(lanes.get(worker) ?? []), d]);
  }

  return (
    <div className="board" ref={boardRef}>
      <Column title="Pending" hint="stored, waiting for a worker" tone="neutral" items={byStatus('PENDING')} base={receiverBaseUrl} />
      <div className="column">
        <ColumnHeader title="In progress" hint="claimed by a worker" tone="info" count={inProgress.length} />
        {inProgress.length === 0 && <div className="column-empty">—</div>}
        {[...lanes].map(([worker, items]) => (
          <div key={worker} className="lane">
            <div className="lane-title mono">{worker}</div>
            {items.map((d) => (
              <Card key={d.id} delivery={d} base={receiverBaseUrl} />
            ))}
          </div>
        ))}
      </div>
      <Column title="Delivered" hint="receiver answered 2xx" tone="success" items={byStatus('DELIVERED')} base={receiverBaseUrl} />
      <Column title="Failed" hint="final: no more attempts" tone="danger" items={byStatus('FAILED')} base={receiverBaseUrl} />
    </div>
  );
}

function ColumnHeader({ title, hint, tone, count }: { title: string; hint: string; tone: string; count: number }) {
  return (
    <div className={`column-header column-${tone}`}>
      <span className="column-title">
        {title} <span className="column-count">{count}</span>
      </span>
      <span className="column-hint">{hint}</span>
    </div>
  );
}

function Column(props: { title: string; hint: string; tone: string; items: DeliverySummary[]; base: string | undefined }) {
  const visible = props.items.slice(0, CARDS_PER_COLUMN);
  return (
    <div className="column">
      <ColumnHeader title={props.title} hint={props.hint} tone={props.tone} count={props.items.length} />
      {visible.length === 0 && <div className="column-empty">—</div>}
      {visible.map((d) => (
        <Card key={d.id} delivery={d} base={props.base} />
      ))}
      {props.items.length > visible.length && (
        <div className="column-more">+ {props.items.length - visible.length} more</div>
      )}
    </div>
  );
}

function Card({ delivery: d, base }: { delivery: DeliverySummary; base: string | undefined }) {
  const navigate = useNavigate();
  const path = base && d.endpointUrl.startsWith(base) ? d.endpointUrl.slice(base.length) : d.endpointUrl;
  return (
    <button
      type="button"
      data-flip-id={d.id}
      className={`card card-${d.status.toLowerCase()} ${d.leaseExpired ? 'card-stuck' : ''}`}
      onClick={() => navigate(`/deliveries/${d.id}`)}
      title="Open delivery lifecycle"
    >
      <span className="card-top">
        <span className="card-type">{d.eventType}</span>
        <span className="mono card-id">{shortId(d.id)}</span>
      </span>
      <span className="card-path mono">{path}</span>
      <span className="card-status">
        <CardStatus delivery={d} />
      </span>
    </button>
  );
}

function CardStatus({ delivery: d }: { delivery: DeliverySummary }) {
  switch (d.status) {
    case 'PENDING':
      return <span className="muted">waiting for a worker</span>;
    case 'IN_PROGRESS':
      return d.leaseExpired ? (
        <span className="text-warning">lease expired: worker gone, stuck until recovery</span>
      ) : (
        <span className="text-info">
          <span className="pulse" aria-hidden="true" /> attempt {d.attemptCount} running
        </span>
      );
    case 'DELIVERED':
      return <span className="text-success">HTTP {d.lastHttpStatus}</span>;
    case 'FAILED':
      return <span className="text-danger">{d.lastError}</span>;
  }
}

/**
 * FLIP animation: after each render, compare every card's position with where it was in the
 * previous render and animate from the old position to the new one (Web Animations API).
 */
function useFlipAnimation(containerRef: React.RefObject<HTMLDivElement | null>) {
  const positions = useRef(new Map<string, { x: number; y: number }>());
  useLayoutEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const origin = container.getBoundingClientRect();
    const next = new Map<string, { x: number; y: number }>();
    container.querySelectorAll<HTMLElement>('[data-flip-id]').forEach((node) => {
      const id = node.dataset.flipId as string;
      const rect = node.getBoundingClientRect();
      const position = { x: rect.left - origin.left, y: rect.top - origin.top };
      next.set(id, position);
      if (reduceMotion) return;
      const before = positions.current.get(id);
      if (!before) {
        node.animate([{ opacity: 0, transform: 'scale(0.92)' }, { opacity: 1, transform: 'none' }], {
          duration: 350,
          easing: 'ease-out',
        });
      } else if (before.x !== position.x || before.y !== position.y) {
        node.animate(
          [{ transform: `translate(${before.x - position.x}px, ${before.y - position.y}px)` }, { transform: 'none' }],
          { duration: 550, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' },
        );
      }
    });
    positions.current = next;
  });
}
