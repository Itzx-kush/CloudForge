import { GitBranch, Lock, ShieldAlert } from 'lucide-react';
import { DashboardCard } from '../components/DashboardCard';
import { PageContainer } from '../components/PageContainer';
import { StatusBadge } from '../components/StatusBadge';
import './Pages.css';

export function RepositoriesPage() {
  return (
    <PageContainer
      title="Repositories"
      description="Source-code repository linking, webhook verification, and build-trigger governance."
      phaseBadge={{ label: 'Phase 1 UI Foundation · Phase 2 GitHub App', tone: 'planned' }}
      actions={
        <button
          type="button"
          className="btn"
          disabled
          title="GitHub OAuth and GitHub App integration are intentionally deferred to Phase 2."
        >
          <GitBranch size={15} aria-hidden="true" />
          <span>Connect GitHub App (Phase 2)</span>
        </button>
      }
    >
      <div className="cf-notice cf-notice--warning">
        <div className="cf-notice__icon" aria-hidden="true">
          <ShieldAlert size={18} />
        </div>
        <div>
          <p className="cf-notice__title">Phase 1 Security Boundary: No Arbitrary Repository Execution</p>
          <p className="cf-notice__text">
            GitHub OAuth, repository cloning, and automated code builds are deliberately
            excluded from Phase 1. Before executing any user repository code in later phases,
            CloudForge will enforce short-lived GitHub App installation tokens, signed webhook
            verification, and sandboxed rootless build workers with strict CPU, memory, and
            network egress limits.
          </p>
        </div>
      </div>

      <div className="cf-grid cf-grid--2">
        <DashboardCard
          title="GitHub Integration Status"
          subtitle="Planned least-privilege source connection model"
          icon={<GitBranch size={17} />}
          headerAction={<StatusBadge label="Not Connected" tone="neutral" size="sm" />}
        >
          <ul className="cf-subtle-list">
            <li>
              <div>
                <div className="cf-subtle-list__title">GitHub OAuth &amp; Identity</div>
                <div className="cf-subtle-list__desc">
                  User authentication and repository listing scoped strictly to authorized workspaces.
                </div>
              </div>
              <StatusBadge label="Phase 2" tone="planned" size="sm" />
            </li>
            <li>
              <div>
                <div className="cf-subtle-list__title">GitHub App Installation Tokens</div>
                <div className="cf-subtle-list__desc">
                  Short-lived, per-repository read tokens replacing long-lived personal access tokens.
                </div>
              </div>
              <StatusBadge label="Phase 2" tone="planned" size="sm" />
            </li>
            <li>
              <div>
                <div className="cf-subtle-list__title">HMAC-SHA256 Signed Webhooks</div>
                <div className="cf-subtle-list__desc">
                  Cryptographic verification of push and release events before queuing builds.
                </div>
              </div>
              <StatusBadge label="Phase 2" tone="planned" size="sm" />
            </li>
          </ul>
        </DashboardCard>

        <DashboardCard
          title="Build Isolation Controls (Planned)"
          subtitle="Defense-in-depth requirements prior to enabling repository builds"
          icon={<Lock size={17} />}
          headerAction={<StatusBadge label="Security Spec" tone="info" size="sm" />}
        >
          <ul className="cf-subtle-list">
            <li>
              <div>
                <div className="cf-subtle-list__title">Ephemeral Sandboxed Workers</div>
                <div className="cf-subtle-list__desc">
                  Unprivileged build containers destroyed immediately after artifact publication.
                </div>
              </div>
              <StatusBadge label="Required" tone="info" size="sm" />
            </li>
            <li>
              <div>
                <div className="cf-subtle-list__title">Hard Resource Limits</div>
                <div className="cf-subtle-list__desc">
                  CPU, RAM, disk, and timeout ceilings preventing noisy-neighbor or DoS abuse.
                </div>
              </div>
              <StatusBadge label="Required" tone="info" size="sm" />
            </li>
            <li>
              <div>
                <div className="cf-subtle-list__title">Secret &amp; Vulnerability Scanning</div>
                <div className="cf-subtle-list__desc">
                  Pre-deployment Trivy / SBOM / secret scanning checks in the DevSecOps gate.
                </div>
              </div>
              <StatusBadge label="Phase 4" tone="planned" size="sm" />
            </li>
          </ul>
        </DashboardCard>
      </div>
    </PageContainer>
  );
}
