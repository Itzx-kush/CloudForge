import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { ErrorState } from './ErrorState';
import { LoadingState } from './LoadingState';

export function ProtectedRoute() {
  const { status, errorMessage, refreshSession } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return (
      <div className="cf-auth-gate">
        <LoadingState
          title="Verifying CloudForge session"
          description="Checking authenticated user session and workspace memberships..."
        />
      </div>
    );
  }

  if (status === 'error') {
    return (
      <div className="cf-auth-gate">
        <ErrorState
          title="Unable to verify authentication session"
          message={errorMessage ?? 'The CloudForge backend API did not respond.'}
          onRetry={() => {
            void refreshSession();
          }}
        />
      </div>
    );
  }

  if (status === 'unauthenticated') {
    return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />;
  }

  return <Outlet />;
}
