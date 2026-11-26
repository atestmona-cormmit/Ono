cmake_minimum_required(VERSION 3.18)
project(nodebridge)

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so)

add_library(nodebridge SHARED native-lib.cpp)
target_include_directories(nodebridge PRIVATE ${CMAKE_SOURCE_DIR}/node)
target_link_libraries(nodebridge node log)
