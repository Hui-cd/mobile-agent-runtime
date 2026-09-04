#!/bin/zsh
set -euo pipefail

ROOT="${0:A:h:h}"
: "${MOBILE_AGENT_IOS_DEVICE:?Set the target device identifier}"
: "${MOBILE_AGENT_SIGNING_IDENTITY:?Set your codesigning identity name or SHA-1}"

DEVICE="$MOBILE_AGENT_IOS_DEVICE"
HOST_BUNDLE_ID="${MOBILE_AGENT_LIVECONTAINER_BUNDLE_ID:-ai.mobileagent.livecontainer}"
SIGNING_IDENTITY="$MOBILE_AGENT_SIGNING_IDENTITY"
FRAMEWORK="$ROOT/research/build/MobileAgentBridge.framework"
LOADER_MARKER="$ROOT/research/ios-livecontainer-bridge/README.loader-marker.txt"
DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

"$ROOT/scripts/build-ios-livecontainer-bridge.sh" >/dev/null
/usr/bin/codesign --force --sign "$SIGNING_IDENTITY" --timestamp=none "$FRAMEWORK"
/usr/bin/codesign --verify --strict --verbose=2 "$FRAMEWORK"

DEVELOPER_DIR="$DEVELOPER_DIR" xcrun devicectl device copy to \
  --device "$DEVICE" \
  --source "$FRAMEWORK" \
  --source "$LOADER_MARKER" \
  --destination Documents/Tweaks \
  --domain-type appDataContainer \
  --domain-identifier "$HOST_BUNDLE_ID"

print "Deployed bridge and loader marker to $HOST_BUNDLE_ID/Documents/Tweaks on $DEVICE"
