#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
CACHE_DIR="${HOME}/Library/Caches/m_extension_server/embedded-openjdk-ios13-v15"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/m-extension-server-zero.XXXXXX")"
FRAMEWORKS_DIR="${SCRIPT_DIR}/Frameworks"
RUNTIME_DIR="${SCRIPT_DIR}/Runtime"
trap 'rm -rf "${WORK_DIR}"' EXIT

SERVER_JAR="${CACHE_DIR}/MExtensionServer-ios.jar"
OPENJDK_ZIP="${CACHE_DIR}/OpenJDK.xcframework.zip"
JAVA_BUNDLE_ZIP="${CACHE_DIR}/java_bundle-device.zip"

SERVER_URL="https://github.com/1Selxo/M-Extension-Server/releases/download/ios-runtime-v7/MExtensionServer-ios.jar"
SERVER_SHA256="40f29f44014fbe1d68b9c368a7c88ab559f8de40e7554c4873389a475aee2fe0"
OPENJDK_URL="https://github.com/1Selxo/Mangatan/releases/download/embedded-openjdk-ios13-v15/OpenJDK.xcframework.zip"
OPENJDK_SHA256="40030bd6a582d301798fc3775104a985fabf07ee2a4d4ffbc42e1bb27fb0a53d"
JAVA_BUNDLE_URL="https://github.com/1Selxo/Mangatan/releases/download/embedded-openjdk-ios13-v15/java_bundle-device.zip"
JAVA_BUNDLE_SHA256="7a22aabad510c3060ccd792ac30a3f11172a106eeb7773e89b8b1c53241b7e84"

if [ -d "${FRAMEWORKS_DIR}/OpenJDKRuntime.xcframework" ] &&
   [ -f "${RUNTIME_DIR}/MExtensionServer.jar" ]; then
  exit 0
fi

mkdir -p "${CACHE_DIR}"

download_verified() {
  url="$1"
  destination="$2"
  expected="$3"
  if [ -f "${destination}" ]; then
    actual="$(/usr/bin/shasum -a 256 "${destination}" | /usr/bin/cut -d ' ' -f 1)"
    [ "${actual}" = "${expected}" ] && return
    rm -f "${destination}"
  fi
  temporary="${destination}.download"
  rm -f "${temporary}"
  /usr/bin/curl --fail --location --retry 3 --output "${temporary}" "${url}"
  actual="$(/usr/bin/shasum -a 256 "${temporary}" | /usr/bin/cut -d ' ' -f 1)"
  if [ "${actual}" != "${expected}" ]; then
    rm -f "${temporary}"
    echo "SHA-256 mismatch for ${url}" >&2
    exit 1
  fi
  mv "${temporary}" "${destination}"
}

download_verified "${SERVER_URL}" "${SERVER_JAR}" "${SERVER_SHA256}"
download_verified "${OPENJDK_URL}" "${OPENJDK_ZIP}" "${OPENJDK_SHA256}"
download_verified "${JAVA_BUNDLE_URL}" "${JAVA_BUNDLE_ZIP}" "${JAVA_BUNDLE_SHA256}"

JAVA_HOME_EFFECTIVE="${JAVA_HOME:-}"
if [ ! -x "${JAVA_HOME_EFFECTIVE}/bin/javac" ]; then
  JAVA_HOME_EFFECTIVE="$(/usr/libexec/java_home -v 21)"
fi

mkdir -p "${WORK_DIR}/framework" "${WORK_DIR}/java-bundle"
/usr/bin/unzip -q "${OPENJDK_ZIP}" -d "${WORK_DIR}/framework"
/usr/bin/unzip -q "${JAVA_BUNDLE_ZIP}" -d "${WORK_DIR}/java-bundle"

STATIC_LIBRARY="$(/usr/bin/find "${WORK_DIR}/framework" -type f -name libdevice.a -print -quit)"
HEADERS_DIR="$(/usr/bin/find "${WORK_DIR}/framework" -type d -name Headers -print -quit)"
MODULES="$(/usr/bin/find "${WORK_DIR}/java-bundle" -type f -path '*/lib/modules' -print -quit)"
JAVA_ROOT="$(/usr/bin/dirname "$(/usr/bin/dirname "${MODULES}")")"
if [ -z "${STATIC_LIBRARY}" ] || [ -z "${HEADERS_DIR}" ] ||
   [ ! -f "${JAVA_ROOT}/conf/security/java.security" ]; then
  echo "The pinned OpenJDK runtime archive is incomplete" >&2
  exit 1
fi

SHIM_CLASSES="${WORK_DIR}/logging-shim-classes"
mkdir -p "${SHIM_CLASSES}"
"${JAVA_HOME_EFFECTIVE}/bin/javac" \
  --patch-module "java.logging=${SCRIPT_DIR}/RuntimeSources/ios_jul_shim" \
  -d "${SHIM_CLASSES}" \
  "${SCRIPT_DIR}/RuntimeSources/ios_jul_shim/java/util/logging/Level.java" \
  "${SCRIPT_DIR}/RuntimeSources/ios_jul_shim/java/util/logging/Logger.java"
"${JAVA_HOME_EFFECTIVE}/bin/jar" --create \
  --file "${WORK_DIR}/java-logging-shim.jar" \
  -C "${SHIM_CLASSES}" .

DEVICE_FRAMEWORK="${WORK_DIR}/device/OpenJDKRuntime.framework"
mkdir -p "${DEVICE_FRAMEWORK}/Headers" "${DEVICE_FRAMEWORK}/lib/lib"
/usr/bin/ditto "${HEADERS_DIR}" "${DEVICE_FRAMEWORK}/Headers"
/bin/cp "${SCRIPT_DIR}/RuntimeSources/OpenJDKRuntime-Info.plist" "${DEVICE_FRAMEWORK}/Info.plist"
/bin/cp "${JAVA_ROOT}/lib/modules" "${DEVICE_FRAMEWORK}/lib/lib/modules"
/bin/cp "${JAVA_ROOT}/lib/tzdb.dat" "${DEVICE_FRAMEWORK}/lib/lib/tzdb.dat"
/usr/bin/ditto "${JAVA_ROOT}/lib/security" "${DEVICE_FRAMEWORK}/lib/lib/security"
/usr/bin/ditto "${JAVA_ROOT}/conf" "${DEVICE_FRAMEWORK}/lib/conf"

DEVICE_SDK="$(/usr/bin/xcrun --sdk iphoneos --show-sdk-path)"
/usr/bin/xcrun --sdk iphoneos clang++ \
  -target arm64-apple-ios13.0 \
  -isysroot "${DEVICE_SDK}" \
  -dynamiclib \
  -Wl,-all_load \
  "${STATIC_LIBRARY}" \
  "${SCRIPT_DIR}/RuntimeSources/openjdk_runtime_exports.cpp" \
  -Wl,-install_name,@rpath/OpenJDKRuntime.framework/OpenJDKRuntime \
  -Wl,-compatibility_version,1.0.0 \
  -Wl,-current_version,1.0.0 \
  -lz \
  -framework Foundation \
  -framework CoreFoundation \
  -o "${DEVICE_FRAMEWORK}/OpenJDKRuntime"

SIMULATOR_FRAMEWORK="${WORK_DIR}/simulator/OpenJDKRuntime.framework"
mkdir -p "${SIMULATOR_FRAMEWORK}"
/bin/cp "${SCRIPT_DIR}/RuntimeSources/OpenJDKRuntime-Info.plist" "${SIMULATOR_FRAMEWORK}/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleSupportedPlatforms:0 iPhoneSimulator' "${SIMULATOR_FRAMEWORK}/Info.plist"
SIMULATOR_SDK="$(/usr/bin/xcrun --sdk iphonesimulator --show-sdk-path)"
for arch in arm64 x86_64; do
  /usr/bin/xcrun --sdk iphonesimulator clang++ \
    -target "${arch}-apple-ios13.0-simulator" \
    -isysroot "${SIMULATOR_SDK}" \
    -dynamiclib \
    "${SCRIPT_DIR}/RuntimeSources/openjdk_simulator_stub.cpp" \
    -Wl,-install_name,@rpath/OpenJDKRuntime.framework/OpenJDKRuntime \
    -o "${WORK_DIR}/OpenJDKRuntime-${arch}"
done
/usr/bin/lipo -create \
  "${WORK_DIR}/OpenJDKRuntime-arm64" \
  "${WORK_DIR}/OpenJDKRuntime-x86_64" \
  -output "${SIMULATOR_FRAMEWORK}/OpenJDKRuntime"

rm -rf "${FRAMEWORKS_DIR}/OpenJDKRuntime.xcframework" "${RUNTIME_DIR}"
mkdir -p "${FRAMEWORKS_DIR}" "${RUNTIME_DIR}/lib/security"
/usr/bin/xcodebuild -create-xcframework \
  -framework "${DEVICE_FRAMEWORK}" \
  -framework "${SIMULATOR_FRAMEWORK}" \
  -output "${FRAMEWORKS_DIR}/OpenJDKRuntime.xcframework"
/bin/cp "${SERVER_JAR}" "${RUNTIME_DIR}/MExtensionServer.jar"
/bin/cp "${WORK_DIR}/java-logging-shim.jar" "${RUNTIME_DIR}/java-logging-shim.jar"
/bin/cp "${JAVA_ROOT}/lib/security/cacerts" "${RUNTIME_DIR}/lib/security/cacerts"
/bin/cp "${JAVA_ROOT}/release" "${RUNTIME_DIR}/release"
/bin/cp "${SCRIPT_DIR}/RuntimeSources/THIRD_PARTY_NOTICES.md" \
  "${RUNTIME_DIR}/THIRD_PARTY_NOTICES.md"

test -x "${FRAMEWORKS_DIR}/OpenJDKRuntime.xcframework/ios-arm64/OpenJDKRuntime.framework/OpenJDKRuntime"
test -f "${RUNTIME_DIR}/MExtensionServer.jar"
echo "Prepared embedded OpenJDK Zero runtime for m_extension_server"
