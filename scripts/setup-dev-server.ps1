# 一键准备 LeavesMC 本地开发服务器（使用 LeavesMC 官方构建 API）
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\setup-dev-server.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\setup-dev-server.ps1 -MinecraftVersion 1.21.8
param(
    [string]$MinecraftVersion = "1.21.8"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $root "run"
$plugins = Join-Path $run "plugins"

New-Item -ItemType Directory -Force -Path $run, $plugins | Out-Null

Write-Host "==> 查询 Leaves $MinecraftVersion 的构建..." -ForegroundColor Cyan
$api = "https://api.leavesmc.org/v2/projects/leaves/versions/$MinecraftVersion/builds"
$response = Invoke-RestMethod -Uri $api -Headers @{ "User-Agent" = "LeavesDeepSeek-Setup" }

$builds = @($response.builds)
if ($builds.Count -eq 0) {
    throw "没有找到 Leaves $MinecraftVersion 的构建，请检查版本号：https://leavesmc.org/downloads/all"
}

# 优先使用 default 通道的最新构建
$preferred = $builds | Where-Object { $_.channel -eq "default" }
$target = @($preferred | Sort-Object build -Descending) + @($builds | Sort-Object build -Descending) |
    Where-Object { $_.downloads -and $_.downloads.application } |
    Select-Object -First 1

if (-not $target) {
    throw "没有找到可下载的构建文件，请手动下载：https://leavesmc.org/downloads/leaves"
}

$jarName = $target.downloads.application.name
$jar = Join-Path $run "leaves.jar"
$url = "$api/$($target.build)/downloads/$jarName"

Write-Host "==> 下载构建 #$($target.build)：$jarName" -ForegroundColor Cyan
Invoke-WebRequest -Uri $url -OutFile $jar -UseBasicParsing
Write-Host "    已保存到 $jar" -ForegroundColor DarkGray

Write-Host "==> 写入 EULA 与基础 server.properties" -ForegroundColor Cyan
Set-Content -Path (Join-Path $run "eula.txt") -Value "eula=true" -Encoding UTF8
@(
    "online-mode=false"
    "spawn-protection=0"
    "max-players=20"
    "view-distance=8"
    "simulation-distance=6"
    "level-name=world"
) | Set-Content -Path (Join-Path $run "server.properties") -Encoding UTF8

if (-not (Test-Path (Join-Path $run "leaves.yml"))) {
    Write-Host "==> 提示：首次启动会生成 leaves.yml，请确认 settings.modify.fakeplayer.enable 与 use-action 为 true" -ForegroundColor Yellow
}

Write-Host "==> 构建插件..." -ForegroundColor Cyan
& (Join-Path $root "gradlew.bat") -p $root build

$built = Get-ChildItem (Join-Path $root "build\libs") -Filter "LeavesDeepSeek-*.jar" |
    Where-Object { $_.Name -notlike "*-sources*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($built) {
    Copy-Item $built.FullName $plugins -Force
    Write-Host "==> 已复制 $($built.Name) 到 run/plugins" -ForegroundColor Green
}

@'
Set-Location $PSScriptRoot
java -Xms2G -Xmx2G -jar leaves.jar --nogui
'@ | Set-Content -Path (Join-Path $run "start.ps1") -Encoding UTF8

Write-Host ""
Write-Host "完成！启动服务器：" -ForegroundColor Green
Write-Host "  powershell -ExecutionPolicy Bypass -File .\run\start.ps1"
Write-Host "首次启动后请编辑 run/plugins/LeavesDeepSeek/config.yml 填写 DeepSeek API Key。"
Write-Host "并确认 run/leaves.yml 中 settings.modify.fakeplayer.enable 与 use-action 为 true。"
