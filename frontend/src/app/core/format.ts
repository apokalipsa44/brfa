/** Date/time helpers for the sidebar and analysis headers. */

/** "just now", "4 min ago", "2 h ago", "3 days ago", then a date. */
export function relativeTime(iso: string, now: Date = new Date()): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const seconds = Math.max(0, Math.round((now.getTime() - then) / 1000));
  if (seconds < 45) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  if (days <= 7) return `${days} ${days === 1 ? 'day' : 'days'} ago`;
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }).format(
    new Date(then),
  );
}

/** "12 Oct 2026, 15:04" for the analysis header. */
export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

/**
 * Parses the backend's applied date (an ISO local date) into a local Date without a UTC shift.
 * Falls back to a generic parse for anything that is not yyyy-MM-dd.
 */
function parseLocalDate(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (match) return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** "14 Oct" — the applied date, with the year only when it is not the current year. */
export function formatAppliedDate(iso: string, now: Date = new Date()): string {
  const date = parseLocalDate(iso);
  if (!date) return '';
  const options: Intl.DateTimeFormatOptions =
    date.getFullYear() === now.getFullYear()
      ? { day: 'numeric', month: 'short' }
      : { day: 'numeric', month: 'short', year: 'numeric' };
  return new Intl.DateTimeFormat('en-GB', options).format(date);
}

/** "14 Oct 2026" — the full applied date for tooltips and the detail header. */
export function formatFullDate(iso: string): string {
  const date = parseLocalDate(iso);
  if (!date) return '';
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }).format(date);
}

/** Today as an ISO local date (yyyy-MM-dd), matching the backend's appliedAt. */
export function todayIso(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '';
  const kb = bytes / 1024;
  if (kb < 1000) return `${Math.max(1, Math.round(kb))} KB`;
  return `${(kb / 1024).toFixed(1)} MB`;
}
