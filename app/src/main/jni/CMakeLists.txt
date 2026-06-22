cmake_minimum_required(VERSION 3.22.1)

project("vcam_native")

add_library(
    vcam_native
    SHARED
    vcam_jni.cpp
    v4l2_injector.cpp
    image_converter.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

target_link_libraries(
    vcam_native
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
)

target_compile_options(vcam_native PRIVATE -O2 -Wall)
