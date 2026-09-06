cmake_minimum_required(VERSION 3.22.1)
project("asskt")
include("${CMAKE_CURRENT_LIST_DIR}/../../../../cmake/native-flags.cmake")

add_library(${CMAKE_PROJECT_NAME} SHARED AssKt.c)
find_package(lib_ass REQUIRED CONFIG)
set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES OUTPUT_NAME "asskt")
if(CMAKE_BUILD_TYPE MATCHES "^(Release|RelWithDebInfo)$")
    target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE ${LIBASS_COMPILE_FLAGS})
    target_link_options(${CMAKE_PROJECT_NAME} PRIVATE ${LIBASS_OPT_FLAGS})
endif()
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
        lib_ass::ass
        android
        jnigraphics
        GLESv2
        z
        log)
