#include <jni.h>
#include "second_screen.h"

// Thin JNI wrappers around second_screen.c's SM2_* functions, for the
// dual-screen mod's Java side (com.raekwon.supermetroid.GameState). Flat
// primitives/arrays only, no structs; array-output functions bounds-check
// against the caller's array length and fail silently (false/no write)
// rather than throwing, matching zelda3-android's second_screen_jni.c.

#define JFN(name) Java_com_raekwon_supermetroid_GameState_##name

JNIEXPORT jint JNICALL JFN(getSamusX)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetSamusX();
}

JNIEXPORT jint JNICALL JFN(getSamusY)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetSamusY();
}

JNIEXPORT jint JNICALL JFN(getArea)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetArea();
}

JNIEXPORT jint JNICALL JFN(getRoom)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetRoom();
}

JNIEXPORT jboolean JNICALL JFN(hasAreaMap)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_HasAreaMap() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JFN(areaHasAnyExploredTile)(JNIEnv *env, jclass clazz, jint area) {
  (void)env; (void)clazz;
  return SM2_AreaHasAnyExploredTile(area) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JFN(getGameState)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetGameState();
}

JNIEXPORT jboolean JNICALL JFN(isPlayingLive)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_IsPlayingLive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JFN(getRoomMapRect)(JNIEnv *env, jclass clazz, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 4) return JNI_FALSE;
  int tmp[4];
  SM2_GetRoomMapRect(tmp);
  (*env)->SetIntArrayRegion(env, out, 0, 4, (jint *)tmp);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JFN(readExploredTiles)(JNIEnv *env, jclass clazz, jbyteArray out) {
  (void)clazz;
  if (!out) return JNI_FALSE;
  jsize n = (*env)->GetArrayLength(env, out);
  if (n > 0x100) n = 0x100;
  uint8 tmp[0x100];
  SM2_ReadExploredTiles(tmp, n);
  (*env)->SetByteArrayRegion(env, out, 0, n, (jbyte *)tmp);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JFN(decodeExploredGrid)(JNIEnv *env, jclass clazz, jbyteArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 64 * 32) return JNI_FALSE;
  uint8 tmp[64 * 32];
  SM2_DecodeExploredGrid(tmp);
  (*env)->SetByteArrayRegion(env, out, 0, 64 * 32, (jbyte *)tmp);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JFN(decodeExploredGridForArea)(JNIEnv *env, jclass clazz, jint area, jbyteArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 64 * 32) return JNI_FALSE;
  uint8 tmp[64 * 32];
  if (!SM2_DecodeExploredGridForArea(area, tmp)) return JNI_FALSE;
  (*env)->SetByteArrayRegion(env, out, 0, 64 * 32, (jbyte *)tmp);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JFN(getSamusMapTile)(JNIEnv *env, jclass clazz, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 2) return JNI_FALSE;
  int tmp[2];
  SM2_GetSamusMapTile(&tmp[0], &tmp[1]);
  (*env)->SetIntArrayRegion(env, out, 0, 2, (jint *)tmp);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JFN(getSamusMapPosFixed)(JNIEnv *env, jclass clazz, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 2) return JNI_FALSE;
  int tmp[2];
  SM2_GetSamusMapPosFixed(&tmp[0], &tmp[1]);
  (*env)->SetIntArrayRegion(env, out, 0, 2, (jint *)tmp);
  return JNI_TRUE;
}

// 512x256 ARGB8888 scratch buffer - too large to put on the stack safely,
// same reasoning as zelda3-android's second_screen_jni.c g_jni_px buffer.
static uint32 g_map_px[512 * 256];

JNIEXPORT jboolean JNICALL JFN(renderAreaMap)(JNIEnv *env, jclass clazz, jint area, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 512 * 256) return JNI_FALSE;
  if (!SM2_RenderAreaMap(area, g_map_px)) return JNI_FALSE;
  (*env)->SetIntArrayRegion(env, out, 0, 512 * 256, (jint *)g_map_px);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JFN(renderAreaLabel)(JNIEnv *env, jclass clazz, jint area, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 96 * 8) return JNI_FALSE;
  uint32 px[96 * 8];
  if (!SM2_RenderAreaLabel(area, px)) return JNI_FALSE;
  (*env)->SetIntArrayRegion(env, out, 0, 96 * 8, (jint *)px);
  return JNI_TRUE;
}

JNIEXPORT jint JNICALL JFN(getCollectedItems)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetCollectedItems();
}

JNIEXPORT jint JNICALL JFN(getEquippedItems)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetEquippedItems();
}

JNIEXPORT jint JNICALL JFN(getCollectedBeams)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetCollectedBeams();
}

JNIEXPORT jint JNICALL JFN(getEquippedBeams)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetEquippedBeams();
}

JNIEXPORT jint JNICALL JFN(getHealth)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetHealth();
}

JNIEXPORT jint JNICALL JFN(getMaxHealth)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetMaxHealth();
}

JNIEXPORT jint JNICALL JFN(getReserveHealth)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetReserveHealth();
}

JNIEXPORT jint JNICALL JFN(getMaxReserveHealth)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetMaxReserveHealth();
}

JNIEXPORT jint JNICALL JFN(getMissiles)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetMissiles();
}

JNIEXPORT jint JNICALL JFN(getMaxMissiles)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetMaxMissiles();
}

JNIEXPORT jint JNICALL JFN(getSuperMissiles)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetSuperMissiles();
}

JNIEXPORT jint JNICALL JFN(getMaxSuperMissiles)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetMaxSuperMissiles();
}

JNIEXPORT jint JNICALL JFN(getPowerBombs)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetPowerBombs();
}

JNIEXPORT jint JNICALL JFN(getMaxPowerBombs)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetMaxPowerBombs();
}

JNIEXPORT jint JNICALL JFN(getSelectedAmmo)(JNIEnv *env, jclass clazz) {
  (void)env; (void)clazz;
  return SM2_GetSelectedAmmo();
}

JNIEXPORT void JNICALL JFN(setSelectedAmmo)(JNIEnv *env, jclass clazz, jint index) {
  (void)env; (void)clazz;
  SM2_SetSelectedAmmo(index);
}

JNIEXPORT jboolean JNICALL JFN(renderItemIcon)(JNIEnv *env, jclass clazz, jint bit, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 64 * 8) return JNI_FALSE;
  uint32 px[64 * 8];
  if (!SM2_RenderItemIcon(bit, px)) return JNI_FALSE;
  (*env)->SetIntArrayRegion(env, out, 0, 64 * 8, (jint *)px);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JFN(renderBeamIcon)(JNIEnv *env, jclass clazz, jint bit, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 64 * 8) return JNI_FALSE;
  uint32 px[64 * 8];
  if (!SM2_RenderBeamIcon(bit, px)) return JNI_FALSE;
  (*env)->SetIntArrayRegion(env, out, 0, 64 * 8, (jint *)px);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JFN(renderMissileIcon)(JNIEnv *env, jclass clazz, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 24 * 16) return JNI_FALSE;
  uint32 px[24 * 16];
  if (!SM2_RenderMissileIcon(px)) return JNI_FALSE;
  (*env)->SetIntArrayRegion(env, out, 0, 24 * 16, (jint *)px);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JFN(renderSuperMissileIcon)(JNIEnv *env, jclass clazz, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 16 * 16) return JNI_FALSE;
  uint32 px[16 * 16];
  if (!SM2_RenderSuperMissileIcon(px)) return JNI_FALSE;
  (*env)->SetIntArrayRegion(env, out, 0, 16 * 16, (jint *)px);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JFN(renderPowerBombIcon)(JNIEnv *env, jclass clazz, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 16 * 16) return JNI_FALSE;
  uint32 px[16 * 16];
  if (!SM2_RenderPowerBombIcon(px)) return JNI_FALSE;
  (*env)->SetIntArrayRegion(env, out, 0, 16 * 16, (jint *)px);
  return JNI_TRUE;
}

// 64x136 ARGB8888 scratch buffer - too large to put on the stack safely,
// same reasoning as g_map_px above.
static uint32 g_wireframe_px[64 * 136];

JNIEXPORT jboolean JNICALL JFN(renderSamusWireframe)(JNIEnv *env, jclass clazz, jint equippedItems, jintArray out) {
  (void)clazz;
  if (!out || (*env)->GetArrayLength(env, out) < 64 * 136) return JNI_FALSE;
  if (!SM2_RenderSamusWireframe(equippedItems, g_wireframe_px)) return JNI_FALSE;
  (*env)->SetIntArrayRegion(env, out, 0, 64 * 136, (jint *)g_wireframe_px);
  return JNI_TRUE;
}

// kSM2RoomArtMaxW x kSM2RoomArtMaxH ARGB8888 scratch buffer - too large to
// put on the stack safely, same reasoning as g_map_px above (16MB at the
// full 2048x2048 cap, so definitely not stack-local).
static uint32 g_room_art_px[kSM2RoomArtMaxW * kSM2RoomArtMaxH];

// outDims must be length >= 2: {realWidthPx, realHeightPx} - the room's
// true pixel dimensions, which may exceed what actually got rendered into
// out if this room is one of the handful bigger than kSM2RoomArtMaxW x
// kSM2RoomArtMaxH (see SM2_RenderCurrentRoomArt's own comment). out must
// be length >= kSM2RoomArtMaxW * kSM2RoomArtMaxH, but only the first
// w_used*h_used entries are actually written/copied back - most real
// rooms are far smaller than the full cap, so copying only the used
// portion (row by row, since out's own stride is the fixed
// kSM2RoomArtMaxW, not w_used) keeps this cheap for the common case
// instead of always moving the full 16MB buffer across the JNI boundary.
JNIEXPORT jboolean JNICALL JFN(renderCurrentRoomArt)(JNIEnv *env, jclass clazz, jintArray out, jintArray outDims) {
  (void)clazz;
  if (!out || !outDims) return JNI_FALSE;
  if ((*env)->GetArrayLength(env, out) < kSM2RoomArtMaxW * kSM2RoomArtMaxH) return JNI_FALSE;
  if ((*env)->GetArrayLength(env, outDims) < 2) return JNI_FALSE;
  int w_used, h_used;
  if (!SM2_RenderCurrentRoomArt(g_room_art_px, kSM2RoomArtMaxW, kSM2RoomArtMaxH, &w_used, &h_used)) return JNI_FALSE;
  for (int y = 0; y < h_used; y++) {
    (*env)->SetIntArrayRegion(env, out, y * kSM2RoomArtMaxW, w_used, (jint *)(g_room_art_px + (size_t)y * kSM2RoomArtMaxW));
  }
  jint dims[2] = { w_used, h_used };
  (*env)->SetIntArrayRegion(env, outDims, 0, 2, dims);
  return JNI_TRUE;
}
