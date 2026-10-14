param([Parameter(Mandatory)][string]$Serial,[Parameter(Mandatory)][string]$Path)
$info=New-Object Diagnostics.ProcessStartInfo
$info.FileName=(Get-Command adb).Source
$info.ArgumentList.Add('-s'); $info.ArgumentList.Add($Serial)
$info.ArgumentList.Add('exec-out'); $info.ArgumentList.Add('screencap'); $info.ArgumentList.Add('-p')
$info.RedirectStandardOutput=$true; $info.UseShellExecute=$false
$process=[Diagnostics.Process]::Start($info)
$file=[IO.File]::Create($Path)
try { $process.StandardOutput.BaseStream.CopyTo($file) } finally { $file.Dispose() }
$process.WaitForExit()
if ($process.ExitCode -ne 0) { throw 'Screenshot failed' }