#!/usr/bin/env bash
# Publishes the SDK built by release.yml in ONE distribution repository: the guide (page/) on its main branch, the
# Gradle plugin and stein-api on its "maven" branch, and the release (dist/ and notes.md). Called once for
# Stein-Project/stein-sdk and once for the transition mirror (x4vieer/stein-sdk), each with its token in GH_TOKEN.
# Ported from the sdk part of stein-master tools/publish.sh.
#
#   publish-sdk.sh <owner/stein-sdk> <secret name> <commit author name> <commit author email>
#   env: VERSION, SEED_OWNER (optional: where the "maven" branch is copied from the first time, the mirror)
# Run from the workspace root (sdk/ is the checkout of this repository).
set -euo pipefail
REPO="${1:?repository}"; SECRET="${2:?secret}"; NAME="${3:?name}"; EMAIL="${4:?email}"
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/dist.sh"
dist_need_token "$SECRET"
: "${VERSION:?}"
url="https://x-access-token:${GH_TOKEN}@github.com/$REPO.git"

echo "== Stein SDK $VERSION -> $REPO"

# --- the guide, the licence, the notices and the example mod, on main ---
# Before the release: GitHub does not create a release in an empty repository (there is no commit for the tag).
dist_push_dir "$REPO" main page "Stein SDK $VERSION" "$NAME" "$EMAIL"

# --- the Gradle plugin: the "maven" branch is a Maven repository ---
# Read through raw.githubusercontent.com/<owner>/stein-sdk/maven with no login (the template's settings.gradle). The
# plugin is published INSIDE a copy of the branch, not copied onto it: Gradle only adds, the new version next to the
# old ones and maven-metadata.xml with all of them. Nothing there is ever deleted: an old mod project keeps finding the
# version it asks for. The first time (the organisation's branch does not exist yet) the branch starts as a copy of
# the mirror's, so the versions published before the move are served from the new address too.
work="$(mktemp -d)"
if git clone -q --branch maven --single-branch "$url" "$work/maven" 2>/dev/null; then
  :
elif [ -n "${SEED_OWNER:-}" ] && [ "$SEED_OWNER/stein-sdk" != "$REPO" ] \
    && git clone -q --branch maven --single-branch "https://github.com/$SEED_OWNER/stein-sdk.git" "$work/maven" 2>/dev/null; then
  echo "maven branch started from $SEED_OWNER/stein-sdk"
  sed -i "s|raw.githubusercontent.com/$SEED_OWNER/stein-sdk/maven|raw.githubusercontent.com/$REPO/maven|g; s|github.com/$SEED_OWNER/stein-sdk|github.com/$REPO|g" \
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
  # copied from the mirror is still pushed when this repository does not have it yet.
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
