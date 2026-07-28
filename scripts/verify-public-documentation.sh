#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
cd "$root"

markdown_files=()
while IFS= read -r path; do
  markdown_files+=("$path")
done < <(
  find . \
    -path './.gradle' -prune -o \
    -path './build' -prune -o \
    -path '*/build' -prune -o \
    -type f -name '*.md' -print |
    LC_ALL=C sort
)

if [[ ${#markdown_files[@]} -eq 0 ]]; then
  echo "仓库中没有找到 Markdown 文档" >&2
  exit 1
fi

for path in "${markdown_files[@]}"; do
  if ! perl -CSD -0777 -e '$text = <>; exit($text =~ /\p{Han}/ ? 0 : 1)' "$path"; then
    echo "Markdown 文档缺少中文内容：$path" >&2
    exit 1
  fi
done

if grep -R -n -E \
  --exclude-dir=.gradle \
  --exclude-dir=build \
  --include='*.md' \
  '/Volumes/Store|/Users/|risingstones-android' \
  .; then
  echo "Markdown 文档包含本机路径或旧工程目录名" >&2
  exit 1
fi

echo "已验证 ${#markdown_files[@]} 个中文 Markdown 文档"
