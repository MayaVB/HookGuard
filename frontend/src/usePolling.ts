import { useEffect, useRef, useState } from 'react';

export interface PollingState<T> {
  data: T | undefined;
  error: Error | undefined;
  updatedAt: Date | undefined;
}

/**
 * Fetches immediately, then again `intervalMs` after each response (not on a fixed timer), so a
 * slow API never causes overlapping requests. Stops when `shouldContinue(data)` returns false.
 * Re-starts when `key` changes; in-flight requests are aborted on change or unmount.
 */
export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  intervalMs: number,
  key: string,
  shouldContinue: (data: T) => boolean = () => true,
): PollingState<T> {
  const [state, setState] = useState<PollingState<T>>({ data: undefined, error: undefined, updatedAt: undefined });
  const fetcherRef = useRef(fetcher);
  const continueRef = useRef(shouldContinue);
  fetcherRef.current = fetcher;
  continueRef.current = shouldContinue;

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    const tick = async () => {
      let keepGoing = true;
      try {
        const data = await fetcherRef.current(controller.signal);
        setState({ data, error: undefined, updatedAt: new Date() });
        keepGoing = continueRef.current(data);
      } catch (error) {
        if (controller.signal.aborted) return;
        setState((previous) => ({ ...previous, error: error as Error }));
      }
      if (keepGoing && !controller.signal.aborted) {
        timer = setTimeout(tick, intervalMs);
      }
    };

    setState({ data: undefined, error: undefined, updatedAt: undefined });
    void tick();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [key, intervalMs]);

  return state;
}
