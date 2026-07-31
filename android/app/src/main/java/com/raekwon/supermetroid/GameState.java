package com.raekwon.supermetroid;

// Read-only bridge to the running game's state. The native side lives in
// src/second_screen.c (platform-agnostic SM2_* functions) and
// src/platform/android/second_screen_jni.c (the JNI wrappers below),
// compiled into libmain.so - which SDLActivity already loads (see
// MainActivity.getLibraries()), so no System.loadLibrary() call is needed
// here.
public class GameState {
    public static native int getSamusX();
    public static native int getSamusY();
    public static native int getArea();
    public static native int getRoom();
    public static native boolean hasAreaMap();
    // True if the player has explored any tile at all in the given area
    // (0-7), not just the currently-loaded one.
    public static native boolean areaHasAnyExploredTile(int area);

    // Raw game_state value - see the GameState enum in ida_types.h.
    public static native int getGameState();
    // True during normal gameplay (and its brief door/room-load blips),
    // false during menus, pause, cutscenes, death, or demo attract-mode.
    public static native boolean isPlayingLive();

    // out must be length >= 4: {roomX, roomY, roomWidthBlocks, roomHeightBlocks}
    public static native boolean getRoomMapRect(int[] out);

    // Copies up to out.length (max 256) bytes of the live explored-tile bitmap.
    public static native boolean readExploredTiles(byte[] out);

    // out must be length >= 64*32: one byte (0/1) per map tile, row-major.
    public static native boolean decodeExploredGrid(byte[] out);

    // Same as decodeExploredGrid, but for any area (0-7), not just the
    // currently-loaded one - used by the world view to crop each area down
    // to its own actually-explored extent. out must be length >= 64*32.
    public static native boolean decodeExploredGridForArea(int area, byte[] out);

    // out must be length >= 2: {tileX, tileY}, same space as decodeExploredGrid.
    public static native boolean getSamusMapTile(int[] out);

    // Decodes the given area's real map tile graphics (from ROM, same data
    // the in-game pause menu uses) into a 512x256 ARGB8888 buffer, one
    // 8x8px tile per map-grid cell (64x32). out must be length >= 512*256.
    public static native boolean renderAreaMap(int area, int[] out);

    // Renders the real in-game equipment-screen icon for the given
    // collected_items/collected_beams bit into a 64x8 ARGB8888 strip (out
    // must be length >= 64*8). Returns false for Grapple/X-Ray, which have
    // no equipment-screen icon in vanilla SM.
    public static native boolean renderItemIcon(int bit, int[] out);
    public static native boolean renderBeamIcon(int bit, int[] out);

    // Renders the real pause-menu equipment screen's green wireframe Samus
    // body graphic into a 64x136 ARGB8888 buffer (out must be length >=
    // 64*136). equippedItems picks the correct one of 4 suit-state variants -
    // pass getEquippedItems()'s own return value.
    public static native boolean renderSamusWireframe(int equippedItems, int[] out);

    // Renders the real in-game area-name label graphic (12 tiles x 1 tile =
    // 96x8px) for the given area index. out must be length >= 96*8.
    public static native boolean renderAreaLabel(int area, int[] out);

    // Renders the actual gameplay-HUD missile icon into a 24x16 ARGB8888
    // buffer (out must be length >= 24*16).
    public static native boolean renderMissileIcon(int[] out);
    // Same, for the Super Missile / Power Bomb HUD tank icons - each a
    // 16x16 ARGB8888 buffer (out must be length >= 16*16).
    public static native boolean renderSuperMissileIcon(int[] out);
    public static native boolean renderPowerBombIcon(int[] out);

    public static native int getCollectedItems();
    public static native int getEquippedItems();
    public static native int getCollectedBeams();
    public static native int getEquippedBeams();

    public static native int getHealth();
    public static native int getMaxHealth();
    public static native int getReserveHealth();
    public static native int getMaxReserveHealth();
    public static native int getMissiles();
    public static native int getMaxMissiles();
    public static native int getSuperMissiles();
    public static native int getMaxSuperMissiles();
    public static native int getPowerBombs();
    public static native int getMaxPowerBombs();

    // The Select-button "currently armed ammo" slot: 0=none, 1=Missiles,
    // 2=Super Missiles, 3=Power Bombs, 4=Grapple, 5=X-Ray. setSelectedAmmo
    // has the same real effect as pressing Select on the controller until
    // that slot is reached (no-op for values outside 0-5).
    public static final int AMMO_NONE = 0;
    public static final int AMMO_MISSILES = 1;
    public static final int AMMO_SUPER_MISSILES = 2;
    public static final int AMMO_POWER_BOMBS = 3;
    public static final int AMMO_GRAPPLE = 4;
    public static final int AMMO_XRAY = 5;
    public static native int getSelectedAmmo();
    public static native void setSelectedAmmo(int index);

    // Real room background art - the actual walls/floor/decoration tiles
    // the game draws during live gameplay, NOT the schematic pause-map
    // rectangles renderAreaMap produces. out must be length >=
    // ROOM_ART_MAX_W*ROOM_ART_MAX_H (only the first outDims[1] rows x
    // outDims[0] columns are actually written - the rest of out is
    // untouched, not cleared, so callers must respect outDims rather than
    // assuming the whole buffer is valid image data). outDims must be
    // length >= 2: {realWidthPx, realHeightPx}, the room's true pixel
    // size - may exceed ROOM_ART_MAX_W/H for a handful of SM's largest
    // rooms, meaning the render was clipped rather than an error.
    public static final int ROOM_ART_MAX_W = 2048;
    public static final int ROOM_ART_MAX_H = 2048;
    public static native boolean renderCurrentRoomArt(int[] out, int[] outDims);
}
