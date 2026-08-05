
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <assert.h>

#include "dma.h"
#include "snes.h"

static const int bAdrOffsets[8][4] = {
  {0, 0, 0, 0},
  {0, 1, 0, 1},
  {0, 0, 0, 0},
  {0, 0, 1, 1},
  {0, 1, 2, 3},
  {0, 1, 0, 1},
  {0, 0, 0, 0},
  {0, 0, 1, 1}
};

static const int transferLength[8] = {
  1, 2, 2, 4, 4, 4, 2, 4
};

static void dma_transferByte(Dma* dma, uint16_t aAdr, uint8_t aBank, uint8_t bAdr, bool fromB);

Dma* dma_init(Snes* snes) {
  Dma* dma = malloc(sizeof(Dma));
  dma->snes = snes;
  return dma;
}

void dma_free(Dma* dma) {
  free(dma);
}

void dma_reset(Dma* dma) {
  for(int i = 0; i < 8; i++) {
    dma->channel[i].bAdr = 0xff;
    dma->channel[i].aAdr = 0xffff;
    dma->channel[i].aBank = 0xff;
    dma->channel[i].size = 0xffff;
    dma->channel[i].indBank = 0xff;
    dma->channel[i].tableAdr = 0xffff;
    dma->channel[i].repCount = 0xff;
    dma->channel[i].unusedByte = 0xff;
    dma->channel[i].dmaActive = false;
    dma->channel[i].hdmaActive = false;
    dma->channel[i].mode = 7;
    dma->channel[i].fixed = true;
    dma->channel[i].decrement = true;
    dma->channel[i].indirect = true;
    dma->channel[i].fromB = true;
    dma->channel[i].unusedBit = true;
    dma->channel[i].doTransfer = false;
    dma->channel[i].terminated = false;
    dma->channel[i].offIndex = 0;
  }
  dma->hdmaTimer = 0;
  dma->dmaTimer = 0;
  dma->dmaBusy = false;
}

void dma_saveload(Dma *dma, SaveLoadFunc *func, void *ctx) {
  func(ctx, &dma->channel, offsetof(Dma, pad) + 7 - offsetof(Dma, channel));
}

uint8_t dma_read(Dma* dma, uint16_t adr) {
  uint8_t c = (adr & 0x70) >> 4;
  switch(adr & 0xf) {
    case 0x0: {
      uint8_t val = dma->channel[c].mode;
      val |= dma->channel[c].fixed << 3;
      val |= dma->channel[c].decrement << 4;
      val |= dma->channel[c].unusedBit << 5;
      val |= dma->channel[c].indirect << 6;
      val |= dma->channel[c].fromB << 7;
      return val;
    }
    case 0x1: {
      return dma->channel[c].bAdr;
    }
    case 0x2: {
      return dma->channel[c].aAdr & 0xff;
    }
    case 0x3: {
      return dma->channel[c].aAdr >> 8;
    }
    case 0x4: {
      return dma->channel[c].aBank;
    }
    case 0x5: {
      return dma->channel[c].size & 0xff;
    }
    case 0x6: {
      return dma->channel[c].size >> 8;
    }
    case 0x7: {
      return dma->channel[c].indBank;
    }
    case 0x8: {
      return dma->channel[c].tableAdr & 0xff;
    }
    case 0x9: {
      return dma->channel[c].tableAdr >> 8;
    }
    case 0xa: {
      return dma->channel[c].repCount;
    }
    case 0xb:
    case 0xf: {
      return dma->channel[c].unusedByte;
    }
    default: {
      assert(0);
      return dma->snes->openBus;
    }
  }
}

void dma_write(Dma* dma, uint16_t adr, uint8_t val) {
  uint8_t c = (adr & 0x70) >> 4;
  switch(adr & 0xf) {
    case 0x0: {
      dma->channel[c].mode = val & 0x7;
      dma->channel[c].fixed = val & 0x8;
      dma->channel[c].decrement = val & 0x10;
      dma->channel[c].unusedBit = val & 0x20;
      dma->channel[c].indirect = val & 0x40;
      dma->channel[c].fromB = val & 0x80;
      break;
    }
    case 0x1: {
      dma->channel[c].bAdr = val;
      break;
    }
    case 0x2: {
      dma->channel[c].aAdr = (dma->channel[c].aAdr & 0xff00) | val;
      break;
    }
    case 0x3: {
      dma->channel[c].aAdr = (dma->channel[c].aAdr & 0xff) | (val << 8);
      break;
    }
    case 0x4: {
      dma->channel[c].aBank = val;
      break;
    }
    case 0x5: {
      dma->channel[c].size = (dma->channel[c].size & 0xff00) | val;
      break;
    }
    case 0x6: {
      dma->channel[c].size = (dma->channel[c].size & 0xff) | (val << 8);
      break;
    }
    case 0x7: {
      dma->channel[c].indBank = val;
      break;
    }
    case 0x8: {
      dma->channel[c].tableAdr = (dma->channel[c].tableAdr & 0xff00) | val;
      break;
    }
    case 0x9: {
      dma->channel[c].tableAdr = (dma->channel[c].tableAdr & 0xff) | (val << 8);
      break;
    }
    case 0xa: {
      dma->channel[c].repCount = val;
      break;
    }
    case 0xb:
    case 0xf: {
      dma->channel[c].unusedByte = val;
      break;
    }
    default: {
      break;
    }
  }
}

extern bool g_fail;

void dma_doDma(Dma* dma) {
  if(dma->dmaTimer > 0) {
    dma->dmaTimer -= 2;
    return;
  }
  // figure out first channel that is active
  int i = 0;
  for(i = 0; i < 8; i++) {
    if(dma->channel[i].dmaActive) {
      break;
    }
  }
  if(i == 8) {
    // no active channels
    dma->dmaBusy = false;
    return;
  }

  if (!dma->channel[i].fromB && (dma->channel[i].aBank & 0x80) && !(dma->channel[i].aAdr & 0x8000) && !g_fail) {
    printf("Warning! DMA from addr 0x%x\n", dma->channel[i].aBank << 16 | dma->channel[i].aAdr);
    g_fail = true;
  }

  // do channel i
  dma_transferByte(
    dma, dma->channel[i].aAdr, dma->channel[i].aBank,
    dma->channel[i].bAdr + bAdrOffsets[dma->channel[i].mode][dma->channel[i].offIndex++], dma->channel[i].fromB
  );
  dma->channel[i].offIndex &= 3;
  dma->dmaTimer += 6; // 8 cycles for each byte taken, -2 for this cycle
  if(!dma->channel[i].fixed) {
    dma->channel[i].aAdr += dma->channel[i].decrement ? -1 : 1;
  }
  dma->channel[i].size--;
  if(dma->channel[i].size == 0) {
    dma->channel[i].offIndex = 0; // reset offset index
    dma->channel[i].dmaActive = false;
    dma->dmaTimer += 8; // 8 cycle overhead per channel
  }
}

void dma_initHdma(Dma* dma) {
  dma->hdmaTimer = 0;
  bool hdmaHappened = false;
  for(int i = 0; i < 8; i++) {
    if(dma->channel[i].hdmaActive) {
      hdmaHappened = true;
      // terminate any dma
      dma->channel[i].dmaActive = false;
      dma->channel[i].offIndex = 0;
      // load address, repCount, and indirect address if needed
      dma->channel[i].tableAdr = dma->channel[i].aAdr;
      dma->channel[i].repCount = snes_read(dma->snes, (dma->channel[i].aBank << 16) | dma->channel[i].tableAdr++);
      if (dma->channel[i].repCount == 0) {
        dma->channel[i].terminated = true;
        continue;
      }
      dma->hdmaTimer += 8; // 8 cycle overhead for each active channel
      if(dma->channel[i].indirect) {
        dma->channel[i].size = snes_read(dma->snes, (dma->channel[i].aBank << 16) | dma->channel[i].tableAdr++);
        dma->channel[i].size |= snes_read(dma->snes, (dma->channel[i].aBank << 16) | dma->channel[i].tableAdr++) << 8;
        dma->hdmaTimer += 16; // another 16 cycles for indirect (total 24)
      }
      dma->channel[i].doTransfer = true;
    } else {
      dma->channel[i].doTransfer = false;
    }
    dma->channel[i].terminated = false;
  }
  if(hdmaHappened) dma->hdmaTimer += 16; // 18 cycles overhead, -2 for this cycle
}

void dma_doHdma(Dma* dma) {
  dma->hdmaTimer = 0;
  bool hdmaHappened = false;
  for(int i = 0; i < 8; i++) {
    if(dma->channel[i].hdmaActive && !dma->channel[i].terminated) {
//      printf("DMA %d: 0x%x\n", i, dma->channel[i].bAdr);
      hdmaHappened = true;
      // terminate any dma
      dma->channel[i].dmaActive = false;
      dma->channel[i].offIndex = 0;
      // do the hdma
      dma->hdmaTimer += 8; // 8 cycles overhead for each active channel

      
      if(dma->channel[i].doTransfer) {
        for(int j = 0; j < transferLength[dma->channel[i].mode]; j++) {
          dma->hdmaTimer += 8; // 8 cycles for each byte transferred
          if(dma->channel[i].indirect) {
            dma_transferByte(
              dma, dma->channel[i].size++, dma->channel[i].indBank,
              dma->channel[i].bAdr + bAdrOffsets[dma->channel[i].mode][j], dma->channel[i].fromB
            );
          } else {
            dma_transferByte(
              dma, dma->channel[i].tableAdr++, dma->channel[i].aBank,
              dma->channel[i].bAdr + bAdrOffsets[dma->channel[i].mode][j], dma->channel[i].fromB
            );
          }
        }
      }
      dma->channel[i].repCount--;
      dma->channel[i].doTransfer = dma->channel[i].repCount & 0x80;
      if((dma->channel[i].repCount & 0x7f) == 0) {
        dma->channel[i].repCount = snes_read(dma->snes, (dma->channel[i].aBank << 16) | dma->channel[i].tableAdr++);
        if(dma->channel[i].indirect) {
          // TODO: oddness with not fetching high byte if last active channel and reCount is 0
          dma->channel[i].size = snes_read(dma->snes, (dma->channel[i].aBank << 16) | dma->channel[i].tableAdr++);
          dma->channel[i].size |= snes_read(dma->snes, (dma->channel[i].aBank << 16) | dma->channel[i].tableAdr++) << 8;
          dma->hdmaTimer += 16; // 16 cycles for new indirect address
        }
        if(dma->channel[i].repCount == 0) dma->channel[i].terminated = true;
        dma->channel[i].doTransfer = true;
      }
    }
  }
  if(hdmaHappened) dma->hdmaTimer += 16; // 18 cycles overhead, -2 for this cycle
}

// "HIDE MAIN HUD" settings toggle (second_screen.c/SM2_IsHudHidden): SM's
// HUD tilemap lives at fixed WRAM address $7E:C608 (hud_tilemap in
// variables.h, g_ram+0xC608), 192 bytes (96 tilemap words, includes the
// minimap - UpdateMinimapInside in sm_90.c writes hud_tilemap[26..30],
// [58..62], [90..94] directly) - queued for a DMA upload to VRAM every
// frame by HandleHudTilemap (sm_80.c). That queue gets drained by real ROM
// machine code under RM_THEIRS (Android's forced run mode - see
// sm_cpu_infra.c), NOT the decompiled NMI_ProcessVramWriteQueue C function,
// so hooking this at the WRAM DMA-source-address level (instead of in the
// decompiled queue-processing code, which never runs on Android) is what
// actually works regardless of run mode: it's the one thing that's true
// both for the decompile and the byte-accurate CPU-emulated ROM path.
extern bool g_hud_hidden;
extern uint16_t g_hud_tilemap_vram_dst;
#define kHudTilemapWramAddr 0xC608
#define kHudTilemapWramSize 192
// Static minimap border/frame graphic: InitializeHud (sm_80.c) DMAs this
// directly from ROM ($80:988B, 64 bytes) to a FIXED VRAM address (0x5800,
// addr_unk_605800) once per room/game entry - separate from hud_tilemap
// entirely (source is ROM, not WRAM, and it only runs once, not every
// frame), so it needs its own filter here. Both the source and destination
// are fixed literals in the original code (not derived at runtime like
// hud_tilemap's own VRAM destination can vary), so no runtime discovery
// needed for this one.
#define kHudMinimapBorderRomBank 0x80
#define kHudMinimapBorderRomAddr 0x988b
#define kHudMinimapBorderRomSize 0x40
#define kHudMinimapBorderVramDst 0x5800

static void dma_transferByte(Dma* dma, uint16_t aAdr, uint8_t aBank, uint8_t bAdr, bool fromB) {
  // TODO: invalid writes:
  //   accesing b-bus via a-bus gives open bus,
  //   $2180-$2183 while accessing ram via a-bus open busses $2180-$2183
  //   cannot access $4300-$437f (dma regs), or $420b / $420c
  if(fromB) {
    snes_write(dma->snes, (aBank << 16) | aAdr, snes_readBBus(dma->snes, bAdr));
  } else {
    uint8_t val = snes_read(dma->snes, (aBank << 16) | aAdr);
    bool isHudTilemap = aBank == 0x7E && aAdr >= kHudTilemapWramAddr
        && aAdr < kHudTilemapWramAddr + kHudTilemapWramSize;
    bool isMinimapBorder = aBank == kHudMinimapBorderRomBank
        && aAdr >= kHudMinimapBorderRomAddr
        && aAdr < kHudMinimapBorderRomAddr + kHudMinimapBorderRomSize;
    if (isHudTilemap) {
      // Latch this transfer's VRAM destination (dma->snes->ppu->vramPointer,
      // pre-increment - the byte about to be written lands there) so
      // SM2_SetHudHidden can force-blank whatever's already in VRAM the
      // moment the toggle flips on, not just gate future writes - see that
      // function's own comment.
      g_hud_tilemap_vram_dst = dma->snes->ppu->vramPointer & 0x7ffe;
    }
    if (g_hud_hidden && isHudTilemap) {
      // Blank tile 0x2c0f (same backdrop tile HandleHudTilemap's own
      // kHudTilemaps_Row1to3 fills every empty HUD position with) -
      // low byte first, matching the tilemap's little-endian word layout.
      val = ((aAdr - kHudTilemapWramAddr) & 1) ? 0x2c : 0x0f;
    } else if (g_hud_hidden && isMinimapBorder) {
      val = ((aAdr - kHudMinimapBorderRomAddr) & 1) ? 0x2c : 0x0f;
    }
    snes_writeBBus(dma->snes, bAdr, val);
  }
}

bool dma_cycle(Dma* dma) {
  if(dma->hdmaTimer > 0) {
    dma->hdmaTimer -= 2;
    return true;
  } else if(dma->dmaBusy) {
    dma_doDma(dma);
    return true;
  }
  return false;
}

void dma_startDma(Dma* dma, uint8_t val, bool hdma) {
  for(int i = 0; i < 8; i++) {
    if(hdma) {
      dma->channel[i].hdmaActive = val & (1 << i);
    } else {
      dma->channel[i].dmaActive = val & (1 << i);
    }
  }
  if(!hdma) {
    dma->dmaBusy = val;
    dma->dmaTimer += dma->dmaBusy ? 16 : 0; // 12-24 cycle overhead for entire dma transfer
  }
}
