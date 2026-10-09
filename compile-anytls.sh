#!/bin/bash
# 构建 AnyTLS 插件 libanytls.so（anytls-plugin/）到 KNcloud/app/libs/<abi>/。
# 用 NDK 的 clang 开 cgo：安卓没有 /etc/resolv.conf，纯 Go 解析器解析不了域名，
# 必须走 bionic 的 getaddrinfo。
set -o errexit
set -o pipefail
set -o nounset

__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ ! -d ${NDK_HOME:-} ]]; then
  echo "Android NDK: NDK_HOME not found. please set env \$NDK_HOME"
  exit 1
fi
OUT="${1:-$__dir/KNcloud/app/libs}"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

targets=(
  "aarch64-linux-android24 arm64 arm64-v8a"
  "armv7a-linux-androideabi24 arm armeabi-v7a"
  "x86_64-linux-android24 amd64 x86_64"
  "i686-linux-android24 386 x86"
)

cd "$__dir/anytls-plugin"
for target in "${targets[@]}"; do
  IFS=' ' read -r ndk_target goarch abi <<< "$target"
  echo "Building libanytls.so for ${abi}"
  mkdir -p "$OUT/$abi"
  GOARM=7 CC="$TOOLCHAIN/${ndk_target}-clang" CGO_ENABLED=1 GOOS=android GOARCH=$goarch \
    go build -o "$OUT/$abi/libanytls.so" -trimpath -buildvcs=false \
    -ldflags "-s -w -buildid=" .
done
ls -la "$OUT"/*/libanytls.so
