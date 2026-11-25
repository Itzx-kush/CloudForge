import { useEffect, useState } from 'react';
import { DashboardCard } from '../components/DashboardCard';
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
    fetchWorkerPoolStatus(workspace).then(value => { if (current) setPool(value); })
      .catch(() => { if (current) setPoolError(true); });
    fetchSecurityAssessments(workspace).then(value => { if (current) setReports(value); })
      .catch(() => { if (current) setReportError(true); });
    return () => { current = false; };
  }, [workspace, version]);

  return <DashboardCard title="Workspace operations" headerAction={
    <button type="button" className="btn" onClick={() => setVersion(value => value + 1)}>Refresh operations</button>
  }>
    <h3>Pipeline stages</h3>
    <p role="status">{poolError ? 'Worker status unavailable.' : pool
      ? `${pool.workspaceReadyStages} queued · ${pool.workspaceRunningStages} running · ${pool.workspaceFailedAttempts} failed attempts`
      : 'Loading worker status…'}</p>
    <h3>Signed artifact assessments</h3>
    {reportError ? <p role="alert">Security assessments unavailable.</p>
      : !reports ? <p role="status">Loading assessments…</p>
      : reports.length === 0 ? <p>No signed assessments recorded for this workspace.</p>
      : <ul>{reports.map(report => <li key={report.artifact + report.assessedAt}>
        <strong>{report.critical} critical · {report.high} high</strong>
        <p>Artifact <code>{report.artifact}</code> · {new Date(report.assessedAt * 1000).toLocaleString()}</p>
        <details><summary>SBOM checksum</summary><code style={{ overflowWrap: 'anywhere' }}>{report.sbomSha256}</code></details>
      </li>)}</ul>}
  </DashboardCard>;
}
