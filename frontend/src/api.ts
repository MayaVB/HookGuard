// Types mirror the HookGuard REST API (hookguard-service, package io.hookguard.delivery.api).

export const DELIVERY_STATUSES = ['PENDING', 'IN_PROGRESS', 'DELIVERED', 'FAILED'] as const;
export type DeliveryStatus = (typeof DELIVERY_STATUSES)[number];

export type AttemptOutcome = 'SUCCESS' | 'HTTP_ERROR' | 'TIMEOUT' | 'CONNECTION_ERROR' | 'REJECTED';

export interface DeliverySummary {
  id: string;
  eventId: string;
  eventType: string;
  endpointId: string;
  endpointUrl: string;
  status: DeliveryStatus;
  attemptCount: number;
  lastHttpStatus: number | null;
  lastError: string | null;
  claimedBy: string | null;
  claimExpiresAt: string | null;
  /** IN_PROGRESS but the claim lease ran out (database clock): the worker most likely died. */
  leaseExpired: boolean;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Attempt {
  attemptNumber: number;
  workerId: string;
  startedAt: string;
  finishedAt: string;
  durationMs: number;
  outcome: AttemptOutcome;
  httpStatus: number | null;
  responseBody: string | null;
  responseTruncated: boolean;
  errorMessage: string | null;
}

export interface DeliveryDetail {
  id: string;
  status: DeliveryStatus;
  attemptCount: number;
  nextAttemptAt: string;
  claimedBy: string | null;
  claimExpiresAt: string | null;
  leaseExpired: boolean;
  lastHttpStatus: number | null;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  event: { id: string; eventType: string; createdAt: string; payload: unknown };
  endpoint: { id: string; url: string; description: string | null };
  attempts: Attempt[];
}

export interface Stats {
  total: number;
  byStatus: Record<DeliveryStatus, number>;
}

export interface QueueStats {
  name: string;
  reachable: boolean;
  readyMessages: number | null;
  consumers: number | null;
}

export interface WorkerActivity {
  workerId: string;
  attemptsLast10Minutes: number;
  inFlight: number;
  expiredLeases: number;
  lastAttemptAt: string | null;
}

export interface Overview {
  serverTime: string;
  deliveries: Stats;
  attemptsRecorded: number;
  workQueue: QueueStats;
  deadLetterQueue: QueueStats;
  workers: WorkerActivity[];
}

export interface Endpoint {
  id: string;
  url: string;
  description: string | null;
  createdAt: string;
}

export interface EventAccepted {
  eventId: string;
  deliveryId: string;
  status: DeliveryStatus;
}

/** A request as logged by the demo receiver (reached through the /receiver proxy). */
export interface ReceivedRequest {
  receivedAt: string;
  path: string;
  deliveryId: string | null;
  eventType: string | null;
  attempt: string | null;
  respondedStatus: number;
  duplicate: boolean;
  bodyPreview: string | null;
}

export interface ReceiverControl {
  outage: boolean;
  loggedRequests: number;
}

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { Accept: 'application/json', ...(init.body ? { 'Content-Type': 'application/json' } : {}) },
  });
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    try {
      const problem = (await response.json()) as { detail?: string; errors?: { field: string; message: string }[] };
      if (problem.errors?.length) detail = problem.errors.map((e) => `${e.field}: ${e.message}`).join('; ');
      else if (problem.detail) detail = problem.detail;
    } catch {
      // not a JSON body; keep the status line
    }
    throw new ApiError(response.status, detail);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

const post = <T>(path: string, body: unknown) => request<T>(path, { method: 'POST', body: JSON.stringify(body) });

export const api = {
  stats: (signal?: AbortSignal) => request<Stats>('/api/stats', { signal }),

  overview: (signal?: AbortSignal) => request<Overview>('/api/overview', { signal }),

  deliveries: (params: { status?: DeliveryStatus; page: number; size: number }, signal?: AbortSignal) => {
    const query = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    if (params.status) query.set('status', params.status);
    return request<Page<DeliverySummary>>(`/api/deliveries?${query}`, { signal });
  },

  delivery: (id: string, signal?: AbortSignal) =>
    request<DeliveryDetail>(`/api/deliveries/${encodeURIComponent(id)}`, { signal }),

  endpoints: () => request<Endpoint[]>('/api/endpoints'),

  registerEndpoint: (url: string, description: string) => post<Endpoint>('/api/endpoints', { url, description }),

  submitEvent: (endpointId: string, eventType: string, payload: object) =>
    post<EventAccepted>('/api/events', { endpointId, eventType, payload }),

  /** Demo-only API; 404 when the server runs without HOOKGUARD_DEMO_ENABLED=true. */
  demoInfo: () => request<{ receiverBaseUrl: string }>('/api/demo'),

  publishDuplicates: (deliveryId: string, copies: number) =>
    post<{ deliveryId: string; copies: number }>(`/api/demo/deliveries/${encodeURIComponent(deliveryId)}/duplicates`, {
      copies,
    }),
};

/** The demo receiver, reached through the dashboard's /receiver proxy (Vite in dev, nginx in Docker). */
export const receiver = {
  received: (limit: number, signal?: AbortSignal) => request<ReceivedRequest[]>(`/receiver/received?limit=${limit}`, { signal }),
  control: (signal?: AbortSignal) => request<ReceiverControl>('/receiver/control', { signal }),
  setOutage: (enabled: boolean) =>
    request<ReceiverControl>('/receiver/control/outage', { method: 'PUT', body: JSON.stringify({ enabled }) }),
  clear: () => request<void>('/receiver/received', { method: 'DELETE' }),
};

export function isTerminal(status: DeliveryStatus): boolean {
  return status === 'DELIVERED' || status === 'FAILED';
}
