<#
.SYNOPSIS
Verifies BOTH permitted and forbidden RBAC operations for cloudforge-deployer in a namespace.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Namespace
)

$ErrorActionPreference = "Stop"
$sa = "system:serviceaccount:${Namespace}:cloudforge-deployer"

function Assert-CanI {
    param([string]$Expected, [string[]]$CheckArgs)
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $actual = (& kubectl auth can-i --as=$sa @CheckArgs 2>$null | Out-String).Trim()
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($actual -ne $Expected) {
        throw "FAIL: expected '$Expected' got '$actual' for: $($CheckArgs -join ' ')"
    }
    Write-Host "PASS ($Expected): $($CheckArgs -join ' ')"
}

Assert-CanI "yes" @("get", "deployments.apps", "-n", $Namespace)
Assert-CanI "yes" @("create", "deployments.apps", "-n", $Namespace)
Assert-CanI "yes" @("patch", "deployments.apps", "-n", $Namespace)
Assert-CanI "yes" @("get", "services", "-n", $Namespace)
Assert-CanI "yes" @("create", "services", "-n", $Namespace)
Assert-CanI "yes" @("patch", "services", "-n", $Namespace)
Assert-CanI "yes" @("list", "pods", "-n", $Namespace)
Assert-CanI "yes" @("list", "replicasets.apps", "-n", $Namespace)
Assert-CanI "yes" @("list", "events", "-n", $Namespace)

Assert-CanI "no" @("get", "secrets", "-n", $Namespace)
Assert-CanI "no" @("create", "secrets", "-n", $Namespace)
Assert-CanI "no" @("create", "rolebindings.rbac.authorization.k8s.io", "-n", $Namespace)
Assert-CanI "no" @("create", "serviceaccounts", "-n", $Namespace)
Assert-CanI "no" @("create", "namespaces")
Assert-CanI "no" @("create", "clusterrolebindings.rbac.authorization.k8s.io")
Assert-CanI "no" @("get", "deployments.apps", "-n", "kube-system")
Assert-CanI "no" @("create", "deployments.apps", "-n", "kube-system")
Assert-CanI "no" @("get", "pods", "-n", "default")

Write-Host "All RBAC least-privilege checks passed for $sa."
$global:LASTEXITCODE = 0
exit 0
