$base = "http://192.168.0.103:8080"

function Status {
    try { (Invoke-WebRequest -Uri "$base/status" -UseBasicParsing -TimeoutSec 8).Content }
    catch { $null }
}
function Get-Bytes($url) {
    $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20
    $ms = New-Object System.IO.MemoryStream
    $resp.RawContentStream.CopyTo($ms)
    return $ms.ToArray()
}
function Read-Int($b, $o) {
    return [int]$b[$o]*16777216 + [int]$b[$o+1]*65536 + [int]$b[$o+2]*256 + [int]$b[$o+3]
}
function Read-Long($b, $o) {
    $v = [int64]0
    for ($k = 0; $k -lt 8; $k++) { $v = $v*256 + $b[$o+$k] }
    return $v
}
function Find-Box($b, $from, $to, $type) {
    $i = $from
    while ($i + 8 -le $to -and $i + 8 -le $b.Length) {
        $size = Read-Int $b $i
        if ($size -lt 8) { return -1 }
        if ([System.Text.Encoding]::ASCII.GetString($b, $i+4, 4) -eq $type) { return $i }
        $i += $size
    }
    return -1
}

Write-Output "waiting for build 06:45 ..."
$deadline = (Get-Date).AddMinutes(25)
$json = $null
while ((Get-Date) -lt $deadline) {
    $raw = Status
    if ($raw) {
        try { $j = $raw | ConvertFrom-Json } catch { $j = $null }
        if ($j -and $j.build -eq "2026-10-21 07:25") { $json = $j; break }
    }
    Start-Sleep -Seconds 4
}
if (-not $json) { Write-Output "build 06:45 was never seen"; exit 1 }
Write-Output ("build 06:45 is running")

Write-Output ""
Write-Output "=== do the fragments carry a time of their own? ==="
$times = @()
for ($i = 0; $i -lt 5; $i++) {
    $playlist = [System.Text.Encoding]::ASCII.GetString((Get-Bytes "$base/live.m3u8"))
    $seg = (($playlist -split "`n") | Where-Object { $_ -match '^/seg-' } | Select-Object -First 1).Trim()
    if (-not $seg) { Start-Sleep -Seconds 2; continue }
    $b = Get-Bytes "$base$seg"
    $moof = Find-Box $b 0 $b.Length "moof"
    if ($moof -lt 0) { Write-Output ("  " + $seg + ": no moof"); Start-Sleep -Seconds 2; continue }
    $traf = Find-Box $b ($moof+8) ($moof + (Read-Int $b $moof)) "traf"
    $tfdt = Find-Box $b ($traf+8) ($traf + (Read-Int $b $traf)) "tfdt"
    if ($tfdt -lt 0) {
        Write-Output ("  " + $seg + ": NO tfdt  <- the player cannot place it")
    } else {
        $v = $b[$tfdt+8]
        $t = Read-Long $b ($tfdt + 12)
        $times += $t
        Write-Output ("  " + $seg + ": tfdt version " + $v + "  baseMediaDecodeTime " + $t + "  (" + [math]::Round($t/90000,2) + " s)")
    }
    Start-Sleep -Seconds 1
}
if ($times.Count -ge 2) {
    $growing = $true
    for ($i = 1; $i -lt $times.Count; $i++) { if ($times[$i] -le $times[$i-1]) { $growing = false } }
    Write-Output ("  the times grow: " + $growing)
    $last = $times[$times.Count - 1]
    Write-Output ("  the last time is " + [math]::Round($last/90000,2) + " s into the stream")
}

Write-Output ""
Write-Output "=== the feed ==="
for ($i = 1; $i -le 8; $i++) {
    $raw = Status
    if (-not $raw) { Write-Output ("  t+" + $i + ": <no answer>"); Start-Sleep -Seconds 2; continue }
    $j = $raw | ConvertFrom-Json
    Write-Output ("  t+" + $i + "s streaming=" + $j.streaming + " fragments=" + $j.fragments +
        " analyzed=" + $j.analyzed + " frames=" + $j.frames + " error='" + $j.error + "'")
    Write-Output ("        " + $j.encoder)
    Start-Sleep -Seconds 2
}
