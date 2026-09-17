# Drift check for a (vendored) copy of the protocol schemas.
# Windows developer equivalent of check-protocol-drift.sh.
#
# Usage: .\check-protocol-drift.ps1 -TargetDir <schemas-dir>
#   <schemas-dir> must contain schemas.sha256 and the schemas under v1/.
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$TargetDir
)

$ErrorActionPreference = 'Stop'
$manifest = Join-Path $TargetDir 'schemas.sha256'
if (-not (Test-Path -LiteralPath $manifest)) {
  throw "missing manifest: $manifest"
}

# Hash LF-normalized bytes so Windows CRLF worktrees and Linux CI agree.
function Get-LfSha256([string]$path) {
  $bytes = [System.IO.File]::ReadAllBytes($path)
  $lf = New-Object System.Collections.Generic.List[byte]
  foreach ($b in $bytes) { if ($b -ne 13) { $lf.Add($b) } }
  $sha = [System.Security.Cryptography.SHA256]::Create()
  ([System.BitConverter]::ToString($sha.ComputeHash($lf.ToArray())) -replace '-', '').ToLower()
}

$listed = @{}
foreach ($line in Get-Content -LiteralPath $manifest) {
  if ($line.Trim() -eq '') { continue }
  $hash, $rel = $line -split '\s{2,}'
  $listed[$rel] = $hash
}

$actual = @{}
$v1Dir = Join-Path $TargetDir 'v1'
if (Test-Path -LiteralPath $v1Dir) {
  foreach ($file in Get-ChildItem -Path $v1Dir -Filter *.json -File) {
    $actual["v1/$($file.Name)"] = Get-LfSha256 $file.FullName
  }
}

$errors = @()
foreach ($entry in $listed.GetEnumerator()) {
  if (-not $actual.ContainsKey($entry.Key)) {
    $errors += "missing: $($entry.Key)"
  } elseif ($actual[$entry.Key] -ne $entry.Value) {
    $errors += "modified: $($entry.Key)"
  }
}
foreach ($entry in $actual.GetEnumerator()) {
  if (-not $listed.ContainsKey($entry.Key)) {
    $errors += "not in manifest: $($entry.Key)"
  }
}

if ($errors.Count -gt 0) {
  throw "protocol schema drift detected: $($errors -join ', ')"
}
Write-Host "protocol schemas up to date: $TargetDir"
