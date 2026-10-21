$base = "http://192.168.0.103:8080"

function Status {
    try { (Invoke-WebRequest -Uri "$base/status" -UseBasicParsing -TimeoutSec 8).Content }
    catch { $null }
}

Write-Output "waiting for the new build (09:05) ..."
$deadline = (Get-Date).AddMinutes(30)
$json = $null
while ((Get-Date) -lt $deadline) {
    $raw = Status
    if ($raw) {
        try { $j = $raw | ConvertFrom-Json } catch { $j = $null }
        if ($j -and $j.build -eq "2026-10-21 05:35") { $json = $j; break }
    }
    Start-Sleep -Seconds 4
}
if (-not $json) { Write-Output "the build was never seen"; exit 1 }
Write-Output ("build 09:05 is running")

Write-Output ""
Write-Output "=== the feed ==="
for ($i = 1; $i -le 10; $i++) {
    $raw = Status
    if (-not $raw) { Write-Output ("  t+" + $i + ": <no answer>"); Start-Sleep -Seconds 2; continue }
    $j = $raw | ConvertFrom-Json
    Write-Output ("  t+" + $i + "s streaming=" + $j.streaming + " fragments=" + $j.fragments +
        " frames=" + $j.frames + " error='" + $j.error + "'")
    Write-Output ("        " + $j.encoder)
    Start-Sleep -Seconds 3
}
