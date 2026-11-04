#!/usr/bin/env bash
# 根据「上一个 v0.1.* tag..HEAD」的 commit 自动生成 Release notes。
# 单个 commit 时展开它的正文（列表项）；多个 commit 时按关键词归类。
#
# 用法：release-notes.sh v0.1.42 release-notes.md
set -euo pipefail

tag="${1:?用法: release-notes.sh <tag> <output>}"
out="${2:?用法: release-notes.sh <tag> <output>}"
repo="${GITHUB_REPOSITORY:-}"
version="${tag#v}"

prev="$(git describe --tags --abbrev=0 --match 'v0.1.*' 2>/dev/null || true)"
if [ "$prev" = "$tag" ]; then
  prev="$(git describe --tags --abbrev=0 --match 'v0.1.*' "${tag}^" 2>/dev/null || true)"
fi

if [ -n "$prev" ]; then
  range="${prev}..HEAD"
  compare="https://github.com/${repo}/compare/${prev}...${tag}"
else
  range="HEAD~1..HEAD"
  compare="https://github.com/${repo}/commits/${tag}"
fi

# 初始 commit / 浅克隆等情况下没有 HEAD~1
if ! git rev-parse "${range%%..*}" >/dev/null 2>&1; then
  range="HEAD"
fi

count="$(git rev-list --count --no-merges "$range")"
strip_prefix() {
  sed -E 's/^(Android|CI|文档|Docs)[[:space:]]*[：:][[:space:]]?//'
}

{
  echo "## ${tag}"
  echo

  if [ "$count" -le 1 ]; then
    # count=0 时通常只有一条 merge commit，也取出它的标题和正文
    subject="$(git log "$range" -1 --pretty=format:'%s' | strip_prefix)"
    body="$(git log "$range" -1 --pretty=format:'%b' \
      | sed -E '/^[Cc]o-[Aa]uthored-[Bb]y:/d' \
      | sed -E 's/[[:space:]]+$//' \
      | sed '/^$/d')"
    echo "### ${subject:-本次构建}"
    echo
    if [ -n "$body" ]; then
      echo "$body"
      echo
    fi
  else
    feat=()
    fix=()
    tune=()
    other=()
    # git log --pretty 不会在最后一条后换行，read 需要兼容这种 EOF
    while IFS= read -r subject || [ -n "$subject" ]; do
      [ -n "$subject" ] || continue
      case "$subject" in
        *新增* | *添加* | *支持* | *上线* | feat* | Feat* | *feature*) feat+=("- $subject") ;;
        *修复* | *修正* | *修好* | *修掉* | *解决* | *bug* | *Bug* | fix* | Fix*) fix+=("- $subject") ;;
        *优化* | *调整* | *改进* | *重构* | *性能* | perf* | Perf* | *refactor*) tune+=("- $subject") ;;
        *) other+=("- $subject") ;;
      esac
    done < <(git log "$range" --no-merges --pretty=format:'%s' | strip_prefix)

    if [ ${#feat[@]} -gt 0 ]; then
      echo "### 新功能"
      printf '%s\n' "${feat[@]}"
      echo
    fi
    if [ ${#fix[@]} -gt 0 ]; then
      echo "### 修复"
      printf '%s\n' "${fix[@]}"
      echo
    fi
    if [ ${#tune[@]} -gt 0 ]; then
      echo "### 优化"
      printf '%s\n' "${tune[@]}"
      echo
    fi
    if [ ${#other[@]} -gt 0 ]; then
      echo "### 其他"
      printf '%s\n' "${other[@]}"
      echo
    fi
  fi

  echo "### 安装"
  echo
  echo "- \`claudroid-${version}.apk\`（arm64-v8a / x86_64）"
  echo "- 校验：\`claudroid-${version}.apk.sha256\`"
  echo "- 覆盖安装会保留设置与会话；若签名不同，请先卸载旧版。"
  echo
  if [ -n "$repo" ]; then
    echo "[查看完整对比](${compare})"
  fi
} >"$out"
