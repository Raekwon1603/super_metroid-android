#include <string.h>
#include "second_screen.h"
#include "variables.h"
#include "sm_rtl.h"

// Same ROM addresses LoadPauseMenuMapTilemap (sm_82.c) uses to build the
// in-game pause-menu map screen - redefined locally here the same way
// sm_82.c/sm_90.c each redefine kPauseMenuMapData/kPauseMenuMapTilemaps
// locally rather than sharing a header.
#define kPauseMenuMapTilemaps ((LongPtr *)RomFixedPtr(0x82964a))
#define kMapTileGfx ((const uint8 *)RomFixedPtr(0xb68000))  // 768 tiles x 32 bytes, SNES 4bpp
#define kPauseScreenPalettes ((const uint16 *)RomFixedPtr(0xb6f000))  // 256 x BGR555
#define kMapTileCount 768
#define kMapGridW 64
#define kMapGridH 32

// The gameplay HUD (BG3) is a SEPARATE tile graphics/palette bank from the
// pause-menu screens above - BG3 runs in 2bpp (16 bytes/tile, 4 colors per
// palette row), not 4bpp like BG1/BG2 (BGMODE=9 is Mode 1 with BG3 on top;
// Mode 1's BG3 is always 2bpp). Confirmed by direct ROM decode: attempting
// to decode kHudTilemaps_Missiles22's tile indices as 4bpp against
// kMapTileGfx/kPauseScreenPalettes produced garbled glyph-noise, not icon
// art; switching to 2bpp against these addresses (the BG3 tile-graphics
// DMA source in LoadStdBG3andSpriteTilesClearTilemaps, and the palette DMA
// source in LoadInitialPalette, both sm_82.c) produced correct, recognizable
// missile/super-missile/power-bomb tank icon shapes (verified via a
// standalone Python re-implementation against the real ROM dump before
// this code was written).
#define kHudTileGfx ((const uint8 *)RomFixedPtr(0x9ab200))  // BG3 char data, 2bpp
#define kHudPalette ((const uint16 *)RomFixedPtr(0x9a8000))  // full 256-color initial palette
#define kHudTileCount 512  // DMA'd region is 0x2000 bytes / 16 bytes-per-2bpp-tile (sm_82.c)

// Equipment-screen icon tables (see LoadEquipmentScreenEquipmentTilemaps in
// sm_82.c, which redefines these same addresses locally too). Each entry is
// a small run of tilemap entries: element 0 is a shared blank/spacer tile
// (identical across every item - confirmed by dumping the actual ROM data
// on-device), and the rest are the item's real icon strip, drawn from the
// same kMapTileGfx/kPauseScreenPalettes bank as the map art.
#define kEquipmentBitmasks_Weapons ((const uint16 *)RomFixedPtr(0x82c04c))
#define kEquipmentBitmasks_Suits ((const uint16 *)RomFixedPtr(0x82c056))
#define kEquipmentBitmasks_Boots ((const uint16 *)RomFixedPtr(0x82c062))
#define kEquipmentTilemaps_Weapons ((const uint16 *)RomFixedPtr(0x82c08c))
#define kEquipmentTilemaps_Suits ((const uint16 *)RomFixedPtr(0x82c096))
#define kEquipmentTilemaps_Boots ((const uint16 *)RomFixedPtr(0x82c0a2))
#define kEquipIconTiles 8  // output width in tiles; weapons only use 4 and leave the rest transparent

// The elevator/room-select screen's per-area name label (see
// DrawRoomSelectMapAreaLabel and its kPauseAreaLabelTilemap[area_index] use
// in sm_82.c, which redefines this same address locally too): an array of 8
// bank-0x82 offsets, one per area, each pointing to a 12-entry tilemap strip
// (12 tiles x 1 tile = 96x8px) drawn from the same kMapTileGfx/
// kPauseScreenPalettes bank as the map and equipment icons.
#define kPauseAreaLabelTilemap ((const uint16 *)RomFixedPtr(0x82965f))
#define kAreaLabelTiles 12

int SM2_GetSamusX(void) { return samus_x_pos; }
int SM2_GetSamusY(void) { return samus_y_pos; }
int SM2_GetArea(void) { return area_index; }
int SM2_GetRoom(void) { return room_index; }
bool SM2_HasAreaMap(void) { return has_area_map != 0; }

int SM2_GetGameState(void) { return game_state; }

bool SM2_IsPlayingLive(void) {
  // 7 = fade-in into gameplay, 8 = main gameplay, 9..0xB = the brief
  // hit-door-block/loading-next-room blip - see the GameState enum in
  // ida_types.h. Everything else is a menu, pause, cutscene, death sequence,
  // or demo attract-mode.
  switch (game_state) {
    case 7: case 8: case 9: case 0xA: case 0xB:
      return true;
    default:
      return false;
  }
}

void SM2_GetRoomMapRect(int *out) {
  out[0] = room_x_coordinate_on_map;
  out[1] = room_y_coordinate_on_map;
  out[2] = room_width_in_blocks;
  out[3] = room_height_in_blocks;
}

void SM2_ReadExploredTiles(uint8 *out, int n) {
  if (n > 0x100) n = 0x100;
  memcpy(out, map_tiles_explored, n);
}

// map_tiles_explored's packed bit layout, generalized to any 256-byte
// explored-bits source - shared by SM2_DecodeExploredGrid (the live current
// area) and SM2_RenderAreaMap's other-area path (explored_map_tiles_saved).
static void DecodeExploredGridFrom(const uint8 *bits, uint8 *out) {
  for (int y = 0; y < 32; y++) {
    for (int x = 0; x < 64; x++) {
      int half = x >> 5;
      int col = (x >> 3) & 3;
      int byte_index = col + half * 128 + 4 * y;
      uint8 bit = 0x80 >> (x & 7);
      out[y * 64 + x] = (bits[byte_index] & bit) ? 1 : 0;
    }
  }
}

void SM2_DecodeExploredGrid(uint8 *out) {
  DecodeExploredGridFrom(map_tiles_explored, out);
}

void SM2_GetSamusMapTile(int *out_x, int *out_y) {
  *out_x = room_x_coordinate_on_map + (samus_x_pos >> 8);
  *out_y = room_y_coordinate_on_map + (samus_y_pos >> 8) + 1;
}

// SNES 4bpp planar: 32 bytes/tile - first 16 bytes are bitplanes 0/1
// interleaved per row, last 16 are bitplanes 2/3 interleaved per row.
// (px, py) are pixel coords within the tile, pixel 0 = MSB (leftmost).
static int Snes4bppColorIndex(const uint8 *tile, int px, int py) {
  int bit = 7 - px;
  uint8 p0 = tile[py * 2];
  uint8 p1 = tile[py * 2 + 1];
  uint8 p2 = tile[16 + py * 2];
  uint8 p3 = tile[16 + py * 2 + 1];
  return ((p0 >> bit) & 1) | (((p1 >> bit) & 1) << 1) | (((p2 >> bit) & 1) << 2) | (((p3 >> bit) & 1) << 3);
}

// SNES 2bpp planar: 16 bytes/tile, 2 bytes per row (bitplanes 0/1
// interleaved) - half the bitplanes of 4bpp, so only 4 colors per palette
// row (indices 0-3) instead of 16. Used by the gameplay HUD's BG3 layer -
// see kHudTileGfx/kHudPalette's own comment for why this differs from the
// 4bpp pause-menu tile bank.
static int Snes2bppColorIndex(const uint8 *tile, int px, int py) {
  int bit = 7 - px;
  uint8 p0 = tile[py * 2];
  uint8 p1 = tile[py * 2 + 1];
  return ((p0 >> bit) & 1) | (((p1 >> bit) & 1) << 1);
}

// SNES BGR555 -> Android's 0xAARRGGBB int format.
static uint32 Snes15ToArgb(uint16 c) {
  uint32 r5 = c & 0x1F, g5 = (c >> 5) & 0x1F, b5 = (c >> 10) & 0x1F;
  uint32 r = r5 * 255 / 31, g = g5 * 255 / 31, b = b5 * 255 / 31;
  return 0xFF000000u | (r << 16) | (g << 8) | b;
}

static void FillTilePixels(uint32 *out, int tx, int ty, uint32 color) {
  uint32 base = (uint32)ty * 8 * (kMapGridW * 8) + (uint32)tx * 8;
  for (int py = 0; py < 8; py++)
    for (int px = 0; px < 8; px++)
      out[base + (uint32)py * (kMapGridW * 8) + px] = color;
}

// The gameplay HUD's missile-icon tilemap (3 tiles wide x 2 tall = 24x16px),
// exactly as written by AddMissilesToHudTilemap in sm_80.c
// (hud_tilemap[10,11,12,42,43,44] = kHudTilemaps_Missiles[0..5], where
// 42/43/44 = 10/11/12 + 32, i.e. one tilemap row down). Unlike the
// equipment-screen data this is a literal array in the decompiled source,
// not ROM-only - copied here verbatim from kHudTilemaps_Missiles[0..5].
static const uint16 kHudMissileIconTilemap[6] = {
  0x344b, 0x3449, 0x744b, 0x344c, 0x344a, 0x744c,
};

// kHudTilemaps_Missiles[22] in sm_80.c (a literal tilemap array, not
// ROM-only data) backs FOUR gameplay-HUD icons, each a different 4-entry
// slice consumed via AddToTilemapInner's own byte-offset math: Missiles
// itself is handled separately (a 3x2 icon, AddMissilesToHudTilemap) using
// entries [0..5]; Supers/PowerBombs/Grapple/X-ray are each a 2x2 icon
// (AddSuperMissilesToHudTilemap etc, offsets 12/20/28/36 bytes = uint16
// indices 6/10/14/18). Copied here verbatim - see sm_80.c:1269-1299.
static const uint16 kHudTilemaps_Missiles22[22] = {
  0x344b, 0x3449, 0x744b, 0x344c, 0x344a, 0x744c, 0x3434, 0x7434, 0x3435, 0x7435,
  0x3436, 0x7436, 0x3437, 0x7437, 0x3438, 0x7438, 0x3439, 0x7439, 0x343a, 0x743a,
  0x343b, 0x743b,
};

bool SM2_RenderMissileIcon(uint32 *out) {
  if (!g_rom) return false;
  memset(out, 0, sizeof(uint32) * 24 * 16);
  for (int ty = 0; ty < 2; ty++) {
    for (int tx = 0; tx < 3; tx++) {
      uint16 entry = kHudTilemaps_Missiles22[ty * 3 + tx];
      int tile_index = entry & 0x3FF;
      int palette_row = (entry >> 10) & 7;
      bool flip_x = (entry & 0x4000) != 0;
      bool flip_y = (entry & 0x8000) != 0;
      if (tile_index >= kHudTileCount) continue;

      const uint8 *tile = kHudTileGfx + tile_index * 16;
      for (int py = 0; py < 8; py++) {
        int sy = flip_y ? 7 - py : py;
        for (int px = 0; px < 8; px++) {
          int sx = flip_x ? 7 - px : px;
          int ci = Snes2bppColorIndex(tile, sx, sy);
          if (ci == 0) continue;
          uint16 color15 = kHudPalette[palette_row * 4 + ci];
          out[(ty * 8 + py) * 24 + tx * 8 + px] = Snes15ToArgb(color15);
        }
      }
    }
  }
  return true;
}

// Shared by SM2_RenderSuperMissileIcon/SM2_RenderPowerBombIcon: decodes a
// 2x2-tile (16x16px) HUD icon from a 4-entry slice of
// kHudTilemaps_Missiles22, laid out [topLeft, topRight, bottomLeft,
// bottomRight] - matching AddToTilemapInner's own hud_tilemap[v2, v2+1,
// v2+32, v2+33] placement (a one-row-down wrap, i.e. row-major 2x2).
static void RenderHud2x2Icon(const uint16 *entries, uint32 *out) {
  memset(out, 0, sizeof(uint32) * 16 * 16);
  for (int ty = 0; ty < 2; ty++) {
    for (int tx = 0; tx < 2; tx++) {
      uint16 entry = entries[ty * 2 + tx];
      int tile_index = entry & 0x3FF;
      int palette_row = (entry >> 10) & 7;
      bool flip_x = (entry & 0x4000) != 0;
      bool flip_y = (entry & 0x8000) != 0;
      if (tile_index >= kHudTileCount) continue;

      const uint8 *tile = kHudTileGfx + tile_index * 16;
      for (int py = 0; py < 8; py++) {
        int sy = flip_y ? 7 - py : py;
        for (int px = 0; px < 8; px++) {
          int sx = flip_x ? 7 - px : px;
          int ci = Snes2bppColorIndex(tile, sx, sy);
          if (ci == 0) continue;
          uint16 color15 = kHudPalette[palette_row * 4 + ci];
          out[(ty * 8 + py) * 16 + tx * 8 + px] = Snes15ToArgb(color15);
        }
      }
    }
  }
}

bool SM2_RenderSuperMissileIcon(uint32 *out) {
  if (!g_rom) return false;
  RenderHud2x2Icon(kHudTilemaps_Missiles22 + 6, out);
  return true;
}

bool SM2_RenderPowerBombIcon(uint32 *out) {
  if (!g_rom) return false;
  RenderHud2x2Icon(kHudTilemaps_Missiles22 + 10, out);
  return true;
}

// Decodes `count` tilemap entries (skipping the shared blank entry[0] -
// see the kEquipmentBitmasks_* comment above) into a (kEquipIconTiles*8)x8
// strip. Unused trailing tiles (when count < kEquipIconTiles) and each
// tile's own palette index 0 are left fully transparent, so the icon reads
// as a sprite over whatever background it's drawn on rather than a solid
// block.
static void RenderEquipIconEntries(const uint16 *entries, int count, uint32 *out) {
  memset(out, 0, sizeof(uint32) * kEquipIconTiles * 8);
  for (int t = 0; t < count && t < kEquipIconTiles; t++) {
    uint16 entry = entries[1 + t];
    int tile_index = entry & 0x3FF;
    int palette_row = (entry >> 10) & 7;
    bool flip_x = (entry & 0x4000) != 0;
    bool flip_y = (entry & 0x8000) != 0;
    if (tile_index >= kMapTileCount) continue;

    const uint8 *tile = kMapTileGfx + tile_index * 32;
    for (int py = 0; py < 8; py++) {
      int sy = flip_y ? 7 - py : py;
      for (int px = 0; px < 8; px++) {
        int sx = flip_x ? 7 - px : px;
        int ci = Snes4bppColorIndex(tile, sx, sy);
        if (ci == 0) continue;  // palette index 0 = transparent for icon sprites
        uint16 color15 = kPauseScreenPalettes[palette_row * 16 + ci];
        out[py * (kEquipIconTiles * 8) + t * 8 + px] = Snes15ToArgb(color15);
      }
    }
  }
}

// Renders the real equipment-screen icon for the given collected_items bit
// into a 64x8 ARGB8888 strip (out must be kEquipIconTiles*8*8 uint32s).
// Returns false for items with no equipment-screen icon in vanilla SM
// (Grapple, X-Ray - confirmed absent from kEquipmentBitmasks_Suits/Boots)
// or if the ROM isn't loaded yet.
bool SM2_RenderItemIcon(int bit, uint32 *out) {
  if (!g_rom) return false;
  for (int i = 0; i < 6; i++) {
    if (kEquipmentBitmasks_Suits[i] == bit) {
      RenderEquipIconEntries((const uint16 *)RomPtr_82(kEquipmentTilemaps_Suits[i]), 8, out);
      return true;
    }
  }
  for (int i = 0; i < 3; i++) {
    if (kEquipmentBitmasks_Boots[i] == bit) {
      RenderEquipIconEntries((const uint16 *)RomPtr_82(kEquipmentTilemaps_Boots[i]), 8, out);
      return true;
    }
  }
  return false;
}

// Same as SM2_RenderItemIcon but for a collected_beams bit.
bool SM2_RenderBeamIcon(int bit, uint32 *out) {
  if (!g_rom) return false;
  for (int i = 0; i < 5; i++) {  // kEquipmentBitmasks_Weapons is 5 entries, not 6 - confirmed via ROM dump (index 5 aliases into kEquipmentBitmasks_Suits).
    if (kEquipmentBitmasks_Weapons[i] == bit) {
      RenderEquipIconEntries((const uint16 *)RomPtr_82(kEquipmentTilemaps_Weapons[i]), 4, out);
      return true;
    }
  }
  return false;
}

bool SM2_RenderAreaLabel(int area, uint32 *out) {
  if (!g_rom) return false;
  if (area < 0 || area > 7) return false;

  memset(out, 0, sizeof(uint32) * kAreaLabelTiles * 8 * 8);
  const uint16 *entries = (const uint16 *)RomPtr_82(kPauseAreaLabelTilemap[area]);
  for (int t = 0; t < kAreaLabelTiles; t++) {
    uint16 entry = entries[t] & 0xEFFF;  // matches DrawRoomSelectMapAreaLabel's masking
    int tile_index = entry & 0x3FF;
    int palette_row = (entry >> 10) & 7;
    bool flip_x = (entry & 0x4000) != 0;
    bool flip_y = (entry & 0x8000) != 0;
    if (tile_index >= kMapTileCount) continue;

    const uint8 *tile = kMapTileGfx + tile_index * 32;
    for (int py = 0; py < 8; py++) {
      int sy = flip_y ? 7 - py : py;
      for (int px = 0; px < 8; px++) {
        int sx = flip_x ? 7 - px : px;
        int ci = Snes4bppColorIndex(tile, sx, sy);
        if (ci == 0) continue;  // palette index 0 = transparent, matching the equipment icon strips
        uint16 color15 = kPauseScreenPalettes[palette_row * 16 + ci];
        out[py * (kAreaLabelTiles * 8) + t * 8 + px] = Snes15ToArgb(color15);
      }
    }
  }
  return true;
}

// Matches LoadPauseMenuMapTilemap's `!sign16(area_index - 7)` check (true
// for area_index >= 7): area 6 is Ceres, which has its own real tilemap
// entry (see MapIconDataPointers in ida_types.h: ceres is index 6, a
// distinct field from crateria at index 0) - only area 7 (the unused
// "debug" entry, never reached in normal play) falls back to Crateria's data.
static int RemapArea(int area) { return (area >= 7) ? 0 : area; }

// For the currently-loaded area, use the live in-RAM explored bits (freshest,
// may be ahead of the last area-transition/save sync). For any other area -
// used by the second screen's zoomed-out multi-area world view - fall back
// to explored_map_tiles_saved, which LoadMirrorOfExploredMapTiles/
// SaveExploredMapTilesToSaved (sm_80.c) keep synced per-area on every area
// transition, not just at save stations.
static void GetExploredGridForArea(int remapped_area, uint8 *out) {
  if (remapped_area == area_index) {
    DecodeExploredGridFrom(map_tiles_explored, out);
  } else {
    DecodeExploredGridFrom((const uint8 *)explored_map_tiles_saved + remapped_area * 256, out);
  }
}

// Whether the player has actually explored any part of the given area (any
// area, not just the currently-loaded one) - used by the second screen's
// world view to hide areas nothing has been revealed in yet, rather than
// showing an empty area's tile grid as if it were "on the map".
bool SM2_AreaHasAnyExploredTile(int area) {
  if (!g_rom) return false;
  if (area < 0 || area > 7) return false;
  uint8 explored[kMapGridW * kMapGridH];
  GetExploredGridForArea(RemapArea(area), explored);
  for (int i = 0; i < kMapGridW * kMapGridH; i++)
    if (explored[i]) return true;
  return false;
}

bool SM2_DecodeExploredGridForArea(int area, uint8 *out) {
  if (!g_rom) return false;
  if (area < 0 || area > 7) return false;
  GetExploredGridForArea(RemapArea(area), out);
  return true;
}

bool SM2_RenderAreaMap(int area, uint32 *out) {
  if (!g_rom) return false;  // ROM not loaded yet - see the header comment.

  if (area < 0 || area > 7) return false;

  static const uint32 kUnexploredColor = 0xFF14141Eu;
  int remapped_area = RemapArea(area);
  const uint16 *tilemap = (const uint16 *)RomPtr(Load24(&kPauseMenuMapTilemaps[remapped_area]));
  const uint8 *gfx = kMapTileGfx;
  const uint16 *pal = kPauseScreenPalettes;

  uint8 explored[kMapGridW * kMapGridH];
  GetExploredGridForArea(remapped_area, explored);

  for (int ty = 0; ty < kMapGridH; ty++) {
    for (int tx = 0; tx < kMapGridW; tx++) {
      if (!explored[ty * kMapGridW + tx]) {
        FillTilePixels(out, tx, ty, kUnexploredColor);
        continue;
      }

      // Tilemap entries are stored as two 32x32 "screen" blocks side by
      // side (standard SNES 64-wide BG layout) - same halving as
      // SM2_DecodeExploredGrid's byte packing, but on whole 16-bit entries.
      int half = tx >> 5;
      int i = half * 1024 + ty * 32 + (tx & 31);
      uint16 entry = tilemap[i];
      int tile_index = entry & 0x3FF;
      int palette_row = (entry >> 10) & 7;
      bool flip_x = (entry & 0x4000) != 0;
      bool flip_y = (entry & 0x8000) != 0;

      if (tile_index >= kMapTileCount) {
        FillTilePixels(out, tx, ty, kUnexploredColor);
        continue;
      }

      const uint8 *tile = gfx + tile_index * 32;
      uint32 base = (uint32)ty * 8 * (kMapGridW * 8) + (uint32)tx * 8;
      for (int py = 0; py < 8; py++) {
        int sy = flip_y ? 7 - py : py;
        for (int px = 0; px < 8; px++) {
          int sx = flip_x ? 7 - px : px;
          int ci = Snes4bppColorIndex(tile, sx, sy);
          uint16 color15 = pal[palette_row * 16 + ci];
          out[base + (uint32)py * (kMapGridW * 8) + px] = Snes15ToArgb(color15);
        }
      }
    }
  }
  return true;
}

int SM2_GetCollectedItems(void) { return collected_items; }
int SM2_GetEquippedItems(void) { return equipped_items; }
int SM2_GetCollectedBeams(void) { return collected_beams; }
int SM2_GetEquippedBeams(void) { return equipped_beams; }

int SM2_GetHealth(void) { return samus_health; }
int SM2_GetMaxHealth(void) { return samus_max_health; }
int SM2_GetReserveHealth(void) { return samus_reserve_health; }
int SM2_GetMaxReserveHealth(void) { return samus_max_reserve_health; }
int SM2_GetMissiles(void) { return samus_missiles; }
int SM2_GetMaxMissiles(void) { return samus_max_missiles; }
int SM2_GetSuperMissiles(void) { return samus_super_missiles; }
int SM2_GetMaxSuperMissiles(void) { return samus_max_super_missiles; }
int SM2_GetPowerBombs(void) { return samus_power_bombs; }
int SM2_GetMaxPowerBombs(void) { return samus_max_power_bombs; }

int SM2_GetSelectedAmmo(void) { return hud_item_index; }

void SM2_SetSelectedAmmo(int index) {
  // Same effect as pressing Select on the controller until this slot is
  // reached (HandleSwitchingHudSelection in sm_90.c) - a direct write is
  // safe since hud_item_index is a plain selection index (0=none,
  // 1=Missiles, 2=Supers, 3=PBs, 4=Grapple, 5=X-Ray), not a bitfield, and
  // the HUD/aim-cursor code re-reads it every frame rather than caching it.
  if (index < 0 || index > 5) return;
  hud_item_index = index;
}
