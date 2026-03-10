cmake_minimum_required(VERSION 3.18)
project(adb_pairing C CXX)

# Build BoringSSL without assembly (avoids Go/Perl requirement)
set(OPENSSL_NO_ASM ON CACHE BOOL "" FORCE)

add_subdirectory(boringssl EXCLUDE_FROM_ALL)

add_library(adb_pairing SHARED adb_pairing_jni.cpp)

target_include_directories(adb_pairing PRIVATE boringssl/include)

find_library(log-lib log)
target_link_libraries(adb_pairing crypto ssl ${log-lib})
