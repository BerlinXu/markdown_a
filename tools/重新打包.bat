@echo off
chcp 65001 >nul
cd /d "%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "tools\build.ps1" :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
if errorlevel 1 goto fail
if not exist "app\build\outputs\apk\release\app-release.apk" goto unsigned
if not exist release mkdir release
copy /Y "app\build\outputs\apk\release\app-release.apk" "release\纸墨Markdown.apk" >nul
if errorlevel 1 goto fail
powershell.exe -NoProfile -Command "$apk = Get-Item -LiteralPath 'release\纸墨Markdown.apk'; $digest = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); [System.IO.File]::WriteAllText((Join-Path $apk.DirectoryName 'SHA256SUMS.txt'), $digest + '  ' + $apk.Name + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))"
if errorlevel 1 goto fail
echo 已生成关闭调试的签名 Release：release\纸墨Markdown.apk
pause
exit /b 0
:unsigned
echo 未生成签名 Release。请先设置 README 中的 MD_RELEASE_* 签名环境变量。
echo unsigned APK 不能安装，本脚本不会将其复制为可分发 APK。
pause
exit /b 1
:fail
echo 构建失败，请查看上方错误。
pause
exit /b 1
