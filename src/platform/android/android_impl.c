#include <SDL.h>
#include <stdio.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include "android_impl.h"

// libc printf()/stderr writes don't reach logcat by default on Android.
// This engine has diagnostic printf()/Warning() calls (coroutine-desync and
// bad-game-state detection in sm_82.c, snapshot mismatch reporting in
// sm_cpu_infra.c) that are otherwise invisible on-device. Redirect stdout and
// stderr into a pipe and forward each line to logcat, temporarily, for
// debugging a device-only bug report.
static void *AndroidImpl_LogcatRedirectThread(void *arg) {
  int fd = (int)(intptr_t)arg;
  char buf[1024];
  ssize_t n, used = 0;
  while ((n = read(fd, buf + used, sizeof(buf) - 1 - used)) > 0) {
    used += n;
    buf[used] = 0;
    char *line = buf, *nl;
    while ((nl = strchr(line, '\n')) != NULL) {
      *nl = 0;
      __android_log_write(ANDROID_LOG_INFO, "sm_native", line);
      line = nl + 1;
    }
    used -= (line - buf);
    memmove(buf, line, used);
  }
  return NULL;
}

static void AndroidImpl_RedirectStdioToLogcat(void) {
  int fds[2];
  if (pipe(fds) != 0) return;
  dup2(fds[1], STDOUT_FILENO);
  dup2(fds[1], STDERR_FILENO);
  close(fds[1]);
  setvbuf(stdout, NULL, _IOLBF, 0);
  setvbuf(stderr, NULL, _IONBF, 0);
  pthread_t thread;
  pthread_create(&thread, NULL, AndroidImpl_LogcatRedirectThread, (void *)(intptr_t)fds[0]);
  pthread_detach(thread);
}

void AndroidImpl_Init(void) {
  AndroidImpl_RedirectStdioToLogcat();
  const char *dir = SDL_AndroidGetExternalStoragePath();
  if (!dir) return;
  chdir(dir);
  mkdir("saves", 0777);
}

const char *AndroidImpl_GetRomPath(void) {
  static char path[1024];
  // SDL_AndroidGetExternalStoragePath() is the app-specific external storage
  // root, i.e. the native-side equivalent of Java's getExternalFilesDir(null)
  // - the same directory SetupActivity copies the picked ROM into.
  const char *dir = SDL_AndroidGetExternalStoragePath();
  snprintf(path, sizeof(path), "%s/sm.smc", dir ? dir : ".");
  return path;
}
