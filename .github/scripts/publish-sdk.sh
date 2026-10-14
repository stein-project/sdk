#!/usr/bin/env bash
# Publishes the SDK built by release.yml in this repository, with the token in GH_TOKEN: the Gradle plugin and
# stein-api on its "maven" branch, and the release (dist/ and notes.md).
#
#   publish-sdk.sh <owner/repo> <commit author name> <commit author email>
#   env: VERSION, LEGACY_REPO (optional: the frozen repository whose public "maven" branch is copied, read only, the
#        first time; it is never written)
# Run from the workspace root (sdk/ is the checkout of this repository).
set -euo pipefail
REPO="${1:?repository}"; NAME="${2:?name}"; EMAIL="${3:?email}"
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/dist.sh"
: "${VERSION:?}" "${GH_TOKEN:?}"
url="https://x-access-token:${GH_TOKEN}@github.com/$REPO.git"

echo "== Stein SDK $VERSION -> $REPO"

# --- the Gradle plugin: the "maven" branch is a Maven repository ---
# Read through raw.githubusercontent.com/<owner>/<repo>/maven with no login (the template's settings.gradle). The
# plugin is published INSIDE a copy of the branch, not copied onto it: Gradle only adds, the new version next to the
# old ones and maven-metadata.xml with all of them. Nothing there is ever deleted: an old mod project keeps finding the
# version it asks for. The first time (the branch does not exist here yet) it starts as a copy of the frozen
# repository's public one (read only), so the versions published before the move are served from the new address too.
work="$(mktemp -d)"
if git clone -q --branch maven --single-branch "$url" "$work/maven" 2>/dev/null; then
  :
elif [ -n "${LEGACY_REPO:-}" ] && [ "$LEGACY_REPO" != "$REPO" ] \
    && git clone -q --branch maven --single-branch "https://github.com/$LEGACY_REPO.git" "$work/maven" 2>/dev/null; then
  echo "maven branch started from $LEGACY_REPO"
  sed -i "s|raw.githubusercontent.com/$LEGACY_REPO/maven|raw.githubusercontent.com/$REPO/maven|g; s|github.com/$LEGACY_REPO|github.com/$REPO|g" \
    "$work/maven/README.md" 2>/dev/null || true
else
  git init -q -b maven "$work/maven"
fi
# Byte for byte: the .sha1 next to every file must keep matching (git on Windows would turn LF into CRLF).
printf '* -text\n' > "$work/maven/.gitattributes"
if [ ! -f "$work/maven/README.md" ]; then
  printf '%s\n' "# Stein SDK — Maven repository" "" \
    "The Gradle plugin \`dev.xavier.stein.mod\` and \`dev.xavier.stein:stein-api\`, served from this branch." \
    "Use it from \`settings.gradle\`:" "" '```groovy' "pluginManagement {" "    repositories {" \
    "        maven { url = uri(\"https://raw.githubusercontent.com/$REPO/maven\") }" \
    "        gradlePluginPortal()" "    }" "}" '```' "" "See the guide on the [main branch](https://github.com/$REPO)." \
    > "$work/maven/README.md"
fi
(cd sdk && ./gradlew --no-daemon -q publishPlugin -PsteinRepo="$work/maven")
(
  cd "$work/maven"
  git add -A
  # Add, never delete nor replace what is out: a published version does not change (the stein-api of the same Loader
  # version rebuilt by a new SDK, or a repeated attempt). What was there stays; only the version index
  # (maven-metadata.xml and its sums) is updated.
  velhos="$(git diff --cached --name-only --diff-filter=DM | grep -vE 'maven-metadata\.xml' || true)"
  if [ -n "$velhos" ]; then
    echo "already published, kept as they were:"; printf '   %s\n' $velhos
    printf '%s\n' "$velhos" | xargs git reset -q HEAD --
    printf '%s\n' "$velhos" | xargs git checkout -q HEAD --
  fi
  # No new file (the same version again): maven-metadata.xml would only change its time, nothing to commit. A branch
  # copied from the frozen repository is still pushed when this repository does not have it yet.
  novo=0
  if [ -n "$(git diff --cached --name-only --diff-filter=A)" ]; then
    git -c user.name="$NAME" -c user.email="$EMAIL" commit -q -m "Stein SDK $VERSION (Gradle plugin)"
    novo=1
  else
    git reset -q --hard 2>/dev/null || true
  fi
  if [ "$novo" = 1 ] || ! git ls-remote --exit-code --heads "$url" maven >/dev/null 2>&1; then
    git push -q "$url" HEAD:maven
    echo "Gradle plugin published (maven branch)"
  else
    echo "maven branch was already up to date"
  fi
)
rm -rf "$work"

# --- the release, last: the SDK counts as published only once it exists ---
dist_release "$REPO" "v$VERSION" "Stein SDK $VERSION" notes.md 0 dist/*
