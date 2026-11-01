# Masaüstü (Linux) testi. OCCT aynı ayarlarla masaüstü için derlenip kurulduktan sonra:
#   cmake -S . -B build -DOCCT_PREFIX=/yol/occt-kurulum && cmake --build build
cmake_minimum_required(VERSION 3.16)
project(step_host_test CXX)
set(CMAKE_CXX_STANDARD 17)
if(NOT OCCT_PREFIX)
  message(FATAL_ERROR "OCCT_PREFIX tanımlanmalı")
endif()
file(GLOB OCCT_LIBS "${OCCT_PREFIX}/lib/libTK*.a")
find_package(Threads REQUIRED)
add_executable(step_host_test main.cpp ../step_mesher.cpp)
target_include_directories(step_host_test PRIVATE "${OCCT_PREFIX}/include/opencascade")
target_link_libraries(step_host_test PRIVATE
  -Wl,--start-group ${OCCT_LIBS} -Wl,--end-group Threads::Threads ${CMAKE_DL_LIBS})
