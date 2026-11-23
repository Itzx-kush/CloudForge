import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';

function mockJsonResponse(body: unknown, status = 200, requestId = 'req-test-1'): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'X-Request-Id': requestId,
    },
  });
}

describe('CloudForge Phase 1 Web Console', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('displays live UP backend and PostgreSQL status from GET /api/health', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockJsonResponse({
        application: 'CloudForge Backend',
        version: '0.1.0-phase1',
        phase: 'Phase 1 — Platform Foundation',
        status: 'UP',
        database: {
          status: 'UP',
          engine: 'PostgreSQL 17.4',
          responseTimeMs: 4,
          message: 'Database connection verified',
        },
        timestamp: '2026-11-23T06:00:00Z',
      })
    );

    render(
      <MemoryRouter initialEntries={['/']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('CloudForge Backend')).toBeInTheDocument();
    });

    expect(screen.getByText('PostgreSQL 17.4')).toBeInTheDocument();
    expect(screen.getByText('Healthy (HTTP 200)')).toBeInTheDocument();
    expect(screen.getByText('Database connection verified')).toBeInTheDocument();
  });

  it('shows degraded alert when backend returns HTTP 503 due to database outage', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockJsonResponse(
        {
          application: 'CloudForge Backend',
          version: '0.1.0-phase1',
          phase: 'Phase 1 — Platform Foundation',
          status: 'DEGRADED',
          database: {
            status: 'DOWN',
            engine: 'PostgreSQL',
            responseTimeMs: 3001,
            message: 'Database connectivity check failed',
          },
          timestamp: '2026-11-23T06:05:00Z',
        },
        503
      )
    );

    render(
      <MemoryRouter initialEntries={['/']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(
        screen.getByText('Backend is running in degraded mode (database unavailable)')
      ).toBeInTheDocument();
    });

    expect(screen.getByText('Degraded (HTTP 503)')).toBeInTheDocument();
  });

  it('renders ErrorState when backend is unreachable', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));

    render(
      <MemoryRouter initialEntries={['/']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(
        screen.getByText('Spring Boot backend is unreachable')
      ).toBeInTheDocument();
    });
  });

  it('navigates across all seven Phase 1 sidebar pages cleanly', async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockJsonResponse({
        application: 'CloudForge Backend',
        version: '0.1.0-phase1',
        phase: 'Phase 1 — Platform Foundation',
        status: 'UP',
        database: {
          status: 'UP',
          engine: 'PostgreSQL 17.4',
          responseTimeMs: 3,
          message: 'Database connection verified',
        },
        timestamp: '2026-11-23T06:10:00Z',
      })
    );

    render(
      <MemoryRouter initialEntries={['/']}>
        <AppRoutes />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Platform Overview' })).toBeInTheDocument();
    });

    const routes: Array<[string, string]> = [
      ['Projects', 'Projects'],
      ['Repositories', 'Repositories'],
      ['Deployments', 'Deployments'],
      ['Pipelines', 'Pipelines'],
      ['Monitoring', 'Monitoring'],
      ['Settings', 'Settings'],
    ];

    for (const [linkName, headingName] of routes) {
      await user.click(screen.getByRole('link', { name: linkName }));
      expect(
        screen.getByRole('heading', { name: headingName, level: 1 })
      ).toBeInTheDocument();
    }
  });
});
