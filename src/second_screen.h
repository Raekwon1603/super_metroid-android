#pragma once
#include <stdbool.h>
#include "types.h"

// Read-only bridge into the running game's live state, for the Android
// dual-screen mod (platform/android/second_screen_jni.c) and any other
// platform that wants to build a companion display. Plain C, no platform
// dependencies - just reads g_ram-backed globals from variables.h.

// ---- player / location ----
int SM2_GetSamusX(void);
int SM2_GetSamusY(void);
int SM2_GetArea(void);
int SM2_GetRoom(void);
// Whether the map station for the *currently loaded* area has been
// activated (unlocks the pause-menu map for that area).
bool SM2_HasAreaMap(void);
// Fills out[0..3] with {room_x_coordinate_on_map, room_y_coordinate_on_map,
// room_width_in_blocks, room_height_in_blocks} - the current room's
// placement within its area's map grid.
void SM2_GetRoomMapRect(int *out);

// ---- map explore state ----
// Copies the live per-area explored-tile bitmap (map_tiles_explored,
// g_ram+0x7F7, 256 bytes) into out; n is clamped to 256.
void SM2_ReadExploredTiles(uint8 *out, int n);

// ---- items (equipped_items / collected_items bitfields, g_ram+0x9A2/0x9A4) ----
// Bit values reverse-derived from this decomp's own usage (palette/pose
// selection, PLM pickup handlers, HUD icon code) - see second_screen.c.
enum {
  kSM2Item_Varia = 0x0001,
  kSM2Item_SpringBall = 0x0002,
  kSM2Item_MorphBall = 0x0004,
  kSM2Item_ScrewAttack = 0x0008,
  kSM2Item_Gravity = 0x0020,
  kSM2Item_HiJump = 0x0100,
  kSM2Item_SpaceJump = 0x0200,
  kSM2Item_Bombs = 0x1000,
  kSM2Item_SpeedBooster = 0x2000,
  kSM2Item_Grapple = 0x4000,
  kSM2Item_XRay = 0x8000,
};
int SM2_GetCollectedItems(void);
int SM2_GetEquippedItems(void);

// ---- beams (equipped_beams / collected_beams bitfields, g_ram+0x9A6/0x9A8) ----
enum {
  kSM2Beam_Wave = 0x0001,
  kSM2Beam_Ice = 0x0002,
  kSM2Beam_Spazer = 0x0004,
  kSM2Beam_Plasma = 0x0008,
  kSM2Beam_Charge = 0x1000,
};
int SM2_GetCollectedBeams(void);
int SM2_GetEquippedBeams(void);

// ---- health / ammo ----
int SM2_GetHealth(void);
int SM2_GetMaxHealth(void);
int SM2_GetReserveHealth(void);
int SM2_GetMaxReserveHealth(void);
int SM2_GetMissiles(void);
int SM2_GetMaxMissiles(void);
int SM2_GetSuperMissiles(void);
int SM2_GetMaxSuperMissiles(void);
int SM2_GetPowerBombs(void);
int SM2_GetMaxPowerBombs(void);
