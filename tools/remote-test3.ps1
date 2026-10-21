$base = "http://192.168.0.103:8080"

function Status {
    try { (Invoke-WebRequest -Uri "$base/status" -UseBasicParsing -TimeoutSec 8).Content }
    catch { $null }
}
function Post($path) {
    try { (Invoke-WebRequest -Uri "$base$path" -Method POST -UseBasicParsing -TimeoutSec 20).Content }
    catch { "FAILED: " + $_.Exception.Message }
}
function Show($label) {
    $raw = Status
    if (-not $raw) { Write-Output ("  " + $label + ": <no answer>"); return $null }
    try { $j = $raw | ConvertFrom-Json } catch { Write-Output ("  " + $label + ": <bad json>"); return $null }
    Write-Output ("  " + $label + ": rec=" + $j.recording + " recMode=" + $j.recordingMode +
        " streaming=" + $j.streaming + " fragments=" + $j.fragments +
        " frames=" + $j.frames + " bytes=" + $j.bytes +
        " camera=" + $j.camera + " unavailable=" + $j.streamUnavailable +
        " error='" + $j.error + "'")
    return $j
}

Write-Output "waiting for build 03:30 ..."
$deadline = (Get-Date).AddMinutes(25)
$fresh = $false
while ((Get-Date) -lt $deadline) {
    $raw = Status
    if ($raw) {
        try { $j = $raw | ConvertFrom-Json } catch { $j = $null }
        if ($j -and $j.build -eq "2026-10-21 05:25") {
            Write-Output ("build 03:30 is running: " + $raw)
            $fresh = $true
            break
        }
    }
    Start-Sleep -Seconds 4
}
if (-not $fresh) { Write-Output "build 03:30 was never installed"; exit 1 }

Write-Output ""
Write-Output "=== 1. the live feed with the app closed ==="
for ($i = 1; $i -le 8; $i++) {
    $j = Show ("t+" + $i)
    if ($j -and $j.streaming) { Write-Output "  -> the live feed is running"; break }
    Start-Sleep -Seconds 3
}

Write-Output ""
Write-Output "=== 2. video recording from the dashboard ==="
Write-Output ("  POST -> " + (Post '/record/video/start'))
for ($i = 1; $i -le 10; $i++) {
    Start-Sleep -Seconds 1
    Show ("t+" + $i)
}

Write-Output ""
Write-Output "=== 3. stop it - the live feed has to come back ==="
Write-Output ("  POST -> " + (Post '/record/stop'))
for ($i = 1; $i -le 10; $i++) {
    Start-Sleep -Seconds 2
    $j = Show ("t+" + ($i * 2))
    if ($j -and $j.streaming) { Write-Output "  -> the live feed is back"; break }
}
