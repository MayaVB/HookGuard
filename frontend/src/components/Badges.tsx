import type { AttemptOutcome, DeliveryStatus } from '../api';
import { statusLabel } from '../format';

const STATUS_TONE: Record<DeliveryStatus, string> = {
  PENDING: 'neutral',
  IN_PROGRESS: 'info',
  DELIVERED: 'success',
  FAILED: 'danger',
};

const OUTCOME_TONE: Record<AttemptOutcome, string> = {
  SUCCESS: 'success',
  HTTP_ERROR: 'danger',
  TIMEOUT: 'warning',
  CONNECTION_ERROR: 'danger',
  REJECTED: 'danger',
};

export function StatusBadge({ status }: { status: DeliveryStatus }) {
  return (
    <span className={`badge badge-${STATUS_TONE[status]}`}>
      {status === 'IN_PROGRESS' && <span className="pulse" aria-hidden="true" />}
      {statusLabel(status)}
    </span>
  );
}

export function OutcomeBadge({ outcome }: { outcome: AttemptOutcome }) {
  return <span className={`badge badge-${OUTCOME_TONE[outcome]}`}>{statusLabel(outcome)}</span>;
}
