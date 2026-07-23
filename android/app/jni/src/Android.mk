LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := main

# The engine's canonical source lives at the repo root's src/, shared with the
# desktop (Makefile) and Switch builds - not duplicated under jni/.
SM_ROOT := $(LOCAL_PATH)/../../../..
SM_SRC := $(SM_ROOT)/src
SDL_PATH := ../SDL2

LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(SDL_PATH)/include $(SM_SRC) $(SM_ROOT)

# opengl.c/glsl_shader.c target desktop GL3 core profile (via third_party/gl_core)
# and are not GLES-portable; the Android build always uses the SDL_Renderer
# backend instead (see the __ANDROID__ guard in main.c), so they're excluded here.
LOCAL_SRC_FILES := \
	$(filter-out $(SM_SRC)/opengl.c $(SM_SRC)/glsl_shader.c, $(wildcard $(SM_SRC)/*.c)) \
	$(wildcard $(SM_SRC)/snes/*.c) \
	$(wildcard $(SM_SRC)/platform/android/*.c)

LOCAL_SHARED_LIBRARIES := SDL2

LOCAL_LDLIBS := -lOpenSLES -llog -landroid

include $(BUILD_SHARED_LIBRARY)
