param([Parameter(Mandatory)][string]$Serial,[Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label,[string]$Adb='adb',[string]$OutputDirectory=(Join-Path $PSScriptRoot '..\docs\evidence'))
$ErrorActionPreference='Stop'
function Read-Adb([string[]]$Arguments) {
    $result=& $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return $result
}
$model=((Read-Adb -Arguments @('shell','getprop','ro.product.model')) -join '').Trim()
if ($model -ne 'Retroid Pocket Flip2') { throw "Wrong device: $model" }
$firmware=((Read-Adb -Arguments @('shell','getprop','ro.build.fingerprint')) -join '').Trim()
if ($firmware -ne 'qti/kona/kona:13/TKQ1.240621.001/eng.RPFlip.20250501.115911:user/release-keys') { throw "Unverified firmware: $firmware" }
$stamp=(Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmssZ')
$destination=Join-Path $OutputDirectory "$stamp-$Label"
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$reads=[ordered]@{ identity=@('shell','id'); system=@('shell','settings','list','system'); secure=@('shell','settings','list','secure'); global=@('shell','settings','list','global'); properties=@('shell','getprop'); input=@('shell','dumpsys','input'); services=@('shell','service','list'); recent_log=@('shell','logcat','-d','-t','300','-v','threadtime') }
foreach ($name in $reads.Keys) { Read-Adb -Arguments $reads[$name] | Set-Content -LiteralPath (Join-Path $destination "$name.txt") -Encoding utf8 }
@{model=$model;fingerprint=$firmware;serial=$Serial;label=$Label;capturedUtc=$stamp;readOnly=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'metadata.json') -Encoding utf8
Write-Output $destination