$base = "http://192.168.0.103:8080"

function Status {
    try { (Invoke-WebRequest -Uri "$base/status" -UseBasicParsing -TimeoutSec 8).Content }
    catch { $null }
}
function Post($path) {
    try { (Invoke-WebRequest -Uri "$base$path" -Method POST -UseBasicParsing -TimeoutSec 20).Content }
    catch { "FAILED: " + $_.Exception.Message }
}

Write-Output "waiting for the phone's server ..."
$deadline = (Get-Date).AddMinutes(20)
$json = $null
while ((Get-Date) -lt $deadline) {
    $raw = Status
    if ($raw) {
        try { $json = $raw | ConvertFrom-Json } catch { $json = $null }
        if ($json) { break }
    }
    Start-Sleep -Seconds 4
}
if (-not $json) { Write-Output "the server never came up"; exit 1 }

Write-Output ("server up: " + ($json | ConvertTo-Json -Compress))
Write-Output ""
Write-Output "=== 1. live stream ==="
Write-Output ("  streaming=" + $json.streaming + " fragments=" + $json.fragments + " mode=" + $json.mode)
Start-Sleep -Seconds 4
$json = Status | ConvertFrom-Json
Write-Output ("  after 4s: streaming=" + $json.streaming + " fragments=" + $json.fragments)
Write-Output ""
Write-Output "=== 2. start a video recording from the dashboard ==="
Write-Output ("  POST -> " + (Post '/record/video/start'))
for ($i = 1; $i -le 12; $i++) {
    Start-Sleep -Seconds 1
    $j = Status | ConvertFrom-Json
    Write-Output ("  t+" + $i + "s recording=" + $j.recording + " mode=" + $j.recordingMode + " error='" + $j.error + "'")
}
Write-Output ""
Write-Output "=== 3. stop it ==="
Write-Output ("  POST -> " + (Post '/record/stop'))
for ($i = 1; $i -le 8; $i++) {
    Start-Sleep -Seconds 1
    $j = Status | ConvertFrom-Json
    Write-Output ("  t+" + $i + "s recording=" + $j.recording + " error='" + $j.error + "' streaming=" + $j.streaming + " fragments=" + $j.fragments)
}
Write-Output ""
Write-Output "=== 4. audio recording ==="
Write-Output ("  POST -> " + (Post '/record/audio/start'))
for ($i = 1; $i -le 5; $i++) {
    Start-Sleep -Seconds 1
    $j = Status | ConvertFrom-Json
    Write-Output ("  t+" + $i + "s recording=" + $j.recording + " mode=" + $j.recordingMode + " error='" + $j.error + "'")
}
Write-Output ("  stop -> " + (Post '/record/stop'))
