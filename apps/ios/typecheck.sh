#!/bin/sh
set -eu

XCODE_SWIFTC="/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/swiftc"
IOS_SDK="/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"
SOURCE_FILES=$(find apps/ios/MobileAgent -name '*.swift' -print | sort)

"$XCODE_SWIFTC" \
  -typecheck \
  -parse-as-library \
  -sdk "$IOS_SDK" \
  -target arm64-apple-ios17.0-simulator \
  -module-cache-path /tmp/mobile-agent-swift-module-cache \
  $SOURCE_FILES

