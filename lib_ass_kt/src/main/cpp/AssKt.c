#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ass/ass.h"

#define LOG_TAG "SubtitleRenderer"
#define ATLAS_QUAD_STRIDE 8
#define ATLAS_ACTIVE_BOUNDS_STRIDE 4
#define ATLAS_PATCH_RECT_STRIDE 5
#define ATLAS_GUTTER 1
#define ATLAS_CHANGE_METADATA 1
#define ATLAS_CHANGE_INCREMENTAL 2
#define ATLAS_CHANGE_REPLACE 3
#define ATLAS_PACKET_MAGIC UINT32_C(0x41544631)
#define ATLAS_PACKET_VERSION UINT32_C(1)
#define ATLAS_PACKET_HEADER_SIZE 96
#define ATLAS_PAGE_RECORD_SIZE 32
#define ATLAS_QUAD_RECORD_SIZE 32
#define ATLAS_PATCH_RECORD_SIZE 28
#define ATLAS_PACKET_FLAG_ACTIVE_BOUNDS UINT32_C(1)


static jclass g_ass_event_class;
static jmethodID g_ass_event_ctor;
static jclass g_ass_frame_class;
static jmethodID g_ass_frame_ctor;
static jclass g_ass_tex_class;
static jmethodID g_ass_tex_ctor;
static jclass g_ass_atlas_frame_class;
static jmethodID g_ass_atlas_frame_ctor;

static jclass g_bitmap_class;
static jmethodID g_bitmap_create;
static jobject g_bitmap_argb8888;
static jobject g_bitmap_alpha8;

static void assMessageCallback(int level, const char *fmt, va_list args, void *data) {
    (void) data;
    if (level > 4) return;
    if (level >= 2) {
        __android_log_vprint(ANDROID_LOG_WARN, LOG_TAG, fmt, args);
    } else {
        __android_log_vprint(ANDROID_LOG_ERROR, LOG_TAG, fmt, args);
    }
}

static jclass cache_class(JNIEnv *env, const char *name) {
    jclass local = (*env)->FindClass(env, name);
    if (local == NULL) return NULL;
    jclass global = (jclass) (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    return global;
}

static int cache_jni_ids(JNIEnv *env) {
    g_ass_event_class = cache_class(env, "io/github/peerless2012/ass/AssEvent");
    g_ass_frame_class = cache_class(env, "io/github/peerless2012/ass/AssFrame");
    g_ass_tex_class = cache_class(env, "io/github/peerless2012/ass/AssTex");
    g_ass_atlas_frame_class = cache_class(env, "io/github/peerless2012/ass/AssAtlasFrame");
    g_bitmap_class = cache_class(env, "android/graphics/Bitmap");
    jclass bitmap_config_class = cache_class(env, "android/graphics/Bitmap$Config");

    if (g_ass_event_class == NULL || g_ass_frame_class == NULL ||
        g_ass_tex_class == NULL || g_ass_atlas_frame_class == NULL ||
        g_bitmap_class == NULL ||
        bitmap_config_class == NULL) {
        if (bitmap_config_class != NULL) {
            (*env)->DeleteGlobalRef(env, bitmap_config_class);
        }
        return 0;
    }

    g_ass_event_ctor = (*env)->GetMethodID(
        env,
        g_ass_event_class,
        "<init>",
        "(JJIIILjava/lang/String;IIILjava/lang/String;Ljava/lang/String;)V"
    );
    g_ass_frame_ctor = (*env)->GetMethodID(
        env,
        g_ass_frame_class,
        "<init>",
        "([Lio/github/peerless2012/ass/AssTex;I)V"
    );
    g_ass_tex_ctor = (*env)->GetMethodID(
        env,
        g_ass_tex_class,
        "<init>",
        "(IIIIILandroid/graphics/Bitmap;I)V"
    );
    g_ass_atlas_frame_ctor = (*env)->GetMethodID(
        env,
        g_ass_atlas_frame_class,
        "<init>",
        "(Ljava/nio/ByteBuffer;)V"
    );
    g_bitmap_create = (*env)->GetStaticMethodID(
        env,
        g_bitmap_class,
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );

    jfieldID argb8888_id = (*env)->GetStaticFieldID(
        env,
        bitmap_config_class,
        "ARGB_8888",
        "Landroid/graphics/Bitmap$Config;"
    );
    jfieldID alpha8_id = (*env)->GetStaticFieldID(
        env,
        bitmap_config_class,
        "ALPHA_8",
        "Landroid/graphics/Bitmap$Config;"
    );

    if (g_ass_event_ctor == NULL || g_ass_frame_ctor == NULL ||
        g_ass_tex_ctor == NULL || g_ass_atlas_frame_ctor == NULL ||
        g_bitmap_create == NULL || argb8888_id == NULL || alpha8_id == NULL) {
        (*env)->DeleteGlobalRef(env, bitmap_config_class);
        return 0;
    }

    jobject argb8888 = (*env)->GetStaticObjectField(env, bitmap_config_class, argb8888_id);
    jobject alpha8 = (*env)->GetStaticObjectField(env, bitmap_config_class, alpha8_id);
    g_bitmap_argb8888 = argb8888 == NULL ? NULL : (*env)->NewGlobalRef(env, argb8888);
    g_bitmap_alpha8 = alpha8 == NULL ? NULL : (*env)->NewGlobalRef(env, alpha8);
    if (argb8888 != NULL) (*env)->DeleteLocalRef(env, argb8888);
    if (alpha8 != NULL) (*env)->DeleteLocalRef(env, alpha8);
    (*env)->DeleteGlobalRef(env, bitmap_config_class);

    return g_bitmap_argb8888 != NULL && g_bitmap_alpha8 != NULL;
}

static void clear_jni_ids(JNIEnv *env) {
#define DELETE_GLOBAL(value) \
    do { \
        if ((value) != NULL) { \
            (*env)->DeleteGlobalRef(env, (value)); \
            (value) = NULL; \
        } \
    } while (0)

    DELETE_GLOBAL(g_ass_event_class);
    DELETE_GLOBAL(g_ass_frame_class);
    DELETE_GLOBAL(g_ass_tex_class);
    DELETE_GLOBAL(g_ass_atlas_frame_class);
    DELETE_GLOBAL(g_bitmap_class);
    DELETE_GLOBAL(g_bitmap_argb8888);
    DELETE_GLOBAL(g_bitmap_alpha8);
#undef DELETE_GLOBAL
}

// -----------------------------------------------------------------------------------------------
// ASS library
// -----------------------------------------------------------------------------------------------

static jlong nativeAssInit(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    ASS_Library *library = ass_library_init();
    if (library == NULL) return 0;
    ass_set_message_cb(library, assMessageCallback, NULL);
    ass_set_extract_fonts(library, 1);
    return (jlong) (intptr_t) library;
}

static jint nativeAssLibraryVersion(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return ass_library_version();
}

static void nativeAssAddFont(
    JNIEnv *env,
    jclass clazz,
    jlong ass,
    jstring name,
    jbyteArray byte_array
) {
    (void) clazz;
    if (ass == 0 || name == NULL || byte_array == NULL) return;

    jsize length = (*env)->GetArrayLength(env, byte_array);
    jbyte *bytes = (*env)->GetByteArrayElements(env, byte_array, NULL);
    if (bytes == NULL) return;

    const char *font_name = (*env)->GetStringUTFChars(env, name, NULL);
    if (font_name != NULL) {
        ass_add_font((ASS_Library *) (intptr_t) ass, font_name, (char *) bytes, length);
        (*env)->ReleaseStringUTFChars(env, name, font_name);
    }

    // The input buffer is read-only; never copy native contents back to Java.
    (*env)->ReleaseByteArrayElements(env, byte_array, bytes, JNI_ABORT);
}

static void nativeAssClearFont(JNIEnv *env, jclass clazz, jlong ass) {
    (void) env;
    (void) clazz;
    if (ass != 0) ass_clear_fonts((ASS_Library *) (intptr_t) ass);
}

static void nativeAssDeinit(JNIEnv *env, jclass clazz, jlong ass) {
    (void) env;
    (void) clazz;
    if (ass != 0) ass_library_done((ASS_Library *) (intptr_t) ass);
}

static JNINativeMethod method_table[] = {
    {"nativeAssInit", "()J", (void *) nativeAssInit},
    {"nativeAssLibraryVersion", "()I", (void *) nativeAssLibraryVersion},
    {"nativeAssAddFont", "(JLjava/lang/String;[B)V", (void *) nativeAssAddFont},
    {"nativeAssClearFont", "(J)V", (void *) nativeAssClearFont},
    {"nativeAssDeinit", "(J)V", (void *) nativeAssDeinit},
};

// -----------------------------------------------------------------------------------------------
// ASS track
// -----------------------------------------------------------------------------------------------

static jlong nativeAssTrackInit(JNIEnv *env, jclass clazz, jlong ass) {
    (void) env;
    (void) clazz;
    if (ass == 0) return 0;
    return (jlong) (intptr_t) ass_new_track((ASS_Library *) (intptr_t) ass);
}

static jint nativeAssTrackGetWidth(JNIEnv *env, jclass clazz, jlong track) {
    (void) env;
    (void) clazz;
    return track == 0 ? 0 : ((ASS_Track *) (intptr_t) track)->PlayResX;
}

static jint nativeAssTrackGetHeight(JNIEnv *env, jclass clazz, jlong track) {
    (void) env;
    (void) clazz;
    return track == 0 ? 0 : ((ASS_Track *) (intptr_t) track)->PlayResY;
}

static jint nativeAssTrackGetYCbCrMatrix(JNIEnv *env, jclass clazz, jlong track) {
    (void) env;
    (void) clazz;
    return track == 0 ? 1 : (jint) ((ASS_Track *) (intptr_t) track)->YCbCrMatrix;
}

static jobjectArray nativeAssTrackGetEvents(JNIEnv *env, jclass clazz, jlong track) {
    (void) clazz;
    if (track == 0) return NULL;

    ASS_Track *ass_track = (ASS_Track *) (intptr_t) track;
    if (ass_track->n_events <= 0) return NULL;

    jobjectArray events = (*env)->NewObjectArray(
        env,
        ass_track->n_events,
        g_ass_event_class,
        NULL
    );
    if (events == NULL) return NULL;

    for (int i = 0; i < ass_track->n_events; ++i) {
        ASS_Event *event = &ass_track->events[i];
        jstring name = (*env)->NewStringUTF(env, event->Name != NULL ? event->Name : "");
        jstring effect = (*env)->NewStringUTF(env, event->Effect != NULL ? event->Effect : "");
        jstring text = (*env)->NewStringUTF(env, event->Text != NULL ? event->Text : "");
        if (name == NULL || effect == NULL || text == NULL) {
            if (name != NULL) (*env)->DeleteLocalRef(env, name);
            if (effect != NULL) (*env)->DeleteLocalRef(env, effect);
            if (text != NULL) (*env)->DeleteLocalRef(env, text);
            return events;
        }

        jobject java_event = (*env)->NewObject(
            env,
            g_ass_event_class,
            g_ass_event_ctor,
            (jlong) event->Start,
            (jlong) event->Duration,
            (jint) event->ReadOrder,
            (jint) event->Layer,
            (jint) event->Style,
            name,
            (jint) event->MarginL,
            (jint) event->MarginR,
            (jint) event->MarginV,
            effect,
            text
        );

        (*env)->DeleteLocalRef(env, name);
        (*env)->DeleteLocalRef(env, effect);
        (*env)->DeleteLocalRef(env, text);

        if (java_event == NULL) return events;
        (*env)->SetObjectArrayElement(env, events, i, java_event);
        (*env)->DeleteLocalRef(env, java_event);
    }

    return events;
}

static void nativeAssTrackClearEvents(JNIEnv *env, jclass clazz, jlong track) {
    (void) env;
    (void) clazz;
    if (track == 0) return;
    ASS_Track *ass_track = (ASS_Track *) (intptr_t) track;
    if (ass_track->events == NULL) {
        ass_track->n_events = 0;
        return;
    }
    for (int i = 0; i < ass_track->n_events; ++i) {
        ass_free_event(ass_track, i);
    }
    ass_track->n_events = 0;
}

static int valid_array_range(JNIEnv *env, jbyteArray array, jint offset, jint length) {
    if (array == NULL || offset < 0 || length < 0) return 0;
    jsize size = (*env)->GetArrayLength(env, array);
    return offset <= size && length <= size - offset;
}

static void nativeAssTrackReadBuffer(
    JNIEnv *env,
    jclass clazz,
    jlong track,
    jbyteArray buffer,
    jint offset,
    jint length
) {
    (void) clazz;
    if (track == 0 || !valid_array_range(env, buffer, offset, length)) return;
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) return;
    ass_process_data((ASS_Track *) (intptr_t) track, (char *) bytes + offset, length);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
}

static void nativeAssTrackReadChunk(
    JNIEnv *env,
    jclass clazz,
    jlong track,
    jlong start,
    jlong duration,
    jbyteArray buffer,
    jint offset,
    jint length
) {
    (void) clazz;
    if (track == 0 || !valid_array_range(env, buffer, offset, length)) return;
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) return;
    ass_process_chunk(
        (ASS_Track *) (intptr_t) track,
        (char *) bytes + offset,
        length,
        start,
        duration
    );
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
}

static void nativeAssTrackDeinit(JNIEnv *env, jclass clazz, jlong track) {
    (void) env;
    (void) clazz;
    if (track != 0) ass_free_track((ASS_Track *) (intptr_t) track);
}

static JNINativeMethod track_method_table[] = {
    {"nativeAssTrackInit", "(J)J", (void *) nativeAssTrackInit},
    {"nativeAssTrackGetWidth", "(J)I", (void *) nativeAssTrackGetWidth},
    {"nativeAssTrackGetHeight", "(J)I", (void *) nativeAssTrackGetHeight},
    {"nativeAssTrackGetYCbCrMatrix", "(J)I", (void *) nativeAssTrackGetYCbCrMatrix},
    {"nativeAssTrackGetEvents", "(J)[Lio/github/peerless2012/ass/AssEvent;", (void *) nativeAssTrackGetEvents},
    {"nativeAssTrackClearEvents", "(J)V", (void *) nativeAssTrackClearEvents},
    {"nativeAssTrackReadBuffer", "(J[BII)V", (void *) nativeAssTrackReadBuffer},
    {"nativeAssTrackReadChunk", "(JJJ[BII)V", (void *) nativeAssTrackReadChunk},
    {"nativeAssTrackDeinit", "(J)V", (void *) nativeAssTrackDeinit},
};

// -----------------------------------------------------------------------------------------------
// Legacy frame conversion
// -----------------------------------------------------------------------------------------------

static jobject create_bitmap(JNIEnv *env, const ASS_Image *image) {
    jobject bitmap = (*env)->CallStaticObjectMethod(
        env,
        g_bitmap_class,
        g_bitmap_create,
        image->w,
        image->h,
        g_bitmap_argb8888
    );
    if (bitmap == NULL) return NULL;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        (*env)->DeleteLocalRef(env, bitmap);
        return NULL;
    }

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || pixels == NULL) {
        (*env)->DeleteLocalRef(env, bitmap);
        return NULL;
    }

    unsigned int r = (image->color >> 24) & 0xFF;
    unsigned int g = (image->color >> 16) & 0xFF;
    unsigned int b = (image->color >> 8) & 0xFF;
    unsigned int opacity = 0xFF - (image->color & 0xFF);

    for (int y = 0; y < image->h; ++y) {
        uint32_t *line = (uint32_t *) ((char *) pixels + (size_t) y * info.stride);
        const unsigned char *mask = image->bitmap + (size_t) y * image->stride;
        for (int x = 0; x < image->w; ++x) {
            unsigned int alpha = (opacity * mask[x]) / 255;
            if (alpha == 0) {
                line[x] = 0;
                continue;
            }
            line[x] = (alpha << 24) |
                (((b * alpha) / 255) << 16) |
                (((g * alpha) / 255) << 8) |
                ((r * alpha) / 255);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

static jobject create_alpha_bitmap(JNIEnv *env, const ASS_Image *image) {
    jobject bitmap = (*env)->CallStaticObjectMethod(
        env,
        g_bitmap_class,
        g_bitmap_create,
        image->w,
        image->h,
        g_bitmap_alpha8
    );
    if (bitmap == NULL) return NULL;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        (*env)->DeleteLocalRef(env, bitmap);
        return NULL;
    }

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || pixels == NULL) {
        (*env)->DeleteLocalRef(env, bitmap);
        return NULL;
    }

    for (int y = 0; y < image->h; ++y) {
        unsigned char *dst = (unsigned char *) pixels + (size_t) y * info.stride;
        const unsigned char *src = image->bitmap + (size_t) y * image->stride;
        memcpy(dst, src, (size_t) image->w);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

static jint create_texture(const ASS_Image *image) {
    GLuint texture = 0;
    GLint previous_alignment = 4;
    GLint previous_row_length = 0;
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &previous_alignment);
    glGetIntegerv(GL_UNPACK_ROW_LENGTH_EXT, &previous_row_length);

    glGenTextures(1, &texture);
    if (texture == 0) return 0;

    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glPixelStorei(GL_UNPACK_ROW_LENGTH_EXT, image->stride);
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_ALPHA,
        image->w,
        image->h,
        0,
        GL_ALPHA,
        GL_UNSIGNED_BYTE,
        image->bitmap
    );
    glPixelStorei(GL_UNPACK_ROW_LENGTH_EXT, previous_row_length);
    glPixelStorei(GL_UNPACK_ALIGNMENT, previous_alignment);
    glBindTexture(GL_TEXTURE_2D, 0);
    return (jint) texture;
}

static int count_ass_images(const ASS_Image *images) {
    int count = 0;
    for (const ASS_Image *image = images; image != NULL; image = image->next) {
        ++count;
    }
    return count;
}

typedef struct AtlasWorkspace AtlasWorkspace;

typedef struct NativeAssRender {
    ASS_Renderer *renderer;
    AtlasWorkspace *atlas;
    int frame_width;
    int frame_height;
} NativeAssRender;

static void free_atlas_workspace(AtlasWorkspace *workspace);

static NativeAssRender *native_ass_render(jlong handle) {
    return (NativeAssRender *) (intptr_t) handle;
}

static ASS_Renderer *ass_renderer(jlong handle) {
    NativeAssRender *render = native_ass_render(handle);
    return render == NULL ? NULL : render->renderer;
}

static jobject nativeAssRenderFrame(
    JNIEnv *env,
    jclass clazz,
    jlong render,
    jlong track,
    jlong time,
    jint type
) {
    (void) clazz;
    if (render == 0 || track == 0) return NULL;

    int changed = 0;
    ASS_Image *images = ass_render_frame(
        ass_renderer(render),
        (ASS_Track *) (intptr_t) track,
        time,
        &changed
    );

    if (images == NULL) {
        if (changed == 0) return NULL;
        // Preserve empty changed frames so bitmap renderers clear stale subtitles.
        return (*env)->NewObject(env, g_ass_frame_class, g_ass_frame_ctor, NULL, changed);
    }
    if (changed == 0) {
        return (*env)->NewObject(env, g_ass_frame_class, g_ass_frame_ctor, NULL, changed);
    }

    int count = count_ass_images(images);
    jobjectArray textures = (*env)->NewObjectArray(env, count, g_ass_tex_class, NULL);
    if (textures == NULL) return NULL;

    int index = 0;
    for (ASS_Image *image = images; image != NULL; image = image->next, ++index) {
        jobject bitmap = NULL;
        jint texture = 0;
        if (image->w > 0 && image->h > 0 && image->bitmap != NULL) {
            if (type == 0) {
                bitmap = create_bitmap(env, image);
            } else if (type == 1) {
                bitmap = create_alpha_bitmap(env, image);
            } else if (type == 2) {
                texture = create_texture(image);
            }
        }

        jobject ass_texture = (*env)->NewObject(
            env,
            g_ass_tex_class,
            g_ass_tex_ctor,
            image->dst_x,
            image->dst_y,
            image->w,
            image->h,
            (jint) image->color,
            bitmap,
            texture
        );
        if (ass_texture != NULL) {
            (*env)->SetObjectArrayElement(env, textures, index, ass_texture);
            (*env)->DeleteLocalRef(env, ass_texture);
        }
        if (bitmap != NULL) (*env)->DeleteLocalRef(env, bitmap);
    }

    jobject frame = (*env)->NewObject(
        env,
        g_ass_frame_class,
        g_ass_frame_ctor,
        textures,
        changed
    );
    (*env)->DeleteLocalRef(env, textures);
    return frame;
}

// -----------------------------------------------------------------------------------------------
// Batched atlas conversion
// -----------------------------------------------------------------------------------------------

typedef struct AtlasEntry {
    const ASS_Image *image;
    int page;
    int atlas_x;
    int atlas_y;
    int padded_width;
    int padded_height;
} AtlasEntry;

typedef struct AtlasPage {
    int width;
    int height;
    int cursor_x;
    int cursor_y;
    int row_height;
    int max_x;
    int max_y;
} AtlasPage;

#define ATLAS_BUFFER_SLOTS 3

typedef struct DirectAtlasPage {
    unsigned char *bytes;
    size_t capacity;
} DirectAtlasPage;

typedef struct PreviousAtlasEntry {
    int width;
    int height;
    int page;
    int atlas_x;
    int atlas_y;
} PreviousAtlasEntry;

typedef struct AtlasBufferSlot {
    DirectAtlasPage packet;
} AtlasBufferSlot;

struct AtlasWorkspace {
    AtlasEntry *entries;
    AtlasPage *layout_pages;
    jint *widths;
    jint *heights;
    jint *quads;
    size_t image_capacity;
    size_t metadata_page_capacity;
    AtlasBufferSlot slots[ATLAS_BUFFER_SLOTS];
    DirectAtlasPage *previous_masks;
    size_t previous_mask_capacity;
    int next_slot;
    int last_page_count;
    jint *last_widths;
    jint *last_heights;
    size_t last_page_capacity;
    PreviousAtlasEntry *previous_entries;
    size_t previous_entry_capacity;
    int previous_entry_count;
    uint64_t content_serial;
};

static int reserve_array(void **array, size_t *capacity, size_t required, size_t element_size) {
    if (*capacity >= required) return 1;
    size_t new_capacity = *capacity > 0 ? *capacity : 4;
    while (new_capacity < required) {
        if (new_capacity > SIZE_MAX / 2) {
            new_capacity = required;
            break;
        }
        new_capacity *= 2;
    }
    if (new_capacity > SIZE_MAX / element_size) return 0;
    void *replacement = realloc(*array, new_capacity * element_size);
    if (replacement == NULL) return 0;
    *array = replacement;
    *capacity = new_capacity;
    return 1;
}

static int reserve_workspace(AtlasWorkspace *workspace, size_t image_count) {
    if (workspace->image_capacity >= image_count) return 1;
    size_t capacity = workspace->image_capacity;
    if (!reserve_array((void **) &workspace->entries, &capacity, image_count, sizeof(AtlasEntry))) {
        return 0;
    }
    AtlasPage *pages = realloc(workspace->layout_pages, capacity * sizeof(AtlasPage));
    jint *quads = realloc(workspace->quads, capacity * ATLAS_QUAD_STRIDE * sizeof(jint));
    if (pages == NULL || quads == NULL) {
        if (pages != NULL) workspace->layout_pages = pages;
        if (quads != NULL) workspace->quads = quads;
        return 0;
    }
    workspace->layout_pages = pages;
    workspace->quads = quads;
    workspace->image_capacity = capacity;
    return 1;
}

static int reserve_page_metadata(AtlasWorkspace *workspace, size_t page_count) {
    if (workspace->metadata_page_capacity >= page_count) return 1;
    size_t new_capacity = workspace->metadata_page_capacity > 0 ? workspace->metadata_page_capacity : 4;
    while (new_capacity < page_count) new_capacity *= 2;
    jint *widths = realloc(workspace->widths, new_capacity * sizeof(jint));
    jint *heights = realloc(workspace->heights, new_capacity * sizeof(jint));
    if (widths == NULL || heights == NULL) {
        if (widths != NULL) workspace->widths = widths;
        if (heights != NULL) workspace->heights = heights;
        return 0;
    }
    workspace->widths = widths;
    workspace->heights = heights;
    workspace->metadata_page_capacity = new_capacity;
    return 1;
}

static int reserve_direct_page(DirectAtlasPage *page, size_t required) {
    if (page->capacity >= required) return 1;
    size_t capacity = page->capacity > 0 ? page->capacity : 1024;
    while (capacity < required) {
        if (capacity > SIZE_MAX / 2) {
            capacity = required;
            break;
        }
        capacity *= 2;
    }
    unsigned char *bytes = realloc(page->bytes, capacity);
    if (bytes == NULL) return 0;
    page->bytes = bytes;
    page->capacity = capacity;
    return 1;
}

static int reserve_last_layout(AtlasWorkspace *workspace, size_t page_count) {
    if (workspace->last_page_capacity >= page_count) return 1;
    size_t capacity = workspace->last_page_capacity > 0 ? workspace->last_page_capacity : 4;
    while (capacity < page_count) capacity *= 2;
    jint *widths = realloc(workspace->last_widths, capacity * sizeof(jint));
    jint *heights = realloc(workspace->last_heights, capacity * sizeof(jint));
    if (widths == NULL || heights == NULL) {
        if (widths != NULL) workspace->last_widths = widths;
        if (heights != NULL) workspace->last_heights = heights;
        return 0;
    }
    workspace->last_widths = widths;
    workspace->last_heights = heights;
    workspace->last_page_capacity = capacity;
    return 1;
}

static int checked_add_size(size_t left, size_t right, size_t *result) {
    if (left > SIZE_MAX - right) return 0;
    *result = left + right;
    return 1;
}

static int checked_mul_size(size_t left, size_t right, size_t *result) {
    if (left != 0 && right > SIZE_MAX / left) return 0;
    *result = left * right;
    return 1;
}

static int align_size_8(size_t value, size_t *result) {
    size_t aligned;
    if (!checked_add_size(value, 7, &aligned)) return 0;
    *result = aligned & ~(size_t) 7;
    return 1;
}

static void packet_put_u32(unsigned char *packet, size_t offset, uint32_t value) {
    memcpy(packet + offset, &value, sizeof(value));
}

static void packet_put_u64(unsigned char *packet, size_t offset, uint64_t value) {
    memcpy(packet + offset, &value, sizeof(value));
}

static int reserve_previous_masks(AtlasWorkspace *workspace, size_t image_count) {
    if (workspace->previous_mask_capacity >= image_count) return 1;
    size_t old_capacity = workspace->previous_mask_capacity;
    size_t new_capacity = old_capacity > 0 ? old_capacity : 4;
    while (new_capacity < image_count) new_capacity *= 2;
    DirectAtlasPage *masks = realloc(
        workspace->previous_masks,
        new_capacity * sizeof(DirectAtlasPage)
    );
    if (masks == NULL) return 0;
    memset(masks + old_capacity, 0, (new_capacity - old_capacity) * sizeof(DirectAtlasPage));
    workspace->previous_masks = masks;
    workspace->previous_mask_capacity = new_capacity;
    return 1;
}

static int reserve_previous_entries(AtlasWorkspace *workspace, size_t image_count) {
    return reserve_array(
        (void **) &workspace->previous_entries,
        &workspace->previous_entry_capacity,
        image_count,
        sizeof(PreviousAtlasEntry)
    );
}

static void free_atlas_workspace(AtlasWorkspace *workspace) {
    if (workspace == NULL) return;
    free(workspace->entries);
    free(workspace->layout_pages);
    free(workspace->widths);
    free(workspace->heights);
    free(workspace->quads);
    free(workspace->last_widths);
    free(workspace->last_heights);
    free(workspace->previous_entries);
    for (size_t mask = 0; mask < workspace->previous_mask_capacity; ++mask) {
        free(workspace->previous_masks[mask].bytes);
    }
    free(workspace->previous_masks);
    for (int slot_index = 0; slot_index < ATLAS_BUFFER_SLOTS; ++slot_index) {
        free(workspace->slots[slot_index].packet.bytes);
    }
    free(workspace);
}

static uint64_t ceil_sqrt_u64(uint64_t value) {
    if (value <= 1) return value;
    uint64_t low = 1;
    uint64_t high = 1;
    while (high <= value / high) {
        high <<= 1;
        if (high == 0) {
            high = UINT64_MAX;
            break;
        }
    }
    while (low + 1 < high) {
        uint64_t mid = low + (high - low) / 2;
        if (mid > value / mid) {
            high = mid;
        } else if (mid * mid == value) {
            return mid;
        } else {
            low = mid;
        }
    }
    return high;
}

static int next_power_of_two(int value) {
    if (value <= 1) return 1;
    unsigned int v = (unsigned int) value - 1;
    v |= v >> 1;
    v |= v >> 2;
    v |= v >> 4;
    v |= v >> 8;
    v |= v >> 16;
    if (v == UINT32_MAX) return INT32_MAX;
    return (int) (v + 1);
}

static int is_renderable_image(const ASS_Image *image) {
    if (image == NULL || image->bitmap == NULL || image->w <= 0 || image->h <= 0) return 0;
    return (0xFF - (image->color & 0xFF)) > 0;
}

static int count_renderable_images(const ASS_Image *images) {
    int count = 0;
    for (const ASS_Image *image = images; image != NULL; image = image->next) {
        if (!is_renderable_image(image)) continue;
        if (count == INT_MAX) return -1;
        ++count;
    }
    return count;
}

static int is_previous_atlas_layout_compatible(
    const AtlasWorkspace *workspace,
    AtlasEntry *entries,
    int count
) {
    if (workspace->previous_entry_count != count || workspace->last_page_count <= 0) return 0;
    for (int i = 0; i < count; ++i) {
        const ASS_Image *image = entries[i].image;
        const PreviousAtlasEntry *previous = &workspace->previous_entries[i];
        if (previous->width != image->w || previous->height != image->h) {
            return 0;
        }
        entries[i].page = previous->page;
        entries[i].atlas_x = previous->atlas_x;
        entries[i].atlas_y = previous->atlas_y;
    }
    return 1;
}

static int is_previous_mask_identical(
    const AtlasWorkspace *workspace,
    const AtlasEntry *entry,
    int index
) {
    const ASS_Image *image = entry->image;
    if (index < 0 || index >= workspace->previous_entry_count) return 0;
    const unsigned char *mask = workspace->previous_masks[index].bytes;
    if (mask == NULL) return 0;
    for (int y = 0; y < image->h; ++y) {
        const unsigned char *previous = mask + (size_t) y * image->w;
        const unsigned char *current = image->bitmap + (size_t) y * image->stride;
        if (memcmp(previous, current, (size_t) image->w) != 0) return 0;
    }
    return 1;
}

static int are_uploaded_atlas_masks_identical(
    const AtlasWorkspace *workspace,
    const AtlasEntry *entries,
    int count
) {
    for (int i = 0; i < count; ++i) {
        if (!is_previous_mask_identical(workspace, &entries[i], i)) return 0;
    }
    return 1;
}

static void save_previous_entries(
    AtlasWorkspace *workspace,
    AtlasEntry *entries,
    int count,
    int save_masks
) {
    for (int i = 0; i < count; ++i) {
        const ASS_Image *image = entries[i].image;
        PreviousAtlasEntry *previous = &workspace->previous_entries[i];
        previous->width = image->w;
        previous->height = image->h;
        previous->page = entries[i].page;
        previous->atlas_x = entries[i].atlas_x;
        previous->atlas_y = entries[i].atlas_y;
        if (save_masks) {
            unsigned char *mask = workspace->previous_masks[i].bytes;
            for (int y = 0; y < image->h; ++y) {
                memcpy(
                    mask + (size_t) y * image->w,
                    image->bitmap + (size_t) y * image->stride,
                    (size_t) image->w
                );
            }
        }
    }
    workspace->previous_entry_count = count;
}

static void compute_active_bounds(
    const AtlasEntry *entries,
    int count,
    int frame_width,
    int frame_height,
    jint bounds[ATLAS_ACTIVE_BOUNDS_STRIDE]
) {
    bounds[0] = bounds[1] = bounds[2] = bounds[3] = 0;
    if (frame_width <= 0 || frame_height <= 0 || count <= 0) return;
    int left = frame_width;
    int top = frame_height;
    int right = 0;
    int bottom = 0;
    for (int i = 0; i < count; ++i) {
        const ASS_Image *image = entries[i].image;
        int64_t image_right = (int64_t) image->dst_x + image->w;
        int64_t image_bottom = (int64_t) image->dst_y + image->h;
        int x0 = image->dst_x < 0 ? 0 : image->dst_x;
        int y0 = image->dst_y < 0 ? 0 : image->dst_y;
        int x1 = image_right > frame_width ? frame_width : (int) image_right;
        int y1 = image_bottom > frame_height ? frame_height : (int) image_bottom;
        if (x0 >= x1 || y0 >= y1) continue;
        if (x0 < left) left = x0;
        if (y0 < top) top = y0;
        if (x1 > right) right = x1;
        if (y1 > bottom) bottom = y1;
    }
    if (left >= right || top >= bottom) return;
    bounds[0] = left;
    bounds[1] = top;
    bounds[2] = right - left;
    bounds[3] = bottom - top;
}

static int choose_atlas_width(
    const AtlasEntry *entries,
    int count,
    int max_atlas_size
) {
    uint64_t total_area = 0;
    int widest = 1;
    for (int i = 0; i < count; ++i) {
        uint64_t area = (uint64_t) entries[i].padded_width * entries[i].padded_height;
        total_area = UINT64_MAX - total_area < area ? UINT64_MAX : total_area + area;
        if (entries[i].padded_width > widest) widest = entries[i].padded_width;
    }

    uint64_t root = ceil_sqrt_u64(total_area);
    int target = root > INT32_MAX ? max_atlas_size : (int) root;
    if (target < widest) target = widest;
    if (target < 64) target = 64;
    target = next_power_of_two(target);
    if (target > max_atlas_size) target = max_atlas_size;
    return target;
}

static int layout_atlas(
    AtlasEntry *entries,
    int count,
    AtlasPage *pages,
    int max_pages,
    int packing_width,
    int max_atlas_size
) {
    if (count == 0) return 0;
    if (packing_width <= 0 || packing_width > max_atlas_size) return -1;

    int page_index = 0;
    memset(&pages[0], 0, sizeof(AtlasPage));

    for (int i = 0; i < count; ++i) {
        AtlasEntry *entry = &entries[i];
        AtlasPage *page = &pages[page_index];

        if (entry->image->w > max_atlas_size || entry->image->h > max_atlas_size) {
            return -1;
        }

        if (entry->padded_width > packing_width) {
            // A full-width mask cannot have a gutter on both sides.
            entry->padded_width = entry->image->w;
        }
        if (entry->padded_height > max_atlas_size) {
            entry->padded_height = entry->image->h;
        }
        if (entry->padded_width > packing_width || entry->padded_height > max_atlas_size) {
            return -1;
        }

        if (page->cursor_x > 0 &&
            (int64_t) page->cursor_x + entry->padded_width > packing_width) {
            int64_t next_row = (int64_t) page->cursor_y + page->row_height;
            if (next_row > INT_MAX) return -1;
            page->cursor_y = (int) next_row;
            page->cursor_x = 0;
            page->row_height = 0;
        }

        if ((int64_t) page->cursor_y + entry->padded_height > max_atlas_size) {
            page->width = page->max_x;
            page->height = page->max_y;
            ++page_index;
            if (page_index >= max_pages) return -1;
            page = &pages[page_index];
            memset(page, 0, sizeof(AtlasPage));
        }

        int gutter_x = entry->padded_width > entry->image->w ? ATLAS_GUTTER : 0;
        int gutter_y = entry->padded_height > entry->image->h ? ATLAS_GUTTER : 0;
        entry->page = page_index;
        entry->atlas_x = page->cursor_x + gutter_x;
        entry->atlas_y = page->cursor_y + gutter_y;

        page->cursor_x += entry->padded_width;
        if (entry->padded_height > page->row_height) page->row_height = entry->padded_height;
        if (page->cursor_x > page->max_x) page->max_x = page->cursor_x;
        int64_t row_bottom = (int64_t) page->cursor_y + page->row_height;
        if (row_bottom > INT_MAX) return -1;
        if ((int) row_bottom > page->max_y) page->max_y = (int) row_bottom;
    }

    AtlasPage *last_page = &pages[page_index];
    last_page->width = last_page->max_x;
    last_page->height = last_page->max_y;
    return page_index + 1;
}

static jobject new_packet_frame(JNIEnv *env, unsigned char *bytes, size_t size) {
    jobject buffer = (*env)->NewDirectByteBuffer(env, bytes, (jlong) size);
    if (buffer == NULL) return NULL;
    jobject frame = (*env)->NewObject(
        env,
        g_ass_atlas_frame_class,
        g_ass_atlas_frame_ctor,
        buffer
    );
    (*env)->DeleteLocalRef(env, buffer);
    return frame;
}

static jobject new_empty_atlas_frame(
    JNIEnv *env,
    AtlasWorkspace *workspace,
    int changed,
    uint64_t content_serial
) {
    int slot_index = workspace->next_slot;
    workspace->next_slot = (slot_index + 1) % ATLAS_BUFFER_SLOTS;
    DirectAtlasPage *packet_slot = &workspace->slots[slot_index].packet;
    if (!reserve_direct_page(packet_slot, ATLAS_PACKET_HEADER_SIZE)) return NULL;
    unsigned char *packet = packet_slot->bytes;
    memset(packet, 0, ATLAS_PACKET_HEADER_SIZE);
    packet_put_u32(packet, 0, ATLAS_PACKET_MAGIC);
    packet_put_u32(packet, 4, ATLAS_PACKET_VERSION);
    packet_put_u32(packet, 8, ATLAS_PACKET_HEADER_SIZE);
    packet_put_u32(packet, 12, (uint32_t) changed);
    packet_put_u64(packet, 48, content_serial);
    packet_put_u64(packet, 56, content_serial);
    packet_put_u32(packet, 72, ATLAS_PACKET_HEADER_SIZE);
    packet_put_u32(packet, 76, ATLAS_PACKET_HEADER_SIZE);
    packet_put_u32(packet, 80, ATLAS_PACKET_HEADER_SIZE);
    packet_put_u32(packet, 84, ATLAS_PACKET_HEADER_SIZE);
    return new_packet_frame(env, packet, ATLAS_PACKET_HEADER_SIZE);
}

static jobject nativeAssRenderAtlasFrame(
    JNIEnv *env,
    jclass clazz,
    jlong render,
    jlong track,
    jlong time,
    jint max_atlas_size,
    jboolean allow_incremental,
    jboolean force_replacement
) {
    (void) clazz;
    if (render == 0 || track == 0 || max_atlas_size <= 0) return NULL;
    NativeAssRender *native = native_ass_render(render);
    if (native == NULL || native->renderer == NULL) return NULL;
    if (native->atlas == NULL) {
        native->atlas = calloc(1, sizeof(AtlasWorkspace));
        if (native->atlas == NULL) return NULL;
    }
    AtlasWorkspace *workspace = native->atlas;

    int changed = 0;
    ASS_Image *images = ass_render_frame(
        native->renderer,
        (ASS_Track *) (intptr_t) track,
        time,
        &changed
    );

    // Null is the zero-allocation unchanged sentinel for the atlas API.
    if (changed == 0 && !force_replacement) return NULL;

    int count = count_renderable_images(images);
    if (count == 0) {
        workspace->content_serial++;
        workspace->previous_entry_count = 0;
        workspace->last_page_count = 0;
        return new_empty_atlas_frame(
            env,
            workspace,
            ATLAS_CHANGE_REPLACE,
            workspace->content_serial
        );
    }
    if (count < 0 || count > INT_MAX / ATLAS_QUAD_STRIDE) return NULL;
    if ((size_t) count > SIZE_MAX / sizeof(AtlasEntry) ||
        (size_t) count > SIZE_MAX / sizeof(AtlasPage) ||
        (size_t) count > SIZE_MAX / ATLAS_QUAD_STRIDE / sizeof(jint)) {
        return NULL;
    }

    jobject result = NULL;
    AtlasEntry *entries = workspace->entries;
    AtlasPage *pages = workspace->layout_pages;
    uint64_t copied_mask_bytes = 0;
    uint64_t base_content_serial = workspace->content_serial;
    uint64_t result_content_serial = base_content_serial;
    int patch_count = 0;
    if (!reserve_workspace(workspace, (size_t) count) ||
        !reserve_previous_entries(workspace, (size_t) count)) goto cleanup;
    entries = workspace->entries;
    pages = workspace->layout_pages;
    memset(entries, 0, (size_t) count * sizeof(AtlasEntry));
    memset(pages, 0, (size_t) count * sizeof(AtlasPage));

    int entry_index = 0;
    for (ASS_Image *image = images; image != NULL; image = image->next) {
        if (!is_renderable_image(image)) continue;
        AtlasEntry *entry = &entries[entry_index++];
        entry->image = image;
        entry->padded_width = image->w;
        entry->padded_height = image->h;
        if (image->w <= max_atlas_size - 2) entry->padded_width += 2;
        if (image->h <= max_atlas_size - 2) entry->padded_height += 2;
    }
    if (!reserve_previous_masks(workspace, (size_t) count)) goto cleanup;
    for (int i = 0; i < count; ++i) {
        const ASS_Image *image = entries[i].image;
        if ((size_t) image->w > SIZE_MAX / (size_t) image->h ||
            !reserve_direct_page(
                &workspace->previous_masks[i],
                (size_t) image->w * (size_t) image->h
            )) {
            goto cleanup;
        }
    }

    int reused_layout = is_previous_atlas_layout_compatible(workspace, entries, count);
    int reused_masks = !force_replacement && reused_layout &&
        (changed == 1 || are_uploaded_atlas_masks_identical(workspace, entries, count));
    int page_count = workspace->last_page_count;
    int output_change = ATLAS_CHANGE_METADATA;
    if (reused_masks) {
        for (int page = 0; page < page_count; ++page) {
            pages[page].width = workspace->last_widths[page];
            pages[page].height = workspace->last_heights[page];
        }
    } else if (!force_replacement && reused_layout && allow_incremental) {
        output_change = ATLAS_CHANGE_INCREMENTAL;
        for (int page = 0; page < page_count; ++page) {
            pages[page].width = workspace->last_widths[page];
            pages[page].height = workspace->last_heights[page];
        }
        for (int i = 0; i < count; ++i) {
            if (!is_previous_mask_identical(workspace, &entries[i], i)) ++patch_count;
        }
    } else {
        output_change = ATLAS_CHANGE_REPLACE;
        int packing_width = choose_atlas_width(entries, count, max_atlas_size);
        page_count = layout_atlas(
            entries,
            count,
            pages,
            count,
            packing_width,
            max_atlas_size
        );
        if (page_count < 0 && packing_width < max_atlas_size) {
            memset(pages, 0, (size_t) count * sizeof(AtlasPage));
            page_count = layout_atlas(
                entries,
                count,
                pages,
                count,
                max_atlas_size,
                max_atlas_size
            );
        }
    }
    if (page_count <= 0) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            LOG_TAG,
            "Unable to pack libass masks into GL atlas"
        );
        goto cleanup;
    }

    if (!reserve_page_metadata(workspace, (size_t) page_count)) goto cleanup;
    jint *width_values = workspace->widths;
    jint *height_values = workspace->heights;
    jint *quad_values = workspace->quads;

    for (int page = 0; page < page_count; ++page) {
        width_values[page] = pages[page].width;
        height_values[page] = pages[page].height;
    }
    for (int i = 0; i < count; ++i) {
        const AtlasEntry *entry = &entries[i];
        const ASS_Image *image = entry->image;
        int offset = i * ATLAS_QUAD_STRIDE;
        quad_values[offset + 0] = image->dst_x;
        quad_values[offset + 1] = image->dst_y;
        quad_values[offset + 2] = image->w;
        quad_values[offset + 3] = image->h;
        quad_values[offset + 4] = (jint) image->color;
        quad_values[offset + 5] = entry->page;
        quad_values[offset + 6] = entry->atlas_x;
        quad_values[offset + 7] = entry->atlas_y;
    }

    jint active_values[ATLAS_ACTIVE_BOUNDS_STRIDE];
    compute_active_bounds(
        entries,
        count,
        native->frame_width,
        native->frame_height,
        active_values
    );
    if (output_change == ATLAS_CHANGE_INCREMENTAL) {
        if (base_content_serial == UINT64_MAX || patch_count <= 0) goto cleanup;
        result_content_serial = base_content_serial + 1;
    } else if (output_change == ATLAS_CHANGE_REPLACE) {
        if (base_content_serial == UINT64_MAX ||
            !reserve_last_layout(workspace, (size_t) page_count)) goto cleanup;
        result_content_serial = base_content_serial + 1;
    }

    size_t page_record_bytes;
    size_t quad_record_bytes;
    size_t patch_record_bytes;
    size_t quad_records_offset;
    size_t patch_records_offset;
    size_t payload_offset;
    size_t total_size;
    if (!checked_mul_size((size_t) page_count, ATLAS_PAGE_RECORD_SIZE, &page_record_bytes) ||
        !checked_mul_size((size_t) count, ATLAS_QUAD_RECORD_SIZE, &quad_record_bytes) ||
        !checked_mul_size((size_t) patch_count, ATLAS_PATCH_RECORD_SIZE, &patch_record_bytes) ||
        !checked_add_size(ATLAS_PACKET_HEADER_SIZE, page_record_bytes, &quad_records_offset) ||
        !checked_add_size(quad_records_offset, quad_record_bytes, &patch_records_offset)) {
        goto cleanup;
    }
    size_t metadata_end;
    if (!checked_add_size(patch_records_offset, patch_record_bytes, &metadata_end) ||
        !align_size_8(metadata_end, &payload_offset)) goto cleanup;

    size_t payload_bytes = 0;
    if (output_change == ATLAS_CHANGE_REPLACE) {
        for (int page = 0; page < page_count; ++page) {
            size_t page_bytes;
            if (!checked_mul_size((size_t) pages[page].width, (size_t) pages[page].height, &page_bytes) ||
                !checked_add_size(payload_bytes, page_bytes, &payload_bytes)) goto cleanup;
        }
    } else if (output_change == ATLAS_CHANGE_INCREMENTAL) {
        for (int i = 0; i < count; ++i) {
            if (is_previous_mask_identical(workspace, &entries[i], i)) continue;
            size_t patch_bytes;
            if (!checked_mul_size(
                    (size_t) entries[i].image->w,
                    (size_t) entries[i].image->h,
                    &patch_bytes
                ) || !checked_add_size(payload_bytes, patch_bytes, &payload_bytes)) goto cleanup;
        }
    }
    if (!checked_add_size(payload_offset, payload_bytes, &total_size) || total_size > INT_MAX) {
        goto cleanup;
    }

    int slot_index = workspace->next_slot;
    workspace->next_slot = (slot_index + 1) % ATLAS_BUFFER_SLOTS;
    DirectAtlasPage *packet_slot = &workspace->slots[slot_index].packet;
    if (!reserve_direct_page(packet_slot, total_size)) goto cleanup;
    unsigned char *packet = packet_slot->bytes;
    memset(packet, 0, payload_offset);
    packet_put_u32(packet, 0, ATLAS_PACKET_MAGIC);
    packet_put_u32(packet, 4, ATLAS_PACKET_VERSION);
    packet_put_u32(packet, 8, (uint32_t) total_size);
    packet_put_u32(packet, 12, (uint32_t) output_change);
    packet_put_u32(packet, 16, (uint32_t) page_count);
    packet_put_u32(packet, 20, (uint32_t) count);
    packet_put_u32(packet, 24, (uint32_t) patch_count);
    packet_put_u32(packet, 28, ATLAS_PACKET_FLAG_ACTIVE_BOUNDS);
    for (int component = 0; component < ATLAS_ACTIVE_BOUNDS_STRIDE; ++component) {
        packet_put_u32(packet, 32 + component * sizeof(uint32_t), (uint32_t) active_values[component]);
    }
    packet_put_u64(packet, 48, result_content_serial);
    packet_put_u64(packet, 56, base_content_serial);
    packet_put_u32(packet, 72, ATLAS_PACKET_HEADER_SIZE);
    packet_put_u32(packet, 76, (uint32_t) quad_records_offset);
    packet_put_u32(packet, 80, (uint32_t) patch_records_offset);
    packet_put_u32(packet, 84, (uint32_t) payload_offset);

    for (int page = 0; page < page_count; ++page) {
        size_t record = ATLAS_PACKET_HEADER_SIZE + (size_t) page * ATLAS_PAGE_RECORD_SIZE;
        packet_put_u32(packet, record, (uint32_t) width_values[page]);
        packet_put_u32(packet, record + 4, (uint32_t) height_values[page]);
    }
    for (int i = 0; i < count; ++i) {
        size_t record = quad_records_offset + (size_t) i * ATLAS_QUAD_RECORD_SIZE;
        for (int component = 0; component < ATLAS_QUAD_STRIDE; ++component) {
            packet_put_u32(
                packet,
                record + (size_t) component * sizeof(uint32_t),
                (uint32_t) quad_values[i * ATLAS_QUAD_STRIDE + component]
            );
        }
    }

    size_t payload_cursor = payload_offset;
    if (output_change == ATLAS_CHANGE_INCREMENTAL) {
        int patch_index = 0;
        for (int i = 0; i < count; ++i) {
            AtlasEntry *entry = &entries[i];
            if (is_previous_mask_identical(workspace, entry, i)) continue;
            const ASS_Image *image = entry->image;
            size_t byte_count = (size_t) image->w * (size_t) image->h;
            size_t record = patch_records_offset + (size_t) patch_index * ATLAS_PATCH_RECORD_SIZE;
            packet_put_u32(packet, record, (uint32_t) entry->page);
            packet_put_u32(packet, record + 4, (uint32_t) entry->atlas_x);
            packet_put_u32(packet, record + 8, (uint32_t) entry->atlas_y);
            packet_put_u32(packet, record + 12, (uint32_t) image->w);
            packet_put_u32(packet, record + 16, (uint32_t) image->h);
            packet_put_u32(packet, record + 20, (uint32_t) payload_cursor);
            packet_put_u32(packet, record + 24, (uint32_t) byte_count);
            for (int y = 0; y < image->h; ++y) {
                const unsigned char *src = image->bitmap + (size_t) y * image->stride;
                memcpy(packet + payload_cursor + (size_t) y * image->w, src, (size_t) image->w);
            }
            payload_cursor += byte_count;
            copied_mask_bytes += byte_count;
            ++patch_index;
        }
        if (patch_index != patch_count) goto cleanup;
    } else if (output_change == ATLAS_CHANGE_REPLACE) {
        for (int page_index = 0; page_index < page_count; ++page_index) {
            int width = pages[page_index].width;
            int height = pages[page_index].height;
            size_t byte_count = (size_t) width * (size_t) height;
            size_t record = ATLAS_PACKET_HEADER_SIZE +
                (size_t) page_index * ATLAS_PAGE_RECORD_SIZE;
            packet_put_u32(packet, record + 8, (uint32_t) payload_cursor);
            packet_put_u32(packet, record + 12, (uint32_t) byte_count);
            packet_put_u32(packet, record + 24, (uint32_t) width);
            packet_put_u32(packet, record + 28, (uint32_t) height);
            unsigned char *page_bytes = packet + payload_cursor;
            memset(page_bytes, 0, byte_count);
            for (int i = 0; i < count; ++i) {
                const AtlasEntry *entry = &entries[i];
                if (entry->page != page_index) continue;
                const ASS_Image *image = entry->image;
                copied_mask_bytes += (uint64_t) image->w * (uint64_t) image->h;
                for (int y = 0; y < image->h; ++y) {
                    unsigned char *dst = page_bytes +
                        (size_t) (entry->atlas_y + y) * (size_t) width + entry->atlas_x;
                    const unsigned char *src = image->bitmap + (size_t) y * image->stride;
                    memcpy(dst, src, (size_t) image->w);
                }
            }
            payload_cursor += byte_count;
        }
    }
    if (payload_cursor != total_size || copied_mask_bytes > INT64_MAX) goto cleanup;
    packet_put_u64(packet, 64, copied_mask_bytes);
    result = new_packet_frame(env, packet, total_size);
    if (result != NULL) {
        if (output_change == ATLAS_CHANGE_REPLACE) {
            memcpy(workspace->last_widths, width_values, (size_t) page_count * sizeof(jint));
            memcpy(workspace->last_heights, height_values, (size_t) page_count * sizeof(jint));
            workspace->last_page_count = page_count;
            workspace->content_serial = result_content_serial;
        } else if (output_change == ATLAS_CHANGE_INCREMENTAL) {
            workspace->content_serial = result_content_serial;
        }
        save_previous_entries(
            workspace,
            entries,
            count,
            output_change != ATLAS_CHANGE_METADATA
        );
    }

cleanup:
    return result;
}

// -----------------------------------------------------------------------------------------------
// ASS renderer
// -----------------------------------------------------------------------------------------------

static jlong nativeAssRenderInit(JNIEnv *env, jclass clazz, jlong ass) {
    (void) env;
    (void) clazz;
    if (ass == 0) return 0;
    ASS_Renderer *renderer = ass_renderer_init((ASS_Library *) (intptr_t) ass);
    if (renderer == NULL) return 0;
    NativeAssRender *native = calloc(1, sizeof(NativeAssRender));
    if (native == NULL) {
        ass_renderer_done(renderer);
        return 0;
    }
    native->renderer = renderer;
    ass_set_fonts(renderer, NULL, "sans-serif", ASS_FONTPROVIDER_FONTCONFIG, NULL, 1);
    return (jlong) (intptr_t) native;
}

static void nativeAssRenderSetFontScale(JNIEnv *env, jclass clazz, jlong render, jfloat scale) {
    (void) env;
    (void) clazz;
    ASS_Renderer *renderer = ass_renderer(render);
    if (renderer != NULL) ass_set_font_scale(renderer, scale);
}

static void nativeAssRenderSetCacheLimit(
    JNIEnv *env,
    jclass clazz,
    jlong render,
    jint glyph_max,
    jint bitmap_max_size
) {
    (void) env;
    (void) clazz;
    ASS_Renderer *renderer = ass_renderer(render);
    if (renderer != NULL) ass_set_cache_limits(renderer, glyph_max, bitmap_max_size);
}

static jint nativeAssRenderSetThreads(
    JNIEnv *env,
    jclass clazz,
    jlong render,
    jint threads
) {
    (void) env;
    (void) clazz;
    ASS_Renderer *renderer = ass_renderer(render);
    if (renderer == NULL || threads <= 0) return 0;
    return (jint) ass_set_threads(renderer, (unsigned) threads);
}

static void nativeAssRenderSetFrameSize(
    JNIEnv *env,
    jclass clazz,
    jlong render,
    jint width,
    jint height
) {
    (void) env;
    (void) clazz;
    NativeAssRender *native = native_ass_render(render);
    if (native != NULL && native->renderer != NULL) {
        native->frame_width = width;
        native->frame_height = height;
        ass_set_frame_size(native->renderer, width, height);
    }
}

static void nativeAssRenderSetStorageSize(
    JNIEnv *env,
    jclass clazz,
    jlong render,
    jint width,
    jint height
) {
    (void) env;
    (void) clazz;
    ASS_Renderer *renderer = ass_renderer(render);
    if (renderer != NULL) ass_set_storage_size(renderer, width, height);
}

static void nativeAssRenderSetPixelAspect(
    JNIEnv *env,
    jclass clazz,
    jlong render,
    jdouble pixel_aspect
) {
    (void) env;
    (void) clazz;
    ASS_Renderer *renderer = ass_renderer(render);
    if (renderer != NULL) ass_set_pixel_aspect(renderer, pixel_aspect);
}

static void nativeAssRenderDeinit(JNIEnv *env, jclass clazz, jlong render) {
    (void) env;
    (void) clazz;
    NativeAssRender *native = native_ass_render(render);
    if (native == NULL) return;
    free_atlas_workspace(native->atlas);
    if (native->renderer != NULL) ass_renderer_done(native->renderer);
    free(native);
}

static JNINativeMethod render_method_table[] = {
    {"nativeAssRenderInit", "(J)J", (void *) nativeAssRenderInit},
    {"nativeAssRenderSetFontScale", "(JF)V", (void *) nativeAssRenderSetFontScale},
    {"nativeAssRenderSetCacheLimit", "(JII)V", (void *) nativeAssRenderSetCacheLimit},
    {"nativeAssRenderSetThreads", "(JI)I", (void *) nativeAssRenderSetThreads},
    {"nativeAssRenderSetStorageSize", "(JII)V", (void *) nativeAssRenderSetStorageSize},
    {"nativeAssRenderSetFrameSize", "(JII)V", (void *) nativeAssRenderSetFrameSize},
    {"nativeAssRenderSetPixelAspect", "(JD)V", (void *) nativeAssRenderSetPixelAspect},
    {"nativeAssRenderFrame", "(JJJI)Lio/github/peerless2012/ass/AssFrame;", (void *) nativeAssRenderFrame},
    {"nativeAssRenderAtlasFrame", "(JJJIZZ)Lio/github/peerless2012/ass/AssAtlasFrame;", (void *) nativeAssRenderAtlasFrame},
    {"nativeAssRenderDeinit", "(J)V", (void *) nativeAssRenderDeinit},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass ass_class = (*env)->FindClass(env, "io/github/peerless2012/ass/Ass");
    jclass track_class = (*env)->FindClass(env, "io/github/peerless2012/ass/AssTrack");
    jclass render_class = (*env)->FindClass(env, "io/github/peerless2012/ass/AssRender");
    if (ass_class == NULL || track_class == NULL || render_class == NULL) {
        if (ass_class != NULL) (*env)->DeleteLocalRef(env, ass_class);
        if (track_class != NULL) (*env)->DeleteLocalRef(env, track_class);
        if (render_class != NULL) (*env)->DeleteLocalRef(env, render_class);
        return JNI_ERR;
    }

    int registration_failed =
        (*env)->RegisterNatives(
            env,
            ass_class,
            method_table,
            sizeof(method_table) / sizeof(method_table[0])
        ) < 0 ||
        (*env)->RegisterNatives(
            env,
            track_class,
            track_method_table,
            sizeof(track_method_table) / sizeof(track_method_table[0])
        ) < 0 ||
        (*env)->RegisterNatives(
            env,
            render_class,
            render_method_table,
            sizeof(render_method_table) / sizeof(render_method_table[0])
        ) < 0;

    (*env)->DeleteLocalRef(env, ass_class);
    (*env)->DeleteLocalRef(env, track_class);
    (*env)->DeleteLocalRef(env, render_class);
    if (registration_failed) return JNI_ERR;

    if (!cache_jni_ids(env)) {
        clear_jni_ids(env);
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        clear_jni_ids(env);
    }
}
