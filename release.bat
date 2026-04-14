@echo off
chcp 65001 >nul 2>&1
setlocal EnableDelayedExpansion

REM ============================================================
REM  Maven 项目一键发布脚本（通用版 / Windows）
REM
REM  自动从 pom.xml 和 git remote 读取项目信息，
REM  无需修改脚本即可在任何 Maven + GitHub + JitPack 项目中使用。
REM
REM  用法:
REM    release.bat <版本号> [选项]
REM    release.bat 0.3.0                  发布 v0.3.0
REM    release.bat 0.3.0 --skip-test      跳过测试
REM    release.bat 0.3.0 --branch main    指定主分支
REM    release.bat 0.3.0 --no-snapshot    不自动升级到下一个 SNAPSHOT
REM
REM  放置: 复制到任意 Maven 项目根目录（与 pom.xml 同级）即可使用
REM ============================================================

set "GIT_REMOTE=origin"
set "SKIP_TEST=false"
set "NO_SNAPSHOT=false"
set "CUSTOM_BRANCH="

REM ---------- 解析参数 ----------
if "%~1"=="" goto :usage
set "RELEASE_VERSION=%~1"
shift

:parse_args
if "%~1"=="" goto :done_args
if "%~1"=="--skip-test" (
    set "SKIP_TEST=true"
    shift
    goto :parse_args
)
if "%~1"=="--no-snapshot" (
    set "NO_SNAPSHOT=true"
    shift
    goto :parse_args
)
if "%~1"=="--branch" (
    set "CUSTOM_BRANCH=%~2"
    shift
    shift
    goto :parse_args
)
if "%~1"=="--remote" (
    set "GIT_REMOTE=%~2"
    shift
    shift
    goto :parse_args
)
if "%~1"=="-h" goto :usage
if "%~1"=="--help" goto :usage
echo [ERROR] 未知参数: %~1
goto :usage
:done_args

set "TAG=v%RELEASE_VERSION%"

REM 计算下一个 SNAPSHOT 版本
for /f "tokens=1,2,3 delims=." %%a in ("%RELEASE_VERSION%") do (
    set "MAJOR=%%a"
    set /a "MINOR=%%b+1"
    set "PATCH=%%c"
)
set "NEXT_VERSION=!MAJOR!.!MINOR!.0-SNAPSHOT"

echo.
echo ============================================================
echo   Maven 项目一键发布（通用版）
echo ============================================================
echo.

REM ============================================================
REM Step 0: 项目信息自动检测
REM ============================================================
echo [INFO] 自动检测项目信息...

if not exist "pom.xml" (
    echo [ERROR] 当前目录没有 pom.xml，请在 Maven 项目根目录执行此脚本
    exit /b 1
)
echo [OK] 找到 pom.xml

REM 提取 artifactId（取 pom.xml 中第二个 artifactId 标签，第一个是 parent 的）
set "ARTIFACT_ID="
set "GROUP_ID="
set "CURRENT_VERSION="
set "ARTIFACT_COUNT=0"

for /f "tokens=*" %%a in ('findstr /n "<artifactId>" pom.xml') do (
    set /a ARTIFACT_COUNT+=1
    if !ARTIFACT_COUNT! equ 2 (
        for /f "tokens=2 delims=<>" %%b in ("%%a") do set "ARTIFACT_ID=%%b"
    )
)

REM 提取 groupId
for /f "tokens=2 delims=<>" %%a in ('findstr /n "<groupId>" pom.xml ^| findstr /n "." ^| findstr "^2:"') do (
    set "GROUP_ID=%%a"
)

REM 尝试用 mvn 命令获取更准确的信息（如果有 Maven）
where mvn >nul 2>&1
if not errorlevel 1 (
    for /f "tokens=*" %%a in ('mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout 2^>nul') do set "ARTIFACT_ID=%%a"
    for /f "tokens=*" %%a in ('mvn help:evaluate -Dexpression=project.groupId -q -DforceStdout 2^>nul') do set "GROUP_ID=%%a"
    for /f "tokens=*" %%a in ('mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2^>nul') do set "CURRENT_VERSION=%%a"
)

REM 如果 mvn 没拿到版本，用 PowerShell 从 pom.xml 解析
if "%CURRENT_VERSION%"=="" (
    for /f "tokens=*" %%a in ('powershell -Command "$m = [regex]::Match((Get-Content 'pom.xml' -Raw), '<version>([\d][\w.-]+)</version>'); if ($m.Success) { $m.Groups[1].Value }"') do set "CURRENT_VERSION=%%a"
)

echo [OK] 项目: %GROUP_ID%:%ARTIFACT_ID%:%CURRENT_VERSION%

REM 检测 git 仓库
git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 当前目录不是 git 仓库
    exit /b 1
)

REM 检测主分支
if "%CUSTOM_BRANCH%"=="" (
    for /f "tokens=3" %%a in ('git remote show %GIT_REMOTE% 2^>nul ^| findstr "HEAD branch"') do set "MAIN_BRANCH=%%a"
    if "!MAIN_BRANCH!"=="" (
        git show-ref --verify --quiet refs/heads/main 2>nul
        if not errorlevel 1 (
            set "MAIN_BRANCH=main"
        ) else (
            set "MAIN_BRANCH=master"
        )
    )
) else (
    set "MAIN_BRANCH=%CUSTOM_BRANCH%"
)
echo [OK] 主分支: %MAIN_BRANCH% (自动检测)

REM 从 git remote URL 提取 GitHub 用户名和仓库名
set "GH_USER="
set "REPO_NAME="
for /f "tokens=*" %%a in ('git remote get-url %GIT_REMOTE% 2^>nul') do set "REMOTE_URL=%%a"

echo %REMOTE_URL% | findstr "github.com" >nul 2>&1
if not errorlevel 1 (
    REM 解析 https://github.com/user/repo.git 或 git@github.com:user/repo.git
    for /f "tokens=*" %%a in ('powershell -Command "$u = '%REMOTE_URL%'; $m = [regex]::Match($u, 'github\.com[:/]([^/]+)/([^/.]+)'); Write-Host $m.Groups[1].Value"') do set "GH_USER=%%a"
    for /f "tokens=*" %%a in ('powershell -Command "$u = '%REMOTE_URL%'; $m = [regex]::Match($u, 'github\.com[:/]([^/]+)/([^/.]+)'); Write-Host $m.Groups[2].Value"') do set "REPO_NAME=%%a"
    echo [OK] GitHub: %GH_USER%/%REPO_NAME%

    echo %GROUP_ID% | findstr /b "com.github." >nul 2>&1
    if not errorlevel 1 (
        set "JITPACK_GROUP=%GROUP_ID%"
    ) else (
        set "JITPACK_GROUP=com.github.%GH_USER%"
        echo [WARN] groupId 不是 com.github.xxx 格式，JitPack 使用: com.github.%GH_USER%
    )
) else (
    set "JITPACK_GROUP=%GROUP_ID%"
    echo [WARN] 未检测到 GitHub 远程仓库
)

echo.
echo ============================================================
echo   发布计划
echo ============================================================
echo   项目:         %GROUP_ID%:%ARTIFACT_ID%
echo   当前版本:     %CURRENT_VERSION%
echo   发布版本:     %RELEASE_VERSION%
echo   Tag:          %TAG%
if "%NO_SNAPSHOT%"=="false" echo   后续版本:     %NEXT_VERSION%
echo   主分支:       %MAIN_BRANCH%
echo   跳过测试:     %SKIP_TEST%
echo ============================================================
echo.

REM 二次确认
set /p "CONFIRM=确认发布？[y/N] "
if /i not "%CONFIRM%"=="y" (
    echo [INFO] 已取消
    exit /b 0
)
echo.

REM ============================================================
REM Step 1: 环境检查
REM ============================================================
echo [INFO] Step 1/6: 环境检查

for /f "tokens=*" %%b in ('git rev-parse --abbrev-ref HEAD') do set "CURRENT_BRANCH=%%b"
if not "%CURRENT_BRANCH%"=="%MAIN_BRANCH%" (
    echo [ERROR] 当前分支是 '%CURRENT_BRANCH%'，请先切换到 '%MAIN_BRANCH%'
    echo   执行: git checkout %MAIN_BRANCH%
    exit /b 1
)
echo [OK] 当前分支: %CURRENT_BRANCH%

git diff --quiet 2>nul
if errorlevel 1 (
    echo [ERROR] 工作区有未提交的变更，请先提交或 stash
    echo   git status 查看详情
    exit /b 1
)
git diff --cached --quiet 2>nul
if errorlevel 1 (
    echo [ERROR] 暂存区有未提交的变更
    exit /b 1
)
echo [OK] 工作区干净

git tag -l "%TAG%" 2>nul | findstr /x "%TAG%" >nul 2>&1
if not errorlevel 1 (
    echo [ERROR] Tag %TAG% 已存在！
    echo   删除本地 tag: git tag -d %TAG%
    echo   删除远程 tag: git push %GIT_REMOTE% :refs/tags/%TAG%
    exit /b 1
)
echo [OK] Tag %TAG% 可用

echo [INFO] 拉取远程最新代码...
git pull %GIT_REMOTE% %MAIN_BRANCH% --ff-only
if errorlevel 1 (
    echo [ERROR] 拉取失败或有冲突，请先手动处理
    exit /b 1
)
echo [OK] 代码已是最新
echo.

REM ============================================================
REM Step 2: 运行测试
REM ============================================================
echo [INFO] Step 2/6: 运行测试

if "%SKIP_TEST%"=="true" (
    echo [WARN] 已跳过测试 (--skip-test)
) else (
    echo [INFO] 执行 mvn test ...
    call mvn test
    if errorlevel 1 (
        echo [ERROR] 测试失败！请修复后再发布
        echo   跳过测试发布: release.bat %RELEASE_VERSION% --skip-test
        exit /b 1
    )
    echo [OK] 全部测试通过
)
echo.

REM ============================================================
REM Step 3: 设置发布版本，提交，打 tag
REM ============================================================
echo [INFO] Step 3/6: 设置发布版本 %RELEASE_VERSION%

REM 优先用 mvn versions:set
where mvn >nul 2>&1
if not errorlevel 1 (
    call mvn versions:set -DnewVersion=%RELEASE_VERSION% -q
    if not errorlevel 1 (
        echo [OK] mvn versions:set → %RELEASE_VERSION%
        if exist pom.xml.versionsBackup del pom.xml.versionsBackup
    ) else (
        goto :sed_replace
    )
) else (
:sed_replace
    REM 备用方案：PowerShell 替换 SNAPSHOT 版本
    powershell -Command ^
        "$content = Get-Content 'pom.xml' -Raw; " ^
        "$pattern = '(<version>)\d+\.\d+\.\d+-SNAPSHOT(</version>)'; " ^
        "$match = [regex]::Match($content, $pattern); " ^
        "if ($match.Success) { " ^
        "    $old = $match.Groups[0].Value; " ^
        "    $content = $content -replace [regex]::Escape($old), '<version>%RELEASE_VERSION%</version>'; " ^
        "    Set-Content 'pom.xml' $content -NoNewline; " ^
        "    Write-Host '[OK] pom.xml 已更新为 %RELEASE_VERSION%'; " ^
        "} else { " ^
        "    Write-Host '[WARN] 未找到 SNAPSHOT，替换项目版本...'; " ^
        "    exit 1; " ^
        "}"
    if errorlevel 1 (
        powershell -Command ^
            "$lines = Get-Content 'pom.xml'; " ^
            "$count = 0; " ^
            "$result = $lines | ForEach-Object { " ^
            "    if ($_ -match '<version>([^<]+)</version>') { " ^
            "        $count++; " ^
            "        if ($count -eq 2) { " ^
            "            $_ -replace '<version>[^<]+</version>', '<version>%RELEASE_VERSION%</version>'; " ^
            "        } else { $_ } " ^
            "    } else { $_ } " ^
            "}; " ^
            "Set-Content 'pom.xml' $result; " ^
            "Write-Host '[OK] pom.xml 已更新为 %RELEASE_VERSION%'"
    )
)

git add pom.xml
git commit -m "release: %TAG%" >nul 2>&1
echo [OK] 已提交 release: %TAG%

git tag %TAG%
echo [OK] 已打 tag: %TAG%
echo.

REM ============================================================
REM Step 4: 推送代码和 tag
REM ============================================================
echo [INFO] Step 4/6: 推送到远程

echo [INFO] 推送 %MAIN_BRANCH% 分支...
git push %GIT_REMOTE% %MAIN_BRANCH%
echo [OK] %MAIN_BRANCH% 分支已推送

echo [INFO] 推送 tag %TAG%...
git push %GIT_REMOTE% %TAG%
echo [OK] Tag %TAG% 已推送
echo.

REM ============================================================
REM Step 5: 升级到下一个 SNAPSHOT 版本
REM ============================================================
if "%NO_SNAPSHOT%"=="false" (
    echo [INFO] Step 5/6: 升级到下一个开发版本 %NEXT_VERSION%

    where mvn >nul 2>&1
    if not errorlevel 1 (
        call mvn versions:set -DnewVersion=%NEXT_VERSION% -q
        if not errorlevel 1 (
            echo [OK] mvn versions:set → %NEXT_VERSION%
            if exist pom.xml.versionsBackup del pom.xml.versionsBackup
        ) else (
            goto :sed_next
        )
    ) else (
:sed_next
        powershell -Command ^
            "$content = Get-Content 'pom.xml' -Raw; " ^
            "$content = $content -replace '<version>%RELEASE_VERSION%</version>', '<version>%NEXT_VERSION%</version>'; " ^
            "Set-Content 'pom.xml' $content -NoNewline; " ^
            "Write-Host '[OK] pom.xml 已更新为 %NEXT_VERSION%'"
    )

    git add pom.xml
    git commit -m "chore: bump version to %NEXT_VERSION%" >nul 2>&1
    echo [OK] 已提交

    echo [INFO] 推送 %MAIN_BRANCH%...
    git push %GIT_REMOTE% %MAIN_BRANCH%
    echo [OK] %MAIN_BRANCH% 分支已推送
) else (
    echo [INFO] Step 5/6: 跳过 (--no-snapshot)
)
echo.

REM ============================================================
REM Step 6: JitPack 构建
REM ============================================================
echo [INFO] Step 6/6: JitPack 构建

if not "%GH_USER%"=="" if not "%REPO_NAME%"=="" (
    set "JITPACK_URL=https://jitpack.io/#%GH_USER%/%REPO_NAME%/%TAG%"
    echo.
    echo   JitPack 状态: !JITPACK_URL!
    echo.

    where curl >nul 2>&1
    if not errorlevel 1 (
        echo [INFO] 正在触发 JitPack 构建...
        curl -s -o nul "https://jitpack.io/!JITPACK_GROUP!/%REPO_NAME%/%TAG%/%REPO_NAME%-%TAG%.pom" 2>nul
        echo [OK] 已触发 JitPack 构建请求
    ) else (
        echo [WARN] 未找到 curl，请手动在浏览器打开上面的链接
    )
    echo [WARN] JitPack 首次构建需要 1-3 分钟
) else (
    echo [WARN] 未检测到 GitHub 仓库，跳过 JitPack 步骤
)
echo.

REM ============================================================
REM 完成
REM ============================================================
echo ============================================================
echo   发布完成！
echo ============================================================
echo.
echo   项目:           %GROUP_ID%:%ARTIFACT_ID%
echo   发布版本:       %RELEASE_VERSION%
echo   Git Tag:        %TAG%
if "%NO_SNAPSHOT%"=="false" echo   下个开发版本:   %NEXT_VERSION%
echo.
if not "%GH_USER%"=="" (
    echo   JitPack 状态:   !JITPACK_URL!
    echo.
    echo   用户引入方式:
    echo.
    echo     ^<dependency^>
    echo         ^<groupId^>!JITPACK_GROUP!^</groupId^>
    echo         ^<artifactId^>%ARTIFACT_ID%^</artifactId^>
    echo         ^<version^>%TAG%^</version^>
    echo     ^</dependency^>
)
echo.
echo ============================================================

REM 尝试自动打开 JitPack 页面
if not "%GH_USER%"=="" (
    where start >nul 2>&1
    if not errorlevel 1 (
        echo [INFO] 正在打开 JitPack 构建状态页面...
        start "" "!JITPACK_URL!"
    )
)

endlocal
exit /b 0

:usage
echo.
echo 用法: release.bat ^<版本号^> [选项]
echo.
echo 选项:
echo   --skip-test       跳过测试
echo   --no-snapshot     发布后不自动创建下一个 SNAPSHOT 版本
echo   --branch ^<名^>     指定主分支（默认自动检测）
echo   --remote ^<名^>     指定 git remote（默认 origin）
echo   -h, --help        显示帮助
echo.
echo 示例:
echo   release.bat 0.3.0
echo   release.bat 1.0.0 --skip-test
echo   release.bat 2.0.0 --branch main --no-snapshot
echo.
endlocal
exit /b 0
