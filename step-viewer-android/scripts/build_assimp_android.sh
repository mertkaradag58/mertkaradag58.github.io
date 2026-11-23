#!/usr/bin/env bash
# Assimp'i (Open Asset Import Library) Android NDK ile statik kütüphane olarak derler.
#
# Mimari / 3B tasarım programlarının mesh (üçgen) dosyalarını okumak için kullanılır.
# Yalnızca şu okuyucular derlenir: OBJ, STL, glTF/GLB, Collada (DAE), 3DS, FBX, PLY, 3MF, AMF, OFF.
# Dışa aktarma, araçlar ve testler kapalıdır.
#
# Kullanım:
#   ANDROID_NDK_HOME=/yol/ndk ./scripts/build_assimp_android.sh [kurulum_klasoru]
#
# Ortam değişkenleri:
#   ANDROID_NDK_HOME  NDK kök klasörü (zorunlu, HOST=1 ise gerekmez)
#   ASSIMP_VERSION    git etiketi (varsayılan: v5.4.3)
#   ABIS              boşlukla ayrılmış ABI listesi (varsayılan: arm64-v8a)
#   ANDROID_PLATFORM  en düşük API (varsayılan: android-24)
#   HOST=1            Android yerine bu bilgisayar için derle (host_test için)
#   JOBS              paralel iş sayısı (varsayılan: çekirdek sayısı)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

ASSIMP_VERSION="${ASSIMP_VERSION:-v5.4.3}"
ABIS="${ABIS:-arm64-v8a}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-24}"
JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu)}"
INSTALL_ROOT="${1:-$PROJECT_DIR/third_party/assimp-install}"
WORK_DIR="${WORK_DIR:-$PROJECT_DIR/third_party/assimp-work}"

SRC_DIR="$WORK_DIR/src-$ASSIMP_VERSION"
if [[ ! -f "$SRC_DIR/CMakeLists.txt" ]]; then
  echo ">> Assimp $ASSIMP_VERSION kaynak kodu indiriliyor..."
  rm -rf "$SRC_DIR"
  git clone --depth 1 --branch "$ASSIMP_VERSION" \
    https://github.com/assimp/assimp.git "$SRC_DIR"
fi

COMMON_ARGS=(
  -G Ninja
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections"
  -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections"
  -DBUILD_SHARED_LIBS=OFF
  -DASSIMP_BUILD_ZLIB=OFF
  -DASSIMP_NO_EXPORT=ON
  -DASSIMP_BUILD_TESTS=OFF
  -DASSIMP_BUILD_ASSIMP_TOOLS=OFF
  -DASSIMP_BUILD_SAMPLES=OFF
  -DASSIMP_BUILD_DOCS=OFF
  -DASSIMP_WARNINGS_AS_ERRORS=OFF
  -DASSIMP_BUILD_USE_CCACHE=OFF
  -DASSIMP_INJECT_DEBUG_POSTFIX=OFF
  -DASSIMP_IGNORE_GIT_HASH=ON
  -DASSIMP_BUILD_DRACO=OFF
  -DASSIMP_BUILD_ALL_IMPORTERS_BY_DEFAULT=OFF
  -DASSIMP_BUILD_OBJ_IMPORTER=ON
  -DASSIMP_BUILD_STL_IMPORTER=ON
  -DASSIMP_BUILD_GLTF_IMPORTER=ON
  -DASSIMP_BUILD_COLLADA_IMPORTER=ON
  -DASSIMP_BUILD_3DS_IMPORTER=ON
  -DASSIMP_BUILD_FBX_IMPORTER=ON
  -DASSIMP_BUILD_PLY_IMPORTER=ON
  -DASSIMP_BUILD_3MF_IMPORTER=ON
  -DASSIMP_BUILD_AMF_IMPORTER=ON
  -DASSIMP_BUILD_OFF_IMPORTER=ON
)

build_one() {
  local name="$1" prefix="$2"
  shift 2
  local build_dir="$WORK_DIR/build-$name"
  echo ">> Assimp derleniyor: $name -> $prefix"
  cmake -S "$SRC_DIR" -B "$build_dir" "${COMMON_ARGS[@]}" -DCMAKE_INSTALL_PREFIX="$prefix" "$@"
  cmake --build "$build_dir" --parallel "$JOBS"
  cmake --install "$build_dir"
  # Sistem zlib bulunamazsa Assimp kendi zlib kopyasını derler ama kurmaz; elle kopyala.
  find "$build_dir" -name 'libzlibstatic.a' -exec cp {} "$prefix/lib/" \;
}

if [[ "${HOST:-0}" == "1" ]]; then
  build_one host "$INSTALL_ROOT/host"
else
  : "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME tanımlanmalı (Android NDK klasörü)}"
  TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
  [[ -f "$TOOLCHAIN" ]] || { echo "Toolchain bulunamadı: $TOOLCHAIN" >&2; exit 1; }
  for ABI in $ABIS; do
    build_one "$ABI" "$INSTALL_ROOT/$ABI" \
      -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
      -DANDROID_ABI="$ABI" \
      -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
      -DANDROID_STL=c++_static
  done
fi

# Lisans dosyası (BSD 3 maddeli).
cp "$SRC_DIR/LICENSE" "$INSTALL_ROOT/ASSIMP_LICENSE.txt" 2>/dev/null || true
echo ">> Tamam. Assimp kurulumu: $INSTALL_ROOT"
