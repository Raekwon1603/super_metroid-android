#include <string.h>
#include "second_screen.h"
#include "ida_types.h"
#include "variables.h"
#include "sm_rtl.h"
#include "funcs.h"
#include "redux_suit_data.h"
#ifdef __ANDROID__
#include <android/log.h>
#endif

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

// The green wireframe Samus body graphic shown in the middle of the real
// pause-menu equipment screen (WriteSamusWireframeTilemap in sm_82.c) - an
// 8x17-tile (64x136px) BG tilemap, one of 4 pre-baked variants selected by
// which suit(s) are equipped (kEquipmentScreenWireframeCmp[i] is matched
// against equipped_items & 0x101 - bit 0x001=Varia, bit 0x100=Gravity - to
// pick kEquipmentScreenWireframePtrs[i], a bank-0x82-relative pointer to
// that variant's 136 literal tilemap words). Same 4bpp tile graphics/
// palette bank as the SUIT/MISC/BOOTS/BEAM icons (kMapTileGfx/
// kPauseScreenPalettes) - verified correct via a standalone Python
// re-implementation against the real ROM dump before this code was
// written (all 4 variants decoded to recognizable Samus silhouettes).
#define kEquipmentScreenWireframeCmp ((const uint16 *)RomFixedPtr(0x82b257))
#define kEquipmentScreenWireframePtrs ((const uint16 *)RomFixedPtr(0x82b25f))
#define kWireframeTilesW 8
#define kWireframeTilesH 17

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

void SM2_GetSamusMapPosFixed(int *out_x256, int *out_y256) {
  *out_x256 = room_x_coordinate_on_map * 256 + samus_x_pos;
  *out_y256 = room_y_coordinate_on_map * 256 + samus_y_pos + 256;
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

// Renders the real pause-menu equipment screen's green wireframe Samus
// body graphic - see kEquipmentScreenWireframeCmp/Ptrs's own comment - into
// a 64x136 ARGB8888 buffer (out must be kWireframeTilesW*8 *
// kWireframeTilesH*8 = 64*136 = 8704 uint32s). Picks the correct one of 4
// pre-baked variants (no suit / Gravity only / Varia only / both) from the
// CALLER-supplied equipped_items value (not read directly here) via the
// same linear-scan match WriteSamusWireframeTilemap itself does. Each
// tile's own palette index 0 is left transparent.
bool SM2_RenderSamusWireframe(int equipped_items_value, uint32 *out) {
  if (!g_rom) return false;
  memset(out, 0, sizeof(uint32) * kWireframeTilesW * 8 * kWireframeTilesH * 8);

  int key = equipped_items_value & 0x101;
  int i = 0;
  // Linear scan matching WriteSamusWireframeTilemap's own loop - the real
  // ROM table always contains a "both" (0x101) entry to terminate against,
  // so no explicit bounds check is needed here either (mirrors the
  // decompiled source exactly).
  while (kEquipmentScreenWireframeCmp[i] != key) i++;

  const uint16 *src = (const uint16 *)RomPtr_82(kEquipmentScreenWireframePtrs[i]);
  for (int row = 0; row < kWireframeTilesH; row++) {
    for (int col = 0; col < kWireframeTilesW; col++) {
      uint16 entry = *src++;
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
          if (ci == 0) continue;
          uint16 color15 = kPauseScreenPalettes[palette_row * 16 + ci];
          out[(row * 8 + py) * (kWireframeTilesW * 8) + col * 8 + px] = Snes15ToArgb(color15);
        }
      }
    }
  }
  return true;
}

// Same 64x136 equipment-screen graphic as SM2_RenderSamusWireframe, but
// using the "Redux Suit" full-color art (redux_suit_data.h - extracted
// directly from the real, pre-built Super-Metroid-Redux ROM, see that
// header's own comment) instead of vanilla's flat green wireframe. Redux
// kept the exact same 4-variant selection scheme as vanilla
// (key = equipped_items & 0x101, order: none/Gravity/Varia/both - see
// redux_suit_data.h), so the variant index is just that same key's linear
// position, no separate lookup table needed for this data.
bool SM2_RenderReduxSuit(int equipped_items_value, uint32 *out) {
  memset(out, 0, sizeof(uint32) * kWireframeTilesW * 8 * kWireframeTilesH * 8);

  static const int kKeys[4] = {0x0, 0x100, 0x1, 0x101};
  int key = equipped_items_value & 0x101;
  int variant = 0;
  while (variant < 4 && kKeys[variant] != key) variant++;
  if (variant == 4) variant = 0;

  const uint16 *src = kReduxSuitTilemaps[variant];
  for (int row = 0; row < kWireframeTilesH; row++) {
    for (int col = 0; col < kWireframeTilesW; col++) {
      uint16 entry = *src++;
      int tile_index = entry & 0x3FF;
      int palette_row = (entry >> 10) & 7;
      bool flip_x = (entry & 0x4000) != 0;
      bool flip_y = (entry & 0x8000) != 0;
      if (tile_index >= kReduxSuitTileCount || palette_row >= kReduxSuitPaletteRowCount) continue;

      const uint8 *tile = kReduxSuitTiles + tile_index * 32;
      for (int py = 0; py < 8; py++) {
        int sy = flip_y ? 7 - py : py;
        for (int px = 0; px < 8; px++) {
          int sx = flip_x ? 7 - px : px;
          int ci = Snes4bppColorIndex(tile, sx, sy);
          if (ci == 0) continue;
          uint16 color15 = kReduxSuitPalette[palette_row * 16 + ci];
          out[(row * 8 + py) * (kWireframeTilesW * 8) + col * 8 + px] = Snes15ToArgb(color15);
        }
      }
    }
  }
  return true;
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

// Cycles hud_item_index by +1/-1 (direction), skipping any ammo type not
// currently owned - same ownership rule HandleSwitchingHudSelection's own
// kRunSwitchedToHudHandler table enforces (sm_90.c), just reimplemented as
// a direct field check here instead of calling that real handler: it's
// gated on joypad1_newkeys matching the real Select button bitmask
// mid-frame, so driving it from an async gamepad-button callback (this is
// called from main.c's L2/R2 trigger handling, outside SM's own per-frame
// input read) risked acting a frame out of sync with the game's own input
// state. A direct hud_item_index write is the same simplification
// SM2_SetSelectedAmmo already makes safely. Only cycles between
// None/Missiles/Supers/PowerBombs (0-3) - Grapple/X-Ray (4-5) have no real
// ammo count to arm/disarm and aren't reachable from the second-screen
// ammo tab either, so skipping them here keeps this consistent with that
// same UI.
void SM2_CycleSelectedAmmo(int direction) {
  if (direction != 1 && direction != -1) return;
  int start = hud_item_index;
  int index = start;
  for (int tries = 0; tries < 4; tries++) {
    index = (index + direction + 4) % 4;
    bool owned = index == kSM2Ammo_None
        || (index == kSM2Ammo_Missiles && samus_missiles > 0)
        || (index == kSM2Ammo_SuperMissiles && samus_super_missiles > 0)
        || (index == kSM2Ammo_PowerBombs && samus_power_bombs > 0);
    if (owned) {
      hud_item_index = index;
      return;
    }
  }
}

// ---- Real room background art (not the schematic pause-map tiles above) ----
//
// Renders the CURRENT room's actual BG1 tilemap, exactly as the real game
// draws it during play - not the abstract colored-rectangle pause-map
// representation SM2_RenderAreaMap produces. This is possible with no
// reverse-engineering of room-state-select logic at all: by the time a
// room has finished loading, the real game has ALREADY walked that logic
// itself (LoadRoomHeader/LoadStateHeader, sm_82.c) and left the results
// sitting in plain, already-decompressed RAM:
//   - level_data (g_ram+0x10002) = the room's BG1 tilemap
//   - tile_table (g_ram+0xA000) = the combined 1024-entry CRE+room
//     metatile table (indices 0-255 CRE, 256-1023 room-specific)
//   - room_width_in_blocks/room_height_in_blocks = the room's real size
// All of this is read-only here - never written - so it's safe to read
// from the JNI/UI thread without disturbing the running game.
//
// NOTE: this deliberately does NOT draw custom_background (the bytes
// right after BG1+BTS in the same decompressed level-data blob) as a
// second visual layer. An earlier version of this function did, on the
// assumption it was BG2 - but custom_background is never DMA'd to BG2
// VRAM or drawn anywhere in this decomp during normal gameplay; its only
// reader is the pause-menu map's boss-icon overlay code, which pulls
// fixed, oddly-specific byte offsets out of it for an unrelated purpose.
// Compositing it as a background fabricates visible content the game
// never actually shows - confirmed on a Tourian recharge-capsule room,
// whose "BG2" decoded to real, structured-looking (but not-drawn) tilemap
// words that rendered as a visibly duplicated tube next to the real one.
//
// The one piece NOT already sitting in plain RAM is the 8x8 tile PIXEL
// graphics themselves (SNES VRAM isn't emulated as a flat readable buffer
// in this decomp) - those still need decompressing from ROM, via the same
// DecompressToMem the real game itself calls (sm_80.c) - not a
// reimplementation, the actual function, so there's no risk of a
// decompression bug independent of the game's own logic. tileset_tiles_
// pointer/tileset_compr_palette_ptr (g_ram+0x7C3/0x7C6) hold the live
// ROM addresses for the CURRENT room's own tile graphics/palette; CRE's
// own tile graphics are always the same fixed ROM address (0xb98000).
//
// Verified end-to-end (decompression, metatile assembly, CRE/room
// graphics-tile split, BG1/BG2 layering) via a standalone Python
// re-implementation against the real ROM dump before this code was
// written - see chat history for the Landing Site / Morph Ball Room
// renders that caught and fixed 3 real bugs (backwards CRE/room graphics
// boundary, wrong ci==0 transparency rule) before this port.
#define kGfxSplit 640  // tile index < 640 = room-specific graphics, >= 640 = CRE (offset by 640) - fixed VRAM convention, NOT derived from either blob's size (sm_82.c:4202-4210)

// Each scratch buffer below is immediately followed by a canary region,
// filled with a distinctive byte before every DecompressToMem call and
// checked afterward by DecompressBoundsOk() - DecompressToMem (sm_80.c)
// has NO bounds checking at all, so an undersized destination doesn't
// fail loudly, it silently keeps writing into whatever static data
// happens to sit next in memory. This bit the room-art renderer twice
// already (first with s_level_data_buf sized 0x8000, too small for
// Crateria's Landing Site; then again sized to exactly match the real
// game's own level_data reservation, which turned out to be 2 bytes short
// of Big Pink's real worst case) - both times the overflow corrupted
// g_snes (sm_cpu_infra.c), crashing the separate SDL emulation thread on
// its very next frame. The canary can't prevent a large enough overflow
// from reaching past it into real data, but it turns "silent heap
// corruption sometime later, symptom nowhere near the cause" into an
// immediate, attributable "false return from this function" the moment
// any of these buffers turns out to be undersized again.
#define kCanarySize 256
#define kCanaryByte 0x5A
static bool DecompressBoundsOk(const uint8 *canary) {
  for (int i = 0; i < kCanarySize; i++) {
    if (canary[i] != kCanaryByte) return false;
  }
  return true;
}

// Scratch decompression buffers - room tile graphics can run up to several
// hundred tiles (32 bytes/tile); sized generously above any real room's
// needs. Static (not stack) since these are too large to put on the stack
// safely, matching the g_map_px precedent in second_screen_jni.c.
static uint8 s_room_tiles_buf[0x8000];
static uint8 s_room_tiles_canary[kCanarySize];
static uint8 s_cre_tiles_buf[0x8000];
static uint8 s_cre_tiles_canary[kCanarySize];

// Level-data blob (BG1 tilemap + BTS collision data), decompressed fresh
// here via the real DecompressToMem rather than trusting g_ram's own
// level_data (which this decomp's normal room-load path already
// populates, but re-decompressing our own copy keeps this function
// self-contained and independent of when/whether the caller's read races
// the game's own room-load sequence).
//
// Sized well above vanilla SM's single largest room by cell count - Big
// Pink (Brinstar), w=5 h=10 screens = 80x160 blocks = 12800 metatile
// cells, needs 2 + size + size/2 = 2 + 25600 + 12800 = 38402 bytes for
// BG1+BTS alone (no BG2). The real game's own level_data reservation
// (g_ram+0x10002 to custom_background at g_ram+0x19602 = exactly 0x9600 =
// 38400 bytes) is NOT safe to copy here - it's 2 bytes short of Big
// Pink's own real requirement, confirmed by a repro crash surviving an
// earlier fix that used that exact size. DecompressToMem has NO bounds
// checking at all (sm_80.c), so an undersized buffer here doesn't fail
// loudly - it silently writes past the end into whatever static data
// happens to sit next in memory, which on this build corrupted g_snes
// (sm_cpu_infra.c) closely enough to crash the SDL thread on its very
// next frame. Rounded up to 0x10000 (65536) for real margin instead of
// hugging the exact known worst case, since a future ROM hack or a room
// this analysis missed could need a little more.
static uint8 s_level_data_buf[0x10000];
static uint8 s_level_data_canary[kCanarySize];

// The room's own live palette (decompressed from tileset_compr_palette_ptr,
// NOT kPauseScreenPalettes - that fixed 0xb6f000 bank is the PAUSE-MENU
// screen's own palette, entirely unrelated to gameplay's real per-room/
// per-tileset colors; using it here was the cause of the wrong-color/
// distorted look on first render). 256 colors x 2 bytes, same as every
// other palette decode in this file - set once per SM2_RenderCurrentRoomArt
// call, read by RenderRoomSubtile via this file-scope pointer rather than
// threading it through every call in the composite chain. Always exactly
// 256 entries (hardware-fixed SNES palette size), so no canary needed -
// unlike the other three buffers, its size can't vary with room content.
static uint16 s_room_palette[256];

// Scratch for the current area's explored-tile grid (64x32, same layout as
// SM2_DecodeExploredGrid), refreshed once per SM2_RenderCurrentRoomArt call
// and used to gate which of the room's own metatile cells CompositeRoomLayer
// actually draws - see its own comment for why (unexplored parts of the
// current room must stay hidden, same rule the schematic map already
// enforces).
static uint8 s_explored_grid[64 * 32];

// Renders one 8x8 subtile (from a TileTable corner word) into out at
// (ox,oy), skipping tiles that are entirely blank (all 64 pixels index 0 -
// SM's real "no art here" placeholder tiles are built this way; ordinary
// tiles use index 0 as a real opaque color, usually black, for internal
// shadow/detail - see chat history for how conflating the two produced
// visible glitches during the Python prototype).
static void RenderRoomSubtile(uint32 *out, int out_w, int out_h, uint16 sub_word,
                               bool meta_flip_x, bool meta_flip_y, int ox, int oy) {
  int tile_index = sub_word & 0x3FF;
  int palette_row = (sub_word >> 10) & 7;
  bool flip_x = ((sub_word & 0x4000) != 0) != meta_flip_x;
  bool flip_y = ((sub_word & 0x8000) != 0) != meta_flip_y;

  const uint8 *gfx;
  int gfx_len, local_idx;
  if (tile_index < kGfxSplit) {
    gfx = s_room_tiles_buf;
    gfx_len = sizeof(s_room_tiles_buf);
    local_idx = tile_index;
  } else {
    gfx = s_cre_tiles_buf;
    gfx_len = sizeof(s_cre_tiles_buf);
    local_idx = tile_index - kGfxSplit;
  }

  int tile_off = local_idx * 32;
  if (tile_off + 32 > gfx_len) return;
  const uint8 *tile = gfx + tile_off;

  bool is_blank = true;
  for (int py = 0; py < 8 && is_blank; py++)
    for (int px = 0; px < 8; px++)
      if (Snes4bppColorIndex(tile, px, py) != 0) { is_blank = false; break; }
  if (is_blank) return;

  for (int py = 0; py < 8; py++) {
    int sy = flip_y ? 7 - py : py;
    int dy = oy + py;
    if (dy < 0 || dy >= out_h) continue;
    for (int px = 0; px < 8; px++) {
      int sx = flip_x ? 7 - px : px;
      int dx = ox + px;
      if (dx < 0 || dx >= out_w) continue;
      int ci = Snes4bppColorIndex(tile, sx, sy);
      uint16 color15 = s_room_palette[palette_row * 16 + ci];
      out[dy * out_w + dx] = Snes15ToArgb(color15);
    }
  }
}

// Composites one BG layer's tilemap (level_data or custom_background) into
// out, grid_w x grid_h metatiles (16x16px each). Mirrors DrawPlmTilemap's
// own combined-flip corner-swap convention (sm_84.c:868-893).
//
// explored (may be NULL to skip the check entirely) is the 64x32 world
// explored-tile grid (SM2_DecodeExploredGrid's own layout - 1 byte per
// map tile, row-major, out[y*64+x]); map_x0/map_y0 are this room's own
// placement within that same grid (room_x_coordinate_on_map/
// room_y_coordinate_on_map, +1 on Y - see SM2_RenderCurrentRoomArt's own
// comment). That world grid is SCREEN granularity (1 cell = 1 real 256x256
// SNES screen = 16x16 of this function's own 16px metatile cells), NOT
// metatile granularity, matching how SM actually reveals the map (a whole
// screen at a time when you enter it) - confirmed via a live repro
// showing only 5 explored cells total for Landing Site's real, heavily-
// explored starting room, forming exactly a 1-screen-wide x 5-screen-tall
// strip matching its own w=9 h=5 screen dimensions. So gx/gy (in
// metatile/block units) need /16 before indexing into it, not used
// directly. Cells in an unexplored screen are left untouched (transparent,
// out already zeroed by the caller) instead of composited, so real-texture
// mode can't be used to see hidden paths/items/room layout ahead of
// actually exploring it - the same reveal-as-you-go rule the schematic
// map already enforces via SM2_RenderAreaMap's own explored gate.
static void CompositeRoomLayer(uint32 *out, int out_w, int out_h, const uint16 *words,
                                int n_words, int grid_w, int grid_h,
                                const uint8 *explored, int map_x0, int map_y0) {
  int cells = grid_w * grid_h;
  if (cells > n_words) cells = n_words;
  for (int i = 0; i < cells; i++) {
    uint16 word = words[i];
    int gx = i % grid_w, gy = i / grid_w;
    if (explored) {
      int mx = map_x0 + (gx >> 4), my = map_y0 + (gy >> 4);
      bool tile_explored = mx >= 0 && mx < 64 && my >= 0 && my < 32 && explored[my * 64 + mx];
      if (!tile_explored) continue;
    }
    int metatile_idx = word & 0x3FF;
    bool meta_flip_x = (word & 0x4000) != 0;
    bool meta_flip_y = (word & 0x8000) != 0;
    if (metatile_idx >= 1024) continue;

    const TileTable *mt = &tile_table.tables[metatile_idx];
    uint16 tl = mt->top_left, tr = mt->top_right, bl = mt->bottom_left, br = mt->bottom_right;
    uint16 c0 = tl, c1 = tr, c2 = bl, c3 = br;  // corner order: TL,TR,BL,BR
    if (meta_flip_x) { c0 = tr; c1 = tl; c2 = br; c3 = bl; }
    if (meta_flip_y) { uint16 t0=c0,t1=c1,t2=c2,t3=c3; c0=t2; c1=t3; c2=t0; c3=t1; }

    int ox = gx * 16, oy = gy * 16;
    RenderRoomSubtile(out, out_w, out_h, c0, meta_flip_x, meta_flip_y, ox, oy);
    RenderRoomSubtile(out, out_w, out_h, c1, meta_flip_x, meta_flip_y, ox + 8, oy);
    RenderRoomSubtile(out, out_w, out_h, c2, meta_flip_x, meta_flip_y, ox, oy + 8);
    RenderRoomSubtile(out, out_w, out_h, c3, meta_flip_x, meta_flip_y, ox + 8, oy + 8);
  }
}

// Renders the CURRENT room's real background art into out (out_w x out_h,
// caller-allocated, capped at kRoomArtMaxW x kRoomArtMaxH - a handful of
// SM's largest rooms, e.g. tall elevator/water shafts, exceed this and get
// clipped rather than requiring an unbounded allocation). Fills out_w_used/
// out_h_used with the room's real pixel dimensions (which may be larger
// than the buffer - callers should treat that as "this room is bigger than
// what got rendered", not an error). Returns false if the ROM isn't loaded
// yet or no room is currently loaded.
bool SM2_RenderCurrentRoomArt(uint32 *out, int out_w, int out_h, int *out_w_used, int *out_h_used) {
  if (!g_rom) return false;
  // Guard against calling this outside real gameplay (title/file-select,
  // intro cinematic, demo attract-mode, etc) - during those, room_index/
  // room_width_in_blocks can hold leftover or hardcoded placeholder values
  // (e.g. CinematicFunction_Intro_Initial in sm_8b.c sets
  // room_width_in_blocks=16 unconditionally for the Ceres intro) while
  // tileset_tiles_pointer/room_compr_level_data_ptr are still 0 or garbage
  // - decompressing from a garbage ROM pointer here previously corrupted
  // memory badly enough to crash the separate SDL emulation thread shortly
  // after, on a brand new save file.
  if (!SM2_IsPlayingLive()) return false;
  if (room_width_in_blocks <= 0 || room_height_in_blocks <= 0) return false;
  if (Load24(&tileset_tiles_pointer) == 0) return false;
  if (Load24(&room_compr_level_data_ptr) == 0) return false;

  int room_px_w = room_width_in_blocks * 16;
  int room_px_h = room_height_in_blocks * 16;
  *out_w_used = room_px_w < out_w ? room_px_w : out_w;
  *out_h_used = room_px_h < out_h ? room_px_h : out_h;

  memset(out, 0, sizeof(uint32) * (size_t)out_w * out_h);

  // Locked for the whole decompress+composite sequence below, not just
  // the DecompressToMem calls: CompositeRoomLayer also reads tile_table
  // (g_ram+0xA000), which the game's OWN room-load code populates via its
  // own DecompressToMem calls - holding the lock the whole time keeps
  // every read in this function consistent with a single point in the
  // game's timeline, not torn across a concurrent room transition.
  SM2_LockGameState();

  memset(s_room_tiles_canary, kCanaryByte, kCanarySize);
  memset(s_cre_tiles_canary, kCanaryByte, kCanarySize);
  memset(s_level_data_canary, kCanaryByte, kCanarySize);

  // Room-specific + CRE tile GRAPHICS (not in plain RAM - VRAM isn't
  // emulated as a flat buffer here - so decompress from ROM via the same
  // function the real game itself calls, sm_80.c).
  DecompressToMem(Load24(&tileset_tiles_pointer), s_room_tiles_buf);
  DecompressToMem(0xb98000, s_cre_tiles_buf);

  // The room's own live palette - NOT kPauseScreenPalettes (that's the
  // pause-menu screen's fixed bank, unrelated to gameplay colors; using
  // it produced visibly wrong colors on first render, e.g. warm gold/brown
  // Tourian metal rendering as cool pink/gray).
  DecompressToMem(Load24(&tileset_compr_palette_ptr), (uint8 *)s_room_palette);

  // Decompress level_data fresh into our own buffer, same as the real
  // game's own LoadLevelDataAndOtherThings (sm_82.c) - only the BG1
  // tilemap + BTS collision data are used here. The bytes that follow
  // (what the real game copies into custom_background) are NOT a second
  // visual layer: custom_background is never DMA'd to BG2 VRAM or drawn
  // during normal gameplay anywhere in this decomp - its only reader is
  // the pause-menu map's boss-icon overlay code, which pulls fixed-offset
  // bytes out of it for an unrelated purpose. Treating it as a real BG2
  // background (an earlier version of this function did) fabricates
  // visible content that the game never actually shows, producing
  // duplicate-looking geometry (confirmed on a Tourian recharge-capsule
  // room, whose "BG2" decoded to a real, structured-looking tilemap that
  // was nonetheless not part of the room's actual visual).
  DecompressToMem(Load24(&room_compr_level_data_ptr), s_level_data_buf);

  // Any of these buffers overflowing means real, unrelated heap/static
  // data past it just got clobbered - bail out now rather than composite
  // from (and let the caller display) a render that's already coincided
  // with memory corruption elsewhere. See s_level_data_buf's own comment
  // for the two real crashes this exact failure mode already caused.
  bool room_ok = DecompressBoundsOk(s_room_tiles_canary);
  bool cre_ok = DecompressBoundsOk(s_cre_tiles_canary);
  bool level_ok = DecompressBoundsOk(s_level_data_canary);
  if (!room_ok || !cre_ok || !level_ok) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "SM2Crash", "canary fail room=%d cre=%d level=%d", room_ok, cre_ok, level_ok);
#endif
    SM2_UnlockGameState();
    return false;
  }

  int grid_w = room_width_in_blocks, grid_h = room_height_in_blocks;
  int n_words = grid_w * grid_h;
  const uint16 *bg1_words = (const uint16 *)(s_level_data_buf + 2);
  SM2_DecodeExploredGrid(s_explored_grid);
  // +1 on the Y origin only - the same offset MarkMapTileAsExplored
  // (sm_90.c: room_y_coordinate_on_map + ... + 1) and SM2_GetSamusMapTile
  // both apply when converting room_y_coordinate_on_map into this same
  // explored-tile grid's row space (room_x_coordinate_on_map needs no such
  // adjustment, only Y). CompositeRoomLayer itself further divides by 16
  // to go from this function's block/metatile units to the explored
  // grid's own screen units - see its own comment for why (confirmed via
  // a live repro against Landing Site's real explored state).
  CompositeRoomLayer(out, out_w, out_h, bg1_words, n_words, grid_w, grid_h,
                      s_explored_grid, room_x_coordinate_on_map, room_y_coordinate_on_map + 1);
  SM2_UnlockGameState();
  return true;
}
