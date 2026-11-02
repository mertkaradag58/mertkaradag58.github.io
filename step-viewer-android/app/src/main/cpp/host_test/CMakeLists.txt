# Masaüstü (Linux) testi. OCCT aynı ayarlarla masaüstü için derlenip kurulduktan sonra:
#   cmake -S . -B build -DOCCT_PREFIX=/yol/occt-kurulum && cmake --build build
# Mesh testi: step_host_test model.step --as-mesh --faces --info --mass
cmake_minimum_required(VERSION 3.16)
project(step_host_test CXX)
set(CMAKE_CXX_STANDARD 17)
if(NOT OCCT_PREFIX)
  message(FATAL_ERROR "OCCT_PREFIX tanımlanmalı")
endif()
file(GLOB OCCT_LIBS "${OCCT_PREFIX}/lib/libTK*.a")
find_package(Threads REQUIRED)
add_executable(step_host_test main.cpp ../step_mesher.cpp ../mesh_model.cpp ../mesh_import.cpp)
target_include_directories(step_host_test PRIVATE "${OCCT_PREFIX}/include/opencascade")
target_link_libraries(step_host_test PRIVATE
  -Wl,--start-group ${OCCT_LIBS} -Wl,--end-group Threads::Threads ${CMAKE_DL_LIBS})
# Assimp isteğe bağlı: -DASSIMP_PREFIX=/yol (scripts/build_assimp_android.sh, HOST=1).
# Verilmezse mesh dosyaları okunamaz ama --as-mesh testi yine çalışır.
if(ASSIMP_PREFIX)
  target_include_directories(step_host_test PRIVATE "${ASSIMP_PREFIX}/include")
  target_link_libraries(step_host_test PRIVATE "${ASSIMP_PREFIX}/lib/libassimp.a" z)
else()
  target_compile_definitions(step_host_test PRIVATE STEPVIEWER_NO_ASSIMP)
endif()
