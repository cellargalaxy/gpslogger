#!/usr/bin/env sh
set -eu

DEFAULT_UPSTREAM_URL="https://github.com/mendhak/gpslogger.git"

UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-upstream}"
UPSTREAM_URL="${UPSTREAM_URL:-$DEFAULT_UPSTREAM_URL}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-master}"
TARGET_BRANCH="${TARGET_BRANCH:-}"
SYNC_MODE="${SYNC_MODE:-merge}"
PUSH_REMOTE="${PUSH_REMOTE:-origin}"
PUSH="${PUSH:-0}"
RUN_TESTS="${RUN_TESTS:-0}"
ALLOW_DIRTY="${ALLOW_DIRTY:-0}"
DRY_RUN=0

usage() {
    cat <<'EOF'
用法:
  scripts/merge-official-master.sh [选项]

默认动作:
  1. 确认当前仓库没有未提交改动；
  2. 确认或添加 upstream=https://github.com/mendhak/gpslogger.git；
  3. 拉取 upstream/master；
  4. 将 upstream/master 合入目标分支，目标分支默认是当前分支。

选项:
  --target-branch <branch>     指定要合入官方代码的本地分支，默认当前分支
  --upstream-remote <name>     指定官方远端名，默认 upstream
  --upstream-url <url>         指定官方仓库地址，默认 https://github.com/mendhak/gpslogger.git
  --upstream-branch <branch>   指定官方分支，默认 master
  --mode <merge|rebase>        同步方式，默认 merge；rebase 会改写本地提交历史
  --push                       成功后推送目标分支到 origin，默认不推送
  --push-remote <name>         --push 使用的推送远端，默认 origin
  --run-tests                  成功合入后执行 ./gradlew test
  --allow-dirty                允许在有未提交改动时执行，默认拒绝
  --dry-run                    只打印计划执行的命令，不联网、不改远端、不合并
  -h, --help                   显示帮助

也可以用同名环境变量覆盖默认值，例如:
  TARGET_BRANCH=my-custom PUSH=1 scripts/merge-official-master.sh
EOF
}

die() {
    printf '错误：%s\n' "$*" >&2
    exit 1
}

info() {
    printf '==> %s\n' "$*"
}

run() {
    if [ "$DRY_RUN" = "1" ]; then
        printf '+'
        while [ "$#" -gt 0 ]; do
            printf ' %s' "$1"
            shift
        done
        printf '\n'
    else
        "$@"
    fi
}

official_gpslogger_url() {
    case "$1" in
        https://github.com/mendhak/gpslogger|\
        https://github.com/mendhak/gpslogger.git|\
        git@github.com:mendhak/gpslogger.git|\
        ssh://git@github.com/mendhak/gpslogger.git)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

validate_bool() {
    name=$1
    value=$2
    case "$value" in
        0|1)
            ;;
        *)
            die "$name 只能是 0 或 1，当前是 $value"
            ;;
    esac
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --target-branch)
            [ "$#" -ge 2 ] || die "$1 需要一个分支名"
            TARGET_BRANCH=$2
            shift 2
            ;;
        --upstream-remote)
            [ "$#" -ge 2 ] || die "$1 需要一个远端名"
            UPSTREAM_REMOTE=$2
            shift 2
            ;;
        --upstream-url)
            [ "$#" -ge 2 ] || die "$1 需要一个仓库地址"
            UPSTREAM_URL=$2
            shift 2
            ;;
        --upstream-branch)
            [ "$#" -ge 2 ] || die "$1 需要一个分支名"
            UPSTREAM_BRANCH=$2
            shift 2
            ;;
        --mode)
            [ "$#" -ge 2 ] || die "$1 需要 merge 或 rebase"
            SYNC_MODE=$2
            shift 2
            ;;
        --push)
            PUSH=1
            shift
            ;;
        --push-remote)
            [ "$#" -ge 2 ] || die "$1 需要一个远端名"
            PUSH_REMOTE=$2
            shift 2
            ;;
        --run-tests)
            RUN_TESTS=1
            shift
            ;;
        --allow-dirty)
            ALLOW_DIRTY=1
            shift
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "未知选项：$1。运行 --help 查看用法"
            ;;
    esac
done

case "$SYNC_MODE" in
    merge|rebase)
        ;;
    *)
        die "--mode 只能是 merge 或 rebase，当前是 $SYNC_MODE"
        ;;
esac

validate_bool PUSH "$PUSH"
validate_bool RUN_TESTS "$RUN_TESTS"
validate_bool ALLOW_DIRTY "$ALLOW_DIRTY"

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "当前目录不在 git 仓库内"
cd "$REPO_ROOT"

GIT_DIR=$(git rev-parse --git-dir)
CURRENT_BRANCH=$(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)

if [ -z "$TARGET_BRANCH" ]; then
    [ -n "$CURRENT_BRANCH" ] || die "当前处于 detached HEAD，请用 --target-branch 指定目标分支"
    TARGET_BRANCH=$CURRENT_BRANCH
fi

if [ -f "$GIT_DIR/MERGE_HEAD" ]; then
    die "检测到未完成的 merge，请先解决冲突并提交，或执行 git merge --abort"
fi

if [ -d "$GIT_DIR/rebase-merge" ] || [ -d "$GIT_DIR/rebase-apply" ]; then
    die "检测到未完成的 rebase，请先完成，或执行 git rebase --abort"
fi

if [ "$ALLOW_DIRTY" != "1" ]; then
    if [ -n "$(git status --porcelain --untracked-files=normal)" ]; then
        git status --short
        if [ "$DRY_RUN" = "1" ]; then
            info "工作区不干净；真实执行时会停止，请先提交/贮藏改动，或显式加 --allow-dirty"
        else
            die "工作区不干净，请先提交或贮藏改动；确认要继续时可加 --allow-dirty"
        fi
    fi
fi

if [ -n "$CURRENT_BRANCH" ] && [ "$CURRENT_BRANCH" != "$TARGET_BRANCH" ]; then
    info "切换到目标分支 $TARGET_BRANCH"
    run git checkout "$TARGET_BRANCH"
elif [ -z "$CURRENT_BRANCH" ]; then
    info "当前为 detached HEAD，将切换到目标分支 $TARGET_BRANCH"
    run git checkout "$TARGET_BRANCH"
fi

if existing_url=$(git remote get-url "$UPSTREAM_REMOTE" 2>/dev/null); then
    if [ "$existing_url" = "$UPSTREAM_URL" ]; then
        info "官方远端 $UPSTREAM_REMOTE 已存在：$existing_url"
    elif official_gpslogger_url "$existing_url" && official_gpslogger_url "$UPSTREAM_URL"; then
        info "官方远端 $UPSTREAM_REMOTE 已存在：$existing_url"
    else
        die "远端 $UPSTREAM_REMOTE 已存在但 URL 是 $existing_url；请改用 --upstream-remote，或确认后手动调整该远端"
    fi
else
    info "添加官方远端 $UPSTREAM_REMOTE：$UPSTREAM_URL"
    run git remote add "$UPSTREAM_REMOTE" "$UPSTREAM_URL"
fi

UPSTREAM_REF="refs/remotes/$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"

info "拉取 $UPSTREAM_REMOTE/$UPSTREAM_BRANCH"
run git fetch --prune "$UPSTREAM_REMOTE" "+refs/heads/$UPSTREAM_BRANCH:$UPSTREAM_REF"

if [ "$DRY_RUN" != "1" ]; then
    git rev-parse --verify "$UPSTREAM_REF^{commit}" >/dev/null || die "未找到 $UPSTREAM_REMOTE/$UPSTREAM_BRANCH，请确认远端分支存在"
fi

if [ "$SYNC_MODE" = "merge" ]; then
    info "合入 $UPSTREAM_REMOTE/$UPSTREAM_BRANCH 到 $TARGET_BRANCH"
    if [ "$DRY_RUN" = "1" ]; then
        run git merge --no-edit "$UPSTREAM_REF"
    elif ! git merge --no-edit "$UPSTREAM_REF"; then
        printf '%s\n' '合并冲突处理提示：' >&2
        printf '%s\n' '  1. 处理冲突文件；' >&2
        printf '%s\n' '  2. git add <已解决文件>；' >&2
        printf '%s\n' '  3. git merge --continue；' >&2
        printf '%s\n' '  如需放弃本次合并：git merge --abort' >&2
        exit 1
    fi
else
    info "将 $TARGET_BRANCH rebase 到 $UPSTREAM_REMOTE/$UPSTREAM_BRANCH"
    if [ "$DRY_RUN" = "1" ]; then
        run git rebase "$UPSTREAM_REF"
    elif ! git rebase "$UPSTREAM_REF"; then
        printf '%s\n' 'rebase 冲突处理提示：' >&2
        printf '%s\n' '  1. 处理冲突文件；' >&2
        printf '%s\n' '  2. git add <已解决文件>；' >&2
        printf '%s\n' '  3. git rebase --continue；' >&2
        printf '%s\n' '  如需放弃本次 rebase：git rebase --abort' >&2
        exit 1
    fi
fi

if [ "$RUN_TESTS" = "1" ]; then
    info "执行测试：./gradlew test"
    run ./gradlew test
fi

if [ "$PUSH" = "1" ]; then
    info "推送 $TARGET_BRANCH 到 $PUSH_REMOTE"
    run git push "$PUSH_REMOTE" "$TARGET_BRANCH"
fi

if [ "$DRY_RUN" = "1" ]; then
    info "预演完成，未修改仓库"
else
    info "完成：$TARGET_BRANCH 已同步 $UPSTREAM_REMOTE/$UPSTREAM_BRANCH"
    if [ "$PUSH" != "1" ]; then
        info "本次未推送 fork；需要推送时可执行：git push $PUSH_REMOTE $TARGET_BRANCH"
    fi
fi
