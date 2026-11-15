const relative = new Intl.RelativeTimeFormat('en', { numeric: 'auto', style: 'short' });
const absolute = new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeStyle: 'medium' });

export function timeAgo(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return '—';
  const seconds = Math.round((new Date(iso).getTime() - now.getTime()) / 1000);
  const abs = Math.abs(seconds);
  if (abs < 5) return 'just now';
  if (abs < 60) return relative.format(seconds, 'second');
  if (abs < 3600) return relative.format(Math.round(seconds / 60), 'minute');
  if (abs < 86400) return relative.format(Math.round(seconds / 3600), 'hour');
  return relative.format(Math.round(seconds / 86400), 'day');
}

export function dateTime(iso: string | null | undefined): string {
  return iso ? absolute.format(new Date(iso)) : '—';
}

export function duration(ms: number): string {
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`;
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}

export function statusLabel(status: string): string {
  return status
    .toLowerCase()
    .split('_')
    .map((word, i) => (i === 0 ? word.charAt(0).toUpperCase() + word.slice(1) : word))
    .join(' ');
}
