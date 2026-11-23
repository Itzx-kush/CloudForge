import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchBackendHealth } from '../services/api';
import type { HealthProbeSnapshot } from '../types/health';

export interface BackendHealthState {
  snapshot: HealthProbeSnapshot | null;
  isLoading: boolean;
  isRefreshing: boolean;
  refresh: () => Promise<void>;
}

export function useBackendHealth(pollIntervalMs?: number): BackendHealthState {
  const [snapshot, setSnapshot] = useState<HealthProbeSnapshot | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const hasLoadedRef = useRef<boolean>(false);

  const runProbe = useCallback(async () => {
    if (hasLoadedRef.current) {
      setIsRefreshing(true);
    } else {
      setIsLoading(true);
    }

    const result = await fetchBackendHealth();
    hasLoadedRef.current = true;
    setSnapshot(result);
    setIsLoading(false);
    setIsRefreshing(false);
  }, []);

  useEffect(() => {
    void runProbe();

    if (!pollIntervalMs || pollIntervalMs <= 0) {
      return undefined;
    }

    const intervalId = window.setInterval(() => {
      void runProbe();
    }, pollIntervalMs);

    return () => window.clearInterval(intervalId);
  }, [pollIntervalMs, runProbe]);

  return {
    snapshot,
    isLoading,
    isRefreshing,
    refresh: runProbe,
  };
}
