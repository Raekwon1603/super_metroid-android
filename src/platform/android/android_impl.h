#pragma once

// chdir()s into app-external storage and ensures a "saves" subdirectory
// exists there, so the engine's relative-path save/quicksave/debug-snapshot
// code (RtlSaveLoad, RtlSaveSnapshot - all of which fopen("saves/...")
// relative to cwd) has somewhere writable to resolve to, matching how it
// works on desktop out of a checkout with a saves/ folder alongside it.
void AndroidImpl_Init(void);

// Absolute path to the ROM the Java SetupActivity copied into app-external
// storage (getExternalFilesDir(null)/sm.smc), or "sm.smc" if the external
// files dir can't be queried yet.
const char *AndroidImpl_GetRomPath(void);
