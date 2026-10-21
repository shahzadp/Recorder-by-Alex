$base = "http://192.168.0.103:8080"

function Status {
    try { (Invoke-WebRequest -Uri "$base/status" -UseBasicParsing -TimeoutSec 8).Content }
    catch { $null }
}
function Post($path) {
    try { (Invoke-WebRequest -Uri "$base$path" -Method POST -UseBasicParsing -TimeoutSec 20).Content }
    catch { "FAILED: " + $_.Exception.Message }
}

Write-Output "waiting for a fresh install (the service restarts with mode=audio) ..."
$deadline = (Get-Date).AddMinutes(25)
$fresh = $false
while ((Get-Date) -lt $deadline) {
    $raw = Status
    if ($raw) {
        try { $j = $raw | ConvertFrom-Json } catch { $j = $null }
        if ($j -and $j.mode -eq "audio") {
            Write-Output ("fresh service: " + $raw)
            $fresh = $true
            break
        }
    }
    Start-Sleep -Seconds 4
}
if (-not $fresh) { Write-Output "no fresh install seen"; exit 1 }

Start-Sleep -Seconds 6
Write-Output ""
Write-Output "=== live stream (app closed) ==="
for ($i = 1; $i -le 6; $i++) {
    $j = Status | ConvertFrom-Json
    Write-Output ("  t+" + $i + "s streaming=" + $j.streaming + " fragments=" + $j.fragments + " unavailable=" + $j.streamUnavailable + " error='" + $j.error + "'")
    if ($j.streaming) { break }
    Start-Sleep -Seconds 3
}

Write-Output ""
Write-Output "=== video recording from the dashboard ==="
Write-Output ("  POST -> " + (Post '/record/video/start'))
for ($i = 1; $i -le 10; $i++) {
    Start-Sleep -Seconds 1
    $j = Status | ConvertFrom-Json
    Write-Output ("  t+" + $i + "s recording=" + $j.recording + " mode=" + $j.recordingMode + " error='" + $j.error + "'")
}

Write-Output ""
Write-Output "=== stop it, the live feed has to come back ==="
Write-Output ("  POST -> " + (Post '/record/stop'))
for ($i = 1; $i -le 10; $i++) {
    Start-Sleep -Seconds 2
    $j = Status | ConvertFrom-Json
    Write-Output ("  t+" + ($i * 2) + "s recording=" + $j.recording + " streaming=" + $j.streaming + " fragments=" + $j.fragments + " error='" + $j.error + "'")
    if ($j.streaming) { Write-Output "  -> the live feed is back"; break }
}
