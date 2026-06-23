#define _GNU_SOURCE
  #include <dlfcn.h>
  #include <android/log.h>
  #include <stdio.h>
  #include <stdlib.h>
  #include <string.h>
  #include <pthread.h>
  #include <unistd.h>
  #include <sys/stat.h>
  #include <stdint.h>

  #define TAG "VCamInject"
  #define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
  #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

  #define CAMERA_HARDWARE_MODULE_ID "camera"
  #define HARDWARE_MODULE_TAG 0x48574D4Fu
  #define HARDWARE_DEVICE_TAG 0x48574445u
  #define VCAM_DIR   "/data/local/tmp/vcam"
  #define VCAM_IMAGE VCAM_DIR "/source.jpg"
  #define VCAM_CONFIG VCAM_DIR "/vcam_config"

  /* ── Minimal hw_module_t (AOSP hardware.h binary layout) ── */
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

  /* ── Camera HAL 1.0 types ── */
  #define CAMERA_FACING_FRONT 1
  #define CAMERA_MSG_PREVIEW_FRAME    0x002
  #define CAMERA_MSG_COMPRESSED_IMAGE 0x080

  typedef struct camera_memory {
      void*  data;
      size_t size;
      void*  handle;
      void (*release)(struct camera_memory*);
  } camera_memory_t;

  typedef void (*camera_notify_cb)(int32_t, int32_t, int32_t, void*);
  typedef void (*camera_data_cb)(int32_t, camera_memory_t*, unsigned, void*);
  typedef void (*camera_ts_cb)(int64_t, int32_t, camera_memory_t*, unsigned, void*);
  typedef camera_memory_t* (*camera_get_mem_t)(int, size_t, unsigned, void*);

  struct preview_stream_ops;

  typedef struct camera_device_ops_t {
      int  (*set_preview_window)(struct camera_device*, struct preview_stream_ops*);
      void (*set_callbacks)(struct camera_device*, camera_notify_cb, camera_data_cb,
                            camera_ts_cb, camera_get_mem_t, void*);
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
      hw_device_t          common;
      camera_device_ops_t* ops;
      void*                priv;
  } camera_device_t;

  typedef struct {
      int facing; int orientation; uint32_t device_version;
      const void* static_camera_characteristics;
      int resource_cost;
      const char* const* conflicting_devices;
      size_t conflicting_devices_length;
  } camera_info_t;

  typedef struct {
      hw_module_t   common;
      int  (*get_number_of_cameras)(void);
      int  (*get_camera_info)(int, camera_info_t*);
      void* reserved[30];
  } camera_module_t;

  /* ── Global state ── */
  static camera_data_cb   g_data_cb = NULL;
  static camera_get_mem_t g_get_mem = NULL;
  static void*            g_cb_user = NULL;
  static volatile int     g_preview = 0;
  static pthread_t        g_thread;
  static uint8_t*         g_jpeg = NULL;
  static size_t           g_jsz  = 0;

  static void load_jpeg(void) {
      char path[512];
      strncpy(path, VCAM_IMAGE, sizeof(path)-1);
      FILE* cfg = fopen(VCAM_CONFIG, "r");
      if (cfg) {
          char line[512];
          if (fgets(line, sizeof(line), cfg)) {
              char* nl = strchr(line, '\n'); if (nl) *nl = '\0';
              if (strlen(line) > 4) strncpy(path, line, sizeof(path)-1);
          }
          fclose(cfg);
      }
      FILE* f = fopen(path, "rb");
      if (!f) { LOGE("Cannot open JPEG: %s", path); return; }
      fseek(f, 0, SEEK_END); g_jsz = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
      free(g_jpeg); g_jpeg = (uint8_t*)malloc(g_jsz);
      if (g_jpeg) { fread(g_jpeg, 1, g_jsz, f); LOGI("JPEG loaded: %s (%zu bytes)", path, g_jsz); }
      fclose(f);
  }

  static void fill_nv21(uint8_t* buf, int w, int h) {
      memset(buf, 41, (size_t)(w * h));
      uint8_t* uv = buf + w * h;
      for (int i = 0; i < w * h / 2; i += 2) { uv[i] = 240; uv[i+1] = 110; }
  }

  static void deliver_frame(int w, int h) {
      if (!g_data_cb || !g_get_mem) return;
      size_t sz = (size_t)(w * h * 3 / 2);
      camera_memory_t* mem = g_get_mem(-1, sz, 1, g_cb_user);
      if (!mem || !mem->data) return;
      fill_nv21((uint8_t*)mem->data, w, h);
      g_data_cb(CAMERA_MSG_PREVIEW_FRAME, mem, 0, g_cb_user);
      if (mem->release) mem->release(mem);
  }

  static void* preview_fn(void* arg) {
      LOGI("Preview thread started");
      while (g_preview) { deliver_frame(640, 480); usleep(33333); }
      LOGI("Preview thread stopped");
      return NULL;
  }

  /* ── Camera ops ── */
  static void  op_set_cbs(camera_device_t* d, camera_notify_cb n, camera_data_cb cb,
                           camera_ts_cb ts, camera_get_mem_t gm, void* u) {
      g_data_cb = cb; g_get_mem = gm; g_cb_user = u;
  }
  static int   op_start_preview(camera_device_t* d) {
      load_jpeg(); g_preview = 1; pthread_create(&g_thread, NULL, preview_fn, NULL); return 0;
  }
  static void  op_stop_preview(camera_device_t* d)  { g_preview = 0; pthread_join(g_thread, NULL); }
  static int   op_preview_enabled(camera_device_t* d) { return g_preview; }
  static int   op_take_picture(camera_device_t* d) {
      if (g_data_cb && g_get_mem && g_jpeg && g_jsz) {
          camera_memory_t* mem = g_get_mem(-1, g_jsz, 1, g_cb_user);
          if (mem && mem->data) {
              memcpy(mem->data, g_jpeg, g_jsz);
              g_data_cb(CAMERA_MSG_COMPRESSED_IMAGE, mem, 0, g_cb_user);
              if (mem->release) mem->release(mem);
          }
      }
      return 0;
  }
  static int  op_noop_i(camera_device_t* d, ...) { return 0; }
  static void op_noop_v(camera_device_t* d, ...) {}
  static int  op_set_preview_window(camera_device_t* d, struct preview_stream_ops* w) { return 0; }
  static char* op_get_params(camera_device_t* d) {
      static char p[512];
      snprintf(p, sizeof(p),
          "preview-size=640x480;preview-format=yuv420sp;preview-frame-rate=30;"
          "picture-size=640x480;picture-format=jpeg;jpeg-quality=90;"
          "preview-size-values=640x480,320x240;picture-size-values=640x480,320x240;"
          "preview-frame-rate-values=30;facing=front;orientation=0");
      return p;
  }
  static void op_release(camera_device_t* d) { g_preview = 0; }

  static camera_device_ops_t g_ops = {
      op_set_preview_window,
      op_set_cbs,
      (void(*)(camera_device_t*,int32_t))op_noop_v,
      (void(*)(camera_device_t*,int32_t))op_noop_v,
      (int(*)(camera_device_t*,int32_t))op_noop_i,
      op_start_preview, op_stop_preview, op_preview_enabled,
      (int(*)(camera_device_t*,int))op_noop_i,
      (int(*)(camera_device_t*))op_noop_i,
      (void(*)(camera_device_t*))op_noop_v,
      (int(*)(camera_device_t*))op_noop_i,
      (void(*)(camera_device_t*,const void*))op_noop_v,
      (int(*)(camera_device_t*))op_noop_i,
      (int(*)(camera_device_t*))op_noop_i,
      op_take_picture,
      (int(*)(camera_device_t*))op_noop_i,
      (int(*)(camera_device_t*,const char*))op_noop_i,
      op_get_params,
      (void(*)(camera_device_t*,char*))op_noop_v,
      (int(*)(camera_device_t*,int32_t,int32_t,int32_t))op_noop_i,
      op_release,
      (int(*)(camera_device_t*,int))op_noop_i,
  };

  static camera_device_t g_cam_dev;

  /* ── Module ── */
  static int mod_get_num(void) { return 1; }
  static int mod_get_info(int id, camera_info_t* info) {
      if (id != 0) return -1;
      info->facing = CAMERA_FACING_FRONT; info->orientation = 0;
      info->device_version = 0x100; info->static_camera_characteristics = NULL;
      info->resource_cost = 100; info->conflicting_devices = NULL;
      info->conflicting_devices_length = 0;
      return 0;
  }
  static int mod_open(const hw_module_t* mod, const char* id, hw_device_t** dev) {
      LOGI("Camera open: id=%s", id ? id : "null");
      memset(&g_cam_dev, 0, sizeof(g_cam_dev));
      g_cam_dev.common.tag     = HARDWARE_DEVICE_TAG;
      g_cam_dev.common.version = 0x0100;
      g_cam_dev.common.module  = (hw_module_t*)mod;
      g_cam_dev.common.close   = [](hw_device_t*) { return 0; };
      g_cam_dev.ops            = &g_ops;
      *dev = (hw_device_t*)&g_cam_dev;
      return 0;
  }

  static hw_module_methods_t g_methods = { mod_open };

  static camera_module_t g_module = {
      { HARDWARE_MODULE_TAG, 0x0100, 0x0000,
        CAMERA_HARDWARE_MODULE_ID, "VCam Virtual Camera", "VCam",
        &g_methods, NULL, {} },
      mod_get_num, mod_get_info, {}
  };

  /* ── LD_PRELOAD hooks ── */
  extern "C" int hw_get_module_by_class(const char* cls, const char* inst,
                                         const hw_module_t** mod) {
      if (cls && strcmp(cls, CAMERA_HARDWARE_MODULE_ID) == 0) {
          LOGI("Intercepted hw_get_module_by_class(camera)");
          *mod = &g_module.common; return 0;
      }
      typedef int (*F)(const char*, const char*, const hw_module_t**);
      static F real = (F)dlsym(RTLD_NEXT, "hw_get_module_by_class");
      return real ? real(cls, inst, mod) : -1;
  }
  extern "C" int hw_get_module(const char* id, const hw_module_t** mod) {
      if (id && strcmp(id, CAMERA_HARDWARE_MODULE_ID) == 0) {
          LOGI("Intercepted hw_get_module(camera)");
          *mod = &g_module.common; return 0;
      }
      typedef int (*F)(const char*, const hw_module_t**);
      static F real = (F)dlsym(RTLD_NEXT, "hw_get_module");
      return real ? real(id, mod) : -1;
  }

  __attribute__((constructor)) static void vcam_init(void) {
      LOGI("=== VCam inject loaded ===");
      mkdir(VCAM_DIR, 0777);
      load_jpeg();
  }
  __attribute__((destructor)) static void vcam_fini(void) {
      g_preview = 0;
      LOGI("=== VCam inject unloaded ===");
  }
  