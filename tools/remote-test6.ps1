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

Write-Output "waiting for build 08:15 ..."
$deadline = (Get-Date).AddMinutes(25)
$json = $null
while ((Get-Date) -lt $deadline) {
    $raw = Status
    if ($raw) {
        try { $j = $raw | ConvertFrom-Json } catch { $j = $null }
        if ($j -and $j.build -eq "2026-10-21 08:15") { $json = $j; break }
    }
    Start-Sleep -Seconds 4
}
if (-not $json) { Write-Output "build 08:15 was never seen"; exit 1 }
Write-Output ("build 08:15 is running")

Write-Output ""
Write-Output "=== does the offset point at the sample data? ==="
for ($i = 0; $i -lt 3; $i++) {
    $playlist = [System.Text.Encoding]::ASCII.GetString((Get-Bytes "$base/live.m3u8"))
    $seg = (($playlist -split "`n") | Where-Object { $_ -match '^/seg-' } | Select-Object -First 1).Trim()
    if (-not $seg) { Start-Sleep -Seconds 2; continue }
    $b = Get-Bytes "$base$seg"
    $moof = Find-Box $b 0 $b.Length "moof"
    $traf = Find-Box $b ($moof+8) ($moof + (Read-Int $b $moof)) "traf"
    $trun = Find-Box $b ($traf+8) ($traf + (Read-Int $b $traf)) "trun"
    $mdat = Find-Box $b ($moof + (Read-Int $b $moof)) $b.Length "mdat"
    $dataOffset = Read-Int $b ($trun + 16)
    $pointsAt = $moof + $dataOffset
    $shouldBe = $mdat + 8
    Write-Output ("  " + $seg + ": the offset points at " + $pointsAt + ", the data starts at " + $shouldBe +
        "  -> " + $(if ($pointsAt -eq $shouldBe) { "correct" } else { "WRONG by " + ($pointsAt - $shouldBe) }))
    Start-Sleep -Seconds 1
}

Write-Output ""
Write-Output "=== the times and the feed ==="
for ($i = 1; $i -le 6; $i++) {
    $raw = Status
    if (-not $raw) { Write-Output ("  t+" + $i + ": <no answer>"); Start-Sleep -Seconds 2; continue }
    $j = $raw | ConvertFrom-Json
    Write-Output ("  t+" + $i + "s streaming=" + $j.streaming + " fragments=" + $j.fragments +
        " frames=" + $j.frames + " error='" + $j.error + "'")
    Write-Output ("        " + $j.encoder)
    Start-Sleep -Seconds 2
}
