import { api, isTerminal, receiver, type DeliveryDetail } from '../api';
import { duration, shortId } from '../format';

/**
 * Demo scenarios. Each one uses the real public API (register endpoint, submit event) against the
 * demo receiver, waits for the real outcome, and reports facts read back from HookGuard and the
 * receiver. Nothing is simulated in the browser.
 */

export type Tone = 'success' | 'danger' | 'warning' | 'info' | 'neutral';

export interface ScenarioContext {
  /** Endpoint for a demo-receiver path, registered on first use. */
  ensureEndpoint(path: string, description: string): Promise<string>;
  log(text: string, tone?: Tone): void;
  countSent(n: number): void;
}

export interface Scenario {
  id: string;
  title: string;
  description: string;
  watch: string;
  tone: Tone;
  run(ctx: ScenarioContext): Promise<string[]>;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const orderPayload = () => ({
  orderId: 10_000 + Math.floor(Math.random() * 90_000),
  total: Math.round(Math.random() * 20_000) / 100,
  currency: 'EUR',
});

async function waitForTerminal(deliveryId: string, timeoutMs = 45_000): Promise<DeliveryDetail> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const detail = await api.delivery(deliveryId);
    if (isTerminal(detail.status)) return detail;
    if (Date.now() > deadline) throw new Error(`Delivery ${shortId(deliveryId)} still ${detail.status} after ${timeoutMs / 1000}s`);
    await sleep(400);
  }
}

async function mapLimit<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const index = next++;
      results[index] = await fn(items[index] as T);
    }
  });
  await Promise.all(workers);
  return results;
}

async function sendOne(ctx: ScenarioContext, path: string, description: string, eventType = 'order.created') {
  const endpointId = await ctx.ensureEndpoint(path, description);
  const accepted = await api.submitEvent(endpointId, eventType, orderPayload());
  ctx.countSent(1);
  ctx.log(`202 Accepted → delivery ${shortId(accepted.deliveryId)} stored as PENDING, ID published to RabbitMQ`);
  return accepted.deliveryId;
}

function describeAttempt(detail: DeliveryDetail): string {
  const attempt = detail.attempts[detail.attempts.length - 1];
  if (!attempt) return 'No attempt recorded';
  const result = attempt.httpStatus !== null ? `HTTP ${attempt.httpStatus}` : attempt.outcome;
  return `${result} after ${duration(attempt.durationMs)}, attempt ${attempt.attemptNumber}, worker ${attempt.workerId}`;
}

export const SCENARIOS: Scenario[] = [
  {
    id: 'healthy',
    title: 'Healthy delivery',
    description: 'Receiver answers 200 after 1.5 s.',
    watch: 'The card moves Pending → In progress (in a worker lane) → Delivered.',
    tone: 'success',
    async run(ctx) {
      const id = await sendOne(ctx, '/hooks/slow?delayMs=1500', 'Healthy receiver (responds in 1.5 s)');
      const detail = await waitForTerminal(id);
      return [`${detail.status}: ${describeAttempt(detail)}`];
    },
  },
  {
    id: 'server-error',
    title: 'Receiver error (500)',
    description: 'Receiver always answers HTTP 500.',
    watch: 'The card goes to Failed; the response body is kept for diagnosis.',
    tone: 'danger',
    async run(ctx) {
      const id = await sendOne(ctx, '/hooks/fail', 'Always returns 500');
      const detail = await waitForTerminal(id);
      const body = detail.attempts[0]?.responseBody ?? '';
      return [`${detail.status}: ${describeAttempt(detail)}`, `Response body recorded: ${body.slice(0, 80)}`];
    },
  },
  {
    id: 'timeout',
    title: 'Timeout',
    description: 'Receiver takes 8 s; HookGuard gives up at 5 s.',
    watch: 'In progress for ~5 s, then Failed with outcome TIMEOUT.',
    tone: 'warning',
    async run(ctx) {
      const id = await sendOne(ctx, '/hooks/slow?delayMs=8000', 'Slower than the 5 s timeout');
      const detail = await waitForTerminal(id);
      const attempt = detail.attempts[0];
      return [
        `${detail.status}: ${attempt?.errorMessage ?? describeAttempt(detail)}`,
        `The worker stopped waiting after ${attempt ? duration(attempt.durationMs) : '?'} and freed itself for other work.`,
      ];
    },
  },
  {
    id: 'flaky',
    title: 'Flaky receiver',
    description: 'Receiver fails the first request of each delivery (503), then succeeds.',
    watch: 'Failed after one attempt: Phase 1 does not retry yet.',
    tone: 'warning',
    async run(ctx) {
      const id = await sendOne(ctx, '/hooks/flaky?failTimes=1', 'Fails once per delivery, then succeeds');
      const detail = await waitForTerminal(id);
      return [
        `${detail.status}: ${describeAttempt(detail)}`,
        'A second attempt would have succeeded. Phase 2 adds retries with exponential backoff.',
      ];
    },
  },
  {
    id: 'burst',
    title: 'Burst of 30',
    description: '30 events at once; receiver answers in 0.8 s.',
    watch: 'Work spreads over all consumers; with several workers you see several lanes.',
    tone: 'info',
    async run(ctx) {
      const endpointId = await ctx.ensureEndpoint('/hooks/slow?delayMs=800', 'Healthy receiver (responds in 0.8 s)');
      const started = Date.now();
      const ids = await mapLimit(Array.from({ length: 30 }, (_, i) => i), 10, async () => {
        const accepted = await api.submitEvent(endpointId, 'order.created', orderPayload());
        ctx.countSent(1);
        return accepted.deliveryId;
      });
      ctx.log(`30 events accepted in ${duration(Date.now() - started)}; waiting for delivery…`);
      const details = await mapLimit(ids, 6, (id) => waitForTerminal(id, 90_000));
      const elapsed = Date.now() - started;

      const perWorker = new Map<string, number>();
      for (const d of details) {
        const worker = d.attempts[0]?.workerId ?? 'unknown';
        perWorker.set(worker, (perWorker.get(worker) ?? 0) + 1);
      }
      const log = await receiver.received(200);
      const idSet = new Set(ids);
      const requests = log.filter((r) => r.deliveryId !== null && idSet.has(r.deliveryId));
      const delivered = details.filter((d) => d.status === 'DELIVERED').length;
      return [
        `${delivered}/30 delivered in ${duration(elapsed)} (receiver takes 0.8 s each)`,
        `Split across ${perWorker.size} worker process(es): ${[...perWorker].map(([w, n]) => `${w} × ${n}`).join(', ')}`,
        `Receiver got ${requests.length} requests for 30 deliveries; duplicates: ${requests.filter((r) => r.duplicate).length}`,
      ];
    },
  },
  {
    id: 'duplicates',
    title: 'Duplicate storm',
    description: 'One event, then the same delivery ID is published 9 more times.',
    watch: '10 messages race for one row. Exactly one wins the atomic claim; the receiver gets 1 request.',
    tone: 'info',
    async run(ctx) {
      const id = await sendOne(ctx, '/hooks/slow?delayMs=1500', 'Healthy receiver (responds in 1.5 s)');
      await api.publishDuplicates(id, 9);
      ctx.log(`9 extra copies of ${shortId(id)} published to RabbitMQ (10 messages total)`);
      const detail = await waitForTerminal(id);
      await sleep(1500); // let the remaining copies be consumed and skipped
      const requests = (await receiver.received(200)).filter((r) => r.deliveryId === id);
      return [
        'Messages published for this delivery: 10 (1 by the API, 9 injected)',
        `HTTP requests received by the receiver: ${requests.length}`,
        `Attempts recorded: ${detail.attempts.length} · final status ${detail.status}`,
        'The other 9 copies lost the conditional UPDATE and were acknowledged without sending anything.',
      ];
    },
  },
];
