import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { GitCompareArrows, History, RefreshCw } from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { Modal } from '../components/Modal';
import { StatusBadge } from '../components/StatusBadge';
import {
  compareReleases, fetchKubernetesDeploymentEvents, fetchRelease, fetchReleaseHistory,
  previewReleaseRollback, reconcileRelease, requestReleaseRollback,
} from '../services/api';
import type {
  KubernetesClusterConnection, KubernetesDeploymentEvent, KubernetesRelease, ProjectSummary,
  ReleaseComparison, RollbackPreview,
} from '../types/platform';

const activeStatuses = new Set(['PENDING', 'VALIDATING', 'APPLYING', 'ROLLING_OUT', 'VERIFYING']);

const errorText = (error: unknown) => error instanceof Error ? error.message : 'The request failed.';
const operationKey = () => `cf-release-${crypto.randomUUID()}`;
const formatDate = (value: string) => new Date(value).toLocaleString();
const revisionLabel = (release: KubernetesRelease) =>
  release.revision === null ? 'Reconciliation observation' : `Revision ${release.revision}`;
const valueText = (value: unknown) => {
  if (value == null) return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
};

function DifferenceTable({ comparison }: { comparison: ReleaseComparison }) {
  return (
    <div className="cf-release-differences" aria-label="Release differences">
      {comparison.fields.map(({ field, before, after, changed }) => (
        <div key={field} className={changed ? 'cf-release-difference--changed' : ''}>
          <strong>{field}</strong>
          <span><small>Before</small>{valueText(before)}</span>
          <span><small>After</small>{valueText(after)}</span>
          <StatusBadge label={changed ? 'Changed' : 'Unchanged'} tone={changed ? 'info' : 'neutral'} size="sm" />
        </div>
      ))}
    </div>
  );
}

interface Props {
  workspace: string;
  isOwner: boolean;
  canRequest: boolean;
  executionAvailable: boolean;
  projects: ProjectSummary[];
  clusters: KubernetesClusterConnection[];
}

export function ReleaseHistoryPanel({ workspace, isOwner, canRequest, executionAvailable, projects, clusters }: Props) {
  const [items, setItems] = useState<KubernetesRelease[]>([]);
  const [kind, setKind] = useState('');
  const [project, setProject] = useState('');
  const [cluster, setCluster] = useState('');
  const [deployment, setDeployment] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [selected, setSelected] = useState<KubernetesRelease | null>(null);
  const [events, setEvents] = useState<KubernetesDeploymentEvent[]>([]);
  const [related, setRelated] = useState<KubernetesRelease[]>([]);
  const [candidate, setCandidate] = useState('');
  const [comparison, setComparison] = useState<ReleaseComparison | null>(null);
  const [preview, setPreview] = useState<RollbackPreview | null>(null);
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    let cancelled = false;
    const filters = Object.fromEntries(Object.entries({
      kind, projectId: project, clusterId: cluster, deployment: deployment.trim(), status, page: String(page),
    }).filter(([, value]) => value !== ''));
    setLoading(true);
    setLoadError('');
    fetchReleaseHistory(workspace, filters)
      .then((data) => {
        if (cancelled) return;
        setItems(data.items);
        setTotalPages(data.totalPages);
      })
      .catch((error: unknown) => { if (!cancelled) setLoadError(errorText(error)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [workspace, kind, project, cluster, deployment, status, page]);

  const selectedId = selected?.operation.id;
  const isActive = selected !== null && activeStatuses.has(selected.operation.status);

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    function updateDetails() {
      if (!selectedId) return;
      Promise.all([fetchRelease(workspace, selectedId), fetchKubernetesDeploymentEvents(workspace, selectedId)])
        .then(([release, history]) => {
          if (cancelled) return;
          setSelected(release);
          setEvents(history);
          setItems((rows) => rows.map((row) => row.operation.id === release.operation.id ? release : row));
          if (isActive && release.kind === 'ROLLBACK' && release.operation.status === 'SUCCEEDED' && release.historicallyVerified) {
            setNotice('Rollback completed and verified.');
          }
          if (isActive && release.kind === 'RECONCILIATION' && release.historicallyVerified) {
            setNotice('The worker verified the selected live release.');
          }
        })
        .catch((error: unknown) => { if (!cancelled) setDetailError(errorText(error)); });
    }
    updateDetails();
    const timer = isActive ? window.setInterval(updateDetails, 3000) : undefined;
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [workspace, selectedId, isActive]);

  const selectRelease = useCallback((release: KubernetesRelease) => {
    setSelected(release);
    setEvents([]);
    setRelated([]);
    setCandidate('');
    setComparison(null);
    setPreview(null);
    setDetailError('');
    setNotice('');
    setReason('');
    setConfirmed(false);
    setKey(operationKey());
  }, []);

  useEffect(() => {
    if (!selectedId || !selected) return;
    let cancelled = false;
    const op = selected.operation;
    fetchReleaseHistory(workspace, {
      projectId: op.projectId, environmentId: op.environmentId, clusterId: op.clusterConnectionId,
      deployment: op.deploymentName, size: '100',
    }).then(({ items: matches }) => {
      if (!cancelled) setRelated(matches.filter((row) => row.operation.namespaceBindingId === op.namespaceBindingId));
    }).catch((error: unknown) => { if (!cancelled) setDetailError(errorText(error)); });
    return () => { cancelled = true; };
    // Reload only when a different operation is selected.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspace, selectedId]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setDetailError('');
    try {
      await action();
    } catch (error) {
      setDetailError(errorText(error));
    } finally {
      setBusy(false);
    }
  }

  function queueRollback(event: FormEvent) {
    event.preventDefault();
    if (!preview?.eligible || !confirmed) return;
    void run(async () => {
      const queued = await requestReleaseRollback(workspace, preview.target.operation.id, preview.expectedOperationId, reason, key);
      selectRelease(queued);
      setNotice('Rollback request queued successfully.');
      if (!kind || kind === 'ROLLBACK') setItems((rows) => [queued, ...rows.filter((row) => row.operation.id !== queued.operation.id)]);
    });
  }

  const current = selected?.operation;
  return (
    <DashboardCard title="Release History" icon={<History size={17} />}>
      <div className="cf-release-filters">
        <select className="cf-filter-select" aria-label="Filter by release kind" value={kind}
          onChange={(e) => { setKind(e.target.value); setPage(0); }}>
          <option value="">All releases</option><option value="ROLLBACK">Rollbacks</option>
          <option value="RECONCILIATION">Reconciliation observations</option>
        </select>
        <select className="cf-filter-select" aria-label="Filter releases by project" value={project}
          onChange={(e) => { setProject(e.target.value); setPage(0); }}>
          <option value="">All projects</option>
          {projects.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
        <select className="cf-filter-select" aria-label="Filter releases by cluster" value={cluster}
          onChange={(e) => { setCluster(e.target.value); setPage(0); }}>
          <option value="">All clusters</option>
          {clusters.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
        <select className="cf-filter-select" aria-label="Filter releases by status" value={status}
          onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          <option value="">All outcomes</option><option value="SUCCEEDED">Succeeded</option>
          <option value="FAILED">Failed</option><option value="RECONCILIATION_REQUIRED">Reconciliation required</option>
          <option value="PENDING">Pending</option><option value="CANCELLED">Cancelled</option>
        </select>
        <input aria-label="Filter by exact deployment name" placeholder="Deployment name" value={deployment}
          onChange={(e) => { setDeployment(e.target.value); setPage(0); }} />
      </div>
      {loadError && <p role="alert">{loadError}</p>}
      {loading && <p role="status">Loading releases…</p>}
      {!loading && !loadError && items.length === 0 && <p>No release operations match these filters.</p>}
      {!loading && !loadError && (
        <ol className="cf-release-timeline">
          {items.map((release) => {
            const op = release.operation;
            return <li key={op.id}>
              <div><strong>{revisionLabel(release)}</strong><span>{op.projectName} · {op.environmentName} · {op.deploymentName}</span></div>
              <div><strong>{release.type.replaceAll('_', ' ')}</strong><span>{formatDate(op.completedAt ?? op.createdAt)} · {op.requestedByUsername ?? 'System'}</span></div>
              <div><code title={op.sourceCommitSha}>{op.sourceCommitSha.slice(0, 12)}</code><code title={op.manifestDigest}>{op.manifestDigest.slice(0, 22)}…</code></div>
              <div>
                <StatusBadge label={op.status.replaceAll('_', ' ').toLowerCase()} tone={op.status === 'SUCCEEDED' ? 'healthy' : op.status === 'FAILED' ? 'error' : 'neutral'} size="sm" />
                <span>{release.reconciliationRequired ? 'Needs live inspection' : release.historicallyVerified ? 'Verified rollout evidence' : 'No verified rollout evidence'}</span>
              </div>
              <button type="button" className="btn btn-sm" onClick={() => selectRelease(release)}>Inspect</button>
            </li>;
          })}
        </ol>
      )}
      {totalPages > 1 && <nav className="cf-release-filters" aria-label="Release pages">
        <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span>Page {page + 1} of {totalPages}</span>
        <button type="button" className="btn btn-sm" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</button>
      </nav>}
      <Modal isOpen={selected !== null} title={selected ? revisionLabel(selected) : 'Release details'}
        subtitle={current && `${current.clusterName} / ${current.namespaceName} / ${current.deploymentName}`}
        onClose={() => { if (!busy) setSelected(null); }}>
        {selected && current && <>
          <div aria-live="polite">
            {notice && <p>{notice}</p>}
            <p><strong>{current.status.replaceAll('_', ' ')}</strong> · {current.readyReplicas}/{current.requestedReplicas} ready replicas</p>
          </div>
          {detailError && <p role="alert">{detailError}</p>}
          {current.errorSummary && <p role="status">{current.errorSummary}</p>}
          <dl className="cf-release-detail">
            <dt>Image</dt><dd><code>{current.immutableImageReference}</code></dd>
            <dt>Commit</dt><dd><code>{current.sourceCommitSha}</code></dd>
            <dt>Manifest checksum</dt><dd><code>{current.manifestChecksumSha256}</code></dd>
            <dt>Pipeline run</dt><dd><code>{current.pipelineRunId}</code></dd>
            <dt>Operation</dt><dd><code>{current.id}</code></dd>
            {selected.targetReleaseId && <><dt>Referenced release</dt><dd><code>{selected.targetReleaseId}</code></dd></>}
            {selected.reason && <><dt>Reason</dt><dd>{selected.reason}</dd></>}
            <dt>Last recorded observation</dt><dd>{formatDate(current.updatedAt)} · generation {current.observedGeneration ?? 'unknown'}</dd>
          </dl>
          <details><summary>Workload configuration and conditions</summary>
            <pre>{JSON.stringify(current.configSnapshot, null, 2)}</pre>
            <pre>{JSON.stringify(current.rolloutConditions, null, 2)}</pre>
          </details>
          <h3>Operation timeline</h3>
          {events.length === 0 ? <p>No events recorded.</p> : <ol className="cf-release-events">
            {events.map((event) => <li key={event.id}><time dateTime={event.occurredAt}>{formatDate(event.occurredAt)}</time><p>{event.message}</p></li>)}
          </ol>}
          <div className="cf-release-filters">
            <select className="cf-filter-select" aria-label="Select another revision" value={candidate}
              onChange={(e) => { setCandidate(e.target.value); setComparison(null); }}>
              <option value="">Select another revision</option>
              {related.filter((row) => row.operation.id !== current.id).map((row) => (
                <option key={row.operation.id} value={row.operation.id}>{revisionLabel(row)} · {row.operation.status}</option>
              ))}
            </select>
            <button type="button" className="btn btn-sm" disabled={!candidate || busy}
              onClick={() => void run(async () => setComparison(await compareReleases(workspace, current.id, candidate)))}>
              <GitCompareArrows size={15} aria-hidden="true" /> Compare
            </button>
            {canRequest && selected.historicallyVerified && selected.revision !== null &&
              <button type="button" className="btn btn-sm" disabled={busy}
                onClick={() => void run(async () => {
                  setPreview(await previewReleaseRollback(workspace, current.id));
                  setComparison(null);
                })}>Check rollback eligibility</button>}
          </div>
          {comparison && <DifferenceTable comparison={comparison} />}
          {selected.reconciliationRequired && <div className="cf-release-warning">
            <strong>Live verification required</strong>
            <p>Inspect the affected release or select the revision you expect to be running. If neither matches, inspect the workload on the cluster.</p>
            {isOwner && <button type="button" className="btn btn-sm" disabled={busy || !executionAvailable}
              onClick={() => void run(async () => {
                const queued = await reconcileRelease(workspace, current.id, candidate || selected.targetReleaseId || current.id, key);
                selectRelease(queued);
                setNotice('Read-only reconciliation queued.');
              })}><RefreshCw size={15} aria-hidden="true" /> Inspect live state</button>}
            {!executionAvailable && <p>Kubernetes execution infrastructure is unavailable.</p>}
          </div>}
          {preview && <form className="cf-release-preview" onSubmit={queueRollback}>
            <h3>Rollback to {revisionLabel(preview.target)}</h3>
            {preview.current && <p>Recorded current workload: <code>{preview.current.operation.manifestDigest}</code></p>}
            <p>Restore <code>{preview.target.operation.manifestDigest}</code> with {preview.target.operation.requestedReplicas} replicas.</p>
            <p>{preview.risk}</p>
            {preview.comparison && <DifferenceTable comparison={preview.comparison} />}
            {!preview.eligible ? <p role="status">{preview.blockedReason}</p> : <>
              <label htmlFor="cf-rollback-reason">Rollback reason</label>
              <textarea id="cf-rollback-reason" value={reason} minLength={8} maxLength={128} required
                onChange={(e) => setReason(e.target.value)} />
              <label><input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} required />{' '}
                {preview.productionApprovalRequired ? 'I approve restoring this release in production.' : 'I confirm this release and target.'}
              </label>
              <button type="submit" className="btn btn-primary" disabled={busy || !confirmed || reason.trim().length < 8}>Queue rollback</button>
            </>}
          </form>}
        </>}
      </Modal>
    </DashboardCard>
  );
}
