$base = "http://192.168.0.103:8080"

function Status {
    try { (Invoke-WebRequest -Uri "$base/status" -UseBasicParsing -TimeoutSec 8).Content }
    catch { $null }
}
function Post($path) {
    try { (Invoke-WebRequest -Uri "$base$path" -Method POST -UseBasicParsing -TimeoutSec 25).Content }
    catch { "FAILED: " + $_.Exception.Message }
}
function Show($label) {
    $raw = Status
    if (-not $raw) { Write-Output ("  " + $label + ": <no answer>"); return $null }
    $j = $raw | ConvertFrom-Json
    Write-Output ("  " + $label + ": rec=" + $j.recording + " mode=" + $j.recordingMode +
        " bytes=" + $j.bytes + " frames=" + $j.frames + " fragments=" + $j.fragments +
        " feed=" + $j.feed + " error='" + $j.error + "'")
    return $j
}

Write-Output "waiting for build 04:45 ..."
$deadline = (Get-Date).AddMinutes(30)
$json = $null
while ((Get-Date) -lt $deadline) {
    $raw = Status
    if ($raw) {
        try { $j = $raw | ConvertFrom-Json } catch { $j = $null }
        if ($j -and $j.build -eq "2026-10-21 04:45") { $json = $j; break }
    }
    Start-Sleep -Seconds 4
}
if (-not $json) { Write-Output "the build was never seen"; exit 1 }
Write-Output "build 04:45 is running"
Show "at the start"

Write-Output ""
Write-Output "=== a video recording from the dashboard ==="
Write-Output ("  POST -> " + (Post '/record/video/start'))
for ($i = 1; $i -le 8; $i++) {
    Start-Sleep -Seconds 1
    Show ("  t+" + $i + "s")
}

Write-Output ""
Write-Output "=== stopping it (the file has to be written) ==="
Write-Output ("  POST -> " + (Post '/record/video/stop'))
for ($i = 1; $i -le 6; $i++) {
    Start-Sleep -Seconds 2
    Show ("  t+" + ($i * 2) + "s")
}

Write-Output ""
Write-Output "=== the live feed afterwards ==="
for ($i = 1; $i -le 5; $i++) {
    Start-Sleep -Seconds 2
    Show ("  t+" + ($i * 2) + "s")
}
