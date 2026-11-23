import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import {
  ApiClientError,
  fetchAuthConfig,
  fetchCurrentUser,
  logoutSession,
} from '../services/api';
import type {
  AuthConfig,
  UserProfile,
  WorkspaceRole,
  WorkspaceSummary,
} from '../types/platform';

const STORAGE_WORKSPACE_KEY = 'cloudforge.activeWorkspaceId';

export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated' | 'error';

export interface AuthContextValue {
  status: AuthStatus;
  authConfig: AuthConfig | null;
  user: UserProfile | null;
  workspaces: WorkspaceSummary[];
  activeWorkspace: WorkspaceSummary | null;
  activeRole: WorkspaceRole | null;
  errorMessage: string | null;
  isLoggingOut: boolean;
  setActiveWorkspaceId: (workspaceId: string) => void;
  refreshSession: () => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function readSavedWorkspaceId(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_WORKSPACE_KEY);
  } catch {
    return null;
  }
}

function saveWorkspaceId(workspaceId: string | null): void {
  try {
    if (workspaceId) {
      window.localStorage.setItem(STORAGE_WORKSPACE_KEY, workspaceId);
    } else {
      window.localStorage.removeItem(STORAGE_WORKSPACE_KEY);
    }
  } catch {
    // Ignore storage unavailability
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [authConfig, setAuthConfig] = useState<AuthConfig | null>(null);
  const [user, setUser] = useState<UserProfile | null>(null);
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [activeWorkspaceId, setActiveWorkspaceIdState] = useState<string | null>(
    () => readSavedWorkspaceId()
  );
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  const selectValidWorkspace = useCallback(
    (list: WorkspaceSummary[], preferredId: string | null): string | null => {
      if (list.length === 0) {
        return null;
      }
      if (preferredId && list.some((ws) => ws.id === preferredId)) {
        return preferredId;
      }
      return list[0].id;
    },
    []
  );

  const refreshSession = useCallback(async () => {
    setErrorMessage(null);

    try {
      const config = await fetchAuthConfig();
      setAuthConfig(config);
    } catch {
      // Auth config failure will be reflected if /api/auth/me also fails
    }

    try {
      const me = await fetchCurrentUser();
      setUser(me.user);
      setWorkspaces(me.workspaces);
      setActiveWorkspaceIdState((prev) => {
        const next = selectValidWorkspace(me.workspaces, prev);
        saveWorkspaceId(next);
        return next;
      });
      setStatus('authenticated');
    } catch (error) {
      if (error instanceof ApiClientError && error.status === 401) {
        setUser(null);
        setWorkspaces([]);
        setStatus('unauthenticated');
        return;
      }
      setUser(null);
      setWorkspaces([]);
      setErrorMessage(
        error instanceof Error
          ? error.message
          : 'Unable to verify authentication session with CloudForge API.'
      );
      setStatus('error');
    }
  }, [selectValidWorkspace]);

  useEffect(() => {
    void refreshSession();
  }, [refreshSession]);

  const setActiveWorkspaceId = useCallback((workspaceId: string) => {
    setActiveWorkspaceIdState(workspaceId);
    saveWorkspaceId(workspaceId);
  }, []);

  const logout = useCallback(async () => {
    setIsLoggingOut(true);
    try {
      await logoutSession();
    } catch {
      // Even if session already expired, clear local auth state
    } finally {
      setUser(null);
      setWorkspaces([]);
      setActiveWorkspaceIdState(null);
      saveWorkspaceId(null);
      setStatus('unauthenticated');
      setIsLoggingOut(false);
    }
  }, []);

  const activeWorkspace = useMemo(
    () => workspaces.find((ws) => ws.id === activeWorkspaceId) ?? workspaces[0] ?? null,
    [workspaces, activeWorkspaceId]
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      authConfig,
      user,
      workspaces,
      activeWorkspace,
      activeRole: activeWorkspace?.role ?? null,
      errorMessage,
      isLoggingOut,
      setActiveWorkspaceId,
      refreshSession,
      logout,
    }),
    [
      status,
      authConfig,
      user,
      workspaces,
      activeWorkspace,
      errorMessage,
      isLoggingOut,
      setActiveWorkspaceId,
      refreshSession,
      logout,
    ]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return ctx;
}
