# libciris_aom.so: the JNI side of AomVideoCodec.kt (CSD-134, AV1 both ways).
# libaom itself is built from the pinned release by packaging/aom/build_libaom.sh
# (Gradle task :shared:buildLibaom) into ${AOM_ROOT}/<abi>.
cmake_minimum_required(VERSION 3.22)
project(ciris_aom C)

if(NOT AOM_ROOT)
  message(FATAL_ERROR "AOM_ROOT not set: run :shared:buildLibaom")
endif()
set(AOM_DIR "${AOM_ROOT}/${ANDROID_ABI}")

add_library(ciris_aom SHARED ciris_aom_jni.c)
target_include_directories(ciris_aom PRIVATE "${AOM_DIR}/include")
target_compile_options(ciris_aom PRIVATE -Os -ffunction-sections -fdata-sections)
target_link_libraries(ciris_aom PRIVATE "${AOM_DIR}/lib/libaom.a" m log)
# Only what the wrapper calls, libaom's symbols kept private, and 16 KB pages
# for Android 15 (the app's other native libraries are aligned the same way).
target_link_options(ciris_aom PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -s)
