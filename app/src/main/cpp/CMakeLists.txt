cmake_minimum_required(VERSION 3.22.1)
project("fshu_native")

add_library(fshu_native SHARED fshu_native.cpp)
find_library(log-lib log)
target_link_libraries(fshu_native ${log-lib})
target_link_options(fshu_native PRIVATE "-Wl,-z,max-page-size=16384")
