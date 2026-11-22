cmake_minimum_required(VERSION 3.22.1)
project(anmu_native LANGUAGES CXX)

add_library(anmu_native SHARED
    anmu_native.cpp
)

target_compile_features(anmu_native PRIVATE cxx_std_17)
target_compile_options(anmu_native PRIVATE
    -Wall
    -Wextra
    -Werror
)

find_library(log-lib log)

target_link_libraries(anmu_native
    ${log-lib}
)
