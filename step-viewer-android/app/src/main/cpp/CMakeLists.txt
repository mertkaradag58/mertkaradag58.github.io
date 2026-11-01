cmake_minimum_required(VERSION 3.22.1)
project(stepviewer CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OCCT, scripts/build_occt_android.sh ile derlenip bu klasöre kurulur.
# Gradle bu değeri -DOCCT_DIR=... ile geçirir.
if(NOT OCCT_DIR)
  message(FATAL_ERROR "OCCT_DIR tanımlı değil. Önce scripts/build_occt_android.sh çalıştırın.")
endif()

set(OpenCASCADE_DIR "${OCCT_DIR}/${ANDROID_ABI}/lib/cmake/opencascade")
find_package(OpenCASCADE REQUIRED CONFIG NO_CMAKE_FIND_ROOT_PATH)
message(STATUS "OCCT ${OpenCASCADE_VERSION}: ${OpenCASCADE_INCLUDE_DIR}")

add_library(stepviewer SHARED
  jni_bridge.cpp
  step_mesher.cpp)

target_include_directories(stepviewer PRIVATE ${OpenCASCADE_INCLUDE_DIR})

# Kullanılmayan OCCT kodunu APK'dan atmak için bölüm bazlı derleme + gc-sections.
target_compile_options(stepviewer PRIVATE -O2 -ffunction-sections -fdata-sections -fvisibility=hidden)
target_link_options(stepviewer PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
  # Android 15+ 16 KB sayfa boyutu desteği
  -Wl,-z,max-page-size=16384)

target_link_libraries(stepviewer PRIVATE
  TKDESTEP TKRWMesh TKXCAF TKMesh
  log android)
