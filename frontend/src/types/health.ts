export interface DatabaseHealth {
  status: 'UP' | 'DOWN' | string;
  engine: string;
  responseTimeMs: number;
  message: string;
}

export interface BackendHealthPayload {
  application: string;
  version: string;
  phase: string;
  status: 'UP' | 'DEGRADED' | 'DOWN' | string;
  database: DatabaseHealth;
  timestamp: string;
}

export interface HealthProbeSnapshot {
  payload: BackendHealthPayload | null;
  httpStatus: number | null;
  requestId: string | null;
  clientLatencyMs: number | null;
  checkedAt: string | null;
  networkError: string | null;
}
