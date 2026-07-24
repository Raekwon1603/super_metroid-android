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

    // Renders the real in-game area-name label graphic (12 tiles x 1 tile =
    // 96x8px) for the given area index. out must be length >= 96*8.
    public static native boolean renderAreaLabel(int area, int[] out);

    // Renders the actual gameplay-HUD missile icon into a 24x16 ARGB8888
    // buffer (out must be length >= 24*16).
    public static native boolean renderMissileIcon(int[] out);

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
}
