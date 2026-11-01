#!/usr/bin/env bash
# Open CASCADE Technology'yi (OCCT) Android NDK ile statik kütüphane olarak derler.
#
# Yalnızca STEP/IGES okuma + üçgenleme için gereken araç takımları (ve bağımlılıkları)
# derlenir: TKDESTEP, TKDEIGES, TKXCAF, TKMesh, TKRWMesh (+ TKBinXCAF, STEPCAFControl başlığı için).
# FreeType, OpenGL, Tcl/Tk, Draw, TBB gibi isteğe bağlı bileşenler kapalıdır.
#
# Kullanım:
#   ANDROID_NDK_HOME=/yol/ndk ./scripts/build_occt_android.sh [kurulum_klasoru]
#
# Ortam değişkenleri:
#   ANDROID_NDK_HOME  NDK kök klasörü (zorunlu)
#   OCCT_VERSION      git etiketi (varsayılan: V7_8_1)
#   ABIS              boşlukla ayrılmış ABI listesi (varsayılan: arm64-v8a)
#   ANDROID_PLATFORM  en düşük API (varsayılan: android-24)
#   JOBS              paralel iş sayısı (varsayılan: çekirdek sayısı)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

OCCT_VERSION="${OCCT_VERSION:-V7_8_1}"
ABIS="${ABIS:-arm64-v8a}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-24}"
JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu)}"
INSTALL_ROOT="${1:-$PROJECT_DIR/third_party/occt-install}"
WORK_DIR="${WORK_DIR:-$PROJECT_DIR/third_party/occt-work}"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME tanımlanmalı (Android NDK klasörü)}"
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
[[ -f "$TOOLCHAIN" ]] || { echo "Toolchain bulunamadı: $TOOLCHAIN" >&2; exit 1; }

SRC_DIR="$WORK_DIR/src-$OCCT_VERSION"
if [[ ! -f "$SRC_DIR/CMakeLists.txt" ]]; then
  echo ">> OCCT $OCCT_VERSION kaynak kodu indiriliyor..."
  rm -rf "$SRC_DIR"
  git clone --depth 1 --branch "$OCCT_VERSION" \
    https://github.com/Open-Cascade-SAS/OCCT.git "$SRC_DIR"
fi

TOOLKITS="TKDESTEP;TKDEIGES;TKXCAF;TKMesh;TKRWMesh;TKBinXCAF"

for ABI in $ABIS; do
  BUILD_DIR="$WORK_DIR/build-$ABI"
  PREFIX="$INSTALL_ROOT/$ABI"
  echo ">> OCCT derleniyor: ABI=$ABI -> $PREFIX"

  cmake -S "$SRC_DIR" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections" \
    -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections" \
    -DBUILD_LIBRARY_TYPE=Static \
    -DINSTALL_DIR_LAYOUT=Unix \
    -DBUILD_MODULE_FoundationClasses=OFF \
    -DBUILD_MODULE_ModelingData=OFF \
    -DBUILD_MODULE_ModelingAlgorithms=OFF \
    -DBUILD_MODULE_Visualization=OFF \
    -DBUILD_MODULE_ApplicationFramework=OFF \
    -DBUILD_MODULE_DataExchange=OFF \
    -DBUILD_MODULE_DETools=OFF \
    -DBUILD_MODULE_Draw=OFF \
    -DBUILD_ADDITIONAL_TOOLKITS="$TOOLKITS" \
    -DUSE_FREETYPE=OFF \
    -DUSE_FREEIMAGE=OFF \
    -DUSE_OPENGL=OFF \
    -DUSE_GLES2=OFF \
    -DUSE_RAPIDJSON=OFF \
    -DUSE_DRACO=OFF \
    -DUSE_TBB=OFF \
    -DUSE_VTK=OFF \
    -DUSE_TK=OFF \
    -DUSE_TCL=OFF \
    -DBUILD_DOC_Overview=OFF \
    -DINSTALL_SAMPLES=OFF \
    -DINSTALL_TEST_CASES=OFF

  cmake --build "$BUILD_DIR" --parallel "$JOBS"
  cmake --install "$BUILD_DIR"
done

# Lisans dosyalarını kurulumla birlikte sakla (LGPL 2.1 + OCCT istisnası).
cp "$SRC_DIR/LICENSE_LGPL_21.txt" "$SRC_DIR/OCCT_LGPL_EXCEPTION.txt" "$INSTALL_ROOT/" 2>/dev/null || true
echo ">> Tamam. OCCT kurulumu: $INSTALL_ROOT"
