#include <SDL.h>
#include <stdio.h>
#include "android_impl.h"

const char *AndroidImpl_GetRomPath(void) {
  static char path[1024];
  // SDL_AndroidGetExternalStoragePath() is the app-specific external storage
  // root, i.e. the native-side equivalent of Java's getExternalFilesDir(null)
  // - the same directory SetupActivity copies the picked ROM into.
  const char *dir = SDL_AndroidGetExternalStoragePath();
  snprintf(path, sizeof(path), "%s/sm.smc", dir ? dir : ".");
  return path;
}
