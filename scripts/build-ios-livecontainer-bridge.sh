#!/bin/zsh
set -euo pipefail

ROOT="${0:A:h:h}"
SOURCE="$ROOT/research/ios-livecontainer-bridge"
OUTPUT="$ROOT/research/build/MobileAgentBridge.framework"
SDK="$(DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun --sdk iphoneos --show-sdk-path)"

mkdir -p "$OUTPUT"
cp "$SOURCE/Info.plist" "$OUTPUT/Info.plist"
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun clang \
  -target arm64-apple-ios17.0 -isysroot "$SDK" -fobjc-arc -dynamiclib \
  -framework Foundation -framework UIKit -framework CoreGraphics \
  -install_name '@rpath/MobileAgentBridge.framework/MobileAgentBridge' \
  -current_version 0.1.0 -compatibility_version 0.1.0 \
  "$SOURCE/MobileAgentBridge.m" -o "$OUTPUT/MobileAgentBridge"

/usr/bin/codesign --force --sign - --timestamp=none "$OUTPUT"
echo "$OUTPUT"
