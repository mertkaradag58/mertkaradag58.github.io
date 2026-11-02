cmake_minimum_required(VERSION 3.22.1)
project(stepviewer CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OCCT, scripts/build_occt_android.sh ile derlenip bu klasöre kurulur.
# Gradle bu değeri -DOCCT_DIR=... ile geçirir.
if(NOT OCCT_DIR)
  message(FATAL_ERROR "OCCT_DIR tanımlı değil. Önce scripts/build_occt_android.sh çalıştırın.")
endif()

set(OCCT_PREFIX "${OCCT_DIR}/${ANDROID_ABI}")
# OCCT'nin statik CMake hedefleri bağımlılık bilgisi taşımadığı için kütüphaneler
# doğrudan bağlanır; aralarındaki döngüsel bağımlılıklar --start-group ile çözülür.
file(GLOB OCCT_LIBS "${OCCT_PREFIX}/lib/libTK*.a")
if(NOT OCCT_LIBS)
  message(FATAL_ERROR "OCCT kütüphaneleri bulunamadı: ${OCCT_PREFIX}/lib")
endif()
set(OCCT_INCLUDE_DIR "${OCCT_PREFIX}/include/opencascade")

# Assimp (mesh dosyaları: OBJ, STL, glTF, DAE, 3DS, FBX, PLY), scripts/build_assimp_android.sh
# ile derlenir. Gradle bu değeri -DASSIMP_DIR=... ile geçirir.
if(NOT ASSIMP_DIR)
  message(FATAL_ERROR "ASSIMP_DIR tanımlı değil. Önce scripts/build_assimp_android.sh çalıştırın.")
endif()
set(ASSIMP_PREFIX "${ASSIMP_DIR}/${ANDROID_ABI}")
if(NOT EXISTS "${ASSIMP_PREFIX}/lib/libassimp.a")
  message(FATAL_ERROR "Assimp kütüphanesi bulunamadı: ${ASSIMP_PREFIX}/lib")
endif()

# Assimp sistemdeki zlib'i kullanır; bulamadıysa kendi kopyasını derlemiştir.
if(EXISTS "${ASSIMP_PREFIX}/lib/libzlibstatic.a")
  set(ASSIMP_ZLIB "${ASSIMP_PREFIX}/lib/libzlibstatic.a")
else()
  set(ASSIMP_ZLIB z)
endif()

add_library(stepviewer SHARED
  jni_bridge.cpp
  step_mesher.cpp
  mesh_model.cpp
  mesh_import.cpp
  pmi_extract.cpp
  hole_finder.cpp
  dxf_export.cpp)

target_include_directories(stepviewer PRIVATE ${OCCT_INCLUDE_DIR} "${ASSIMP_PREFIX}/include")

# Kullanılmayan OCCT kodunu APK'dan atmak için bölüm bazlı derleme + gc-sections.
target_compile_options(stepviewer PRIVATE -O2 -ffunction-sections -fdata-sections -fvisibility=hidden)
target_link_options(stepviewer PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
  # Android 15+ 16 KB sayfa boyutu desteği
  -Wl,-z,max-page-size=16384)

target_link_libraries(stepviewer PRIVATE
  -Wl,--start-group ${OCCT_LIBS} -Wl,--end-group
  "${ASSIMP_PREFIX}/lib/libassimp.a" ${ASSIMP_ZLIB}
  log android EGL GLESv2)
