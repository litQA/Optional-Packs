param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$versionFile = Join-Path $ProjectRoot 'version.properties'
if (-not (Test-Path $versionFile)) {
    throw "version.properties was not found at $versionFile"
}

$content = Get-Content $versionFile -Raw
$pattern = '(?m)^version\.build=(\d+)\s*$'
$match = [regex]::Match($content, $pattern)
if (-not $match.Success) {
    throw 'version.build is missing from version.properties'
}

$build = [int]$match.Groups[1].Value + 1
$content = [regex]::Replace($content, $pattern, "version.build=$build")
Set-Content -Path $versionFile -Value $content -Encoding UTF8

$major = [regex]::Match($content, '(?m)^version\.major=(\d+)\s*$').Groups[1].Value
$minor = [regex]::Match($content, '(?m)^version\.minor=(\d+)\s*$').Groups[1].Value
$patch = [regex]::Match($content, '(?m)^version\.patch=(\d+)\s*$').Groups[1].Value
$type = [regex]::Match($content, '(?m)^version\.type=(.+?)\s*$').Groups[1].Value

if ([string]::IsNullOrWhiteSpace($type)) {
    Write-Output "$major.$minor.$patch.$build"
} else {
    Write-Output "$major.$minor.$patch.$build-$type"
}
