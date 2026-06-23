cmake_minimum_required(VERSION 3.22.1)
  project("vcam_native")

  # Library 1: main vcam_native (JNI interface for V4L2 injection)
  add_library(vcam_native SHARED
      vcam_jni.cpp
      v4l2_injector.cpp
      image_converter.cpp
  )

  find_library(log-lib log)
  find_library(jnigraphics-lib jnigraphics)
  find_library(android-lib android)

  target_link_libraries(vcam_native
      ${log-lib}
      ${jnigraphics-lib}
      ${android-lib}
  )
  target_compile_options(vcam_native PRIVATE -O2 -Wall)

  # Library 2: vcam_inject — LD_PRELOAD hook for Camera HAL injection
  add_library(vcam_inject SHARED vcam_inject.cpp)

  target_link_libraries(vcam_inject
      ${log-lib}
      dl
  )
  target_compile_options(vcam_inject PRIVATE -O2 -Wall -std=c++17)

  # Make vcam_inject a standalone .so with no C++ runtime dependency
  set_target_properties(vcam_inject PROPERTIES
      LINK_FLAGS "-Wl,--no-undefined"
  )
  