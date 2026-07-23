# Targeting the AYN Thor (arm64) for now; widen ABI_FILTERS in app/build.gradle
# (and here) later if broader device support is needed.
APP_ABI := arm64-v8a
APP_PLATFORM := android-21
APP_CFLAGS := -Wno-error
