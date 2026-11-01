# Masaüstü (Linux) testi. OCCT'nin masaüstü kurulumuyla derlenir:
#   cmake -S . -B build -DOpenCASCADE_DIR=/yol/occt/lib/cmake/opencascade && cmake --build build
cmake_minimum_required(VERSION 3.16)
project(step_host_test CXX)
set(CMAKE_CXX_STANDARD 17)
find_package(OpenCASCADE REQUIRED CONFIG)
add_executable(step_host_test main.cpp ../step_mesher.cpp)
target_include_directories(step_host_test PRIVATE ${OpenCASCADE_INCLUDE_DIR})
target_link_libraries(step_host_test PRIVATE TKDESTEP TKRWMesh TKXCAF TKMesh)
