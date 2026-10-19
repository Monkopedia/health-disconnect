#!/usr/bin/env bash
# Upload an AAB to a Google Play track through the Android Publisher v3 API.
#
# Env: ACCESS_TOKEN, PACKAGE, TRACK (empty = list tracks only), RELEASE_STATUS, DRY_RUN, TAG.
# Arg: path to the .aab.
#
# Flow: insert an edit -> list tracks -> upload bundle -> assign to track -> validate ->
#       commit (or, on a dry run, delete the edit so nothing is published).
set -euo pipefail
# Without this, `set -e` is off inside $(...), so a failed api call in VAR=$(api ...) would be ignored.
shopt -s inherit_errexit

AAB="${1:?usage: play-upload.sh <file.aab>}"
[ -f "$AAB" ] || { echo "::error::AAB not found: $AAB"; exit 1; }
: "${ACCESS_TOKEN:?}" "${PACKAGE:?}"
TRACK="${TRACK:-}"
RELEASE_STATUS="${RELEASE_STATUS:-completed}"
# Fail closed: anything other than exactly "false" is a dry run.
[ "${DRY_RUN:-true}" = "false" ] && DRY_RUN=false || DRY_RUN=true
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

# Testing tracks only. The service account has no production grant either; this is a second lock.
if [ "$TRACK" = "production" ]; then
  echo "::error::Refusing to upload to the production track."; exit 1
fi

# PLAY_API_BASE exists only so the script can be exercised against a local mock.
BASE="${PLAY_API_BASE:-https://androidpublisher.googleapis.com}"
API="$BASE/androidpublisher/v3/applications/$PACKAGE"
UPLOAD="$BASE/upload/androidpublisher/v3/applications/$PACKAGE"

# api METHOD URL [curl args...] -> prints the response body; on HTTP >= 300 prints the error body
# (Play's error JSON, which carries no credentials) and fails. The token is never echoed.
api() {
  local method=$1 url=$2; shift 2
  local body code
  body=$(mktemp)
  # `|| true`: a transport failure leaves code=000, which is handled below instead of aborting
  # before the error is reported.
  code=$(curl -sS --max-time 600 -o "$body" -w '%{http_code}' -X "$method" \
    -H "Authorization: Bearer $ACCESS_TOKEN" "$@" "$url" || true)
  if [ "$code" = "000" ] || [ "$code" -ge 300 ]; then
    echo "::error::$method ${url#"$BASE"} -> HTTP $code" >&2
    cat "$body" >&2
    rm -f "$body"
    return 1
  fi
  cat "$body"
  rm -f "$body"
}

EDIT=""
COMMITTED=0
cleanup() {
  if [ -n "$EDIT" ] && [ "$COMMITTED" -eq 0 ]; then
    api DELETE "$API/edits/$EDIT" >/dev/null 2>&1 && echo "Discarded edit $EDIT (nothing published)." \
      || echo "::warning::Could not delete edit $EDIT; Play discards uncommitted edits on its own."
  fi
}
trap cleanup EXIT

EDIT=$(api POST "$API/edits" -H 'Content-Type: application/json' -d '{}' | jq -er '.id')
echo "Opened edit $EDIT for $PACKAGE"

TRACKS_JSON=$(api GET "$API/edits/$EDIT/tracks")
{
  echo "## Play tracks for \`$PACKAGE\`"
  echo
  jq -r <<<"$TRACKS_JSON" '
    .tracks[]? | "- **\(.track)**: " +
      ([.releases[]? | "\(.status) [\((.versionCodes // []) | join(","))]"] | join("; ")
       | if . == "" then "no releases" else . end)'
  echo
} | tee -a "$SUMMARY"

if [ -z "$TRACK" ]; then
  echo "No track given: listed tracks only, uploaded nothing." | tee -a "$SUMMARY"
  exit 0
fi

KNOWN=$(jq -r '.tracks[]?.track' <<<"$TRACKS_JSON")
if ! grep -qFx -- "$TRACK" <<<"$KNOWN"; then
  echo "::error::Track '$TRACK' not found. Known tracks: $(tr '\n' ' ' <<<"$KNOWN")"
  exit 1
fi

echo "Uploading $(basename "$AAB") ($(stat -c%s "$AAB") bytes)..."
VC=$(api POST "$UPLOAD/edits/$EDIT/bundles?uploadType=media" \
  -H 'Content-Type: application/octet-stream' --data-binary @"$AAB" | jq -er '.versionCode')
echo "Uploaded bundle: versionCode $VC"

# Release notes come from the fastlane changelog at the tag, the same text F-Droid shows.
NOTES=""
if [ -n "${TAG:-}" ]; then
  NOTES=$(git show "$TAG:fastlane/metadata/android/en-US/changelogs/$VC.txt" 2>/dev/null || true)
fi
if [ -n "$NOTES" ]; then
  LEN=$(jq -rn --arg n "$NOTES" '$n | length')
  if [ "$LEN" -gt 500 ]; then
    echo "::error::Release notes for versionCode $VC are $LEN characters; Play's limit is 500. Shorten fastlane/metadata/android/en-US/changelogs/$VC.txt."
    exit 1
  fi
fi
[ -n "$NOTES" ] || echo "::warning::No fastlane changelog for versionCode $VC at ${TAG:-<no tag>}; releasing without notes."

TRACK_JSON=$(mktemp)
jq -n --arg t "$TRACK" --arg vc "$VC" --arg s "$RELEASE_STATUS" --arg n "$NOTES" '
  {track: $t,
   releases: [ {versionCodes: [$vc], status: $s}
               + (if $n == "" then {} else {releaseNotes: [{language: "en-US", text: $n}]} end) ]}' \
  > "$TRACK_JSON"
api PUT "$API/edits/$EDIT/tracks/$TRACK" -H 'Content-Type: application/json' --data-binary @"$TRACK_JSON" >/dev/null
rm -f "$TRACK_JSON"
echo "Assigned versionCode $VC to '$TRACK' as $RELEASE_STATUS"

api POST "$API/edits/$EDIT:validate" >/dev/null
echo "Edit validated."

if [ "$DRY_RUN" = "true" ]; then
  echo "**Dry run:** versionCode $VC validated on \`$TRACK\` ($RELEASE_STATUS) and discarded — nothing published." | tee -a "$SUMMARY"
  exit 0
fi

api POST "$API/edits/$EDIT:commit" >/dev/null
COMMITTED=1
echo "**Published** versionCode $VC to \`$TRACK\` ($RELEASE_STATUS)." | tee -a "$SUMMARY"
