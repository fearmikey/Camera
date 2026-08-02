cmake_minimum_required(VERSION 3.22.1)

project("panorama_engine")

# Note: OpenCV_DIR should be set in the environment or passed via -DOpenCV_DIR
# if it's not in a standard location.
# For Android, this is typically the path to the OpenCV-android-sdk/sdk/native/jni folder.
find_package(OpenCV REQUIRED)

add_library(panorama_engine SHARED
        PanoramaEngine.cpp
        panorama_jni.cpp)

target_include_directories(panorama_engine PRIVATE
        ${OpenCV_INCLUDE_DIRS})

target_link_libraries(panorama_engine
        ${OpenCV_LIBS}
        android
        log
        jnigraphics)
