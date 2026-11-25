#!/usr/bin/env bash
# Trusted operator station, Cosign v2 and Trivy. Output is private to the operator.
set -euo pipefail
umask 077
image=${1:?immutable image reference}
workspace=${2:?workspace UUID}
artifact=${3:?artifact UUID}
out=${4:?new private output directory}
: "${COSIGN_KEY:?set encrypted signing key path}" "${COSIGN_PUBLIC_KEY:?set verification key path}"
[[ "$image" =~ ^[a-z0-9][a-z0-9./:_-]*@sha256:[a-f0-9]{64}$ ]] || exit 2
mkdir -m 700 -- "$out"
trivy image --timeout 10m --scanners vuln --format json --output "$out/vulns.json" "$image"
trivy image --timeout 10m --format cyclonedx --output "$out/sbom.json" "$image"
jq -e '.bomFormat == "CycloneDX" and (.components|type=="array" and length>0)' "$out/sbom.json" >/dev/null
jq -e '.SchemaVersion == 2 and (.Results|type=="array" and length>0)' "$out/vulns.json" >/dev/null
counts=$(jq -c '[.Results[] | .Vulnerabilities[]? | .Severity] | {critical:map(select(.=="CRITICAL"))|length,high:map(select(.=="HIGH"))|length}' "$out/vulns.json")
# Publishing a signature attests an approved image. Never sign a rejected candidate.
verified=false
if jq -e '.high == 0 and .critical == 0' <<<"$counts" >/dev/null; then
    cosign sign --yes --tlog-upload=false --key "$COSIGN_KEY" "$image"
    cosign verify --insecure-ignore-tlog --key "$COSIGN_PUBLIC_KEY" "$image" >/dev/null
    verified=true
fi
sbom=$(sha256sum "$out/sbom.json" | cut -d' ' -f1)
jq -nc --arg workspace "$workspace" --arg artifact "$artifact" --arg image "$image" --arg sbom "$sbom" \
    --argjson verified "$verified" --argjson counts "$counts" --argjson now "$(date +%s)" \
    '$counts + {version:1,workspace:$workspace,artifact:$artifact,image:$image,assessedAt:$now,sbomSha256:$sbom,imageSignatureVerified:$verified}' > "$out/report.json"
cosign sign-blob --yes --tlog-upload=false --key "$COSIGN_KEY" --output-signature "$out/report.sig" "$out/report.json"
cosign verify-blob --insecure-ignore-tlog --key "$COSIGN_PUBLIC_KEY" --signature "$out/report.sig" "$out/report.json"
jq -n --arg payload "$(base64 -w0 "$out/report.json")" --arg signature "$(cat "$out/report.sig")" \
    '{payload:$payload,signature:$signature}' > "$out/envelope.json"
[[ "$verified" == true ]] || { echo "Image rejected. Submit signed adverse evidence." >&2; exit 3; }
