import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  AlertTriangle,
  Ban,
  Box,
  Check,
  CheckCircle2,
  Copy,
  Cpu,
  GitCommitHorizontal,
  History,
  Info,
  KeyRound,
  Layers,
  Lock,
  Network,
  Plus,
  RefreshCw,
  Rocket,
  Server,
  ShieldCheck,
  Slash,
} from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { DataTable, type TableColumn } from '../components/DataTable';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { Modal } from '../components/Modal';
import { PageContainer } from '../components/PageContainer';
import { SearchField } from '../components/SearchField';
import { StatusBadge, type BadgeTone } from '../components/StatusBadge';
import { Toast } from '../components/Toast';
import { ValidatedForm } from '../components/ValidatedForm';
import { useAuth } from '../context/AuthContext';
import {
  ApiClientError,
  cancelKubernetesDeployment,
  createKubernetesCluster,
  createKubernetesDeployment,
  createNamespaceBinding,
  disableKubernetesCluster,
  fetchEnvironmentSecrets,
  fetchKubernetesClusters,
  fetchKubernetesDeploymentDetail,
  fetchKubernetesDeploymentEvents,
  fetchKubernetesDeployments,
  fetchKubernetesDeploymentStatus,
  fetchKubernetesSubsystemStatus,
  fetchOciArtifacts,
  fetchProjectDetail,
  fetchProjects,
  fetchWorkspaceNamespaces,
  validateKubernetesCluster,
} from '../services/api';
import type {
  EnvironmentSecretSummary,
  EnvironmentType,
  KubernetesClusterConnection,
  KubernetesClusterStatus,
  KubernetesClusterType,
  KubernetesDeploymentEvent,
  KubernetesDeploymentOperation,
  KubernetesDeploymentStatus,
  KubernetesNamespaceBinding,
  KubernetesNetworkPolicyMode,
  KubernetesPodSecurityStandard,
  KubernetesSubsystemStatus,
  OciImageArtifact,
  ProjectEnvironment,
  ProjectSummary,
} from '../types/platform';
import '../components/OciArtifactsPanel.css';
import './Pages.css';
import './DeploymentsPage.css';

const DEPLOYMENT_STATUS_META: Record<
  KubernetesDeploymentStatus,
  { label: string; tone: BadgeTone; pulse?: boolean }
> = {
  PENDING: { label: 'Pending', tone: 'neutral', pulse: true },
  VALIDATING: { label: 'Validating', tone: 'info', pulse: true },
  APPLYING: { label: 'Applying', tone: 'info', pulse: true },
  ROLLING_OUT: { label: 'Rolling Out', tone: 'info', pulse: true },
  VERIFYING: { label: 'Verifying', tone: 'info', pulse: true },
  SUCCEEDED: { label: 'Healthy', tone: 'healthy' },
  FAILED: { label: 'Failed', tone: 'error' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
  RECONCILIATION_REQUIRED: { label: 'Reconciliation Required', tone: 'degraded' },
};

const CLUSTER_STATUS_META: Record<
  KubernetesClusterStatus,
  { label: string; tone: BadgeTone; pulse?: boolean }
> = {
  PENDING_VALIDATION: { label: 'Pending Validation', tone: 'degraded' },
  VALIDATING: { label: 'Validating', tone: 'info', pulse: true },
  VALIDATED: { label: 'Validated', tone: 'healthy' },
  VALIDATION_FAILED: { label: 'Validation Failed', tone: 'error' },
  DISABLED: { label: 'Disabled', tone: 'neutral' },
};

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

function generateIdempotencyKey(): string {
  const randomPart =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID().slice(0, 12)
      : Math.random().toString(36).slice(2, 14);
  return `cf-k8s-${Date.now()}-${randomPart}`;
}

export function DeploymentsPage() {
  const { activeWorkspace, activeRole } = useAuth();
  const isOwner = activeRole === 'OWNER';
  const canRequestDeployments = activeRole === 'OWNER' || activeRole === 'DEVELOPER';

  const [activeTab, setActiveTab] = useState<'deployments' | 'clusters'>('deployments');
  const [subsystemStatus, setSubsystemStatus] = useState<KubernetesSubsystemStatus | null>(null);
  const [clusters, setClusters] = useState<KubernetesClusterConnection[]>([]);
  const [namespaces, setNamespaces] = useState<KubernetesNamespaceBinding[]>([]);
  const [deployments, setDeployments] = useState<KubernetesDeploymentOperation[]>([]);
  const [totalDeployments, setTotalDeployments] = useState(0);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [verifiedArtifacts, setVerifiedArtifacts] = useState<OciImageArtifact[]>([]);

  // Filters
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<KubernetesDeploymentStatus | ''>('');
  const [projectFilter, setProjectFilter] = useState('');
  const [clusterFilter, setClusterFilter] = useState('');

  // Page state
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [toast, setToast] = useState<{
    message: string;
    tone: 'success' | 'info' | 'error';
  } | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  // Register Cluster modal (OWNER only)
  const [isClusterModalOpen, setIsClusterModalOpen] = useState(false);
  const [clusterName, setClusterName] = useState('');
  const [clusterIdSlug, setClusterIdSlug] = useState('');
  const [clusterType, setClusterType] = useState<KubernetesClusterType>('KIND_LOCAL');
  const [clusterEnvCat, setClusterEnvCat] = useState<EnvironmentType>('DEVELOPMENT');
  const [clusterApiUrl, setClusterApiUrl] = useState('');
  const [clusterCaPem, setClusterCaPem] = useState('');
  const [clusterSaName, setClusterSaName] = useState('cloudforge-deployer');
  const [clusterAllowLoopback, setClusterAllowLoopback] = useState(true);
  const [clusterProdApproval, setClusterProdApproval] = useState(true);
  const [clusterDesc, setClusterDesc] = useState('');
  const [credProjectId, setCredProjectId] = useState('');
  const [credEnvId, setCredEnvId] = useState('');
  const [bearerSecretId, setBearerSecretId] = useState('');
  const [credEnvs, setCredEnvs] = useState<ProjectEnvironment[]>([]);
  const [credSecrets, setCredSecrets] = useState<EnvironmentSecretSummary[]>([]);
  const [isSavingCluster, setIsSavingCluster] = useState(false);
  const [clusterFormError, setClusterFormError] = useState<string | null>(null);

  // Bind Namespace modal (OWNER only)
  const [nsTargetCluster, setNsTargetCluster] = useState<KubernetesClusterConnection | null>(null);
  const [nsProjectId, setNsProjectId] = useState('');
  const [nsEnvId, setNsEnvId] = useState('');
  const [nsProjectEnvs, setNsProjectEnvs] = useState<ProjectEnvironment[]>([]);
  const [nsName, setNsName] = useState('');
  const [nsWorkloadSa, setNsWorkloadSa] = useState('cloudforge-workload');
  const [nsPullSecret, setNsPullSecret] = useState('');
  const [nsPss, setNsPss] = useState<KubernetesPodSecurityStandard>('RESTRICTED');
  const [nsNetPol, setNsNetPol] = useState<KubernetesNetworkPolicyMode>('DEFAULT_DENY');
  const [nsQuota, setNsQuota] = useState(true);
  const [nsLimitRange, setNsLimitRange] = useState(true);
  const [isSavingNs, setIsSavingNs] = useState(false);
  const [nsFormError, setNsFormError] = useState<string | null>(null);

  // Create Deployment modal (OWNER & DEVELOPER)
  const [isDeployModalOpen, setIsDeployModalOpen] = useState(false);
  const [depProjectId, setDepProjectId] = useState('');
  const [depEnvId, setDepEnvId] = useState('');
  const [depProjectEnvs, setDepProjectEnvs] = useState<ProjectEnvironment[]>([]);
  const [depClusterId, setDepClusterId] = useState('');
  const [depNamespaceBindingId, setDepNamespaceBindingId] = useState('');
  const [depArtifactId, setDepArtifactId] = useState('');
  const [depName, setDepName] = useState('');
  const [depServiceName, setDepServiceName] = useState('');
  const [depReplicas, setDepReplicas] = useState('2');
  const [depContainerPort, setDepContainerPort] = useState('8080');
  const [depServicePort, setDepServicePort] = useState('80');
  const [depCpuRequest, setDepCpuRequest] = useState('100m');
  const [depCpuLimit, setDepCpuLimit] = useState('500m');
  const [depMemRequest, setDepMemRequest] = useState('128Mi');
  const [depMemLimit, setDepMemLimit] = useState('512Mi');
  const [depMaxSurge, setDepMaxSurge] = useState('1');
  const [depMaxUnavailable, setDepMaxUnavailable] = useState('0');
  const [depReadinessType, setDepReadinessType] = useState<'HTTP_GET' | 'TCP_SOCKET'>('HTTP_GET');
  const [depReadinessPath, setDepReadinessPath] = useState('/healthz');
  const [depLivenessType, setDepLivenessType] = useState<'HTTP_GET' | 'TCP_SOCKET'>('HTTP_GET');
  const [depLivenessPath, setDepLivenessPath] = useState('/healthz');
  const [depReadOnlyFs, setDepReadOnlyFs] = useState(true);
  const [depRunAsNonRoot, setDepRunAsNonRoot] = useState(true);
  const [depProdConfirm, setDepProdConfirm] = useState(false);
  const [depProdReason, setDepProdReason] = useState('');
  const [depIdemKey, setDepIdemKey] = useState('');
  const [isSubmittingDeploy, setIsSubmittingDeploy] = useState(false);
  const [deployFormError, setDeployFormError] = useState<string | null>(null);

  // Deployment Details modal
  const [selectedDeployment, setSelectedDeployment] =
    useState<KubernetesDeploymentOperation | null>(null);
  const [deploymentEvents, setDeploymentEvents] = useState<KubernetesDeploymentEvent[]>([]);
  const [isLoadingDetail, setIsLoadingDetail] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelConfirmOpen, setCancelConfirmOpen] = useState(false);

  const loadData = useCallback(
    async (silent = false) => {
      if (!activeWorkspace) {
        setSubsystemStatus(null);
        setClusters([]);
        setNamespaces([]);
        setDeployments([]);
        setIsLoading(false);
        return;
      }
      if (silent) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }
      setErrorMessage(null);
      try {
        const [statusRes, clustersRes, nsRes, depRes, projRes, artRes] = await Promise.all([
          fetchKubernetesSubsystemStatus(activeWorkspace.id),
          fetchKubernetesClusters(activeWorkspace.id),
          fetchWorkspaceNamespaces(activeWorkspace.id),
          fetchKubernetesDeployments(activeWorkspace.id, {
            projectId: projectFilter || undefined,
            clusterId: clusterFilter || undefined,
            status: statusFilter || undefined,
            q: searchQuery || undefined,
            page: 0,
            size: 25,
          }),
          fetchProjects(activeWorkspace.id, { page: 0, size: 100 }),
          fetchOciArtifacts(activeWorkspace.id, {
            verificationStatus: 'VERIFIED',
            page: 0,
            size: 100,
          }),
        ]);

        setSubsystemStatus(statusRes);
        setClusters(Array.isArray(clustersRes) ? clustersRes : []);
        setNamespaces(Array.isArray(nsRes) ? nsRes : []);
        setDeployments(Array.isArray(depRes.items) ? depRes.items : []);
        setTotalDeployments(depRes.totalElements ?? 0);
        setProjects(Array.isArray(projRes.items) ? projRes.items : []);
        setVerifiedArtifacts(Array.isArray(artRes.items) ? artRes.items : []);
      } catch (error) {
        setErrorMessage(
          error instanceof Error
            ? error.message
            : 'Unable to load Kubernetes clusters and deployment operations.'
        );
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [activeWorkspace, projectFilter, clusterFilter, statusFilter, searchQuery]
  );

  useEffect(() => {
    void loadData(false);
  }, [loadData]);

  // Load environments & secrets for Cluster Registration modal
  useEffect(() => {
    if (!isClusterModalOpen || !isOwner || !activeWorkspace || !credProjectId) {
      setCredEnvs([]);
      return;
    }
    let cancelled = false;
    void fetchProjectDetail(activeWorkspace.id, credProjectId)
      .then((d) => {
        if (!cancelled) setCredEnvs(Array.isArray(d.environments) ? d.environments : []);
      })
      .catch(() => {
        if (!cancelled) setCredEnvs([]);
      });
    return () => {
      cancelled = true;
    };
  }, [isClusterModalOpen, isOwner, activeWorkspace, credProjectId]);

  useEffect(() => {
    if (!isClusterModalOpen || !isOwner || !activeWorkspace || !credProjectId || !credEnvId) {
      setCredSecrets([]);
      return;
    }
    let cancelled = false;
    void fetchEnvironmentSecrets(activeWorkspace.id, credProjectId, credEnvId, {
      status: 'ACTIVE',
      size: 100,
    })
      .then((res) => {
        if (!cancelled) setCredSecrets(Array.isArray(res.items) ? res.items : []);
      })
      .catch(() => {
        if (!cancelled) setCredSecrets([]);
      });
    return () => {
      cancelled = true;
    };
  }, [isClusterModalOpen, isOwner, activeWorkspace, credProjectId, credEnvId]);

  // Load environments for Namespace Binding modal
  useEffect(() => {
    if (!nsTargetCluster || !activeWorkspace || !nsProjectId) {
      setNsProjectEnvs([]);
      return;
    }
    let cancelled = false;
    void fetchProjectDetail(activeWorkspace.id, nsProjectId)
      .then((d) => {
        if (!cancelled) setNsProjectEnvs(Array.isArray(d.environments) ? d.environments : []);
      })
      .catch(() => {
        if (!cancelled) setNsProjectEnvs([]);
      });
    return () => {
      cancelled = true;
    };
  }, [nsTargetCluster, activeWorkspace, nsProjectId]);

  // Load environments for Create Deployment modal
  useEffect(() => {
    if (!isDeployModalOpen || !activeWorkspace || !depProjectId) {
      setDepProjectEnvs([]);
      return;
    }
    let cancelled = false;
    void fetchProjectDetail(activeWorkspace.id, depProjectId)
      .then((d) => {
        if (!cancelled) setDepProjectEnvs(Array.isArray(d.environments) ? d.environments : []);
      })
      .catch(() => {
        if (!cancelled) setDepProjectEnvs([]);
      });
    return () => {
      cancelled = true;
    };
  }, [isDeployModalOpen, activeWorkspace, depProjectId]);

  const validatedClusters = useMemo(
    () => clusters.filter((c) => c.connectionStatus === 'VALIDATED'),
    [clusters]
  );

  const matchingNamespaces = useMemo(
    () =>
      namespaces.filter(
        (n) =>
          n.status === 'ACTIVE' &&
          (!depProjectId || n.projectId === depProjectId) &&
          (!depEnvId || n.environmentId === depEnvId) &&
          (!depClusterId || n.clusterConnectionId === depClusterId)
      ),
    [namespaces, depProjectId, depEnvId, depClusterId]
  );

  const matchingArtifacts = useMemo(
    () =>
      verifiedArtifacts.filter(
        (a) => a.verificationStatus === 'VERIFIED' && (!depProjectId || a.projectId === depProjectId)
      ),
    [verifiedArtifacts, depProjectId]
  );

  const selectedDeployEnv = useMemo(
    () => depProjectEnvs.find((e) => e.id === depEnvId) ?? null,
    [depProjectEnvs, depEnvId]
  );
  const selectedDeployCluster = useMemo(
    () => clusters.find((c) => c.id === depClusterId) ?? null,
    [clusters, depClusterId]
  );
  const isProductionTarget =
    selectedDeployEnv?.environmentType === 'PRODUCTION' ||
    selectedDeployCluster?.environmentCategory === 'PRODUCTION';

  const handleCopy = (key: string, value: string) => {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      void navigator.clipboard.writeText(value);
      setCopiedKey(key);
      window.setTimeout(() => setCopiedKey((c) => (c === key ? null : c)), 1800);
    }
  };

  const openClusterModal = () => {
    setClusterName('');
    setClusterIdSlug('');
    setClusterType('KIND_LOCAL');
    setClusterEnvCat('DEVELOPMENT');
    setClusterApiUrl('https://127.0.0.1:6443');
    setClusterCaPem('');
    setClusterSaName('cloudforge-deployer');
    setClusterAllowLoopback(true);
    setClusterProdApproval(true);
    setClusterDesc('');
    setCredProjectId('');
    setCredEnvId('');
    setBearerSecretId('');
    setClusterFormError(null);
    setIsClusterModalOpen(true);
  };

  const handleCreateCluster = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace || isSavingCluster) return;
    setIsSavingCluster(true);
    setClusterFormError(null);
    try {
      const created = await createKubernetesCluster(activeWorkspace.id, {
        name: clusterName.trim(),
        clusterIdentifier: clusterIdSlug.trim(),
        clusterType,
        environmentCategory: clusterEnvCat,
        apiServerUrl: clusterApiUrl.trim(),
        caCertificatePem: clusterCaPem.trim(),
        credentialProjectId: credProjectId,
        credentialEnvironmentId: credEnvId,
        bearerTokenSecretId: bearerSecretId,
        serviceAccountName: clusterSaName.trim(),
        allowLocalLoopback: clusterAllowLoopback,
        productionApprovalRequired: clusterProdApproval,
        description: clusterDesc.trim() || undefined,
      });
      setIsClusterModalOpen(false);
      setToast({
        message: `Registered cluster "${created.name}" (${created.clusterIdentifier}). Run Validate to verify TLS, RBAC, and namespaces.`,
        tone: 'success',
      });
      await loadData(true);
    } catch (error) {
      setClusterFormError(
        error instanceof ApiClientError || error instanceof Error
          ? error.message
          : 'Unable to register cluster connection.'
      );
    } finally {
      setIsSavingCluster(false);
    }
  };

  const handleValidateCluster = async (cluster: KubernetesClusterConnection) => {
    if (!activeWorkspace) return;
    try {
      await validateKubernetesCluster(activeWorkspace.id, cluster.id);
      setToast({
        message: `Queued TLS, API, RBAC, and namespace validation for "${cluster.name}" on dedicated Kubernetes worker.`,
        tone: 'info',
      });
      await loadData(true);
    } catch (error) {
      setToast({
        message: error instanceof Error ? error.message : 'Unable to request cluster validation.',
        tone: 'error',
      });
    }
  };

  const handleDisableCluster = async (cluster: KubernetesClusterConnection) => {
    if (!activeWorkspace) return;
    try {
      await disableKubernetesCluster(activeWorkspace.id, cluster.id);
      setToast({ message: `Disabled cluster "${cluster.name}".`, tone: 'info' });
      await loadData(true);
    } catch (error) {
      setToast({
        message: error instanceof Error ? error.message : 'Unable to disable cluster.',
        tone: 'error',
      });
    }
  };

  const openBindNamespaceModal = (cluster: KubernetesClusterConnection) => {
    setNsTargetCluster(cluster);
    setNsProjectId('');
    setNsEnvId('');
    setNsName('');
    setNsWorkloadSa('cloudforge-workload');
    setNsPullSecret('');
    setNsPss('RESTRICTED');
    setNsNetPol('DEFAULT_DENY');
    setNsQuota(true);
    setNsLimitRange(true);
    setNsFormError(null);
  };

  const handleBindNamespace = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace || !nsTargetCluster || isSavingNs) return;
    setIsSavingNs(true);
    setNsFormError(null);
    try {
      const bound = await createNamespaceBinding(activeWorkspace.id, nsTargetCluster.id, {
        projectId: nsProjectId,
        environmentId: nsEnvId,
        namespaceName: nsName.trim() || undefined,
        workloadServiceAccountName: nsWorkloadSa.trim() || 'cloudforge-workload',
        imagePullSecretName: nsPullSecret.trim() || null,
        podSecurityStandard: nsPss,
        networkPolicyMode: nsNetPol,
        resourceQuotaConfigured: nsQuota,
        limitRangeConfigured: nsLimitRange,
      });
      setNsTargetCluster(null);
      setToast({
        message: `Bound approved namespace "${bound.namespaceName}" on "${bound.clusterName}".`,
        tone: 'success',
      });
      await loadData(true);
    } catch (error) {
      setNsFormError(
        error instanceof Error ? error.message : 'Unable to bind approved namespace.'
      );
    } finally {
      setIsSavingNs(false);
    }
  };

  const openDeployModal = () => {
    setDepProjectId('');
    setDepEnvId('');
    setDepClusterId(validatedClusters[0]?.id ?? '');
    setDepNamespaceBindingId('');
    setDepArtifactId('');
    setDepName('');
    setDepServiceName('');
    setDepReplicas('2');
    setDepContainerPort('8080');
    setDepServicePort('80');
    setDepCpuRequest('100m');
    setDepCpuLimit('500m');
    setDepMemRequest('128Mi');
    setDepMemLimit('512Mi');
    setDepMaxSurge('1');
    setDepMaxUnavailable('0');
    setDepReadinessType('HTTP_GET');
    setDepReadinessPath('/healthz');
    setDepLivenessType('HTTP_GET');
    setDepLivenessPath('/healthz');
    setDepReadOnlyFs(true);
    setDepRunAsNonRoot(true);
    setDepProdConfirm(false);
    setDepProdReason('');
    setDepIdemKey(generateIdempotencyKey());
    setDeployFormError(null);
    setIsDeployModalOpen(true);
  };

  const handleCreateDeployment = async (event: FormEvent) => {
    event.preventDefault();
    if (!activeWorkspace || isSubmittingDeploy) return;
    setIsSubmittingDeploy(true);
    setDeployFormError(null);
    try {
      const cPort = Number.parseInt(depContainerPort, 10) || 8080;
      const sPort = Number.parseInt(depServicePort, 10) || 80;
      const reps = Number.parseInt(depReplicas, 10) || 1;
      const created = await createKubernetesDeployment(
        activeWorkspace.id,
        {
          projectId: depProjectId,
          environmentId: depEnvId,
          clusterConnectionId: depClusterId,
          namespaceBindingId: depNamespaceBindingId || matchingNamespaces[0]?.id || null,
          ociArtifactId: depArtifactId,
          deploymentName: depName.trim() || undefined,
          serviceName: depServiceName.trim() || undefined,
          replicaCount: reps,
          containerPort: cPort,
          servicePort: sPort,
          cpuRequest: depCpuRequest.trim(),
          cpuLimit: depCpuLimit.trim(),
          memoryRequest: depMemRequest.trim(),
          memoryLimit: depMemLimit.trim(),
          rollingMaxSurge: depMaxSurge.trim(),
          rollingMaxUnavailable: depMaxUnavailable.trim(),
          readinessProbe: {
            type: depReadinessType,
            path: depReadinessType === 'HTTP_GET' ? depReadinessPath.trim() : null,
            port: cPort,
            initialDelaySeconds: 5,
            periodSeconds: 5,
            timeoutSeconds: 3,
            failureThreshold: 3,
            successThreshold: 1,
          },
          livenessProbe: {
            type: depLivenessType,
            path: depLivenessType === 'HTTP_GET' ? depLivenessPath.trim() : null,
            port: cPort,
            initialDelaySeconds: 15,
            periodSeconds: 10,
            timeoutSeconds: 3,
            failureThreshold: 3,
            successThreshold: 1,
          },
          readOnlyRootFilesystem: depReadOnlyFs,
          runAsNonRoot: depRunAsNonRoot,
          runAsUser: 10001,
          productionApprovalConfirmed: isProductionTarget ? depProdConfirm : undefined,
          productionApprovalJustification: isProductionTarget ? depProdReason.trim() : undefined,
        },
        depIdemKey
      );
      setIsDeployModalOpen(false);
      setToast({
        message: `Queued Kubernetes deployment "${created.deploymentName}" (${created.manifestDigest.slice(0, 19)}...) in ${created.namespaceName}.`,
        tone: 'success',
      });
      await loadData(true);
    } catch (error) {
      setDeployFormError(
        error instanceof Error ? error.message : 'Unable to create Kubernetes deployment.'
      );
    } finally {
      setIsSubmittingDeploy(false);
    }
  };

  const openDeploymentDetail = async (dep: KubernetesDeploymentOperation) => {
    if (!activeWorkspace) return;
    setSelectedDeployment(dep);
    setDeploymentEvents([]);
    setDetailError(null);
    setCancelConfirmOpen(false);
    setIsLoadingDetail(true);
    try {
      const [detail, events] = await Promise.all([
        fetchKubernetesDeploymentDetail(activeWorkspace.id, dep.id),
        fetchKubernetesDeploymentEvents(activeWorkspace.id, dep.id),
      ]);
      setSelectedDeployment(detail);
      setDeploymentEvents(Array.isArray(events) ? events : []);
    } catch (error) {
      setDetailError(
        error instanceof Error ? error.message : 'Unable to load deployment details.'
      );
    } finally {
      setIsLoadingDetail(false);
    }
  };

  const refreshSelectedDeploymentStatus = async () => {
    if (!activeWorkspace || !selectedDeployment) return;
    setIsLoadingDetail(true);
    setDetailError(null);
    try {
      const [liveStatus, events] = await Promise.all([
        fetchKubernetesDeploymentStatus(activeWorkspace.id, selectedDeployment.id),
        fetchKubernetesDeploymentEvents(activeWorkspace.id, selectedDeployment.id),
      ]);
      setSelectedDeployment((prev) =>
        prev
          ? {
              ...prev,
              status: liveStatus.status,
              applyInitiated: liveStatus.applyInitiated,
              observedReplicas: liveStatus.observedReplicas,
              updatedReplicas: liveStatus.updatedReplicas,
              readyReplicas: liveStatus.readyReplicas,
              availableReplicas: liveStatus.availableReplicas,
              observedGeneration: liveStatus.observedGeneration,
              k8sDeploymentUid: liveStatus.k8sDeploymentUid,
              k8sServiceUid: liveStatus.k8sServiceUid,
              rolloutConditions: liveStatus.rolloutConditions,
              podDiagnostics: liveStatus.podDiagnostics,
              failureCategory: liveStatus.failureCategory,
              errorSummary: liveStatus.errorSummary,
              cancellationRequestedAt: liveStatus.cancellationRequestedAt,
              startedAt: liveStatus.startedAt,
              completedAt: liveStatus.completedAt,
              updatedAt: liveStatus.updatedAt,
            }
          : null
      );
      setDeploymentEvents(Array.isArray(events) ? events : []);
      await loadData(true);
    } catch (error) {
      setDetailError(
        error instanceof Error ? error.message : 'Unable to refresh live deployment status.'
      );
    } finally {
      setIsLoadingDetail(false);
    }
  };

  const handleCancelDeployment = async () => {
    if (!activeWorkspace || !selectedDeployment || isCancelling) return;
    setIsCancelling(true);
    try {
      const updated = await cancelKubernetesDeployment(
        activeWorkspace.id,
        selectedDeployment.id,
        'Cancelled from CloudForge Deployments Console'
      );
      setSelectedDeployment(updated);
      setCancelConfirmOpen(false);
      setToast({
        message: `Cancellation requested for "${updated.deploymentName}". Note: cancellation does not perform automatic Phase 3.7 rollback.`,
        tone: 'info',
      });
      await refreshSelectedDeploymentStatus();
    } catch (error) {
      setDetailError(
        error instanceof Error ? error.message : 'Unable to cancel deployment.'
      );
    } finally {
      setIsCancelling(false);
    }
  };

  const deploymentColumns: TableColumn<KubernetesDeploymentOperation>[] = [
    {
      key: 'workload',
      header: 'Workload & Project',
      render: (row) => (
        <div>
          <button
            type="button"
            className="cf-pipelines-link-btn"
            onClick={() => void openDeploymentDetail(row)}
          >
            <strong className="mono">{row.deploymentName}</strong>
          </button>
          <span className="cf-pipelines-subtext">
            {row.projectName} · {row.environmentName} ({row.environmentType})
          </span>
        </div>
      ),
    },
    {
      key: 'target',
      header: 'Cluster & Namespace',
      render: (row) => (
        <div>
          <div style={{ fontSize: '13px', fontWeight: 500 }}>{row.clusterName}</div>
          <span className="cf-pipelines-subtext mono">{row.namespaceName}</span>
        </div>
      ),
    },
    {
      key: 'digest',
      header: 'Verified Image Digest',
      render: (row) => (
        <div>
          <div className="cf-oci-digest-box" title={row.immutableImageReference}>
            <span className="cf-oci-digest-text">{row.manifestDigest}</span>
            <button
              type="button"
              className="cf-oci-copy-btn"
              aria-label={`Copy immutable image reference for ${row.deploymentName}`}
              onClick={() => handleCopy(row.id, row.immutableImageReference)}
            >
              {copiedKey === row.id ? <Check size={11} /> : <Copy size={11} />}
              <span>{copiedKey === row.id ? 'Copied' : 'Copy'}</span>
            </button>
          </div>
          <span className="cf-pipelines-subtext mono">
            <GitCommitHorizontal size={11} aria-hidden="true" /> {row.sourceCommitSha.slice(0, 10)}
          </span>
        </div>
      ),
    },
    {
      key: 'replicas',
      header: 'Replica Readiness',
      render: (row) => {
        const pct =
          row.requestedReplicas > 0
            ? Math.min(100, Math.round((row.readyReplicas / row.requestedReplicas) * 100))
            : 0;
        const fillClass =
          row.status === 'FAILED'
            ? 'cf-k8s-replica-bar__fill--failed'
            : row.status === 'RECONCILIATION_REQUIRED'
              ? 'cf-k8s-replica-bar__fill--warn'
              : row.status === 'SUCCEEDED'
                ? ''
                : 'cf-k8s-replica-bar__fill--progressing';
        return (
          <div className="cf-k8s-replica-meter">
            <div className="cf-k8s-replica-meter__text">
              <span>
                {row.readyReplicas}/{row.requestedReplicas} ready
              </span>
              <span>{row.availableReplicas} avail</span>
            </div>
            <div
              className="cf-k8s-replica-bar"
              role="progressbar"
              aria-label={`Replica readiness for ${row.deploymentName}`}
              aria-valuemin={0}
              aria-valuemax={row.requestedReplicas}
              aria-valuenow={row.readyReplicas}
            >
              <div
                className={`cf-k8s-replica-bar__fill ${fillClass}`}
                style={{ width: `${pct}%` }}
              />
            </div>
          </div>
        );
      },
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => {
        const meta = DEPLOYMENT_STATUS_META[row.status];
        return (
          <div>
            <StatusBadge
              label={meta.label}
              tone={meta.tone}
              pulse={Boolean(meta.pulse)}
              size="sm"
            />
            {row.errorSummary && (
              <span className="cf-pipelines-subtext">{row.errorSummary}</span>
            )}
          </div>
        );
      },
    },
    {
      key: 'timestamps',
      header: 'Updated',
      render: (row) => (
        <span className="mono cf-muted" style={{ fontSize: '12px' }}>
          {formatTimestamp(row.completedAt ?? row.startedAt ?? row.createdAt)}
        </span>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) => (
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => void openDeploymentDetail(row)}
        >
          <Layers size={12} aria-hidden="true" />
          <span>Inspect</span>
        </button>
      ),
    },
  ];

  const clusterColumns: TableColumn<KubernetesClusterConnection>[] = [
    {
      key: 'name',
      header: 'Cluster Connection',
      render: (row) => (
        <div>
          <strong>{row.name}</strong>{' '}
          <span className="cf-attempt-pill">{row.clusterType}</span>{' '}
          <span className="cf-stage-pill">{row.environmentCategory}</span>
          <span className="cf-pipelines-subtext mono">
            {row.clusterIdentifier} · {row.apiServerUrl}
          </span>
        </div>
      ),
    },
    {
      key: 'auth',
      header: 'Identity & TLS CA',
      render: (row) => (
        <div>
          <span className="cf-secret-ref-pill">
            <KeyRound size={11} aria-hidden="true" />
            <span>
              {row.bearerTokenSecretName} ({row.serviceAccountName})
            </span>
          </span>
          <span className="cf-pipelines-subtext mono" title={row.caCertificateFingerprintSha256}>
            CA SHA-256: {row.caCertificateFingerprintSha256.slice(0, 16)}...
          </span>
        </div>
      ),
    },
    {
      key: 'version',
      header: 'Server Version',
      render: (row) => (
        <div className="mono" style={{ fontSize: '12px' }}>
          <div>{row.kubernetesVersion ?? 'Unverified'}</div>
          <span className="cf-pipelines-subtext">
            {row.namespaceBindings.length}{' '}
            {row.namespaceBindings.length === 1 ? 'namespace' : 'namespaces'}
          </span>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => {
        const meta = CLUSTER_STATUS_META[row.connectionStatus];
        return (
          <div>
            <StatusBadge
              label={meta.label}
              tone={meta.tone}
              pulse={Boolean(meta.pulse)}
              size="sm"
            />
            {row.lastValidationError && (
              <span className="cf-pipelines-subtext">{row.lastValidationError}</span>
            )}
          </div>
        );
      },
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) =>
        isOwner ? (
          <div className="cf-inline-actions cf-inline-actions--end">
            {row.connectionStatus !== 'DISABLED' && (
              <>
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => void handleValidateCluster(row)}
                >
                  <ShieldCheck size={12} aria-hidden="true" />
                  <span>Validate</span>
                </button>
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => openBindNamespaceModal(row)}
                >
                  <Network size={12} aria-hidden="true" />
                  <span>Bind Namespace</span>
                </button>
                <button
                  type="button"
                  className="btn btn-sm btn-danger"
                  onClick={() => void handleDisableCluster(row)}
                >
                  <Slash size={12} aria-hidden="true" />
                  <span>Disable</span>
                </button>
              </>
            )}
          </div>
        ) : (
          <span className="cf-muted">Owner Managed</span>
        ),
    },
  ];

  const namespaceColumns: TableColumn<KubernetesNamespaceBinding>[] = [
    {
      key: 'ns',
      header: 'Approved Namespace',
      render: (row) => (
        <div>
          <strong className="mono">{row.namespaceName}</strong>
          <span className="cf-pipelines-subtext">
            Workload SA: <span className="mono">{row.workloadServiceAccountName}</span>
          </span>
        </div>
      ),
    },
    {
      key: 'mapping',
      header: 'Cluster / Project / Environment',
      render: (row) => (
        <div>
          <div>{row.clusterName}</div>
          <span className="cf-pipelines-subtext">
            {row.projectName} · {row.environmentName} ({row.environmentType})
          </span>
        </div>
      ),
    },
    {
      key: 'security',
      header: 'Isolation Policy',
      render: (row) => (
        <div className="cf-run-sha-branch">
          <span className="cf-stage-pill">PSA: {row.podSecurityStandard}</span>
          <span className="cf-attempt-pill">NetPol: {row.networkPolicyMode}</span>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <StatusBadge
          label={row.status === 'ACTIVE' ? 'Active' : 'Disabled'}
          tone={row.status === 'ACTIVE' ? 'healthy' : 'neutral'}
          size="sm"
        />
      ),
    },
  ];

  const isSelectedActive =
    selectedDeployment !== null &&
    ['PENDING', 'VALIDATING', 'APPLYING', 'ROLLING_OUT', 'VERIFYING'].includes(
      selectedDeployment.status
    );

  return (
    <PageContainer
      title="Deployments"
      description="Secure Kubernetes deployment console for approved clusters, isolated namespaces, and immutable digest-verified rolling updates."
      phaseBadge={{
        label: subsystemStatus?.kubernetesEnabled
          ? 'Phase 3.6 · Kubernetes Engine Active'
          : 'Phase 3.6 · Kubernetes Engine (Safe Default)',
        tone: subsystemStatus?.kubernetesEnabled ? 'healthy' : 'info',
      }}
      actions={
        <div className="cf-inline-actions">
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => void loadData(true)}
            disabled={isRefreshing}
          >
            <RefreshCw size={13} className={isRefreshing ? 'cf-spin' : ''} aria-hidden="true" />
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
          </button>
          {isOwner && (
            <button type="button" className="btn btn-sm" onClick={openClusterModal}>
              <Server size={13} aria-hidden="true" />
              <span>Register Cluster</span>
            </button>
          )}
          {canRequestDeployments && (
            <button type="button" className="btn btn-primary btn-sm" onClick={openDeployModal}>
              <Rocket size={13} aria-hidden="true" />
              <span>New Deployment</span>
            </button>
          )}
        </div>
      }
    >
      <div className="cf-k8s-page">
        {toast && (
          <Toast message={toast.message} tone={toast.tone} onDismiss={() => setToast(null)} />
        )}

        {errorMessage && (
          <ErrorState
            title="Unable to load Kubernetes deployments"
            message={errorMessage}
            onRetry={() => void loadData(false)}
          />
        )}

        {isLoading ? (
          <LoadingState
            title="Loading Kubernetes deployment console"
            description="Fetching cluster connections, approved namespaces, and deployment operations..."
          />
        ) : (
          <>
            {/* Overview Telemetry */}
            <section
              aria-label="Kubernetes deployment overview"
              className="cf-grid cf-grid--4 cf-pipelines-stats"
            >
              <div className="cf-stat-tile">
                <div className="cf-stat-tile__top">
                  <span className="cf-stat-tile__label">Configured Clusters</span>
                  <span className="cf-stat-tile__icon" aria-hidden="true">
                    <Server size={16} />
                  </span>
                </div>
                <div className="cf-stat-tile__value" data-testid="k8s-cluster-count">
                  {subsystemStatus?.configuredClusters ?? 0}
                </div>
                <div className="cf-stat-tile__meta">
                  {subsystemStatus?.validatedClusters ?? 0} validated ·{' '}
                  {subsystemStatus?.activeNamespaceBindings ?? 0} namespaces
                </div>
              </div>

              <div className="cf-stat-tile">
                <div className="cf-stat-tile__top">
                  <span className="cf-stat-tile__label">Total Deployments</span>
                  <span className="cf-stat-tile__icon" aria-hidden="true">
                    <Rocket size={16} />
                  </span>
                </div>
                <div className="cf-stat-tile__value" data-testid="k8s-total-deployments">
                  {subsystemStatus?.totalDeployments ?? 0}
                </div>
                <div className="cf-stat-tile__meta">
                  {subsystemStatus?.runningDeployments ?? 0} active in progress
                </div>
              </div>

              <div className="cf-stat-tile">
                <div className="cf-stat-tile__top">
                  <span className="cf-stat-tile__label">Healthy Rollouts</span>
                  <span className="cf-stat-tile__icon" aria-hidden="true">
                    <CheckCircle2 size={16} />
                  </span>
                </div>
                <div className="cf-stat-tile__value" data-testid="k8s-succeeded-deployments">
                  {subsystemStatus?.succeededDeployments ?? 0}
                </div>
                <div className="cf-stat-tile__meta">
                  {subsystemStatus?.failedDeployments ?? 0} failed / needs reconciliation
                </div>
              </div>

              <div className="cf-stat-tile">
                <div className="cf-stat-tile__top">
                  <span className="cf-stat-tile__label">Kubernetes Workers</span>
                  <span className="cf-stat-tile__icon" aria-hidden="true">
                    <Cpu size={16} />
                  </span>
                </div>
                <div className="cf-stat-tile__value" data-testid="k8s-worker-count">
                  {subsystemStatus?.activeDeployWorkers ?? 0}
                </div>
                <div className="cf-stat-tile__meta">
                  {subsystemStatus?.kubernetesEnabled
                    ? 'KUBERNETES_DEPLOY plane active'
                    : 'Disabled by safe default'}
                </div>
              </div>
            </section>

            {subsystemStatus && !subsystemStatus.kubernetesEnabled && (
              <div className="cf-notice cf-notice--info" role="status">
                <div className="cf-notice__icon" aria-hidden="true">
                  <Info size={18} />
                </div>
                <div>
                  <p className="cf-notice__title">
                    Kubernetes Execution Plane Disabled by Default (Render-Safe)
                  </p>
                  <p className="cf-notice__text">
                    The CloudForge control plane never executes <span className="mono">kubectl</span>{' '}
                    or mounts kubeconfig files. Enable{' '}
                    <span className="mono">CLOUDFORGE_K8S_ENABLED=true</span> and connect a
                    dedicated <span className="mono">KUBERNETES_DEPLOY</span> worker to execute real
                    verified rollouts against local kind/k3d/k3s or approved remote clusters.
                  </p>
                </div>
              </div>
            )}

            {/* Tabs */}
            <div className="cf-k8s-tabs" role="tablist" aria-label="Deployments console sections">
              <button
                type="button"
                role="tab"
                aria-selected={activeTab === 'deployments'}
                className={`cf-k8s-tab ${activeTab === 'deployments' ? 'cf-k8s-tab--active' : ''}`}
                onClick={() => setActiveTab('deployments')}
              >
                <History size={14} aria-hidden="true" />
                <span>Deployment History</span>
                <span className="cf-k8s-tab__count">{totalDeployments}</span>
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={activeTab === 'clusters'}
                className={`cf-k8s-tab ${activeTab === 'clusters' ? 'cf-k8s-tab--active' : ''}`}
                onClick={() => setActiveTab('clusters')}
              >
                <Server size={14} aria-hidden="true" />
                <span>Clusters &amp; Namespaces</span>
                <span className="cf-k8s-tab__count">{clusters.length}</span>
              </button>
            </div>

            {activeTab === 'deployments' ? (
              <DashboardCard
                title="Kubernetes Deployment Operations"
                subtitle="Immutable digest-verified rolling updates persisted with manifest checksums and live rollout evidence (Phase 3.7 rollback-ready)"
                icon={<Rocket size={17} />}
                headerAction={
                  <StatusBadge
                    label={`${totalDeployments} ${totalDeployments === 1 ? 'Operation' : 'Operations'}`}
                    tone={totalDeployments > 0 ? 'info' : 'neutral'}
                    size="sm"
                  />
                }
              >
                <div className="cf-toolbar">
                  <SearchField
                    label="Search deployments"
                    placeholder="Search by workload, namespace, SHA-256 digest, or commit..."
                    value={searchQuery}
                    onChange={setSearchQuery}
                  />
                  <div className="cf-filter-group">
                    <select
                      className="cf-filter-select"
                      aria-label="Filter deployments by status"
                      value={statusFilter}
                      onChange={(e) =>
                        setStatusFilter(e.target.value as KubernetesDeploymentStatus | '')
                      }
                    >
                      <option value="">All deployment statuses</option>
                      <option value="PENDING">Pending</option>
                      <option value="VALIDATING">Validating</option>
                      <option value="APPLYING">Applying</option>
                      <option value="ROLLING_OUT">Rolling Out</option>
                      <option value="VERIFYING">Verifying</option>
                      <option value="SUCCEEDED">Healthy</option>
                      <option value="FAILED">Failed</option>
                      <option value="CANCELLED">Cancelled</option>
                      <option value="RECONCILIATION_REQUIRED">Reconciliation Required</option>
                    </select>
                    <select
                      className="cf-filter-select"
                      aria-label="Filter deployments by project"
                      value={projectFilter}
                      onChange={(e) => setProjectFilter(e.target.value)}
                    >
                      <option value="">All projects</option>
                      {projects.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name}
                        </option>
                      ))}
                    </select>
                    <select
                      className="cf-filter-select"
                      aria-label="Filter deployments by cluster"
                      value={clusterFilter}
                      onChange={(e) => setClusterFilter(e.target.value)}
                    >
                      <option value="">All clusters</option>
                      {clusters.map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>

                <DataTable
                  columns={deploymentColumns}
                  rows={deployments}
                  getRowKey={(row) => row.id}
                  emptyMessage="No Kubernetes deployment operations recorded yet. Register and validate a cluster, bind an isolated namespace, and deploy a verified OCI image artifact."
                  caption="Kubernetes deployment operations history"
                />
              </DashboardCard>
            ) : (
              <>
                <DashboardCard
                  title="Approved Kubernetes Clusters"
                  subtitle="SSRF-protected HTTPS API endpoints authenticated strictly via Phase 3.4 AES-256-GCM encrypted ServiceAccount tokens"
                  icon={<Server size={17} />}
                  headerAction={
                    isOwner ? (
                      <button
                        type="button"
                        className="btn btn-primary btn-sm"
                        onClick={openClusterModal}
                      >
                        <Plus size={13} aria-hidden="true" />
                        <span>Add Cluster</span>
                      </button>
                    ) : (
                      <StatusBadge label="Owner Managed" tone="neutral" size="sm" />
                    )
                  }
                >
                  <DataTable
                    columns={clusterColumns}
                    rows={clusters}
                    getRowKey={(row) => row.id}
                    emptyMessage="No Kubernetes clusters registered in this workspace yet."
                    caption="Workspace Kubernetes cluster connections"
                  />
                </DashboardCard>

                <DashboardCard
                  title="Operator-Approved Namespace Bindings"
                  subtitle="Deterministic workspace/project/environment namespace mappings enforcing Pod Security Admission, quotas, and NetworkPolicy isolation"
                  icon={<Network size={17} />}
                  headerAction={
                    <StatusBadge
                      label={`${namespaces.length} ${namespaces.length === 1 ? 'Binding' : 'Bindings'}`}
                      tone={namespaces.length > 0 ? 'healthy' : 'neutral'}
                      size="sm"
                    />
                  }
                >
                  <DataTable
                    columns={namespaceColumns}
                    rows={namespaces}
                    getRowKey={(row) => row.id}
                    emptyMessage="No approved Kubernetes namespaces bound yet. Use 'Bind Namespace' on a registered cluster."
                    caption="Approved Kubernetes namespace bindings"
                  />
                </DashboardCard>
              </>
            )}
          </>
        )}
      </div>

      {/* Register Cluster Modal (OWNER only) */}
      <Modal
        isOpen={isClusterModalOpen}
        title="Register Approved Kubernetes Cluster"
        subtitle="Provide safe HTTPS API metadata, public X.509 CA certificate, and a Phase 3.4 encrypted secret reference for the least-privilege ServiceAccount token."
        onClose={() => setIsClusterModalOpen(false)}
      >
        <ValidatedForm className="cf-form cf-k8s-form" onSubmit={handleCreateCluster}>
          {clusterFormError && (
            <div className="cf-notice cf-notice--error" role="alert">
              <div className="cf-notice__icon" aria-hidden="true">
                <AlertTriangle size={16} />
              </div>
              <p className="cf-notice__text">{clusterFormError}</p>
            </div>
          )}

          <div className="cf-k8s-form-grid">
            <div className="cf-field">
              <label htmlFor="k8s-cluster-name">Display Name</label>
              <input
                id="k8s-cluster-name"
                type="text"
                required
                placeholder="Local Kind Dev Cluster"
                value={clusterName}
                onChange={(e) => setClusterName(e.target.value)}
              />
            </div>
            <div className="cf-field">
              <label htmlFor="k8s-cluster-id">Cluster Identifier</label>
              <input
                id="k8s-cluster-id"
                type="text"
                required
                placeholder="kind-cloudforge-dev"
                value={clusterIdSlug}
                onChange={(e) => setClusterIdSlug(e.target.value)}
              />
            </div>
          </div>

          <div className="cf-k8s-form-grid">
            <div className="cf-field">
              <label htmlFor="k8s-cluster-type">Cluster Type</label>
              <select
                id="k8s-cluster-type"
                value={clusterType}
                onChange={(e) => setClusterType(e.target.value as KubernetesClusterType)}
              >
                <option value="KIND_LOCAL">Local kind Cluster</option>
                <option value="K3D_LOCAL">Local k3d Cluster</option>
                <option value="K3S_LOCAL">Local k3s Cluster</option>
                <option value="REMOTE_KUBERNETES">Remote Kubernetes Cluster</option>
              </select>
            </div>
            <div className="cf-field">
              <label htmlFor="k8s-cluster-env">Environment Category</label>
              <select
                id="k8s-cluster-env"
                value={clusterEnvCat}
                onChange={(e) => setClusterEnvCat(e.target.value as EnvironmentType)}
              >
                <option value="DEVELOPMENT">Development</option>
                <option value="STAGING">Staging</option>
                <option value="PRODUCTION">Production (Requires Explicit Approval)</option>
              </select>
            </div>
          </div>

          <div className="cf-k8s-form-grid">
            <div className="cf-field">
              <label htmlFor="k8s-api-url">HTTPS API Server URL</label>
              <input
                id="k8s-api-url"
                type="url"
                required
                placeholder="https://127.0.0.1:6443"
                value={clusterApiUrl}
                onChange={(e) => setClusterApiUrl(e.target.value)}
              />
            </div>
            <div className="cf-field">
              <label htmlFor="k8s-sa-name">Least-Privilege ServiceAccount</label>
              <input
                id="k8s-sa-name"
                type="text"
                required
                placeholder="cloudforge-deployer"
                value={clusterSaName}
                onChange={(e) => setClusterSaName(e.target.value)}
              />
            </div>
          </div>

          <div className="cf-field">
            <label htmlFor="k8s-ca-pem">Cluster CA Certificate PEM (Public X.509 only)</label>
            <textarea
              id="k8s-ca-pem"
              rows={4}
              required
              className="mono"
              placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"
              value={clusterCaPem}
              onChange={(e) => setClusterCaPem(e.target.value)}
            />
          </div>

          <div className="cf-k8s-form-section">
            <h4 className="cf-k8s-form-section__title">
              Encrypted Bearer Token Secret Reference (Phase 3.4 Vault)
            </h4>
            <div className="cf-k8s-form-grid cf-k8s-form-grid--3">
              <div className="cf-field">
                <label htmlFor="k8s-cred-proj">Secret Project</label>
                <select
                  id="k8s-cred-proj"
                  required
                  value={credProjectId}
                  onChange={(e) => {
                    setCredProjectId(e.target.value);
                    setCredEnvId('');
                    setBearerSecretId('');
                  }}
                >
                  <option value="">Select project...</option>
                  {projects.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="cf-field">
                <label htmlFor="k8s-cred-env">Secret Environment</label>
                <select
                  id="k8s-cred-env"
                  required
                  value={credEnvId}
                  disabled={!credProjectId}
                  onChange={(e) => {
                    setCredEnvId(e.target.value);
                    setBearerSecretId('');
                  }}
                >
                  <option value="">Select environment...</option>
                  {credEnvs.map((env) => (
                    <option key={env.id} value={env.id}>
                      {env.name} ({env.environmentType})
                    </option>
                  ))}
                </select>
              </div>
              <div className="cf-field">
                <label htmlFor="k8s-cred-secret">Bearer Token Secret</label>
                <select
                  id="k8s-cred-secret"
                  required
                  value={bearerSecretId}
                  disabled={!credEnvId}
                  onChange={(e) => setBearerSecretId(e.target.value)}
                >
                  <option value="">Select encrypted secret...</option>
                  {credSecrets.map((sec) => (
                    <option key={sec.id} value={sec.id}>
                      {sec.name} (v{sec.currentVersionNumber})
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </div>

          <div className="cf-k8s-form-grid">
            <label className="cf-k8s-checkbox-row">
              <input
                type="checkbox"
                checked={clusterAllowLoopback}
                onChange={(e) => setClusterAllowLoopback(e.target.checked)}
                disabled={clusterType === 'REMOTE_KUBERNETES'}
              />
              <span>Allow loopback API endpoint (local kind/k3d/k3s development only)</span>
            </label>
            <label className="cf-k8s-checkbox-row">
              <input
                type="checkbox"
                checked={clusterProdApproval}
                onChange={(e) => setClusterProdApproval(e.target.checked)}
              />
              <span>Require explicit approval justification for production deployments</span>
            </label>
          </div>

          <div className="cf-field">
            <label htmlFor="k8s-cluster-desc">Description (Optional)</label>
            <input
              id="k8s-cluster-desc"
              type="text"
              placeholder="Disposable local verification cluster with restricted PSA"
              value={clusterDesc}
              onChange={(e) => setClusterDesc(e.target.value)}
            />
          </div>

          <div className="cf-modal-actions">
            <button
              type="button"
              className="btn"
              onClick={() => setIsClusterModalOpen(false)}
              disabled={isSavingCluster}
            >
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isSavingCluster}>
              <ShieldCheck size={14} aria-hidden="true" />
              <span>{isSavingCluster ? 'Registering...' : 'Register Cluster'}</span>
            </button>
          </div>
        </ValidatedForm>
      </Modal>

      {/* Bind Namespace Modal (OWNER only) */}
      <Modal
        isOpen={nsTargetCluster !== null}
        title={`Bind Approved Namespace${nsTargetCluster ? ` · ${nsTargetCluster.name}` : ''}`}
        subtitle="Map a workspace project and environment to an operator-provisioned isolated namespace."
        onClose={() => setNsTargetCluster(null)}
      >
        <ValidatedForm className="cf-form cf-k8s-form" onSubmit={handleBindNamespace}>
          {nsFormError && (
            <div className="cf-notice cf-notice--error" role="alert">
              <p className="cf-notice__text">{nsFormError}</p>
            </div>
          )}

          <div className="cf-k8s-form-grid">
            <div className="cf-field">
              <label htmlFor="ns-project">Project</label>
              <select
                id="ns-project"
                required
                value={nsProjectId}
                onChange={(e) => {
                  setNsProjectId(e.target.value);
                  setNsEnvId('');
                }}
              >
                <option value="">Select project...</option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="cf-field">
              <label htmlFor="ns-env">Environment</label>
              <select
                id="ns-env"
                required
                value={nsEnvId}
                disabled={!nsProjectId}
                onChange={(e) => setNsEnvId(e.target.value)}
              >
                <option value="">Select environment...</option>
                {nsProjectEnvs.map((env) => (
                  <option key={env.id} value={env.id}>
                    {env.name} ({env.environmentType})
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="cf-field">
            <label htmlFor="ns-name">Namespace Name (leave blank for deterministic default)</label>
            <input
              id="ns-name"
              type="text"
              className="mono"
              placeholder="cf-workspace-project-dev"
              value={nsName}
              onChange={(e) => setNsName(e.target.value)}
            />
          </div>

          <div className="cf-k8s-form-grid">
            <div className="cf-field">
              <label htmlFor="ns-workload-sa">Workload ServiceAccount</label>
              <input
                id="ns-workload-sa"
                type="text"
                required
                value={nsWorkloadSa}
                onChange={(e) => setNsWorkloadSa(e.target.value)}
              />
            </div>
            <div className="cf-field">
              <label htmlFor="ns-pull-secret">Pre-Provisioned Pull Secret (Optional)</label>
              <input
                id="ns-pull-secret"
                type="text"
                placeholder="regcred-readonly"
                value={nsPullSecret}
                onChange={(e) => setNsPullSecret(e.target.value)}
              />
            </div>
          </div>

          <div className="cf-k8s-form-grid">
            <div className="cf-field">
              <label htmlFor="ns-pss">Pod Security Standard</label>
              <select
                id="ns-pss"
                value={nsPss}
                onChange={(e) => setNsPss(e.target.value as KubernetesPodSecurityStandard)}
              >
                <option value="RESTRICTED">Restricted (Recommended)</option>
                <option value="BASELINE">Baseline</option>
              </select>
            </div>
            <div className="cf-field">
              <label htmlFor="ns-netpol">NetworkPolicy Status</label>
              <select
                id="ns-netpol"
                value={nsNetPol}
                onChange={(e) => setNsNetPol(e.target.value as KubernetesNetworkPolicyMode)}
              >
                <option value="DEFAULT_DENY">Default-Deny Applied</option>
                <option value="ENFORCED_VERIFIED">CNI Enforcement Verified</option>
                <option value="UNENFORCED_LOCAL_CNI">Local CNI Unenforced (Documented)</option>
              </select>
            </div>
          </div>

          <div className="cf-k8s-form-grid">
            <label className="cf-k8s-checkbox-row">
              <input
                type="checkbox"
                checked={nsQuota}
                onChange={(e) => setNsQuota(e.target.checked)}
              />
              <span>ResourceQuota configured</span>
            </label>
            <label className="cf-k8s-checkbox-row">
              <input
                type="checkbox"
                checked={nsLimitRange}
                onChange={(e) => setNsLimitRange(e.target.checked)}
              />
              <span>LimitRange configured</span>
            </label>
          </div>

          <div className="cf-modal-actions">
            <button type="button" className="btn" onClick={() => setNsTargetCluster(null)}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isSavingNs}>
              <span>{isSavingNs ? 'Binding...' : 'Bind Namespace'}</span>
            </button>
          </div>
        </ValidatedForm>
      </Modal>

      {/* Create Deployment Modal */}
      <Modal
        isOpen={isDeployModalOpen}
        title="New Kubernetes Deployment"
        subtitle="Deploy a verified immutable OCI image digest as a hardened apps/v1 Deployment + ClusterIP Service."
        onClose={() => setIsDeployModalOpen(false)}
      >
        <ValidatedForm className="cf-form cf-k8s-form" onSubmit={handleCreateDeployment}>
          {deployFormError && (
            <div className="cf-notice cf-notice--error" role="alert">
              <div className="cf-notice__icon" aria-hidden="true">
                <AlertTriangle size={16} />
              </div>
              <p className="cf-notice__text">{deployFormError}</p>
            </div>
          )}

          <div className="cf-k8s-form-section">
            <h4 className="cf-k8s-form-section__title">Target &amp; Verified Immutable Artifact</h4>
            <div className="cf-k8s-form-grid">
              <div className="cf-field">
                <label htmlFor="dep-project">Project</label>
                <select
                  id="dep-project"
                  required
                  value={depProjectId}
                  onChange={(e) => {
                    setDepProjectId(e.target.value);
                    setDepEnvId('');
                    setDepNamespaceBindingId('');
                    setDepArtifactId('');
                  }}
                >
                  <option value="">Select project...</option>
                  {projects.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="cf-field">
                <label htmlFor="dep-env">Target Environment</label>
                <select
                  id="dep-env"
                  required
                  value={depEnvId}
                  disabled={!depProjectId}
                  onChange={(e) => {
                    setDepEnvId(e.target.value);
                    setDepNamespaceBindingId('');
                  }}
                >
                  <option value="">Select environment...</option>
                  {depProjectEnvs.map((env) => (
                    <option key={env.id} value={env.id}>
                      {env.name} ({env.environmentType})
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="cf-k8s-form-grid">
              <div className="cf-field">
                <label htmlFor="dep-cluster">Validated Cluster</label>
                <select
                  id="dep-cluster"
                  required
                  value={depClusterId}
                  onChange={(e) => {
                    setDepClusterId(e.target.value);
                    setDepNamespaceBindingId('');
                  }}
                >
                  <option value="">Select validated cluster...</option>
                  {validatedClusters.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} ({c.clusterIdentifier})
                    </option>
                  ))}
                </select>
              </div>
              <div className="cf-field">
                <label htmlFor="dep-namespace">Approved Namespace Binding</label>
                <select
                  id="dep-namespace"
                  required
                  value={depNamespaceBindingId}
                  onChange={(e) => setDepNamespaceBindingId(e.target.value)}
                >
                  <option value="">Select bound namespace...</option>
                  {matchingNamespaces.map((n) => (
                    <option key={n.id} value={n.id}>
                      {n.namespaceName} (PSA: {n.podSecurityStandard})
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="cf-field">
              <label htmlFor="dep-artifact">Verified OCI Image Artifact (@sha256 immutable)</label>
              <select
                id="dep-artifact"
                required
                value={depArtifactId}
                disabled={!depProjectId}
                onChange={(e) => setDepArtifactId(e.target.value)}
              >
                <option value="">Select verified OCI artifact...</option>
                {matchingArtifacts.map((art) => (
                  <option key={art.id} value={art.id}>
                    {art.imageRepository} ({art.manifestDigest.slice(0, 19)}... · commit{' '}
                    {art.commitSha.slice(0, 8)})
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="cf-k8s-form-section">
            <h4 className="cf-k8s-form-section__title">Workload, Resources &amp; Rolling Update</h4>
            <div className="cf-k8s-form-grid cf-k8s-form-grid--3">
              <div className="cf-field">
                <label htmlFor="dep-name">Deployment Name</label>
                <input
                  id="dep-name"
                  type="text"
                  className="mono"
                  placeholder="auto from project"
                  value={depName}
                  onChange={(e) => setDepName(e.target.value)}
                />
              </div>
              <div className="cf-field">
                <label htmlFor="dep-svc-name">ClusterIP Service Name</label>
                <input
                  id="dep-svc-name"
                  type="text"
                  className="mono"
                  placeholder="matches deployment"
                  value={depServiceName}
                  onChange={(e) => setDepServiceName(e.target.value)}
                />
              </div>
              <div className="cf-field">
                <label htmlFor="dep-replicas">Replicas (1-20)</label>
                <input
                  id="dep-replicas"
                  type="number"
                  min={1}
                  max={20}
                  required
                  value={depReplicas}
                  onChange={(e) => setDepReplicas(e.target.value)}
                />
              </div>
            </div>

            <div className="cf-k8s-form-grid cf-k8s-form-grid--3">
              <div className="cf-field">
                <label htmlFor="dep-cport">Container Port</label>
                <input
                  id="dep-cport"
                  type="number"
                  min={1}
                  max={65535}
                  required
                  value={depContainerPort}
                  onChange={(e) => setDepContainerPort(e.target.value)}
                />
              </div>
              <div className="cf-field">
                <label htmlFor="dep-sport">Service Port</label>
                <input
                  id="dep-sport"
                  type="number"
                  min={1}
                  max={65535}
                  required
                  value={depServicePort}
                  onChange={(e) => setDepServicePort(e.target.value)}
                />
              </div>
              <div className="cf-field">
                <label htmlFor="dep-surge">Max Surge / Max Unavailable</label>
                <div style={{ display: 'flex', gap: '8px' }}>
                  <input
                    id="dep-surge"
                    type="text"
                    aria-label="Rolling update maxSurge"
                    value={depMaxSurge}
                    onChange={(e) => setDepMaxSurge(e.target.value)}
                  />
                  <input
                    type="text"
                    aria-label="Rolling update maxUnavailable"
                    value={depMaxUnavailable}
                    onChange={(e) => setDepMaxUnavailable(e.target.value)}
                  />
                </div>
              </div>
            </div>

            <div className="cf-k8s-form-grid">
              <div className="cf-field">
                <label htmlFor="dep-cpu-req">CPU Request / Limit</label>
                <div style={{ display: 'flex', gap: '8px' }}>
                  <input
                    id="dep-cpu-req"
                    type="text"
                    aria-label="CPU request"
                    value={depCpuRequest}
                    onChange={(e) => setDepCpuRequest(e.target.value)}
                  />
                  <input
                    type="text"
                    aria-label="CPU limit"
                    value={depCpuLimit}
                    onChange={(e) => setDepCpuLimit(e.target.value)}
                  />
                </div>
              </div>
              <div className="cf-field">
                <label htmlFor="dep-mem-req">Memory Request / Limit</label>
                <div style={{ display: 'flex', gap: '8px' }}>
                  <input
                    id="dep-mem-req"
                    type="text"
                    aria-label="Memory request"
                    value={depMemRequest}
                    onChange={(e) => setDepMemRequest(e.target.value)}
                  />
                  <input
                    type="text"
                    aria-label="Memory limit"
                    value={depMemLimit}
                    onChange={(e) => setDepMemLimit(e.target.value)}
                  />
                </div>
              </div>
            </div>
          </div>

          <div className="cf-k8s-form-section">
            <h4 className="cf-k8s-form-section__title">Health Probes &amp; Security Hardening</h4>
            <div className="cf-k8s-form-grid">
              <div className="cf-field">
                <label htmlFor="dep-readiness-type">Readiness Probe</label>
                <div style={{ display: 'flex', gap: '8px' }}>
                  <select
                    id="dep-readiness-type"
                    value={depReadinessType}
                    onChange={(e) =>
                      setDepReadinessType(e.target.value as 'HTTP_GET' | 'TCP_SOCKET')
                    }
                  >
                    <option value="HTTP_GET">HTTP GET</option>
                    <option value="TCP_SOCKET">TCP Socket</option>
                  </select>
                  {depReadinessType === 'HTTP_GET' && (
                    <input
                      type="text"
                      aria-label="Readiness HTTP path"
                      placeholder="/healthz"
                      value={depReadinessPath}
                      onChange={(e) => setDepReadinessPath(e.target.value)}
                    />
                  )}
                </div>
              </div>
              <div className="cf-field">
                <label htmlFor="dep-liveness-type">Liveness Probe</label>
                <div style={{ display: 'flex', gap: '8px' }}>
                  <select
                    id="dep-liveness-type"
                    value={depLivenessType}
                    onChange={(e) =>
                      setDepLivenessType(e.target.value as 'HTTP_GET' | 'TCP_SOCKET')
                    }
                  >
                    <option value="HTTP_GET">HTTP GET</option>
                    <option value="TCP_SOCKET">TCP Socket</option>
                  </select>
                  {depLivenessType === 'HTTP_GET' && (
                    <input
                      type="text"
                      aria-label="Liveness HTTP path"
                      placeholder="/healthz"
                      value={depLivenessPath}
                      onChange={(e) => setDepLivenessPath(e.target.value)}
                    />
                  )}
                </div>
              </div>
            </div>

            <div className="cf-k8s-form-grid">
              <label className="cf-k8s-checkbox-row">
                <input
                  type="checkbox"
                  checked={depRunAsNonRoot}
                  onChange={(e) => setDepRunAsNonRoot(e.target.checked)}
                />
                <span>Enforce non-root UID 10001 + seccomp RuntimeDefault</span>
              </label>
              <label className="cf-k8s-checkbox-row">
                <input
                  type="checkbox"
                  checked={depReadOnlyFs}
                  onChange={(e) => setDepReadOnlyFs(e.target.checked)}
                />
                <span>Read-only root filesystem (with bounded /tmp emptyDir)</span>
              </label>
            </div>
          </div>

          {isProductionTarget && (
            <div className="cf-k8s-prod-gate">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Lock size={15} aria-hidden="true" />
                <strong>Explicit Production Approval Required (OWNER Only)</strong>
              </div>
              <label className="cf-k8s-checkbox-row">
                <input
                  type="checkbox"
                  checked={depProdConfirm}
                  onChange={(e) => setDepProdConfirm(e.target.checked)}
                />
                <span>I confirm this immutable digest is approved for PRODUCTION rollout</span>
              </label>
              <div className="cf-field">
                <label htmlFor="dep-prod-reason">Approval Justification / Change Ticket</label>
                <input
                  id="dep-prod-reason"
                  type="text"
                  required
                  placeholder="CHG-1042 approved release verification"
                  value={depProdReason}
                  onChange={(e) => setDepProdReason(e.target.value)}
                />
              </div>
            </div>
          )}

          <div className="cf-modal-actions">
            <button
              type="button"
              className="btn"
              onClick={() => setIsDeployModalOpen(false)}
              disabled={isSubmittingDeploy}
            >
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isSubmittingDeploy}>
              <Rocket size={14} aria-hidden="true" />
              <span>{isSubmittingDeploy ? 'Queueing Deployment...' : 'Queue Deployment'}</span>
            </button>
          </div>
        </ValidatedForm>
      </Modal>

      {/* Deployment Details Modal */}
      <Modal
        isOpen={selectedDeployment !== null}
        title={
          selectedDeployment
            ? `Deployment · ${selectedDeployment.deploymentName}`
            : 'Deployment Details'
        }
        subtitle={
          selectedDeployment
            ? `${selectedDeployment.clusterName} / ${selectedDeployment.namespaceName}`
            : undefined
        }
        onClose={() => {
          setSelectedDeployment(null);
          setCancelConfirmOpen(false);
        }}
      >
        {selectedDeployment && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: '10px',
                flexWrap: 'wrap',
              }}
            >
              <StatusBadge
                label={DEPLOYMENT_STATUS_META[selectedDeployment.status].label}
                tone={DEPLOYMENT_STATUS_META[selectedDeployment.status].tone}
                pulse={Boolean(DEPLOYMENT_STATUS_META[selectedDeployment.status].pulse)}
              />
              <div className="cf-inline-actions">
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => void refreshSelectedDeploymentStatus()}
                  disabled={isLoadingDetail}
                >
                  <RefreshCw
                    size={12}
                    className={isLoadingDetail ? 'cf-spin' : ''}
                    aria-hidden="true"
                  />
                  <span>Refresh Status</span>
                </button>
                {canRequestDeployments && isSelectedActive && !cancelConfirmOpen && (
                  <button
                    type="button"
                    className="btn btn-sm btn-danger"
                    onClick={() => setCancelConfirmOpen(true)}
                  >
                    <Ban size={12} aria-hidden="true" />
                    <span>Cancel Deployment</span>
                  </button>
                )}
              </div>
            </div>

            {cancelConfirmOpen && (
              <div className="cf-notice cf-notice--warning" role="alert">
                <div className="cf-notice__icon" aria-hidden="true">
                  <AlertTriangle size={16} />
                </div>
                <div style={{ flex: 1 }}>
                  <p className="cf-notice__title">Confirm Deployment Cancellation</p>
                  <p className="cf-notice__text">
                    Cancelling halts further worker writes and marks the operation Cancelled or
                    Reconciliation Required if Kubernetes apply already began. CloudForge does not
                    delete workloads or perform automatic rollback in Phase 3.6 (planned for Phase
                    3.7).
                  </p>
                  <div className="cf-inline-actions" style={{ marginTop: '8px' }}>
                    <button
                      type="button"
                      className="btn btn-sm"
                      onClick={() => setCancelConfirmOpen(false)}
                    >
                      Keep Observing
                    </button>
                    <button
                      type="button"
                      className="btn btn-sm btn-danger"
                      disabled={isCancelling}
                      onClick={() => void handleCancelDeployment()}
                    >
                      {isCancelling ? 'Cancelling...' : 'Confirm Cancel'}
                    </button>
                  </div>
                </div>
              </div>
            )}

            {detailError && (
              <div className="cf-notice cf-notice--error" role="alert">
                <p className="cf-notice__text">{detailError}</p>
              </div>
            )}

            {selectedDeployment.errorSummary && (
              <div className="cf-notice cf-notice--error" role="alert">
                <div className="cf-notice__icon" aria-hidden="true">
                  <AlertTriangle size={16} />
                </div>
                <div>
                  <p className="cf-notice__title">
                    {selectedDeployment.failureCategory ?? 'Deployment Diagnostic'}
                  </p>
                  <p className="cf-notice__text">{selectedDeployment.errorSummary}</p>
                </div>
              </div>
            )}

            {/* Immutable Image Reference */}
            <div>
              <div className="cf-k8s-detail-item__label" style={{ marginBottom: '6px' }}>
                Immutable Deployed Image Reference
              </div>
              <div className="cf-oci-digest-full">
                <span>{selectedDeployment.immutableImageReference}</span>
                <button
                  type="button"
                  className="cf-oci-copy-btn"
                  onClick={() =>
                    handleCopy('modal-ref', selectedDeployment.immutableImageReference)
                  }
                >
                  {copiedKey === 'modal-ref' ? <Check size={12} /> : <Copy size={12} />}
                  <span>{copiedKey === 'modal-ref' ? 'Copied' : 'Copy'}</span>
                </button>
              </div>
            </div>

            {/* Real Observed Replica Readiness */}
            <div className="cf-k8s-replica-cards">
              <div className="cf-k8s-replica-card">
                <span className="cf-k8s-replica-card__label">Requested</span>
                <span className="cf-k8s-replica-card__val">
                  {selectedDeployment.requestedReplicas}
                </span>
              </div>
              <div className="cf-k8s-replica-card">
                <span className="cf-k8s-replica-card__label">Updated</span>
                <span className="cf-k8s-replica-card__val">
                  {selectedDeployment.updatedReplicas}
                </span>
              </div>
              <div className="cf-k8s-replica-card">
                <span className="cf-k8s-replica-card__label">Ready</span>
                <span className="cf-k8s-replica-card__val">
                  {selectedDeployment.readyReplicas}
                </span>
              </div>
              <div className="cf-k8s-replica-card">
                <span className="cf-k8s-replica-card__label">Available</span>
                <span className="cf-k8s-replica-card__val">
                  {selectedDeployment.availableReplicas}
                </span>
              </div>
            </div>

            {/* Workload & Provenance Metadata */}
            <div className="cf-k8s-detail-grid">
              <div className="cf-k8s-detail-item">
                <span className="cf-k8s-detail-item__label">Workload &amp; Service</span>
                <span className="cf-k8s-detail-item__value mono">
                  Deployment/{selectedDeployment.deploymentName} · Service/
                  {selectedDeployment.serviceName} (ClusterIP)
                </span>
              </div>
              <div className="cf-k8s-detail-item">
                <span className="cf-k8s-detail-item__label">Source Commit</span>
                <span className="cf-k8s-detail-item__value mono">
                  {selectedDeployment.sourceCommitSha}
                </span>
              </div>
              <div className="cf-k8s-detail-item">
                <span className="cf-k8s-detail-item__label">Manifest SHA-256 Checksum</span>
                <span className="cf-k8s-detail-item__value mono">
                  {selectedDeployment.manifestChecksumSha256}
                </span>
              </div>
              <div className="cf-k8s-detail-item">
                <span className="cf-k8s-detail-item__label">Kubernetes UIDs &amp; Worker</span>
                <span className="cf-k8s-detail-item__value mono">
                  UID: {selectedDeployment.k8sDeploymentUid ?? '—'} · Worker:{' '}
                  {selectedDeployment.assignedWorkerId ?? 'Unassigned'}
                </span>
              </div>
              <div className="cf-k8s-detail-item">
                <span className="cf-k8s-detail-item__label">Started</span>
                <span className="cf-k8s-detail-item__value mono">
                  {formatTimestamp(selectedDeployment.startedAt)}
                </span>
              </div>
              <div className="cf-k8s-detail-item">
                <span className="cf-k8s-detail-item__label">Completed</span>
                <span className="cf-k8s-detail-item__value mono">
                  {formatTimestamp(selectedDeployment.completedAt)}
                </span>
              </div>
            </div>

            {/* Pod Diagnostics */}
            {selectedDeployment.podDiagnostics.length > 0 && (
              <div>
                <h4 className="cf-k8s-form-section__title" style={{ marginBottom: '8px' }}>
                  Observed Pod Diagnostics
                </h4>
                <div className="cf-k8s-events-timeline">
                  {selectedDeployment.podDiagnostics.map((pod, idx) => (
                    <div key={`${String(pod.podName)}-${idx}`} className="cf-k8s-event-row">
                      <div className="cf-k8s-event-row__head">
                        <span className="cf-k8s-event-row__type">
                          <Box size={11} aria-hidden="true" /> {String(pod.podName ?? 'pod')}
                        </span>
                        <span className="cf-k8s-event-row__time">
                          Phase: {String(pod.phase ?? 'Unknown')} · Ready:{' '}
                          {pod.ready ? 'Yes' : 'No'}
                          {pod.restarts !== undefined ? ` · Restarts: ${String(pod.restarts)}` : ''}
                        </span>
                      </div>
                      {Boolean(pod.reason || pod.message) && (
                        <p className="cf-k8s-event-row__msg">
                          {String(pod.reason ?? '')} {String(pod.message ?? '')}
                        </p>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Deployment Events Timeline */}
            <div>
              <h4 className="cf-k8s-form-section__title" style={{ marginBottom: '8px' }}>
                Deployment Lifecycle Events ({deploymentEvents.length})
              </h4>
              {deploymentEvents.length === 0 ? (
                <p className="cf-muted" style={{ fontSize: '13px' }}>
                  No deployment lifecycle events recorded yet.
                </p>
              ) : (
                <div className="cf-k8s-events-timeline">
                  {deploymentEvents.map((ev) => (
                    <div key={ev.id} className="cf-k8s-event-row">
                      <div className="cf-k8s-event-row__head">
                        <span className="cf-k8s-event-row__type">{ev.eventType}</span>
                        <span className="cf-k8s-event-row__time">
                          {ev.actorLabel} · {formatTimestamp(ev.occurredAt)}
                        </span>
                      </div>
                      <p className="cf-k8s-event-row__msg">{ev.message}</p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </Modal>
    </PageContainer>
  );
}
