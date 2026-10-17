#include <jni.h>
#include <cstdint>
#include <vector>

#include "libraw/libraw.h"

namespace {

constexpr jint kOk = 0;

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_jabs_lumen_engine_RawDecoder_nativeDecode(
        JNIEnv *env, jobject /* thiz */, jstring jpath, jboolean halfSize) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LibRaw raw;
    raw.imgdata.params.half_size = halfSize ? 1 : 0;
    raw.imgdata.params.output_bps = 8;
    raw.imgdata.params.use_camera_wb = 1;
    raw.imgdata.params.output_color = 1;

    jintArray result = nullptr;
    if (raw.open_file(path) == kOk && raw.unpack() == kOk && raw.dcraw_process() == kOk) {
        int err = 0;
        libraw_processed_image_t *img = raw.dcraw_make_mem_image(&err);
        if (img != nullptr && err == kOk && img->colors == 3 && img->bits == 8) {
            const int w = img->width;
            const int h = img->height;
            std::vector<jint> out(2 + static_cast<size_t>(w) * h);
            out[0] = w;
            out[1] = h;
            const uint8_t *src = img->data;
            for (int i = 0; i < w * h; ++i) {
                const uint32_t r = src[i * 3];
                const uint32_t g = src[i * 3 + 1];
                const uint32_t b = src[i * 3 + 2];
                out[2 + i] = static_cast<jint>(0xFF000000u | (r << 16) | (g << 8) | b);
            }
            result = env->NewIntArray(static_cast<jsize>(out.size()));
            env->SetIntArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
        }
        if (img != nullptr) raw.dcraw_clear_mem(img);
    }

    raw.recycle();
    env->ReleaseStringUTFChars(jpath, path);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jabs_lumen_engine_RawDecoder_nativeExtractThumb(
        JNIEnv *env, jobject /* thiz */, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LibRaw raw;

    jbyteArray result = nullptr;
    if (raw.open_file(path) == kOk && raw.unpack_thumb() == kOk) {
        const libraw_thumbnail_t &thumb = raw.imgdata.thumbnail;
        if (thumb.tformat == LIBRAW_THUMBNAIL_JPEG && thumb.tlength > 0) {
            result = env->NewByteArray(static_cast<jsize>(thumb.tlength));
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(thumb.tlength),
                                    reinterpret_cast<const jbyte *>(thumb.thumb));
        }
    }

    raw.recycle();
    env->ReleaseStringUTFChars(jpath, path);
    return result;
}
