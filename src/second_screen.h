#pragma once
#include <stdbool.h>
#include "types.h"

// Read-only bridge into the running game's live state, for the Android
// dual-screen mod (platform/android/second_screen_jni.c) and any other
// platform that wants to build a companion display. Plain C, no platform
// dependencies - just reads g_ram-backed globals from variables.h.

// Implemented in main.c (alongside the existing RtlApuLock/RtlApuUnlock
// pattern) - guards the game's own per-frame execution against this file's
// SM2_RenderCurrentRoomArt/SM2_RenderAreaMap, which call the shared,
// non-reentrant DecompressToMem/DecompressToVRAM (sm_80.c, whose
// decompress_src cursor is a single file-scope global) from the UI/JNI
// thread while the game thread may be mid-decompression itself. Any SM2_*
// function in this file that calls DecompressToMem or DecompressToVRAM
// MUST wrap that call (or the smallest span of calls that must stay
// consistent with each other) in SM2_LockGameState()/SM2_UnlockGameState().
void SM2_LockGameState(void);
void SM2_UnlockGameState(void);

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

// ---- game mode ----
// Raw game_state value (g_ram+0x998) - see the GameState enum in
// ida_types.h for the full state list (menus, cinematics, pause, demo, etc).
int SM2_GetGameState(void);
// True while the live map is meaningful to show: normal gameplay and its
// brief door/room-load transitions. False for anything else (title/file
// select, pause menu, death sequence, cutscenes, demo attract-mode) - those
// already have their own thing on the main display, so the second screen
// should dim rather than show a stale/irrelevant map.
bool SM2_IsPlayingLive(void);

// Renders the real in-game area-name label graphic - the same 12-tile-wide
// strip DrawRoomSelectMapAreaLabel (sm_82.c) draws on the elevator/room-select
// screen - for the given area index into a 96x8 ARGB8888 buffer (out must be
// 96*8 = 768 uint32s), decoded from the same ROM tile/palette data the map
// itself uses. Background and each tile's own palette index 0 are left
// transparent. Returns false if the ROM isn't loaded yet.
bool SM2_RenderAreaLabel(int area, uint32 *out);

// ---- map explore state ----
// Copies the live per-area explored-tile bitmap (map_tiles_explored,
// g_ram+0x7F7, 256 bytes) into out; n is clamped to 256.
void SM2_ReadExploredTiles(uint8 *out, int n);

// Decodes map_tiles_explored's packed bit layout (reverse-derived from
// MarkMapTileAsExplored in sm_90.c) into a plain 64x32 grid, one byte (0/1)
// per map tile, row-major (out[y*64+x]). out must be 64*32 = 2048 bytes.
void SM2_DecodeExploredGrid(uint8 *out);

// Absolute map-tile coordinates (0..63, 0..31) of Samus's current position,
// same coordinate space as SM2_DecodeExploredGrid and SM2_GetRoomMapRect.
void SM2_GetSamusMapTile(int *out_x, int *out_y);

// Same coordinate space as SM2_GetSamusMapTile, but sub-tile-precise and
// scaled by 256 (fixed-point) instead of floored to a whole map tile -
// SM2_GetSamusMapTile only changes once every 256 room-pixels of movement
// (one full map tile), which reads as a visible "snap" every couple of
// seconds of walking rather than smooth motion. samus_x_pos/samus_y_pos
// are plain absolute room-pixel coordinates (confirmed via this decomp's
// own actor code, e.g. sm_a3.c/sm_a5.c assigning samus_x_pos = a real
// actor x_pos field directly - not fixed-point), so dividing by 256
// (one map tile's pixel width) gives a continuously-varying tile-space
// position - out_x256/out_y256 are that value times 256, so callers get
// sub-pixel precision without needing floating point here.
void SM2_GetSamusMapPosFixed(int *out_x256, int *out_y256);

// Whether the player has explored any tile at all in the given area (0-7,
// not just the currently-loaded one) - true real exploration progress, not
// map-station ownership (LoadPauseMenuMapTilemap's own gate, which most
// players won't have for every area they've walked through). Used by the
// second screen's world view to hide areas nothing's been revealed in yet.
bool SM2_AreaHasAnyExploredTile(int area);

// Same as SM2_DecodeExploredGrid, but for any area (0-7), not just the
// currently-loaded one - the current area's bits come from the live in-RAM
// copy, other areas' from explored_map_tiles_saved, matching
// SM2_RenderAreaMap's own per-area source selection. Used by the second
// screen's world view to crop each area's displayed region down to its own
// actually-explored extent rather than a fixed full-area bounding box.
// out must be 64*32 = 2048 bytes. Returns false (leaving out untouched) if
// the ROM isn't loaded yet.
bool SM2_DecodeExploredGridForArea(int area, uint8 *out);

// Renders the given area's map into a 512x256 ARGB8888 buffer (64x32 tiles
// at 8x8px each, matching SM2_DecodeExploredGrid's grid), decoding the same
// SNES 4bpp tile graphics/palette the in-game pause-menu map screen uses
// (see LoadPauseMenuMapTilemap in sm_82.c) rather than a schematic
// placeholder. Unexplored tiles are left a flat dark color instead of
// decoded. Can be called for any area (0-7), not just the currently-loaded
// one - the current area's explored bits come from the live in-RAM copy,
// other areas' from explored_map_tiles_saved (kept in sync per-area on every
// area transition), so a caller can render a multi-area "world view" of
// everywhere explored so far. out must be 512*256 uint32s (0xAARRGGBB per
// pixel, i.e. Android Bitmap.setPixels()'s native int format). Returns false
// (leaving out untouched) if the ROM isn't loaded yet - g_rom is NULL until
// SnesInit() finishes on the native game thread, which can race behind a
// second-screen view that starts drawing immediately on activity start.
bool SM2_RenderAreaMap(int area, uint32 *out);

// Renders the real in-game equipment-screen icon for the given item/beam
// bit into a 64x8 ARGB8888 strip (out must be 64*8 = 512 uint32s), decoded
// from the same ROM tile/palette data the pause menu's equipment screen
// itself uses. Unused space and each tile's own palette index 0 are left
// transparent. Returns false if the ROM isn't loaded yet, or for the two
// items with no equipment-screen icon in vanilla SM (Grapple, X-Ray).
bool SM2_RenderItemIcon(int bit, uint32 *out);
bool SM2_RenderBeamIcon(int bit, uint32 *out);

// Renders the real pause-menu equipment screen's green wireframe Samus
// body graphic into a 64x136 ARGB8888 buffer (out must be 64*136 = 8704
// uint32s). equipped_items_value picks the correct one of 4 pre-baked
// variants (no suit / Gravity only / Varia only / both) - pass
// SM2_GetEquippedItems()'s own return value. Each tile's own palette
// index 0 is left transparent. Returns false if the ROM isn't loaded yet.
bool SM2_RenderSamusWireframe(int equipped_items_value, uint32 *out);

// Same as SM2_RenderSamusWireframe, but using the "Redux Suit" full-color
// art (see redux_suit_data.h) instead of vanilla's green wireframe -
// extracted from the real Super-Metroid-Redux ROM, not this app's own ROM,
// so it works the same regardless of which vanilla ROM the player loaded.
// Never returns false (no ROM/live-game dependency - the art is fully
// embedded), unlike SM2_RenderSamusWireframe.
bool SM2_RenderReduxSuit(int equipped_items_value, uint32 *out);

// Renders the actual gameplay-HUD missile icon (the small tank glyph shown
// next to the ammo count during normal play - kHudTilemaps_Missiles in
// sm_80.c) into a 24x16 ARGB8888 buffer (out must be 24*16 = 384 uint32s).
// Unlike the equipment-screen data, this tilemap is a literal array in the
// decompiled C source, not ROM-only data. Returns false if the ROM isn't
// loaded yet.
bool SM2_RenderMissileIcon(uint32 *out);

// Same idea as SM2_RenderMissileIcon but for the Super Missile / Power Bomb
// HUD tank icons - each a 2x2-tile (16x16px) glyph (out must be 16*16 = 256
// uint32s), also decoded from a literal tilemap array in the decompiled
// source (kHudTilemaps_Missiles22 in second_screen.c, copied from
// kHudTilemaps_Missiles in sm_80.c), not ROM-only data. Returns false if
// the ROM isn't loaded yet.
bool SM2_RenderSuperMissileIcon(uint32 *out);
bool SM2_RenderPowerBombIcon(uint32 *out);

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

// ---- selected ammo (hud_item_index, g_ram+0x9D2) ----
// The Select-button "currently armed ammo" slot (HandleSwitchingHudSelection
// in sm_90.c) - a plain index, not a bitfield: 0=none, 1=Missiles,
// 2=Super Missiles, 3=Power Bombs, 4=Grapple, 5=X-Ray.
enum {
  kSM2Ammo_None = 0,
  kSM2Ammo_Missiles = 1,
  kSM2Ammo_SuperMissiles = 2,
  kSM2Ammo_PowerBombs = 3,
  kSM2Ammo_Grapple = 4,
  kSM2Ammo_XRay = 5,
};
int SM2_GetSelectedAmmo(void);
// Writes hud_item_index directly - the same effect as pressing Select on
// the controller until this slot is reached. No-op for out-of-range values.
void SM2_SetSelectedAmmo(int index);
// Cycles the selected ammo by +1/-1 among None/Missiles/Supers/PowerBombs,
// skipping any type with zero owned count. For binding to a spare gamepad
// button (e.g. L2/R2) so ammo can be switched without opening the second
// screen's AMMO tab.
void SM2_CycleSelectedAmmo(int direction);

// ---- real room background art ----
// Max room-art render buffer dimensions - a handful of SM's largest rooms
// (tall elevator/water shafts) exceed this and get clipped rather than
// requiring an unbounded allocation; the overwhelming majority of real
// rooms fit comfortably within it.
#define kSM2RoomArtMaxW 2048
#define kSM2RoomArtMaxH 2048

// Renders the CURRENT room's real background art (the actual walls/floor/
// decoration tiles the game draws during live gameplay - BG1, plus BG2 if
// this room state has one) into out (out_w x out_h, caller-allocated ARGB
// buffer, capped at kSM2RoomArtMaxW x kSM2RoomArtMaxH). This is NOT the
// same data as SM2_RenderAreaMap (which decodes the schematic pause-menu
// map tiles - simple colored rectangles/outlines) - this decodes the
// literal in-game room art, unabridged. Fills out_w_used/out_h_used with
// the room's real pixel dimensions (room_width_in_blocks/
// room_height_in_blocks * 16), which may exceed the buffer for a few
// oversized rooms - callers should treat that as "bigger than what got
// rendered", not an error. Returns false if the ROM isn't loaded yet or no
// room is currently loaded.
bool SM2_RenderCurrentRoomArt(uint32 *out, int out_w, int out_h, int *out_w_used, int *out_h_used);
