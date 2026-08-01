# Targeting the AYN Thor (arm64) for now; widen ABI_FILTERS in app/build.gradle
# (and here) later if broader device support is needed.
APP_ABI := arm64-v8a
APP_PLATFORM := android-21
APP_CFLAGS := -Wno-error

# Temporary debug build: run gameplay on the real byte-accurate CPU-emulated
# interpreter instead of the decompiled C logic, to test whether a bug seen
# only on-device lives in the decompile. Revert after diagnosis.
APP_CFLAGS += -DFORCE_RM_THEIRS_DEBUG
