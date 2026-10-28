# SonarQube analysis of this repository, with the settings that actually take effect.
#
# Usage:
#   pwsh scripts/scan-sonar.ps1 -HostUrl http://192.168.1.120:9000 -Token <token>
#
# Why a script and not a properties file: the Maven scanner reads neither
# sonar-project.properties nor a sonar.* property in the POM. Both were tried and measured -
# the same exclusion value closes 223 findings when passed here as -D and none when set in
# the build. Anything that looked like configuration but did nothing was removed, so this
# file is the whole story.
#
# Two things matter for an honest result:
#
#   * The Java quality profile must be "Sonar way comprehensive". SonarQube 26.10 ships
#     "Sonar way core" as the default with 274 of the 618 Java rules, and it does not contain
#     S1128 (unused imports) at all - a scan under it called a tree with 40 unused imports
#     clean. Set it once per server:
#       POST /api/qualityprofiles/set_default  language=java  qualityProfile=Sonar way comprehensive
#
#   * The vendored SMSLib and ajwcc PDU trees are excluded. They are tracked copies of
#     upstream (hs-gsm/VENDORING.md) and the golden PDU corpus is the authority on their byte
#     paths; they were 225 of the first 411 findings and a list that is half vendored is a
#     list nobody reads. Everything this project wrote stays in scope.
#
# The build has to have run first: the analyser needs bytecode, and a scan without it skips
# every rule that needs a symbol table (unused imports, unused locals, unused private fields).

param(
    [string]$HostUrl = 'http://192.168.1.120:9000',
    [string]$Token = $env:SONAR_TOKEN,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not $Token) {
    $tokenFile = Join-Path $env:USERPROFILE '.sonar-token'
    if (Test-Path $tokenFile) { $Token = (Get-Content $tokenFile -Raw).Trim() }
}
if (-not $Token) { throw 'no token: pass -Token, set SONAR_TOKEN, or create ~/.sonar-token' }

$exclusions = '**/target/**,**/src/main/java/org/smslib/**,**/src/main/java/org/ajwcc/**'
$cpdExclusions = '**/src/test/**,**/src/main/java/org/smslib/**,**/src/main/java/org/ajwcc/**'

if (-not $SkipBuild) {
    & .\mvnw.cmd -B -DskipTests install
    if ($LASTEXITCODE -ne 0) { throw 'the build failed; a scan without bytecode is not a scan' }
}

& .\mvnw.cmd -B org.sonarsource.scanner.maven:sonar-maven-plugin:5.1.0.4751:sonar `
    "-Dsonar.host.url=$HostUrl" `
    "-Dsonar.token=$Token" `
    "-Dsonar.exclusions=$exclusions" `
    "-Dsonar.cpd.exclusions=$cpdExclusions"
if ($LASTEXITCODE -ne 0) { throw 'the scan failed' }

Write-Host 'Open findings:'
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${Token}:"))
$headers = @{ Authorization = "Basic $basic" }
$uri = "$HostUrl/api/issues/search?componentKeys=com.hushsign:agent-parent" +
    '&statuses=OPEN,CONFIRMED,REOPENED&resolved=false&ps=1&facets=rules'
$result = Invoke-RestMethod -Uri $uri -Headers $headers
Write-Host "  total: $($result.total)"
$result.facets | Where-Object { $_.property -eq 'rules' } | ForEach-Object {
    $_.values | ForEach-Object { "  {0,4}  {1}" -f $_.count, $_.val }
}
