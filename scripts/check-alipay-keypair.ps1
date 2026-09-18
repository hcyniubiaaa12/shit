# =============================================================
# 校验「配置里的应用私钥」与「支付宝控制台的应用公钥」是否同一对密钥
#
# 用法：
#   pwsh scripts/check-alipay-keypair.ps1 -AppPublicKey "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A..."
#   或把公钥存成文件：
#   pwsh scripts/check-alipay-keypair.ps1 -AppPublicKeyFile .\app-pub.txt
#
# 原理：从 PKCS8 私钥取出 RSA 模数 n，与公钥（SPKI 或 PKCS1）的模数 n 逐字节比对
# =============================================================
param(
    [string]$AppPublicKey,
    [string]$AppPublicKeyFile,
    [string]$ConfigPath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $ConfigPath) {
    $ConfigPath = Join-Path $root 'mall-backend\mall-pay-service\src\main\resources\application-local.yml'
}
if ($AppPublicKeyFile) { $AppPublicKey = Get-Content $AppPublicKeyFile -Raw }
if (-not $AppPublicKey) { throw '请用 -AppPublicKey 或 -AppPublicKeyFile 提供控制台的应用公钥' }

function Strip-Key([string]$k) {
    (($k -replace '-----BEGIN [A-Z ]+-----', '') -replace '-----END [A-Z ]+-----', '') -replace '\s', ''
}

function Read-Len([byte[]]$b, [ref]$off) {
    $len = [int]$b[$off.Value]; $off.Value++
    if ($len -band 0x80) {
        $n = $len -band 0x7F; $len = 0
        for ($i = 0; $i -lt $n; $i++) { $len = ($len -shl 8) -bor [int]$b[$off.Value]; $off.Value++ }
    }
    return $len
}

function Read-Tlv([byte[]]$b, [ref]$off) {
    $tag = $b[$off.Value]; $off.Value++
    $len = Read-Len $b $off
    $val = @($b[$off.Value..($off.Value + $len - 1)])
    $off.Value += $len
    return @{ Tag = $tag; Val = $val }
}

# PKCS8: SEQUENCE { INTEGER ver, SEQUENCE algId, OCTET STRING <PKCS1> }
# PKCS1: SEQUENCE { INTEGER n, INTEGER e, ... }
function Get-PrivateModulus([string]$pem) {
    $der = [Convert]::FromBase64String((Strip-Key $pem))
    $off = 0
    $outer = Read-Tlv $der ([ref]$off)
    $body = $outer.Val
    $o = 0
    $first = Read-Tlv $body ([ref]$o)
    if ($first.Tag -eq 0x02) {
        # 直接是 PKCS1 私钥
        return , $first.Val
    }
    if ($first.Tag -ne 0x30) { throw "私钥格式异常：期望 algId SEQUENCE" }
    $oct = Read-Tlv $body ([ref]$o)          # OCTET STRING
    $o2 = 0
    $rsa = Read-Tlv $oct.Val ([ref]$o2)      # SEQUENCE
    $o3 = 0
    $n = Read-Tlv $rsa.Val ([ref]$o3)        # INTEGER modulus
    return , $n.Val
}

function Get-PublicModulus([string]$pem) {
    $der = [Convert]::FromBase64String((Strip-Key $pem))
    $off = 0
    $outer = Read-Tlv $der ([ref]$off)
    $body = $outer.Val
    $o = 0
    $first = Read-Tlv $body ([ref]$o)
    if ($first.Tag -eq 0x30) {
        # SPKI: SEQUENCE { SEQUENCE algId, BIT STRING }
        $bits = Read-Tlv $body ([ref]$o)
        $bv = $bits.Val
        $pkcs1 = @($bv[1..($bv.Length - 1)])     # 跳过未使用位计数
        $o2 = 0
        $rsa = Read-Tlv $pkcs1 ([ref]$o2)
        $o3 = 0
        $n = Read-Tlv $rsa.Val ([ref]$o3)
        return , $n.Val
    }
    # PKCS1 公钥：第一个 INTEGER 就是 n
    return , $first.Val
}

function Fingerprint([byte[]]$b) {
    $arr = @($b)
    $s = 0
    while ($s -lt $arr.Count -and $arr[$s] -eq 0) { $s++ }
    $e = [Math]::Min($s + 15, $arr.Count - 1)
    return (@($arr[$s..$e]) | ForEach-Object { ([int]$_).ToString('x2') }) -join ''
}

# ---- 配置 ----
$cfg = Get-Content $ConfigPath -Raw
if ($cfg -match '(?m)^\s*app-id\s*:\s*"([^"]*)"') { Write-Host ("配置 app-id        = " + $Matches[1]) }
if ($cfg -notmatch '(?m)^\s*private-key\s*:\s*"([^"]*)"') { throw '配置里没找到 private-key' }
$privPem = $Matches[1]
if ($privPem.Trim() -eq '') { throw '配置里的 private-key 是空的' }

$nPriv = Get-PrivateModulus $privPem
$nPub = Get-PublicModulus $AppPublicKey

$fpPriv = Fingerprint $nPriv
$fpPub = Fingerprint $nPub
Write-Host ("配置私钥模数        = " + $fpPriv + "  (" + $nPriv.Count + " 字节)")
Write-Host ("控制台应用公钥模数  = " + $fpPub + "  (" + $nPub.Count + " 字节)")
Write-Host ''

if ($fpPriv -eq $fpPub) {
    Write-Host '✅ 配对一致：支付宝能验签通过' -ForegroundColor Green
    exit 0
}
Write-Host '❌ 不配对：配置里的私钥不是这个应用公钥对应的私钥' -ForegroundColor Red
Write-Host '   → 把控制台「查看密钥」的【应用私钥 JAVA 语言】整段复制到 application-local.yml 的 private-key' -ForegroundColor Yellow
exit 1
