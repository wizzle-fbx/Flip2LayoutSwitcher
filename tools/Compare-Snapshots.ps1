param([Parameter(Mandatory)][string]$Before,[Parameter(Mandatory)][string]$After)
$ErrorActionPreference='Stop'
foreach ($name in @('system','secure','global','properties')) {
    Write-Output "[$name] <= before; => after"
    Compare-Object (Get-Content -LiteralPath (Join-Path $Before "$name.txt")) (Get-Content -LiteralPath (Join-Path $After "$name.txt"))
}
foreach ($path in @($Before,$After)) {
    Write-Output "[input: $path]"
    Select-String -LiteralPath (Join-Path $path 'input.txt') -Pattern 'Retroid Pocket Controller|Xbox Wireless Controller|vendor=0x2022|KeyLayoutFile'
}