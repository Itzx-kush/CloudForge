import { useEffect, useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { StatusBadge } from '../components/StatusBadge';
import { fetchSecurityAssessments, fetchWorkerPoolStatus } from '../services/api';
import type { SecurityAssessment } from '../services/api';
import type { WorkerPoolStatus } from '../types/platform';

export function WorkspaceOperations({ workspace }: { workspace: string }) {
  const [pool, setPool] = useState<WorkerPoolStatus>();
  const [reports, setReports] = useState<SecurityAssessment[]>();
  const [poolError, setPoolError] = useState(false);
  const [reportError, setReportError] = useState(false);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    let current = true;
    setPoolError(false);
    setReportError(false);
    setPool(undefined);
    setReports(undefined);
    fetchWorkerPoolStatus(workspace)
      .then((value) => {
        if (current) setPool(value);
      })
      .catch(() => {
        if (current) setPoolError(true);
      });
    fetchSecurityAssessments(workspace)
      .then((value) => {
        if (current) setReports(value);
      })
      .catch(() => {
        if (current) setReportError(true);
      });
    return () => {
      current = false;
    };
  }, [workspace, version]);

  const workerStatusBadge = poolError
    ? { label: 'Status Unavailable', tone: 'error' as const }
    : !pool
      ? { label: 'Loading', tone: 'neutral' as const }
      : !pool.workerPlaneEnabled
        ? { label: 'Execution Plane Disabled', tone: 'neutral' as const }
        : pool.availableWorkers > 0
          ? {
              label: `${pool.availableWorkers} Worker${pool.availableWorkers === 1 ? '' : 's'} Available`,
              tone: 'healthy' as const,
            }
          : pool.busyWorkers > 0
            ? {
                label: `${pool.busyWorkers} Worker${pool.busyWorkers === 1 ? '' : 's'} Busy`,
                tone: 'info' as const,
              }
            : { label: 'No Online Workers', tone: 'degraded' as const };

  return (
    <DashboardCard
      title="Workspace operations"
      subtitle="Live worker pool availability, workspace stage telemetry, and signed supply-chain security evidence"
      icon={<ShieldCheck size={17} />}
      headerAction={
        <div className="cf-inline-actions">
          <StatusBadge label={workerStatusBadge.label} tone={workerStatusBadge.tone} size="sm" />
          <button type="button" className="btn btn-sm" onClick={() => setVersion((value) => value + 1)}>
            Refresh operations
          </button>
        </div>
      }
    >
      <h3>Worker execution plane &amp; pipeline stages</h3>
      {poolError ? (
        <p role="alert">Worker status unavailable.</p>
      ) : !pool ? (
        <p role="status">Loading worker status…</p>
      ) : (
        <>
          {!pool.workerPlaneEnabled && (
            <p className="cf-muted" role="status">
              Isolated worker execution is disabled in this environment (control-plane safe default).
              Pipeline runs remain queued until an authorized external worker environment is enabled.
            </p>
          )}
          <p role="status">
            {pool.workspaceReadyStages} queued · {pool.workspaceRunningStages} running ·{' '}
            {pool.workspaceFailedAttempts} failed attempts
            {pool.workerPlaneEnabled
              ? ` · ${pool.availableWorkers} available / ${pool.registeredWorkers} registered workers`
              : ''}
          </p>
        </>
      )}

      <h3>Signed artifact assessments</h3>
      {reportError ? (
        <p role="alert">Security assessments unavailable.</p>
      ) : !reports ? (
        <p role="status">Loading assessments…</p>
      ) : reports.length === 0 ? (
        <p className="cf-muted">
          No signed assessments recorded for this workspace. Production Kubernetes deployments
          require current signed Trivy/Cosign evidence with zero high or critical findings.
        </p>
      ) : (
        <ul className="cf-subtle-list">
          {reports.map((report) => {
            const approved = report.critical === 0 && report.high === 0;
            return (
              <li key={report.artifact + report.assessedAt}>
                <div>
                  <div className="cf-subtle-list__title">
                    <strong>
                      {report.critical} critical · {report.high} high
                    </strong>{' '}
                    <StatusBadge
                      label={approved ? 'Zero High/Critical' : 'Findings Detected'}
                      tone={approved ? 'healthy' : 'error'}
                      size="sm"
                    />
                  </div>
                  <p className="cf-subtle-list__desc">
                    Artifact <code className="mono">{report.artifact}</code> ·{' '}
                    {new Date(report.assessedAt * 1000).toLocaleString()}
                  </p>
                  <details>
                    <summary>SBOM checksum</summary>
                    <code className="mono" style={{ overflowWrap: 'anywhere' }}>
                      {report.sbomSha256}
                    </code>
                  </details>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </DashboardCard>
  );
}
