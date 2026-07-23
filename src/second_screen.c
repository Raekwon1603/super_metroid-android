#include <string.h>
#include "second_screen.h"
#include "variables.h"

int SM2_GetSamusX(void) { return samus_x_pos; }
int SM2_GetSamusY(void) { return samus_y_pos; }
int SM2_GetArea(void) { return area_index; }
int SM2_GetRoom(void) { return room_index; }
bool SM2_HasAreaMap(void) { return has_area_map != 0; }

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
