set(LIBASS_OPT_FLAGS)
set(LIBASS_ABI_COMPILE_FLAGS)

if(CMAKE_BUILD_TYPE MATCHES "^(Release|RelWithDebInfo)$")
    set(LIBASS_OPT_FLAGS -O3 -flto=thin)
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(LIBASS_ABI_COMPILE_FLAGS -mfpu=neon -mthumb)
    endif()
endif()

set(LIBASS_COMPILE_FLAGS ${LIBASS_OPT_FLAGS} ${LIBASS_ABI_COMPILE_FLAGS})
