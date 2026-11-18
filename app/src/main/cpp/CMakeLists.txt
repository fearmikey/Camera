cmake_minimum_required(VERSION 3.22.1)

project("panorama_engine")

# OpenCV is consumed as a prefab package from the official Maven Central AAR
# (org.opencv:opencv, Apache-2.0). No prebuilt binaries are vendored in the repo.
find_package(OpenCV REQUIRED CONFIG)

add_library(panorama_engine SHARED
        PanoramaEngine.cpp
        panorama_jni.cpp
        vision_jni.cpp)

target_link_libraries(panorama_engine
        OpenCV::opencv_java4
        android
        log
        jnigraphics)
