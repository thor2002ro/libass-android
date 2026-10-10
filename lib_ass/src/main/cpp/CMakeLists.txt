cmake_minimum_required(VERSION 3.22.1)
project(ass C CXX)

include(ExternalProject)
include("${CMAKE_CURRENT_LIST_DIR}/../../../../cmake/native-flags.cmake")

set(SOURCES_DIR "${CMAKE_CURRENT_SOURCE_DIR}/sources")
set(INSTALL_DIR "${CMAKE_BINARY_DIR}")

if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
    set(BUILD_HOST "x86_64-linux")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(BUILD_HOST "x86_64-darwin")
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(BUILD_HOST "x86_64-windows")
endif()

string(JOIN " " LIBASS_COMPILE_FLAGS_STRING ${LIBASS_COMPILE_FLAGS})
string(JOIN " " LIBASS_OPT_FLAGS_STRING ${LIBASS_OPT_FLAGS})

set(CONFIGURE_ENV
    AR=${CMAKE_AR}
    "CC=${CMAKE_C_COMPILER} --target=${CMAKE_C_COMPILER_TARGET}"
    "CXX=${CMAKE_CXX_COMPILER} --target=${CMAKE_C_COMPILER_TARGET}"
    NM=${CMAKE_NM}
    OBJDUMP=${CMAKE_OBJDUMP}
    RANLIB=${CMAKE_RANLIB}
    STRIP=${CMAKE_STRIP}
    "PKG_CONFIG_PATH=${CMAKE_CURRENT_SOURCE_DIR}/cmake:${INSTALL_DIR}/lib/pkgconfig"
    "CFLAGS=${CMAKE_C_FLAGS} ${LIBASS_COMPILE_FLAGS_STRING} -fPIC --target=${CMAKE_C_COMPILER_TARGET}"
    "CXXFLAGS=${CMAKE_CXX_FLAGS} ${LIBASS_COMPILE_FLAGS_STRING} -fPIC --target=${CMAKE_CXX_COMPILER_TARGET}"
    "LDFLAGS=${CMAKE_SHARED_LINKER_FLAGS} ${LIBASS_OPT_FLAGS_STRING} --target=${CMAKE_C_COMPILER_TARGET}"
    "ASFLAGS=-fPIC"
    "YASMFLAGS=-fPIC"
)

set(BUILD_AND_HOST
    "--build=${BUILD_HOST}"
    "--host=${CMAKE_C_COMPILER_TARGET}"
    "--target=${CMAKE_C_COMPILER_TARGET}"
)

set(DEPENDENCY_CMAKE_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
    -DCMAKE_INSTALL_PREFIX=${INSTALL_DIR}
    -DCMAKE_C_FLAGS_RELEASE=${LIBASS_COMPILE_FLAGS_STRING}
    -DCMAKE_CXX_FLAGS_RELEASE=${LIBASS_COMPILE_FLAGS_STRING}
    -DCMAKE_SHARED_LINKER_FLAGS_RELEASE=${LIBASS_OPT_FLAGS_STRING}
    -DCMAKE_C_FLAGS_RELWITHDEBINFO=${LIBASS_COMPILE_FLAGS_STRING}
    -DCMAKE_CXX_FLAGS_RELWITHDEBINFO=${LIBASS_COMPILE_FLAGS_STRING}
    -DCMAKE_SHARED_LINKER_FLAGS_RELWITHDEBINFO=${LIBASS_OPT_FLAGS_STRING}
)

if(CMAKE_ANDROID_ARCH STREQUAL "arm64")
    set(MESON_CPU_FAMILY "aarch64")
elseif(CMAKE_ANDROID_ARCH STREQUAL "arm")
    set(MESON_CPU_FAMILY "arm")
else()
    set(MESON_CPU_FAMILY "${CMAKE_ANDROID_ARCH}")
endif()

function(format_meson_array output)
    if(ARGN)
        list(JOIN ARGN "', '" values)
        set(${output} "['${values}']" PARENT_SCOPE)
    else()
        set(${output} "[]" PARENT_SCOPE)
    endif()
endfunction()
format_meson_array(LIBASS_MESON_COMPILE_ARGS ${LIBASS_COMPILE_FLAGS})
format_meson_array(LIBASS_MESON_LINK_ARGS ${LIBASS_OPT_FLAGS})
configure_file(cmake/meson-cross-android.in meson-cross-compile @ONLY)

ExternalProject_Add(expat_project
    SOURCE_DIR "${SOURCES_DIR}/expat/expat"
    INSTALL_DIR "${INSTALL_DIR}"
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env ${CONFIGURE_ENV}
        <SOURCE_DIR>/configure ${BUILD_AND_HOST} --prefix=<INSTALL_DIR>
        --enable-static --disable-shared --without-tests --without-docbook
    BUILD_COMMAND make -j$ENV{CMAKE_BUILD_PARALLEL_LEVEL}
    INSTALL_COMMAND make install
)

ExternalProject_Add(fribidi_project
    SOURCE_DIR "${SOURCES_DIR}/fribidi"
    INSTALL_DIR "${INSTALL_DIR}"
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env ${CONFIGURE_ENV}
        <SOURCE_DIR>/configure ${BUILD_AND_HOST} --prefix=<INSTALL_DIR>
        --enable-static --disable-shared --disable-docs --disable-option-checking
    BUILD_COMMAND make -j$ENV{CMAKE_BUILD_PARALLEL_LEVEL} "SUBDIRS=gen.tab lib"
    INSTALL_COMMAND make "SUBDIRS=gen.tab lib" install
)

ExternalProject_Add(unibreak_project
    SOURCE_DIR "${SOURCES_DIR}/unibreak"
    INSTALL_DIR "${INSTALL_DIR}"
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env ${CONFIGURE_ENV}
        <SOURCE_DIR>/configure ${BUILD_AND_HOST} --prefix=<INSTALL_DIR>
        --enable-static --disable-shared
    BUILD_COMMAND make -j$ENV{CMAKE_BUILD_PARALLEL_LEVEL}
    INSTALL_COMMAND make install
)

ExternalProject_Add(freetype_project
    DEPENDS fribidi_project
    SOURCE_DIR "${SOURCES_DIR}/freetype"
    INSTALL_DIR "${INSTALL_DIR}"
    CMAKE_ARGS ${DEPENDENCY_CMAKE_ARGS} -DDISABLE_FORCE_DEBUG_POSTFIX=ON
)

ExternalProject_Add(harfbuzz_project
    DEPENDS freetype_project
    SOURCE_DIR "${SOURCES_DIR}/harfbuzz"
    INSTALL_DIR "${INSTALL_DIR}"
    CMAKE_ARGS ${DEPENDENCY_CMAKE_ARGS}
)

ExternalProject_Add(fontconfig_project
    DEPENDS expat_project freetype_project
    SOURCE_DIR "${SOURCES_DIR}/fontconfig"
    INSTALL_DIR "${INSTALL_DIR}"
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env
        "PKG_CONFIG_PATH=${CMAKE_CURRENT_SOURCE_DIR}/cmake:<INSTALL_DIR>/lib/pkgconfig"
        "$ENV{LIBASS_MESON}" setup <BINARY_DIR> <SOURCE_DIR>
        --cross-file=${CMAKE_CURRENT_BINARY_DIR}/meson-cross-compile
        --prefix=<INSTALL_DIR> --libdir=lib --buildtype=plain
        --default-library=static --wrap-mode=nofallback
        -Ddoc=disabled -Dnls=disabled
        -Dtests=disabled -Dtools=disabled -Dcache-build=disabled -Dxml-backend=expat
    BUILD_COMMAND "$ENV{LIBASS_MESON}" compile -C <BINARY_DIR>
    INSTALL_COMMAND "$ENV{LIBASS_MESON}" install -C <BINARY_DIR>
)

ExternalProject_Add(libass_project
    DEPENDS fontconfig_project harfbuzz_project freetype_project fribidi_project unibreak_project
    SOURCE_DIR "${SOURCES_DIR}/ass"
    INSTALL_DIR "${INSTALL_DIR}"
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env ${CONFIGURE_ENV}
        <SOURCE_DIR>/configure ${BUILD_AND_HOST} --disable-silent-rules
        --prefix=<INSTALL_DIR> --enable-static --disable-shared
        --enable-fontconfig --enable-libunibreak --srcdir=<SOURCE_DIR>
    BUILD_COMMAND make -j$ENV{CMAKE_BUILD_PARALLEL_LEVEL}
    INSTALL_COMMAND make install
)

add_library(ass SHARED Ass.c)
add_dependencies(ass libass_project)
target_include_directories(ass PUBLIC "${INSTALL_DIR}/include")
target_link_directories(ass PUBLIC "${INSTALL_DIR}/lib")
target_link_libraries(ass
    "-Wl,--whole-archive" expat fontconfig libass.a fribidi freetype harfbuzz unibreak
    "-Wl,--no-whole-archive" z
)

if(CMAKE_BUILD_TYPE MATCHES "^(Release|RelWithDebInfo)$")
    target_compile_options(ass PRIVATE ${LIBASS_COMPILE_FLAGS})
    target_link_options(ass PRIVATE ${LIBASS_OPT_FLAGS})
endif()
