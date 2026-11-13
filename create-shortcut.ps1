# Creates a desktop app icon that starts the Finance app with no console window (runs Finance.vbs).
$root = $PSScriptRoot
$desktop = [Environment]::GetFolderPath('Desktop')
$s = (New-Object -ComObject WScript.Shell).CreateShortcut("$desktop\Sri Raja Rajeshwari Finance.lnk")
$s.TargetPath = "$env:WINDIR\System32\wscript.exe"
$s.Arguments = "`"$root\Finance.vbs`""
$s.WorkingDirectory = $root
$s.IconLocation = "$root\finance.ico"
$s.Description = "Sri Raja Rajeshwari Finance"
$s.Save()
Write-Host "Shortcut created on the desktop: $desktop\Sri Raja Rajeshwari Finance.lnk"
