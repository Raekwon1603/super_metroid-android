#pragma once

// Absolute path to the ROM the Java SetupActivity copied into app-external
// storage (getExternalFilesDir(null)/sm.smc), or "sm.smc" if the external
// files dir can't be queried yet.
const char *AndroidImpl_GetRomPath(void);
