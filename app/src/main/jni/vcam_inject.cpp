#define _GNU_SOURCE
  #include <dlfcn.h>
  #include <android/log.h>
  #include <stdio.h>
  #include <stdlib.h>
  #include <string.h>
  #include <pthread.h>
  #include <unistd.h>
  #include <stdint.h>

  #define TAG "VCamInject"
  #define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
  #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

  #define VCAM_DIR    "/data/local/tmp/vcam"
  #define VCAM_IMAGE  VCAM_DIR "/source.jpg"

  /* ── AOSP hardware.h binary layout ──────────────────────────────────── */
  typedef struct hw_module_methods_t {
      int (*open)(const struct hw_module_t* module, const char* id,
                  struct hw_device_t** device);
  } hw_module_methods_t;

  typedef struct hw_module_t {
      uint32_t tag;
      uint16_t module_api_version;
      uint16_t hal_api_version;
      const char* id;
      const char* name;
      const char* author;
      hw_module_methods_t* methods;
      void* dso;
  #ifdef __LP64__
      uint64_t reserved[32-7];
  #else
      uint32_t reserved[32-7];
  #endif
  } hw_module_t;

  typedef struct hw_device_t {
      uint32_t tag;
      uint32_t version;
      struct hw_module_t* module;
  #ifdef __LP64__
      uint64_t reserved[12];
  #else
      uint32_t reserved[12];
  #endif
      int (*close)(struct hw_device_t* device);
  } hw_device_t;

  /* ── Camera HAL 1 types (minimal) ───────────────────────────────────── */
  typedef struct camera_memory {
      void*  data;
      size_t size;
      void*  handle;
      void  (*release)(struct camera_memory*);
  } camera_memory_t;

  typedef void (*camera_notify_cb)(int32_t, int32_t, int32_t, void*);
  typedef void (*camera_data_cb)(int32_t, camera_memory_t*, unsigned, void*);
  typedef void (*camera_ts_cb)(int64_t, int32_t, camera_memory_t*, unsigned, void*);
  typedef camera_memory_t* (*camera_request_memory_t)(int fd, size_t buf_size, unsigned int num_bufs, void* user);
  struct preview_stream_ops;

  typedef struct camera_device_ops_t {
      int  (*set_preview_window)(struct camera_device*, struct preview_stream_ops*);
      void (*set_callbacks)(struct camera_device*, camera_notify_cb, camera_data_cb,
                            camera_ts_cb, camera_request_memory_t, void*);
      void (*enable_msg_type)(struct camera_device*, int32_t);
      void (*disable_msg_type)(struct camera_device*, int32_t);
      int  (*msg_type_enabled)(struct camera_device*, int32_t);
      int  (*start_preview)(struct camera_device*);
      void (*stop_preview)(struct camera_device*);
      int  (*preview_enabled)(struct camera_device*);
      int  (*store_meta_data_in_buffers)(struct camera_device*, int);
      int  (*start_recording)(struct camera_device*);
      void (*stop_recording)(struct camera_device*);
      int  (*recording_enabled)(struct camera_device*);
      void (*release_recording_frame)(struct camera_device*, const void*);
      int  (*auto_focus)(struct camera_device*);
      int  (*cancel_auto_focus)(struct camera_device*);
      int  (*take_picture)(struct camera_device*);
      int  (*cancel_picture)(struct camera_device*);
      int  (*set_parameters)(struct camera_device*, const char*);
      char*(*get_parameters)(struct camera_device*);
      void (*put_parameters)(struct camera_device*, char*);
      int  (*send_command)(struct camera_device*, int32_t, int32_t, int32_t);
      void (*release)(struct camera_device*);
      int  (*dump)(struct camera_device*, int);
  } camera_device_ops_t;

  typedef struct camera_device {
      hw_device_t         common;
      camera_device_ops_t* ops;
      void*               priv;
  } camera_device_t;

  /* ── Global state ────────────────────────────────────────────────────── */
  typedef int (*hw_get_module_by_class_fn)(const char*, const char*, const hw_module_t**);
  typedef int (*hw_get_module_fn)(const char*, const hw_module_t**);

  static hw_get_module_by_class_fn real_by_class = NULL;
  static hw_get_module_fn          real_get       = NULL;

  static camera_notify_cb        g_notify  = NULL;
  static camera_data_cb          g_data    = NULL;
  static camera_request_memory_t g_get_mem = NULL;
  static void*                   g_user    = NULL;
  static volatile int            g_preview = 0;
  static volatile int            g_stop    = 0;
  static pthread_t               g_thread  = 0;

  /* ── Preview thread — delivers JPEG frames ───────────────────────────── */
  static void* preview_thread(void* arg) {
      (void)arg;
      while (!g_stop) {
          if (g_preview && g_data && g_get_mem) {
              FILE* f = fopen(VCAM_IMAGE, "rb");
              if (f) {
                  fseek(f, 0, SEEK_END);
                  long sz = ftell(f);
                  fseek(f, 0, SEEK_SET);
                  if (sz > 0 && sz < 10*1024*1024) {
                      camera_memory_t* mem = g_get_mem(-1, (size_t)sz, 1, g_user);
                      if (mem && mem->data) {
                          if (fread(mem->data, 1, (size_t)sz, f) == (size_t)sz)
                              g_data(0x080 /*CAMERA_MSG_COMPRESSED_IMAGE*/, mem, 0, g_user);
                          mem->release(mem);
                      }
                  }
                  fclose(f);
              }
          }
          usleep(100000); /* 10 fps — low load */
      }
      return NULL;
  }

  /* ── Fake camera device ops ──────────────────────────────────────────── */
  static int fake_set_preview_window(camera_device_t* d, struct preview_stream_ops* w)
      { (void)d;(void)w; return 0; }

  static void fake_set_callbacks(camera_device_t* d, camera_notify_cb n,
                                  camera_data_cb cb, camera_ts_cb ts,
                                  camera_request_memory_t gm, void* u)
      { (void)d;(void)ts; g_notify=n; g_data=cb; g_get_mem=gm; g_user=u; }

  static void fake_enable_msg (camera_device_t* d, int32_t t) { (void)d;(void)t; }
  static void fake_disable_msg(camera_device_t* d, int32_t t) { (void)d;(void)t; }
  static int  fake_msg_enabled(camera_device_t* d, int32_t t) { (void)d;(void)t; return 0; }

  static int fake_start_preview(camera_device_t* d) {
      (void)d; g_stop=0; g_preview=1;
      pthread_create(&g_thread, NULL, preview_thread, NULL);
      LOGI("VCam: preview started");
      return 0;
  }
  static void fake_stop_preview(camera_device_t* d) {
      (void)d; g_preview=0; g_stop=1;
      if (g_thread) { pthread_join(g_thread,NULL); g_thread=0; }
  }
  static int  fake_preview_enabled(camera_device_t* d)          { (void)d; return g_preview; }
  static int  fake_store_meta(camera_device_t* d, int e)         { (void)d;(void)e; return 0; }
  static int  fake_start_rec(camera_device_t* d)                 { (void)d; return 0; }
  static void fake_stop_rec(camera_device_t* d)                  { (void)d; }
  static int  fake_rec_enabled(camera_device_t* d)               { (void)d; return 0; }
  static void fake_rel_rec(camera_device_t* d, const void* f)    { (void)d;(void)f; }
  static int  fake_auto_focus(camera_device_t* d)                { (void)d; return 0; }
  static int  fake_cancel_af(camera_device_t* d)                 { (void)d; return 0; }
  static int  fake_take_pic(camera_device_t* d)                  { (void)d; return 0; }
  static int  fake_cancel_pic(camera_device_t* d)                { (void)d; return 0; }
  static int  fake_set_params(camera_device_t* d, const char* p) { (void)d;(void)p; return 0; }
  static char* fake_get_params(camera_device_t* d) {
      (void)d;
      return strdup("preview-size=640x480;picture-size=640x480;"
                    "preview-format=jpeg;preview-frame-rate=10;"
                    "picture-format=jpeg;jpeg-quality=90;");
  }
  static void fake_put_params(camera_device_t* d, char* p)       { (void)d; free(p); }
  static int  fake_send_cmd(camera_device_t* d, int32_t c, int32_t a, int32_t b)
      { (void)d;(void)c;(void)a;(void)b; return 0; }
  static void fake_release(camera_device_t* d)  { fake_stop_preview(d); }
  static int  fake_dump(camera_device_t* d, int fd) { (void)d;(void)fd; return 0; }

  static int fake_close(hw_device_t* dev) {
      camera_device_t* cam = (camera_device_t*)dev;
      fake_stop_preview(cam);
      free(cam->ops);
      free(cam);
      return 0;
  }

  static int fake_module_open(const hw_module_t* module, const char* id, hw_device_t** out) {
      (void)id;
      LOGI("VCam: camera open()");
      camera_device_t* cam = (camera_device_t*)calloc(1, sizeof(*cam));
      cam->common.tag    = 0x48574445u; /* HARDWARE_DEVICE_TAG */
      cam->common.version= 0x0100;
      cam->common.module = (hw_module_t*)module;
      cam->common.close  = fake_close;
      camera_device_ops_t* ops = (camera_device_ops_t*)calloc(1, sizeof(*ops));
      ops->set_preview_window         = (int  (*)(struct camera_device*, struct preview_stream_ops*))fake_set_preview_window;
      ops->set_callbacks              = (void (*)(struct camera_device*, camera_notify_cb, camera_data_cb, camera_ts_cb, camera_request_memory_t, void*))fake_set_callbacks;
      ops->enable_msg_type            = (void (*)(struct camera_device*, int32_t))fake_enable_msg;
      ops->disable_msg_type           = (void (*)(struct camera_device*, int32_t))fake_disable_msg;
      ops->msg_type_enabled           = (int  (*)(struct camera_device*, int32_t))fake_msg_enabled;
      ops->start_preview              = (int  (*)(struct camera_device*))fake_start_preview;
      ops->stop_preview               = (void (*)(struct camera_device*))fake_stop_preview;
      ops->preview_enabled            = (int  (*)(struct camera_device*))fake_preview_enabled;
      ops->store_meta_data_in_buffers = (int  (*)(struct camera_device*, int))fake_store_meta;
      ops->start_recording            = (int  (*)(struct camera_device*))fake_start_rec;
      ops->stop_recording             = (void (*)(struct camera_device*))fake_stop_rec;
      ops->recording_enabled          = (int  (*)(struct camera_device*))fake_rec_enabled;
      ops->release_recording_frame    = (void (*)(struct camera_device*, const void*))fake_rel_rec;
      ops->auto_focus                 = (int  (*)(struct camera_device*))fake_auto_focus;
      ops->cancel_auto_focus          = (int  (*)(struct camera_device*))fake_cancel_af;
      ops->take_picture               = (int  (*)(struct camera_device*))fake_take_pic;
      ops->cancel_picture             = (int  (*)(struct camera_device*))fake_cancel_pic;
      ops->set_parameters             = (int  (*)(struct camera_device*, const char*))fake_set_params;
      ops->get_parameters             = (char*(*)(struct camera_device*))fake_get_params;
      ops->put_parameters             = (void (*)(struct camera_device*, char*))fake_put_params;
      ops->send_command               = (int  (*)(struct camera_device*, int32_t, int32_t, int32_t))fake_send_cmd;
      ops->release                    = (void (*)(struct camera_device*))fake_release;
      ops->dump                       = (int  (*)(struct camera_device*, int))fake_dump;
      cam->ops = ops;
      *out = &cam->common;
      LOGI("VCam: camera device created");
      return 0;
  }

  static hw_module_methods_t g_methods = { fake_module_open };
  static hw_module_t g_fake_module = {
      /* tag               */ 0x48574D4Fu,
      /* module_api_version*/ 0x0100,
      /* hal_api_version   */ 0x0100,
      /* id                */ "camera",
      /* name              */ "VCam Fake Camera",
      /* author            */ "VCam",
      /* methods           */ &g_methods,
      /* dso               */ NULL,
  };

  /* ── Constructor: resolve real functions ONCE at load time ───────────── */
  __attribute__((constructor))
  static void vcam_init(void) {
      /* RTLD_NEXT finds the next definition in the link order               */
      real_by_class = (hw_get_module_by_class_fn)dlsym(RTLD_NEXT, "hw_get_module_by_class");
      real_get      = (hw_get_module_fn)         dlsym(RTLD_NEXT, "hw_get_module");
      if (!real_by_class || !real_get) {
          void* lh = dlopen("libhardware.so", RTLD_NOW | RTLD_NOLOAD);
          if (!lh)  lh = dlopen("libhardware.so", RTLD_NOW | RTLD_GLOBAL);
          if (lh) {
              if (!real_by_class) real_by_class = (hw_get_module_by_class_fn)dlsym(lh, "hw_get_module_by_class");
              if (!real_get)      real_get      = (hw_get_module_fn)         dlsym(lh, "hw_get_module");
          }
      }
      LOGI("VCam init: by_class=%p get=%p", real_by_class, real_get);
  }

  /* ── Hooks ───────────────────────────────────────────────────────────── */

  /* CRITICAL: non-camera classes MUST go to the real function.            */
  /* If real_fn is NULL we return -1 rather than returning our fake module.*/
  int hw_get_module_by_class(const char* class_name, const char* inst,
                               const hw_module_t** module) {
      if (class_name && strcmp(class_name, "camera") == 0) {
          LOGI("VCam: intercepted camera (by_class)");
          *module = &g_fake_module;
          return 0;
      }
      if (real_by_class) return real_by_class(class_name, inst, module);
      return -1; /* safe fallback — module not found */
  }

  int hw_get_module(const char* id, const hw_module_t** module) {
      if (id && strcmp(id, "camera") == 0) {
          LOGI("VCam: intercepted camera (get_module)");
          *module = &g_fake_module;
          return 0;
      }
      if (real_get) return real_get(id, module);
      return -1;
  }
  