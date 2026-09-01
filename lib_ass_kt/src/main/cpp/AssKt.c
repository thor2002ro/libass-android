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
#define ATLAS_GUTTER 1


static jclass g_ass_event_class;
static jmethodID g_ass_event_ctor;
static jclass g_ass_frame_class;
static jmethodID g_ass_frame_ctor;
static jclass g_ass_tex_class;
static jmethodID g_ass_tex_ctor;
static jclass g_ass_atlas_frame_class;
static jmethodID g_ass_atlas_frame_ctor;
static jclass g_byte_buffer_class;

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
    g_byte_buffer_class = cache_class(env, "java/nio/ByteBuffer");
    g_bitmap_class = cache_class(env, "android/graphics/Bitmap");
    jclass bitmap_config_class = cache_class(env, "android/graphics/Bitmap$Config");

    if (g_ass_event_class == NULL || g_ass_frame_class == NULL ||
        g_ass_tex_class == NULL || g_ass_atlas_frame_class == NULL ||
        g_byte_buffer_class == NULL || g_bitmap_class == NULL ||
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
        "([Ljava/nio/ByteBuffer;[I[I[II[IJ)V"
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
    DELETE_GLOBAL(g_byte_buffer_class);
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

typedef struct AtlasBufferSlot {
    DirectAtlasPage *pages;
    size_t page_capacity;
} AtlasBufferSlot;

struct AtlasWorkspace {
    AtlasEntry *entries;
    AtlasPage *layout_pages;
    jint *widths;
    jint *heights;
    jint *quads;
    jint *dirty_rects;
    size_t image_capacity;
    size_t metadata_page_capacity;
    AtlasBufferSlot slots[ATLAS_BUFFER_SLOTS];
    int next_slot;
    int last_content_slot;
    int last_page_count;
    jint *last_widths;
    jint *last_heights;
    size_t last_page_capacity;
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
    jint *dirty = realloc(workspace->dirty_rects, new_capacity * 4 * sizeof(jint));
    if (widths == NULL || heights == NULL || dirty == NULL) {
        if (widths != NULL) workspace->widths = widths;
        if (heights != NULL) workspace->heights = heights;
        if (dirty != NULL) workspace->dirty_rects = dirty;
        return 0;
    }
    workspace->widths = widths;
    workspace->heights = heights;
    workspace->dirty_rects = dirty;
    workspace->metadata_page_capacity = new_capacity;
    return 1;
}

static int reserve_slot_pages(AtlasBufferSlot *slot, size_t page_count) {
    if (slot->page_capacity >= page_count) return 1;
    size_t old_capacity = slot->page_capacity;
    size_t new_capacity = old_capacity > 0 ? old_capacity : 2;
    while (new_capacity < page_count) new_capacity *= 2;
    DirectAtlasPage *pages = realloc(slot->pages, new_capacity * sizeof(DirectAtlasPage));
    if (pages == NULL) return 0;
    memset(pages + old_capacity, 0, (new_capacity - old_capacity) * sizeof(DirectAtlasPage));
    slot->pages = pages;
    slot->page_capacity = new_capacity;
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

static void free_atlas_workspace(AtlasWorkspace *workspace) {
    if (workspace == NULL) return;
    free(workspace->entries);
    free(workspace->layout_pages);
    free(workspace->widths);
    free(workspace->heights);
    free(workspace->quads);
    free(workspace->dirty_rects);
    free(workspace->last_widths);
    free(workspace->last_heights);
    for (int slot_index = 0; slot_index < ATLAS_BUFFER_SLOTS; ++slot_index) {
        AtlasBufferSlot *slot = &workspace->slots[slot_index];
        for (size_t page = 0; page < slot->page_capacity; ++page) free(slot->pages[page].bytes);
        free(slot->pages);
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

static jobject new_empty_atlas_frame(JNIEnv *env, int changed, uint64_t content_serial) {
    jintArray widths = (*env)->NewIntArray(env, 0);
    jintArray heights = (*env)->NewIntArray(env, 0);
    jintArray quads = (*env)->NewIntArray(env, 0);
    jintArray dirty_rects = (*env)->NewIntArray(env, 0);
    if (widths == NULL || heights == NULL || quads == NULL || dirty_rects == NULL) {
        if (widths != NULL) (*env)->DeleteLocalRef(env, widths);
        if (heights != NULL) (*env)->DeleteLocalRef(env, heights);
        if (quads != NULL) (*env)->DeleteLocalRef(env, quads);
        if (dirty_rects != NULL) (*env)->DeleteLocalRef(env, dirty_rects);
        return NULL;
    }

    jobject frame = (*env)->NewObject(
        env,
        g_ass_atlas_frame_class,
        g_ass_atlas_frame_ctor,
        NULL,
        widths,
        heights,
        quads,
        changed,
        dirty_rects,
        (jlong) content_serial
    );
    (*env)->DeleteLocalRef(env, widths);
    (*env)->DeleteLocalRef(env, heights);
    (*env)->DeleteLocalRef(env, quads);
    (*env)->DeleteLocalRef(env, dirty_rects);
    return frame;
}

static jobject nativeAssRenderAtlasFrame(
    JNIEnv *env,
    jclass clazz,
    jlong render,
    jlong track,
    jlong time,
    jint max_atlas_size
) {
    (void) clazz;
    if (render == 0 || track == 0 || max_atlas_size <= 0) return NULL;
    NativeAssRender *native = native_ass_render(render);
    if (native == NULL || native->renderer == NULL) return NULL;
    if (native->atlas == NULL) {
        native->atlas = calloc(1, sizeof(AtlasWorkspace));
        if (native->atlas == NULL) return NULL;
        native->atlas->last_content_slot = -1;
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
    if (changed == 0) return NULL;

    int count = count_renderable_images(images);
    if (count == 0) {
        if (changed >= 2) workspace->content_serial++;
        return new_empty_atlas_frame(env, changed, workspace->content_serial);
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
    jintArray page_widths = NULL;
    jintArray page_heights = NULL;
    jintArray quads = NULL;
    jintArray dirty_rects = NULL;
    jobjectArray page_arrays = NULL;
    if (!reserve_workspace(workspace, (size_t) count)) goto cleanup;
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

    int packing_width = choose_atlas_width(entries, count, max_atlas_size);
    int page_count = layout_atlas(
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
    if (page_count <= 0) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            LOG_TAG,
            "Unable to pack libass masks into GL atlas"
        );
        goto cleanup;
    }

    page_widths = (*env)->NewIntArray(env, page_count);
    page_heights = (*env)->NewIntArray(env, page_count);
    quads = (*env)->NewIntArray(env, count * ATLAS_QUAD_STRIDE);
    dirty_rects = (*env)->NewIntArray(env, page_count * 4);
    if (page_widths == NULL || page_heights == NULL || quads == NULL || dirty_rects == NULL) {
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

    (*env)->SetIntArrayRegion(env, page_widths, 0, page_count, width_values);
    (*env)->SetIntArrayRegion(env, page_heights, 0, page_count, height_values);
    (*env)->SetIntArrayRegion(env, quads, 0, count * ATLAS_QUAD_STRIDE, quad_values);
    if ((*env)->ExceptionCheck(env)) goto cleanup;

    if (changed >= 2) {
        workspace->content_serial++;
        int slot_index = workspace->next_slot;
        workspace->next_slot = (slot_index + 1) % ATLAS_BUFFER_SLOTS;
        AtlasBufferSlot *slot = &workspace->slots[slot_index];
        if (!reserve_slot_pages(slot, (size_t) page_count) ||
            !reserve_last_layout(workspace, (size_t) page_count)) {
            goto cleanup;
        }

        page_arrays = (*env)->NewObjectArray(env, page_count, g_byte_buffer_class, NULL);
        if (page_arrays == NULL) goto cleanup;

        for (int page_index = 0; page_index < page_count; ++page_index) {
            int width = pages[page_index].width;
            int height = pages[page_index].height;
            size_t byte_count = (size_t) width * (size_t) height;
            if (width <= 0 || height <= 0 || byte_count > INT_MAX) goto cleanup;
            DirectAtlasPage *direct_page = &slot->pages[page_index];
            if (!reserve_direct_page(direct_page, byte_count)) goto cleanup;
            unsigned char *page_bytes = direct_page->bytes;
            memset(page_bytes, 0, byte_count);

            for (int i = 0; i < count; ++i) {
                const AtlasEntry *entry = &entries[i];
                if (entry->page != page_index) continue;
                const ASS_Image *image = entry->image;
                for (int y = 0; y < image->h; ++y) {
                    unsigned char *dst = (unsigned char *) page_bytes +
                        (size_t) (entry->atlas_y + y) * (size_t) width + entry->atlas_x;
                    const unsigned char *src = image->bitmap + (size_t) y * image->stride;
                    memcpy(dst, src, (size_t) image->w);
                }
            }

            int dirty_left = 0;
            int dirty_top = 0;
            int dirty_right = width - 1;
            int dirty_bottom = height - 1;
            int previous_slot_index = workspace->last_content_slot;
            if (previous_slot_index >= 0 && workspace->last_page_count == page_count &&
                workspace->last_widths[page_index] == width &&
                workspace->last_heights[page_index] == height) {
                const unsigned char *previous =
                    workspace->slots[previous_slot_index].pages[page_index].bytes;
                dirty_left = width;
                dirty_top = height;
                dirty_right = -1;
                dirty_bottom = -1;
                for (int y = 0; y < height; ++y) {
                    size_t row = (size_t) y * (size_t) width;
                    for (int x = 0; x < width; ++x) {
                        size_t offset = row + (size_t) x;
                        if (previous[offset] == page_bytes[offset]) continue;
                        if (x < dirty_left) dirty_left = x;
                        if (x > dirty_right) dirty_right = x;
                        if (y < dirty_top) dirty_top = y;
                        if (y > dirty_bottom) dirty_bottom = y;
                    }
                }
            }
            int dirty_offset = page_index * 4;
            workspace->dirty_rects[dirty_offset + 0] = dirty_left;
            workspace->dirty_rects[dirty_offset + 1] = dirty_top;
            workspace->dirty_rects[dirty_offset + 2] =
                dirty_right >= dirty_left ? dirty_right - dirty_left + 1 : 0;
            workspace->dirty_rects[dirty_offset + 3] =
                dirty_bottom >= dirty_top ? dirty_bottom - dirty_top + 1 : 0;

            jobject page_buffer = (*env)->NewDirectByteBuffer(env, page_bytes, (jlong) byte_count);
            if (page_buffer == NULL) goto cleanup;
            (*env)->SetObjectArrayElement(env, page_arrays, page_index, page_buffer);
            (*env)->DeleteLocalRef(env, page_buffer);
            if ((*env)->ExceptionCheck(env)) goto cleanup;
        }

        (*env)->SetIntArrayRegion(env, dirty_rects, 0, page_count * 4, workspace->dirty_rects);
        if ((*env)->ExceptionCheck(env)) goto cleanup;
        memcpy(workspace->last_widths, width_values, (size_t) page_count * sizeof(jint));
        memcpy(workspace->last_heights, height_values, (size_t) page_count * sizeof(jint));
        workspace->last_page_count = page_count;
        workspace->last_content_slot = slot_index;
    }

    result = (*env)->NewObject(
        env,
        g_ass_atlas_frame_class,
        g_ass_atlas_frame_ctor,
        page_arrays,
        page_widths,
        page_heights,
        quads,
        changed,
        dirty_rects,
        (jlong) workspace->content_serial
    );

cleanup:
    if (page_arrays != NULL) (*env)->DeleteLocalRef(env, page_arrays);
    if (page_widths != NULL) (*env)->DeleteLocalRef(env, page_widths);
    if (page_heights != NULL) (*env)->DeleteLocalRef(env, page_heights);
    if (quads != NULL) (*env)->DeleteLocalRef(env, quads);
    if (dirty_rects != NULL) (*env)->DeleteLocalRef(env, dirty_rects);
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

static void nativeAssRenderSetFrameSize(
    JNIEnv *env,
    jclass clazz,
    jlong render,
    jint width,
    jint height
) {
    (void) env;
    (void) clazz;
    ASS_Renderer *renderer = ass_renderer(render);
    if (renderer != NULL) ass_set_frame_size(renderer, width, height);
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
    {"nativeAssRenderSetStorageSize", "(JII)V", (void *) nativeAssRenderSetStorageSize},
    {"nativeAssRenderSetFrameSize", "(JII)V", (void *) nativeAssRenderSetFrameSize},
    {"nativeAssRenderSetPixelAspect", "(JD)V", (void *) nativeAssRenderSetPixelAspect},
    {"nativeAssRenderFrame", "(JJJI)Lio/github/peerless2012/ass/AssFrame;", (void *) nativeAssRenderFrame},
    {"nativeAssRenderAtlasFrame", "(JJJI)Lio/github/peerless2012/ass/AssAtlasFrame;", (void *) nativeAssRenderAtlasFrame},
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
