#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <linux/videodev2.h>
#include <android/bitmap.h>
#include <android/log.h>

#include "v4l2_injector.h"

#define TAG "VCamJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::atomic<bool> g_running(false);
static int g_video_fd = -1;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_vcam_utils_CameraInjector_nativeCheckDevice(
        JNIEnv* env, jobject thiz, jstring device_path) {
    const char* path = env->GetStringUTFChars(device_path, nullptr);
    jboolean result = (jboolean)v4l2_check_device(path);
    env->ReleaseStringUTFChars(device_path, path);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_vcam_utils_CameraInjector_nativeInjectImage(
        JNIEnv* env, jobject thiz,
        jstring image_path, jstring video_device) {

    const char* img_path = env->GetStringUTFChars(image_path, nullptr);
    const char* dev_path = env->GetStringUTFChars(video_device, nullptr);

    LOGI("Injecting image: %s -> %s", img_path, dev_path);

    int fd = v4l2_open_device(dev_path);
    if (fd < 0) {
        LOGE("Failed to open device %s", dev_path);
        env->ReleaseStringUTFChars(image_path, img_path);
        env->ReleaseStringUTFChars(video_device, dev_path);
        return -1;
    }

    g_video_fd = fd;
    g_running.store(true);

    // Set output format
    const int WIDTH = 640;
    const int HEIGHT = 480;
    v4l2_set_format(fd, WIDTH, HEIGHT, V4L2_PIX_FMT_YUYV);

    // Allocate YUYV frame buffer
    size_t frame_size = WIDTH * HEIGHT * 2;
    uint8_t* frame_buf = (uint8_t*)malloc(frame_size);
    if (!frame_buf) {
        v4l2_close_device(fd);
        env->ReleaseStringUTFChars(image_path, img_path);
        env->ReleaseStringUTFChars(video_device, dev_path);
        return -2;
    }

    // Fill with a default color pattern (blue screen as placeholder)
    for (int i = 0; i < WIDTH * HEIGHT / 2; i++) {
        frame_buf[i * 4 + 0] = 41;   // Y
        frame_buf[i * 4 + 1] = 240;  // U (blue)
        frame_buf[i * 4 + 2] = 41;   // Y
        frame_buf[i * 4 + 3] = 110;  // V
    }

    // Inject frames in a loop
    int frame_count = 0;
    while (g_running.load()) {
        ssize_t ret = write(fd, frame_buf, frame_size);
        if (ret < 0 && errno != EAGAIN) {
            LOGE("Write error: %s", strerror(errno));
            break;
        }
        frame_count++;
        // ~30fps
        usleep(33333);
    }

    free(frame_buf);
    v4l2_close_device(fd);
    g_video_fd = -1;

    LOGI("Image injection stopped after %d frames", frame_count);

    env->ReleaseStringUTFChars(image_path, img_path);
    env->ReleaseStringUTFChars(video_device, dev_path);
    return frame_count;
}

JNIEXPORT jint JNICALL
Java_com_vcam_utils_CameraInjector_nativeInjectVideo(
        JNIEnv* env, jobject thiz,
        jstring video_path, jstring video_device) {

    const char* vid_path = env->GetStringUTFChars(video_path, nullptr);
    const char* dev_path = env->GetStringUTFChars(video_device, nullptr);

    LOGI("Injecting video: %s -> %s", vid_path, dev_path);

    int fd = v4l2_open_device(dev_path);
    if (fd < 0) {
        env->ReleaseStringUTFChars(video_path, vid_path);
        env->ReleaseStringUTFChars(video_device, dev_path);
        return -1;
    }

    g_video_fd = fd;
    g_running.store(true);

    const int WIDTH = 640;
    const int HEIGHT = 480;
    v4l2_set_format(fd, WIDTH, HEIGHT, V4L2_PIX_FMT_YUYV);

    size_t frame_size = WIDTH * HEIGHT * 2;
    uint8_t* frame_buf = (uint8_t*)malloc(frame_size);

    if (frame_buf) {
        // Animate a color sweep as placeholder while video decoding
        // is handled by the Kotlin layer via MediaMetadataRetriever
        int frame = 0;
        while (g_running.load()) {
            uint8_t y = (frame % 255);
            for (int i = 0; i < WIDTH * HEIGHT / 2; i++) {
                frame_buf[i * 4 + 0] = y;
                frame_buf[i * 4 + 1] = 128;
                frame_buf[i * 4 + 2] = y;
                frame_buf[i * 4 + 3] = 128;
            }
            write(fd, frame_buf, frame_size);
            frame++;
            usleep(33333);
        }
        free(frame_buf);
    }

    v4l2_close_device(fd);
    g_video_fd = -1;

    env->ReleaseStringUTFChars(video_path, vid_path);
    env->ReleaseStringUTFChars(video_device, dev_path);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_vcam_utils_CameraInjector_nativeStopInjection(
        JNIEnv* env, jobject thiz) {
    LOGI("Stop injection requested");
    g_running.store(false);
    if (g_video_fd >= 0) {
        close(g_video_fd);
        g_video_fd = -1;
    }
}

} // extern "C"
