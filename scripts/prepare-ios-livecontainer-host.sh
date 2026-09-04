#!/bin/zsh
set -euo pipefail

# Reproducibly prepares the pinned LiveContainer research host in research/build.
# It does not modify or vendor the upstream source tree.

ROOT="${0:A:h:h}"
VERSION="3.8.0"
UPSTREAM_URL="https://github.com/LiveContainer/LiveContainer/releases/download/${VERSION}/LiveContainer.ipa"
UPSTREAM_SHA256="b6fea95e30083382e29ffef88fa1aaa40b5069e1112e5307d490dab04648bba6"
OUTPUT_APP="$ROOT/research/build/MobileAgentSandbox.app"
OUTPUT_IPA="$ROOT/research/build/MobileAgentSandbox-${VERSION}.ipa"

: "${MOBILE_AGENT_DEVELOPMENT_TEAM:?Set your Apple Development Team ID}"
: "${MOBILE_AGENT_SIGNING_IDENTITY:?Set your codesigning identity name or SHA-1}"
: "${MOBILE_AGENT_PROVISIONING_PROFILE:?Set the path to your provisioning profile}"

TEAM_ID="$MOBILE_AGENT_DEVELOPMENT_TEAM"
SIGNING_IDENTITY="$MOBILE_AGENT_SIGNING_IDENTITY"
PROFILE="$MOBILE_AGENT_PROVISIONING_PROFILE"
BUNDLE_ID="${MOBILE_AGENT_LIVECONTAINER_BUNDLE_ID:-ai.mobileagent.livecontainer}"
# LiveContainer uses the literal `livecontainer` scheme to identify the primary
# host that is allowed to manage/import guests. Isolation comes from bundle ID.
URL_SCHEME="${MOBILE_AGENT_LIVECONTAINER_URL_SCHEME:-livecontainer}"

if [[ ! -f "$PROFILE" ]]; then
  print -u2 "Provisioning profile not found: $PROFILE"
  exit 1
fi
if ! security find-identity -v -p codesigning | grep -q "$SIGNING_IDENTITY"; then
  print -u2 "Signing identity not found: $SIGNING_IDENTITY"
  exit 1
fi

mkdir -p "$ROOT/research/build"
WORK_DIR="$(mktemp -d /tmp/mobile-agent-livecontainer.XXXXXX)"
UPSTREAM_IPA="$WORK_DIR/LiveContainer-${VERSION}.ipa"

curl --fail --location --silent --show-error "$UPSTREAM_URL" --output "$UPSTREAM_IPA"
ACTUAL_SHA256="$(shasum -a 256 "$UPSTREAM_IPA" | awk '{print $1}')"
if [[ "$ACTUAL_SHA256" != "$UPSTREAM_SHA256" ]]; then
  print -u2 "LiveContainer checksum mismatch: expected $UPSTREAM_SHA256, got $ACTUAL_SHA256"
  exit 1
fi

unzip -q "$UPSTREAM_IPA" -d "$WORK_DIR/unpacked"
APP="$WORK_DIR/unpacked/Payload/LiveContainer.app"
INFO="$APP/Info.plist"

# The wildcard development profile has no App Groups entitlement. The stripped
# standalone host intentionally omits extensions that require those groups.
if [[ -d "$APP/PlugIns" ]]; then
  mv "$APP/PlugIns" "$WORK_DIR/PlugIns.disabled"
fi

/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier $BUNDLE_ID" "$INFO"
/usr/libexec/PlistBuddy -c "Set :CFBundleDisplayName Mobile Agent Sandbox" "$INFO"
/usr/libexec/PlistBuddy -c "Set :CFBundleURLTypes:0:CFBundleURLName $BUNDLE_ID.urlscheme" "$INFO"
/usr/libexec/PlistBuddy -c "Set :CFBundleURLTypes:0:CFBundleURLSchemes:0 $URL_SCHEME" "$INFO"
if ! /usr/libexec/PlistBuddy -c "Set :PrimaryLiveContainerTeamId $TEAM_ID" "$INFO" 2>/dev/null; then
  /usr/libexec/PlistBuddy -c "Add :PrimaryLiveContainerTeamId string $TEAM_ID" "$INFO"
fi
cp "$PROFILE" "$APP/embedded.mobileprovision"

ENTITLEMENTS="$WORK_DIR/host-entitlements.plist"
/usr/libexec/PlistBuddy -c 'Clear dict' "$ENTITLEMENTS"
/usr/libexec/PlistBuddy -c "Add :application-identifier string $TEAM_ID.$BUNDLE_ID" "$ENTITLEMENTS"
/usr/libexec/PlistBuddy -c "Add :com.apple.developer.team-identifier string $TEAM_ID" "$ENTITLEMENTS"
/usr/libexec/PlistBuddy -c 'Add :get-task-allow bool true' "$ENTITLEMENTS"
/usr/libexec/PlistBuddy -c 'Add :keychain-access-groups array' "$ENTITLEMENTS"
/usr/libexec/PlistBuddy -c "Add :keychain-access-groups:0 string $TEAM_ID.*" "$ENTITLEMENTS"
/usr/libexec/PlistBuddy -c 'Add :keychain-access-groups:1 string com.apple.token' "$ENTITLEMENTS"

find "$APP/Frameworks" -depth \( -name '*.framework' -o -name '*.dylib' \) -print0 | while IFS= read -r -d '' code; do
  /usr/bin/codesign --force --sign "$SIGNING_IDENTITY" --timestamp=none "$code"
done
/usr/bin/codesign --force --sign "$SIGNING_IDENTITY" --entitlements "$ENTITLEMENTS" --timestamp=none "$APP"
/usr/bin/codesign --verify --deep --strict --verbose=2 "$APP"

ditto "$APP" "$OUTPUT_APP"
ditto -c -k --sequesterRsrc --keepParent "$WORK_DIR/unpacked/Payload" "$OUTPUT_IPA"

print "$OUTPUT_IPA"
print "LiveContainer $VERSION ($UPSTREAM_SHA256), bundle $BUNDLE_ID, team $TEAM_ID"
