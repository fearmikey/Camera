cmake_minimum_required(VERSION 3.22.1)

project("panorama_engine")

# OpenCV is consumed as a prefab package from the official Maven Central AAR
# (org.opencv:opencv, Apache-2.0). No prebuilt binaries are vendored in the repo.
find_package(OpenCV REQUIRED CONFIG)

add_library(panorama_engine SHARED
        PanoramaEngine.cpp
        PortraitEngine.cpp
        panorama_jni.cpp
        vision_jni.cpp)

target_link_libraries(panorama_engine
        OpenCV::opencv_java4
        android
        log
        jnigraphics)

# Reproducible builds (F-Droid verifies its build against the signed GitHub release APK):
# the library must not depend on where the repo, the Gradle cache or the NDK live.
# __FILE__ (used by CV_Assert in OpenCV's inline headers) and debug info would otherwise
# embed absolute paths, and the linker's build-id is a hash over that debug info.
get_filename_component(REPO_ROOT "${CMAKE_SOURCE_DIR}/../../../.." ABSOLUTE)
set(REPRO_PREFIX_MAPS "${REPO_ROOT}=.")
if(ANDROID_NDK)
    list(APPEND REPRO_PREFIX_MAPS "${ANDROID_NDK}=ndk")
endif()
get_target_property(OPENCV_INCLUDE_DIRS OpenCV::opencv_java4 INTERFACE_INCLUDE_DIRECTORIES)
if(OPENCV_INCLUDE_DIRS)
    foreach(dir IN LISTS OPENCV_INCLUDE_DIRS)
        list(APPEND REPRO_PREFIX_MAPS "${dir}=opencv")
    endforeach()
endif()
foreach(map IN LISTS REPRO_PREFIX_MAPS)
    target_compile_options(panorama_engine PRIVATE "-ffile-prefix-map=${map}")
endforeach()
target_link_options(panorama_engine PRIVATE "-Wl,--build-id=none")
