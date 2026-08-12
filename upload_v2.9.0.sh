#!/bin/bash
# — TaskFlow v2.9.0: create GitHub Release + upload local APK + update release.json —
# Runs after the source commit + tag push already succeeded.
# Local APK is signed with the bundled release keystore (CN=TaskFlow, OU=Dev).
set -euo pipefail
cd /workspace

# ====== Config ======
TAG="v2.9.0"
VERSION_NAME="2.9.0"
VERSION_CODE="47"
OWNER="Zero-william163"
REPO="TaskFlow-Pro"
# Token extracted at runtime from the origin remote URL — never hardcoded.
TOKEN=$(git remote get-url origin | sed -nE 's#.*://[^:]+:([^@]+)@.*#\1#p')
APK_PATH="/workspace/app/build/outputs/apk/release/app-release.apk"
APK_ASSET_NAME="taskflow-${TAG}.apk"

echo "=== [1/5] Verify APK + compute metadata ==="
[ -f "$APK_PATH" ] || { echo "FATAL: APK missing"; exit 1; }
APK_SIZE=$(stat -c %s "$APK_PATH")
APK_SHA=$(sha256sum "$APK_PATH" | awk '{print $1}')
echo "APK   : $APK_PATH"
echo "Size  : $APK_SIZE bytes ($(( APK_SIZE / 1024 / 1024 )) MB)"
echo "SHA256: $APK_SHA"
echo "Token : ${TOKEN:0:7}****(redacted)"

echo ""
echo "=== [2/5] Check if Release v2.9.0 already exists; create if not ==="
EXISTING=$(curl -sS -o /tmp/existing.json -w "%{http_code}" \
    -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$TAG")
if [ "$EXISTING" = "200" ]; then
    RELEASE_ID=$(python3 -c "import json; print(json.load(open('/tmp/existing.json'))['id'])")
    echo "Release already exists (id=$RELEASE_ID) — will upload asset to it."
    # Delete any existing asset with the same name to allow re-upload.
    EXISTING_ASSET_ID=$(python3 -c "
import json
d=json.load(open('/tmp/existing.json'))
for a in d.get('assets', []):
    if a['name'] == '$APK_ASSET_NAME':
        print(a['id']); break
" 2>/dev/null || echo "")
    if [ -n "$EXISTING_ASSET_ID" ]; then
        echo "Deleting existing asset id=$EXISTING_ASSET_ID..."
        curl -sS -X DELETE \
            -H "Authorization: token $TOKEN" \
            "https://api.github.com/repos/$OWNER/$REPO/releases/assets/$EXISTING_ASSET_ID" \
            -o /dev/null -w "HTTP %{http_code}\n"
    fi
    UPLOAD_URL_BASE=$(python3 -c "import json; print(json.load(open('/tmp/existing.json'))['upload_url'].split('{')[0])")
    HTML_URL=$(python3 -c "import json; print(json.load(open('/tmp/existing.json'))['html_url'])")
else
    echo "Creating new Release for $TAG..."
    NOTES_FILE=/tmp/release_notes_v2.9.0.md
    awk -v tag="$TAG" '
        BEGIN { capture=0 }
        /^## / {
            if (capture) exit
            if (index($0, tag) > 0) { capture=1; next }
        }
        capture { print }
    ' CHANGELOG.md > "$NOTES_FILE"
    echo "Notes: $(wc -l < "$NOTES_FILE") lines"

    # Build JSON payload safely with Python.
    PAYLOAD=$(python3 -c "
import json
with open('$NOTES_FILE') as f:
    body = f.read()
print(json.dumps({
    'tag_name': '$TAG',
    'name': '$TAG',
    'body': body,
    'draft': False,
    'prerelease': False
}))
")
    CREATE_RESP=$(curl -sS -X POST \
        -H "Authorization: token $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "https://api.github.com/repos/$OWNER/$REPO/releases" \
        -d "$PAYLOAD")
    echo "$CREATE_RESP" > /tmp/create.json
    RELEASE_ID=$(python3 -c "import json; print(json.load(open('/tmp/create.json'))['id'])")
    UPLOAD_URL_BASE=$(python3 -c "import json; print(json.load(open('/tmp/create.json'))['upload_url'].split('{')[0])")
    HTML_URL=$(python3 -c "import json; print(json.load(open('/tmp/create.json'))['html_url'])")
    echo "Created release id=$RELEASE_ID"
fi
echo "HTML URL: $HTML_URL"
echo "Upload URL base: $UPLOAD_URL_BASE"

echo ""
echo "=== [3/5] Upload APK asset ==="
UPLOAD_RESP=$(curl -sS -X POST \
    -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$APK_PATH" \
    "${UPLOAD_URL_BASE}?name=${APK_ASSET_NAME}")
echo "$UPLOAD_RESP" > /tmp/upload.json
ASSET_DOWNLOAD_URL=$(python3 -c "import json; print(json.load(open('/tmp/upload.json'))['browser_download_url'])")
ASSET_SIZE=$(python3 -c "import json; print(json.load(open('/tmp/upload.json'))['size'])")
echo "Asset uploaded: $ASSET_DOWNLOAD_URL"
echo "Asset size    : $ASSET_SIZE bytes"

echo ""
echo "=== [4/5] Regenerate release.json ==="
NOTES_FILE=/tmp/release_notes_v2.9.0.md
awk -v tag="$TAG" '
    BEGIN { capture=0 }
    /^## / {
        if (capture) exit
        if (index($0, tag) > 0) { capture=1; next }
    }
    capture { print }
' CHANGELOG.md > "$NOTES_FILE"

GITHUB_DOWNLOAD_URL="https://github.com/$OWNER/$REPO/releases/download/$TAG/$APK_ASSET_NAME"
GH_PROXY_URL="https://gh-proxy.com/$GITHUB_DOWNLOAD_URL"
GH_FAST_URL="https://ghfast.top/$GITHUB_DOWNLOAD_URL"

python3 -c "
import json
with open('$NOTES_FILE') as f:
    log = f.read()
data = {
    'version': '$VERSION_NAME',
    'code': $VERSION_CODE,
    'versionTag': '$TAG',
    'log': log,
    'apk': '$GITHUB_DOWNLOAD_URL',
    'sha256': '$APK_SHA',
    'size': $APK_SIZE,
    'downloadUrls': [
        {'name': 'GH Proxy', 'url': '$GH_PROXY_URL', 'region': 'domestic'},
        {'name': 'GH Fast',  'url': '$GH_FAST_URL',  'region': 'cdn'},
        {'name': 'GitHub',   'url': '$GITHUB_DOWNLOAD_URL', 'region': 'international'}
    ]
}
with open('/workspace/release.json', 'w') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print('release.json written')
"
head -c 400 /workspace/release.json; echo

echo ""
echo "=== [5/5] Commit release.json to main ==="
git add release.json
if git diff --cached --quiet; then
    echo "release.json unchanged"
else
    git commit -m "chore(release): update release.json for $TAG"
    git push origin main
    echo "Pushed."
fi

echo ""
echo "==============================================="
echo "✅ Release $TAG published successfully!"
echo "==============================================="
echo "Release URL : $HTML_URL"
echo "APK URL     : $ASSET_DOWNLOAD_URL"
echo "SHA256      : $APK_SHA"
echo "Size        : $APK_SIZE bytes ($(( APK_SIZE / 1024 / 1024 )) MB)"
