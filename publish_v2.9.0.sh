#!/bin/bash
# — TaskFlow v2.9.0 release publisher —
# Commits pending changes, tags v2.9.0, pushes to GitHub, then creates a
# GitHub Release via the REST API and uploads the release APK as an asset.
# Finally regenerates release.json and pushes it back to main.
set -euo pipefail
cd /workspace

# ====== Config ======
TAG="v2.9.0"
VERSION_NAME="2.9.0"
VERSION_CODE="47"
OWNER="Zero-william163"
REPO="TaskFlow-Pro"
# Token: read from the existing origin remote URL (already configured by user),
# NOT hardcoded in this file — avoids GitHub Push Protection secret-scanning blocks.
TOKEN=$(git -C /workspace remote get-url origin \
    | sed -nE 's#.*://[^:]+:([^@]+)@.*#\1#p')
if [ -z "$TOKEN" ]; then
    # Fallback to env var if set externally.
    TOKEN="${GITHUB_TOKEN:-}"
fi
if [ -z "$TOKEN" ]; then
    echo "FATAL: No GitHub token found in origin remote URL or \$GITHUB_TOKEN."
    exit 1
fi
APK_PATH="/workspace/app/build/outputs/apk/release/app-release.apk"
APK_ASSET_NAME="taskflow-${TAG}.apk"

echo "=== [1/6] Verify APK exists ==="
if [ ! -f "$APK_PATH" ]; then
    echo "FATAL: APK not found at $APK_PATH — build first."
    exit 1
fi
APK_SIZE=$(stat -c %s "$APK_PATH")
APK_SHA=$(sha256sum "$APK_PATH" | awk '{print $1}')
echo "APK: $APK_PATH"
echo "Size: $APK_SIZE bytes"
echo "SHA256: $APK_SHA"

echo ""
echo "=== [2/6] Stage source changes (exclude build artifacts) ==="
# Stage all source/CHANGELOG changes; APK / build.log are gitignored and skipped.
git add -A
git status --short | head -30

echo ""
echo "=== [3/6] Commit + tag + push ==="
git config user.name  "TaskFlow Release Bot"
git config user.email "release@taskflow.local"
# Commit only if there are staged changes
if git diff --cached --quiet; then
    echo "No changes to commit — proceeding to tag."
else
    git commit -m "feat: v2.9.0 番茄专注沉浸模式 + 已完成全选删除 + 表单默认值变更 + 任务卡片交互分流

- 番茄专注沉浸页 (PomodoroScreen + ViewModel + AudioPlayerManager)
  · 屏幕常亮 / 环形倒计时 / 双音源背景音 BottomSheet / 励志名言
- 倒计时完成自动写入 focus_history 表, StatisticsScreen 同步专注统计图表
- 已完成 Tab 强制渲染清空全部工具栏 + 单条彻底删除确认弹窗
- DatePicker usePlatformDefaultWidth=false 修复数字裁剪
- DueDate 改为可选 (hasDueDate), 默认关闭无限期执行
- 提醒默认开启, 频率默认每日
- 专注时长 FilterChips (10/25/35/自定义), TaskEntity.focusDurationMinutes
- TaskCard 三段点击分流: Checkbox 切换 / Edit 图标编辑 / 卡片主体跳番茄页
- 数据库 v6→v7 迁移 + FocusHistoryEntity/Dao/Repository"
fi

# Delete local tag if it already exists (idempotent re-run), then create fresh.
git tag -d "$TAG" 2>/dev/null || true
git tag "$TAG"
git push origin "$TAG" --force
git push origin main

echo ""
echo "=== [4/6] Create GitHub Release via REST API ==="
# Extract release notes from CHANGELOG.md (the v2.9.0 section).
NOTES_FILE=/tmp/release_notes_v2.9.0.md
awk -v tag="$TAG" '
    BEGIN { capture=0 }
    /^## / {
        if (capture) exit
        if (index($0, tag) > 0) { capture=1; next }
    }
    capture { print }
' CHANGELOG.md > "$NOTES_FILE"

echo "Release notes ($(wc -l < "$NOTES_FILE") lines):"
head -5 "$NOTES_FILE"
echo "..."

# Build JSON payload (use jq for safe quoting).
PAYLOAD=$(jq -n \
    --arg tag "$TAG" \
    --arg name "$TAG" \
    --rawfile body "$NOTES_FILE" \
    '{tag_name:$tag, name:$name, body:$body, draft:false, prerelease:false}')

CREATE_RESP=$(curl -sS -X POST \
    -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com/repos/$OWNER/$REPO/releases" \
    -d "$PAYLOAD")

RELEASE_ID=$(echo "$CREATE_RESP" | jq -r .id)
UPLOAD_URL_BASE=$(echo "$CREATE_RESP" | jq -r .upload_url | sed 's/{?name,label}//')
HTML_URL=$(echo "$CREATE_RESP" | jq -r .html_url)

if [ -z "$RELEASE_ID" ] || [ "$RELEASE_ID" = "null" ]; then
    echo "FATAL: Failed to create release. Response:"
    echo "$CREATE_RESP" | head -40
    exit 1
fi
echo "Created release ID=$RELEASE_ID"
echo "HTML: $HTML_URL"
echo "Upload URL: $UPLOAD_URL_BASE"

echo ""
echo "=== [5/6] Upload APK asset ==="
UPLOAD_RESP=$(curl -sS -X POST \
    -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$APK_PATH" \
    "${UPLOAD_URL_BASE}?name=${APK_ASSET_NAME}")

ASSET_DOWNLOAD_URL=$(echo "$UPLOAD_RESP" | jq -r .browser_download_url)
if [ -z "$ASSET_DOWNLOAD_URL" ] || [ "$ASSET_DOWNLOAD_URL" = "null" ]; then
    echo "FATAL: Asset upload failed. Response:"
    echo "$UPLOAD_RESP" | head -40
    exit 1
fi
echo "Asset uploaded: $ASSET_DOWNLOAD_URL"

echo ""
echo "=== [6/6] Regenerate release.json + commit to main ==="
GITHUB_DOWNLOAD_URL="https://github.com/$OWNER/$REPO/releases/download/$TAG/$APK_ASSET_NAME"
GH_PROXY_URL="https://gh-proxy.com/$GITHUB_DOWNLOAD_URL"
GH_FAST_URL="https://ghfast.top/$GITHUB_DOWNLOAD_URL"
LOG_ESCAPED=$(jq -Rs '.' < "$NOTES_FILE")

jq -n \
    --arg version "$VERSION_NAME" \
    --argjson code "$VERSION_CODE" \
    --arg tag "$TAG" \
    --arg log "$LOG_ESCAPED" \
    --arg sha256 "$APK_SHA" \
    --argjson size "$APK_SIZE" \
    --arg gh "$GITHUB_DOWNLOAD_URL" \
    --arg ghproxy "$GH_PROXY_URL" \
    --arg ghfast "$GH_FAST_URL" \
    '{
        version:$version,
        code:$code,
        versionTag:$tag,
        log:$log,
        apk:$gh,
        sha256:$sha256,
        size:$size,
        downloadUrls:[
            {name:"GH Proxy", url:$ghproxy, region:"domestic"},
            {name:"GH Fast",  url:$ghfast,  region:"cdn"},
            {name:"GitHub",   url:$gh,      region:"international"}
        ]
    }' > /workspace/release.json

echo "release.json preview:"
head -c 400 /workspace/release.json; echo

git add release.json
git commit -m "chore(release): update release.json for $TAG"
git push origin main

echo ""
echo "==============================================="
echo "✅ Release $TAG published successfully!"
echo "==============================================="
echo "Release URL : $HTML_URL"
echo "APK URL     : $ASSET_DOWNLOAD_URL"
echo "SHA256      : $APK_SHA"
echo "Size        : $APK_SIZE bytes ($(( APK_SIZE / 1024 / 1024 )) MB)"
