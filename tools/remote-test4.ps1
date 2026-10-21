$base = "http://192.168.0.103:8080"

function Status {
    try { (Invoke-WebRequest -Uri "$base/status" -UseBasicParsing -TimeoutSec 8).Content }
    catch { $null }
}
function Post($path) {
    try { (Invoke-WebRequest -Uri "$base$path" -Method POST -UseBasicParsing -TimeoutSec 20).Content }
    catch { "FAILED: " + $_.Exception.Message }
}

Write-Output "waiting for build 05:25 ..."
$deadline = (Get-Date).AddMinutes(25)
$json = $null
while ((Get-Date) -lt $deadline) {
    $raw = Status
    if ($raw) {
        try { $j = $raw | ConvertFrom-Json } catch { $j = $null }
        if ($j -and $j.build -eq "2026-10-21 06:20") { $json = $j; break }
    }
    Start-Sleep -Seconds 4
}
if (-not $json) { Write-Output "build 05:25 was never seen"; exit 1 }
Write-Output ("build 05:25 is running")

Write-Output ""
Write-Output "=== the live feed, watched closely ==="
for ($i = 1; $i -le 20; $i++) {
    $raw = Status
    if (-not $raw) { Write-Output ("  t+" + $i + ": <no answer>"); Start-Sleep -Seconds 2; continue }
    try { $j = $raw | ConvertFrom-Json } catch { $j = $null }
    if (-not $j) { Write-Output ("  t+" + $i + ": <bad json>"); continue }
    Write-Output ("  t+" + $i + "s streaming=" + $j.streaming + " fragments=" + $j.fragments +
        " initId=" + $j.initId + " analyzed=" + $j.analyzed + " frames=" + $j.frames +
        " camera=" + $j.camera)
    Write-Output ("        encoder: " + $j.encoder)
    if ($j.error) { Write-Output ("        error: " + $j.error) }
    Start-Sleep -Seconds 2
}
