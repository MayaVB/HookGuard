import { timeAgo } from '../format';

interface Props {
  updatedAt: Date | undefined;
  error: Error | undefined;
}

/** Shows whether the data on screen is fresh, so stale numbers are never mistaken for live ones. */
export function LiveIndicator({ updatedAt, error }: Props) {
  if (error) {
    return (
      <span className="live live-error" role="status">
        <span className="dot" aria-hidden="true" />
        API unreachable — {error.message}
      </span>
    );
  }
  return (
    <span className="live" role="status">
      <span className="dot" aria-hidden="true" />
      {updatedAt ? `Live · updated ${timeAgo(updatedAt.toISOString())}` : 'Connecting…'}
    </span>
  );
}
