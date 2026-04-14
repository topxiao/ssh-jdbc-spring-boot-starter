#!/bin/bash
# ============================================================
#  Maven 项目一键发布脚本（通用版）
#
#  自动从 pom.xml 和 git remote 读取项目信息，
#  无需修改脚本即可在任何 Maven + GitHub + JitPack 项目中使用。
#
#  用法:
#    ./release.sh <版本号> [选项]
#    ./release.sh 0.3.0                  # 发布 v0.3.0，自动升级到 0.4.0-SNAPSHOT
#    ./release.sh 0.3.0 --skip-test      # 跳过测试
#    ./release.sh 0.3.0 --branch main    # 指定主分支（默认自动检测）
#    ./release.sh 0.3.0 --no-snapshot    # 发布后不自动升级到下一个 SNAPSHOT
#
#  放置: 复制到任意 Maven 项目根目录（与 pom.xml 同级）即可使用
# ============================================================

set -euo pipefail

# ---------- 颜色 ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
die()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ---------- 默认值 ----------
SKIP_TEST=false
NO_SNAPSHOT=false
GIT_REMOTE="origin"
CUSTOM_BRANCH=""

# ============================================================
# 解析参数
# ============================================================
if [ $# -lt 1 ]; then
    die "用法: ./release.sh <版本号> [选项]

选项:
  --skip-test       跳过测试
  --no-snapshot     发布后不自动创建下一个 SNAPSHOT 版本
  --branch <名>     指定主分支（默认自动检测）
  --remote <名>     指定 git remote（默认 origin）

示例:
  ./release.sh 0.3.0
  ./release.sh 1.0.0 --skip-test
  ./release.sh 2.0.0 --branch main --no-snapshot"
fi

RELEASE_VERSION="$1"
shift
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-test)    SKIP_TEST=true ;;
        --no-snapshot)  NO_SNAPSHOT=true ;;
        --branch)       shift; CUSTOM_BRANCH="$1" ;;
        --remote)       shift; GIT_REMOTE="$1" ;;
        -h|--help)
            echo "用法: ./release.sh <版本号> [--skip-test] [--no-snapshot] [--branch <名>] [--remote <名>]"
            exit 0 ;;
        *) die "未知参数: $1" ;;
    esac
    shift
done

TAG="v${RELEASE_VERSION}"

# 计算下一个 SNAPSHOT 版本
MAJOR=$(echo "$RELEASE_VERSION" | cut -d. -f1)
MINOR=$(echo "$RELEASE_VERSION" | cut -d. -f2)
PATCH=$(echo "$RELEASE_VERSION" | cut -d. -f3)
NEXT_VERSION="${MAJOR}.$((MINOR + 1)).0-SNAPSHOT"

# ============================================================
# Step 0: 项目信息自动检测
# ============================================================
echo ""
echo -e "${BOLD}============================================================${NC}"
echo -e " ${BOLD}Maven 项目一键发布${NC}"
echo -e "${BOLD}============================================================${NC}"
echo ""

info "自动检测项目信息..."

# 检查 pom.xml
[ -f "pom.xml" ] || die "当前目录没有 pom.xml，请在 Maven 项目根目录执行此脚本"
ok "找到 pom.xml"

# 从 pom.xml 提取项目信息
ARTIFACT_ID=$(mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout 2>/dev/null || \
    grep -m1 "<artifactId>" pom.xml | sed 's/.*<artifactId>\([^<]*\)<\/artifactId>.*/\1/')

GROUP_ID=$(mvn help:evaluate -Dexpression=project.groupId -q -DforceStdout 2>/dev/null || \
    grep -m1 "<groupId>" pom.xml | sed 's/.*<groupId>\([^<]*\)<\/groupId>.*/\1/')

CURRENT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || \
    grep -m1 "<version>.*SNAPSHOT\|<version>[0-9]" pom.xml | sed 's/.*<version>\([^<]*\)<\/version>.*/\1/')

ok "项目: ${GROUP_ID}:${ARTIFACT_ID}:${CURRENT_VERSION}"

# 检查 git 仓库
git rev-parse --is-inside-work-tree > /dev/null 2>&1 || die "当前目录不是 git 仓库"

# 自动检测主分支
if [ -n "$CUSTOM_BRANCH" ]; then
    MAIN_BRANCH="$CUSTOM_BRANCH"
else
    # 优先检测远程 HEAD 指向的分支
    MAIN_BRANCH=$(git remote show "$GIT_REMOTE" 2>/dev/null | grep "HEAD branch" | sed 's/.*: //')
    if [ -z "$MAIN_BRANCH" ]; then
        # 备选：检查本地常见分支名
        for b in main master; do
            if git show-ref --verify --quiet "refs/heads/$b" 2>/dev/null; then
                MAIN_BRANCH="$b"
                break
            fi
        done
    fi
    [ -z "$MAIN_BRANCH" ] && MAIN_BRANCH="main"
fi
ok "主分支: ${MAIN_BRANCH} (自动检测)"

# 从 git remote URL 提取 GitHub 用户名和仓库名
REMOTE_URL=$(git remote get-url "$GIT_REMOTE" 2>/dev/null || echo "")

GH_USER=""
REPO_NAME=""
if echo "$REMOTE_URL" | grep -q "github.com"; then
    # 支持 https://github.com/user/repo.git 和 git@github.com:user/repo.git
    GH_USER=$(echo "$REMOTE_URL" | sed -E 's|.*github.com[:/]([^/]+)/.*|\1|')
    REPO_NAME=$(echo "$REMOTE_URL" | sed -E 's|.*github.com[:/][^/]+/([^/.]+)(\.git)?|\1|')
    ok "GitHub: ${GH_USER}/${REPO_NAME}"

    # 如果 groupId 是 com.github.xxx 格式，自动用于 JitPack
    if echo "$GROUP_ID" | grep -q "^com\.github\."; then
        JITPACK_GROUP="$GROUP_ID"
    else
        JITPACK_GROUP="com.github.${GH_USER}"
        warn "groupId 不是 com.github.xxx 格式，JitPack 使用: ${JITPACK_GROUP}"
    fi
else
    JITPACK_GROUP="$GROUP_ID"
    warn "未检测到 GitHub 远程仓库，JitPack 信息可能不准确"
    warn "远程 URL: ${REMOTE_URL:-未配置}"
fi

echo ""
echo -e "${BOLD}============================================================${NC}"
echo -e " ${BOLD}发布计划${NC}"
echo -e "${BOLD}============================================================${NC}"
echo -e "  项目:         ${CYAN}${GROUP_ID}:${ARTIFACT_ID}${NC}"
echo -e "  当前版本:     ${CURRENT_VERSION}"
echo -e "  发布版本:     ${GREEN}${RELEASE_VERSION}${NC}"
echo -e "  Tag:          ${GREEN}${TAG}${NC}"
if [ "$NO_SNAPSHOT" = false ]; then
    echo -e "  后续版本:     ${YELLOW}${NEXT_VERSION}${NC}"
fi
echo -e "  主分支:       ${MAIN_BRANCH}"
echo -e "  跳过测试:     ${SKIP_TEST}"
echo -e "${BOLD}============================================================${NC}"
echo ""

# 二次确认
read -rp "$(echo -e "${BOLD}确认发布？[y/N]${NC} ")" CONFIRM
if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
    die "已取消"
fi
echo ""

# ============================================================
# Step 1: 环境检查
# ============================================================
info "Step 1/6: 环境检查"

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$CURRENT_BRANCH" != "$MAIN_BRANCH" ]; then
    die "当前分支是 '${CURRENT_BRANCH}'，请先切换到 '${MAIN_BRANCH}'
  执行: git checkout ${MAIN_BRANCH}"
fi
ok "当前分支: ${CURRENT_BRANCH}"

if ! git diff --quiet || ! git diff --cached --quiet; then
    die "工作区有未提交的变更，请先提交或 stash
  git status 查看详情
  git stash 暂存变更"
fi
ok "工作区干净"

if ! git ls-remote --exit-code "$GIT_REMOTE" > /dev/null 2>&1; then
    die "无法连接远程仓库 ${GIT_REMOTE}，请检查网络"
fi
ok "远程仓库连接正常"

if git tag -l "$TAG" | grep -q "$TAG"; then
    die "Tag ${TAG} 已存在！
  查看所有 tag: git tag
  删除本地 tag: git tag -d ${TAG}
  删除远程 tag: git push ${GIT_REMOTE} :refs/tags/${TAG}"
fi
ok "Tag ${TAG} 可用"

info "拉取远程最新代码..."
git pull "$GIT_REMOTE" "$MAIN_BRANCH" --ff-only || die "拉取失败或有冲突，请先手动处理"
ok "代码已是最新"
echo ""

# ============================================================
# Step 2: 运行测试
# ============================================================
info "Step 2/6: 运行测试"

if [ "$SKIP_TEST" = true ]; then
    warn "已跳过测试 (--skip-test)"
else
    info "执行 mvn test ..."
    if mvn test; then
        ok "全部测试通过"
    else
        die "测试失败！请修复后再发布
  跳过测试发布: ./release.sh ${RELEASE_VERSION} --skip-test"
    fi
fi
echo ""

# ============================================================
# Step 3: 设置发布版本，提交，打 tag
# ============================================================
info "Step 3/6: 设置发布版本 ${RELEASE_VERSION}"

# 使用 mvn versions:set（最可靠的方式）
if mvn versions:set -DnewVersion="$RELEASE_VERSION" -q 2>/dev/null; then
    ok "mvn versions:set → ${RELEASE_VERSION}"
    # 清理备份文件
    rm -f pom.xml.versionsBackup
else
    # 备用方案：sed 替换
    warn "mvn versions:set 不可用，使用 sed 替换"
    CURRENT_SNAPSHOT=$(grep -m1 "<version>.*-SNAPSHOT</version>" pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
    if [ -n "$CURRENT_SNAPSHOT" ]; then
        sed -i "s|<version>${CURRENT_SNAPSHOT}</version>|<version>${RELEASE_VERSION}</version>|" pom.xml
        ok "pom.xml: ${CURRENT_SNAPSHOT} → ${RELEASE_VERSION}"
    else
        LINE_NUM=$(grep -n "<version>" pom.xml | head -2 | tail -1 | cut -d: -f1)
        OLD_VER=$(sed -n "${LINE_NUM}p" pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
        sed -i "${LINE_NUM}s|<version>${OLD_VER}</version>|<version>${RELEASE_VERSION}</version>|" pom.xml
        ok "pom.xml: ${OLD_VER} → ${RELEASE_VERSION}"
    fi
fi

git add pom.xml
git commit -m "release: ${TAG}" > /dev/null 2>&1
ok "已提交 release: ${TAG}"

git tag "$TAG"
ok "已打 tag: ${TAG}"
echo ""

# ============================================================
# Step 4: 推送代码和 tag
# ============================================================
info "Step 4/6: 推送到远程"

info "推送 ${MAIN_BRANCH} 分支..."
git push "$GIT_REMOTE" "$MAIN_BRANCH"
ok "${MAIN_BRANCH} 分支已推送"

info "推送 tag ${TAG}..."
git push "$GIT_REMOTE" "$TAG"
ok "Tag ${TAG} 已推送"
echo ""

# ============================================================
# Step 5: 升级到下一个 SNAPSHOT 版本
# ============================================================
if [ "$NO_SNAPSHOT" = false ]; then
    info "Step 5/6: 升级到下一个开发版本 ${NEXT_VERSION}"

    if mvn versions:set -DnewVersion="$NEXT_VERSION" -q 2>/dev/null; then
        ok "mvn versions:set → ${NEXT_VERSION}"
        rm -f pom.xml.versionsBackup
    else
        sed -i "s|<version>${RELEASE_VERSION}</version>|<version>${NEXT_VERSION}</version>|" pom.xml
        ok "pom.xml: ${RELEASE_VERSION} → ${NEXT_VERSION}"
    fi

    git add pom.xml
    git commit -m "chore: bump version to ${NEXT_VERSION}" > /dev/null 2>&1
    ok "已提交"

    info "推送 ${MAIN_BRANCH}..."
    git push "$GIT_REMOTE" "$MAIN_BRANCH"
    ok "${MAIN_BRANCH} 分支已推送"
else
    info "Step 5/6: 跳过 (--no-snapshot)"
fi
echo ""

# ============================================================
# Step 6: 触发 JitPack 构建
# ============================================================
info "Step 6/6: JitPack 构建"

if [ -n "$GH_USER" ] && [ -n "$REPO_NAME" ]; then
    JITPACK_URL="https://jitpack.io/#${GH_USER}/${REPO_NAME}/${TAG}"

    echo ""
    echo -e "  ${CYAN}JitPack 状态:${NC} ${JITPACK_URL}"
    echo ""

    # 触发构建
    if command -v curl > /dev/null 2>&1; then
        info "正在触发 JitPack 构建..."
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
            "https://jitpack.io/${JITPACK_GROUP}/${REPO_NAME}/${TAG}/${REPO_NAME}-${TAG}.pom" 2>/dev/null || echo "000")
        if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "404" ]; then
            ok "已触发 JitPack 构建请求 (HTTP ${HTTP_CODE})"
        else
            warn "JitPack 返回 HTTP ${HTTP_CODE}，请手动检查"
        fi
    else
        warn "未找到 curl，请在浏览器打开上面的链接触发构建"
    fi
    warn "JitPack 首次构建需要 1-3 分钟"
else
    warn "未检测到 GitHub 仓库，跳过 JitPack 步骤"
fi
echo ""

# ============================================================
# 完成
# ============================================================
echo -e "${BOLD}============================================================${NC}"
echo -e " ${GREEN}${BOLD}发布完成！${NC}"
echo -e "${BOLD}============================================================${NC}"
echo ""
echo -e "  项目:           ${CYAN}${GROUP_ID}:${ARTIFACT_ID}${NC}"
echo -e "  发布版本:       ${GREEN}${RELEASE_VERSION}${NC}"
echo -e "  Git Tag:        ${GREEN}${TAG}${NC}"
if [ "$NO_SNAPSHOT" = false ]; then
    echo -e "  下个开发版本:   ${YELLOW}${NEXT_VERSION}${NC}"
fi
echo ""
if [ -n "$GH_USER" ]; then
    echo -e "  JitPack 状态:   ${CYAN}${JITPACK_URL}${NC}"
    echo ""
    echo "  用户引入方式:"
    echo ""
    echo "    <dependency>"
    echo "        <groupId>${JITPACK_GROUP}</groupId>"
    echo "        <artifactId>${ARTIFACT_ID}</artifactId>"
    echo "        <version>${TAG}</version>"
    echo "    </dependency>"
fi
echo ""
echo -e "${BOLD}============================================================${NC}"
