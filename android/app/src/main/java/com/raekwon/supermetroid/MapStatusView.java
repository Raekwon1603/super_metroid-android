package com.raekwon.supermetroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

// The entire second-screen content: a full-screen, live Zebes-area minimap -
// real SNES map tile graphics decoded from the ROM (SM2_RenderAreaMap in
// second_screen.c), not a schematic placeholder - with the real in-game
// area-name label graphic (SM2_RenderAreaLabel) banner across the top.
// Health/ammo/items aren't duplicated here since they're already in the
// main screen's gameplay HUD. The view is a zoomable/pannable viewport onto
// the 64x32 area grid (pinch to zoom, double-tap to reset), auto-centered on
// Samus each frame so panning follows her as she moves - this way the map
// can fill the whole screen with the currently-relevant area instead of
// always rendering the full (mostly-unexplored) area at a tiny scale.
// During menus, pause, cutscenes, death, or demo attract-mode (anything
// GameState.isPlayingLive() says isn't live gameplay) the map dims out
// behind a faint, dimmed Metroid wordmark, matching the zelda3-android
// dual-screen mod's title/cutscene treatment but kept dark since the panel
// isn't actually in use then. Redraws every frame via onDraw() +
// postInvalidateOnAnimation(), pulling fresh state from GameState (JNI)
// each time - no separate polling thread. The map bitmap itself is only
// re-decoded on an area change or every REFRESH_INTERVAL frames (to pick up
// newly explored tiles) rather than every frame, since decoding 2048 SNES
// tiles via JNI each frame would be wasteful.
public class MapStatusView extends View {
    private static final int GRID_W = 64, GRID_H = 32;
    private static final int MAP_PX_W = GRID_W * 8, MAP_PX_H = GRID_H * 8;
    private static final int LABEL_PX_W = 96, LABEL_PX_H = 8;
    private static final int REFRESH_INTERVAL = 20;

    // zoomFactor 1 = the auto-fit view (see autoFit* fields below) is shown
    // as-is, filling the screen with just the explored rooms' extent rather
    // than the whole mostly-unexplored 64x32 grid - so early-game a couple
    // of rooms fill the panel instead of sitting as a speck in a giant black
    // void. Higher = fewer tiles visible within that same auto-fit region,
    // each drawn bigger, for zooming in on the room Samus is in; pinch,
    // double-tap-to-reset, or the on-screen +/- buttons all adjust it.
    // Zooming out past MIN_ZOOM instead flips into worldView (see below).
    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 6f;
    private static final float DEFAULT_ZOOM = MIN_ZOOM;
    private static final float ZOOM_BUTTON_STEP = 1.4f;
    private float zoomFactor = DEFAULT_ZOOM;

    // Same idea as zoomFactor/panOffsetX/Y above but for the WORLD view
    // (all 6 areas): pinching in from the default "fit all explored areas"
    // framing first zooms further into the world composite itself - so you
    // can e.g. zoom in on the Crateria/Brinstar/Tourian cluster and still
    // see all three together - before crossing MAX_WORLD_ZOOM and dropping
    // into the single-area room view. Mirrors zoomFactor's own MIN/MAX
    // range and reset-on-transition behavior.
    private static final float MIN_WORLD_ZOOM = 1f;
    private static final float MAX_WORLD_ZOOM = 4f;
    private float worldZoomFactor = MIN_WORLD_ZOOM;
    private float worldPanOffsetX = 0f, worldPanOffsetY = 0f;
    // Cached each drawWorldView() call, same purpose as tilesPerPixelX/Y
    // below but for the world view's own onScroll handling.
    private float worldTilesPerPixelX = 0f, worldTilesPerPixelY = 0f;

    // Manual pan offset (tile units, added on top of the Samus-centered
    // baseline), scoped to the CURRENT area's own map only - dragging lets
    // you look around the rest of the current area without Samus's
    // position rigidly pinning the view. Deliberately does not cross into
    // neighboring areas' art (unlike an earlier attempt at this that
    // rendered from the shared world-composite bitmap instead of the
    // single-area one): blending two areas' overlapping room layouts
    // together read as confusing rather than useful, since real Zebes room
    // data isn't laid out to tile cleanly - see WORLD_AREA_LAYOUT's own
    // comment on the same overlap. Reset (back to 0,0, i.e. Samus-
    // centered) on double-tap or on switching area, so returning to the
    // default view always means "centered on Samus" again.
    private float panOffsetX = 0f, panOffsetY = 0f;
    // Cached each drawMap() call: how many map tiles one screen pixel
    // currently covers, so onScroll can convert a drag's screen-pixel delta
    // into the same tile units panOffsetX/Y are stored in.
    private float tilesPerPixelX = 0f, tilesPerPixelY = 0f;

    // Explored-tile bounding box for the current area (in tile units, half-
    // open [min,max)), recomputed whenever the explored grid is refreshed.
    // Drives the auto-fit baseline in drawMap so the default view frames
    // just the rooms actually revealed so far instead of the full grid.
    // Starts as "nothing explored" so the very first frame (before any
    // native call has run) falls back to the whole-grid behavior.
    private final byte[] exploredGrid = new byte[GRID_W * GRID_H];
    private int exploredMinX = 0, exploredMinY = 0, exploredMaxX = GRID_W, exploredMaxY = GRID_H;

    // Minimum auto-fit extent, in tiles, so a single freshly-revealed room
    // doesn't zoom in so far that its own art looks blocky/oversized - keeps
    // a reasonable amount of surrounding (unexplored) context visible.
    private static final float MIN_AUTOFIT_TILES_W = 16f;
    private static final float MIN_AUTOFIT_TILES_H = 10f;
    // Margin added around the explored bbox, as a fraction of its own size,
    // so revealed rooms don't touch the screen edge.
    private static final float AUTOFIT_MARGIN_FRAC = 0.25f;

    // Zoomed all the way out: all 6 named areas composited into one shared
    // canvas at their real relative positions - a single connected map, not
    // a grid of separate panels. Area indices per SM's own area_index
    // (confirmed via the MapIconDataPointers field order in ida_types.h):
    // 0 Crateria, 1 Brinstar, 2 Norfair, 3 Wrecked Ship, 4 Maridia,
    // 5 Tourian (Ceres/debug, indices 6/7, aren't part of the explorable
    // Zebes map so they're left out).
    //
    // Each row is {localMinX, localMinY, localMaxX, localMaxY, canvasX,
    // canvasY} in map-tile units. local min/max is that area's own room
    // bounding box within its native 64x32 grid (so we crop out the mostly-
    // empty margin instead of drawing all 64x32 tiles), and canvasX/canvasY
    // is where that cropped region's top-left lands in the shared canvas -
    // drawWorldView frames just the currently-visible areas' extent within
    // it each frame, not the fixed full 6-area span, so a couple of areas
    // fill the screen early on instead of sitting tiny in a mostly-black
    // canvas sized for all six.
    //
    // These aren't guessed: derived from EVERY real inter-area door in the
    // ROM (32 doors / 16 unique connections total - walked via the room/door
    // graph the same way assets/restool.py does: RoomDefHeader.area_index_/
    // x_coordinate_on_map/y_coordinate_on_map/ptr_to_doorout and each
    // DoorDef's dest room + x_pos_in_room/y_pos_in_room landing point,
    // starting from Landing Site + every kLoadStationLists entry as seeds).
    // Each door gives one constraint: "this point in area A's local grid
    // must coincide with this point in area B's local grid." All 16
    // constraints are solved simultaneously (least-squares for an initial
    // global position, then a force-directed relaxation that pulls every
    // area pair toward its door-implied relative offset while pushing apart
    // on bbox overlap) rather than satisfying only a hand-picked few - see
    // the door extraction + solver script kept alongside this change for
    // the derivation. Real Zebes room data from different areas genuinely
    // overlaps in raw coordinates (areas were authored independently), so
    // some residual overlap remains even in this solution - same as the
    // previous hand-tuned layout, which also overlapped (e.g. Crateria/
    // Tourian by 10x9 tiles) despite only encoding 5 connections. Overlap is
    // harmless here since unexplored tiles are fully transparent (see
    // makeUnexploredTransparent) - only real explored-room pixels ever
    // paint over each other, and two real rooms from different areas don't
    // occupy the same screen-space in practice.
    // NOTE: these offsets are the CANVAS DESTINATION ORIGIN of each area's
    // cropped content (i.e. where declMinX/declMinY's corner lands on the
    // shared canvas) - NOT the same as the solver's raw per-area offset
    // added to a room's native 0-63 grid coordinate (that's what
    // WORLD_CONNECTORS' endpoints use instead, since those need true
    // global positions to match up with room-native door coordinates). Get
    // this distinction wrong and an area's art silently draws off-canvas
    // while still LOOKING like a small offset (this bit twice during
    // development - see git history - before being caught): offset here =
    // (bbox min) + (solver's raw offset), always non-negative for a canvas
    // starting at (0,0).
    // BFS spanning-tree layout: root at Crateria, each area anchored to ONE
    // real door connection to an already-placed neighbor with zero gap on
    // that specific door, rather than solving all 16 constraints at once
    // (an earlier force-relaxation approach that simultaneously satisfied
    // all connections is preserved on branch backup-force-directed-layout,
    // but left several areas - Tourian especially - visibly adrift from
    // their neighbors since no single connection was fully satisfied
    // anywhere; anchoring one exact connection per area reads much closer
    // to a single fused landmass, verified on-device).
    private static final float[][] WORLD_AREA_LAYOUT = {
            {6, 0, 57, 19, 6.07f, 4.00f},    // Crateria (anchor, unchanged)
            {5, 0, 58, 20, 2.07f, 12.00f},   // Brinstar
            {2, 0, 38, 18, 30.07f, 30.00f},  // Norfair
            {10, 10, 22, 20, 43.07f, 4.00f}, // Wrecked Ship
            {10, 0, 43, 20, 28.07f, 14.00f}, // Maridia
            {11, 9, 22, 22, 8.07f, 13.00f},  // Tourian
    };

    // Per-area declared-bbox tile counts, used as a static ownership
    // tiebreaker in ensureWorldAreaFresh's per-pixel composite: within an
    // overlap zone between two areas' declared boxes (these overlaps are
    // real, not a bug - see WORLD_AREA_LAYOUT's own comment), the area with
    // the SMALLER declared box always wins, regardless of which one
    // happened to explore/draw there first. Without this, "first area to
    // draw a pixel claims it forever" (worldPixelOwner's original rule)
    // lets a small, fully-enclosed area like Tourian get swallowed by a
    // sprawling area like Brinstar that merely overlaps its territory by
    // coordinate coincidence (Tourian's 11x13 box overlaps Brinstar's by
    // 11x10 - 110 of its own 143 tiles): if enough of Brinstar gets
    // explored before Tourian is ever visited, most of Tourian's own
    // declared box can already be claimed by the time Tourian tries to
    // draw there, and it never gets a chance to reclaim its own footprint.
    // Smaller-box-wins fixes this permanently and order-independently -
    // Wrecked Ship (120 tiles) and Tourian (143) are the two smallest and
    // most likely to otherwise get swallowed by Crateria (969) or Brinstar
    // (1060), the two largest.
    private static final float[] AREA_BBOX_TILES = new float[6];
    static {
        for (int a = 0; a < 6; a++) {
            float[] l = WORLD_AREA_LAYOUT[a];
            AREA_BBOX_TILES[a] = (l[2] - l[0]) * (l[3] - l[1]);
        }
    }

    // Distinct accent color per area (roughly matching each area's own
    // in-game color identity) so the labels stay tellable apart even before
    // the underlying map art's own colors register.
    private static final int[] WORLD_AREA_COLORS = {
            Color.rgb(150, 165, 210), // Crateria - cool gray-blue
            Color.rgb(110, 210, 110), // Brinstar - green
            Color.rgb(235, 110, 90),  // Norfair - red-orange
            Color.rgb(210, 180, 110), // Wrecked Ship - tan/yellow
            Color.rgb(90, 190, 225),  // Maridia - cyan-blue
            Color.rgb(220, 110, 190), // Tourian - magenta/pink
    };

    // Every real inter-area door connection in the game (16 unique pairs,
    // from 32 actual doors - two areas can share more than one door, e.g.
    // Crateria/Wrecked Ship has 5), drawn as a bridge strip directly between
    // the door's own true landing point in each of the two rooms it
    // connects (RoomDefHeader.x/y_coordinate_on_map + DoorDef.x/
    // y_pos_in_room, shifted by each area's WORLD_AREA_LAYOUT offset) so
    // adjacent areas read as one connected map instead of separate floating
    // islands, matching how printed Zebes maps show continuous corridors
    // between regions. Using the real two endpoints directly (rather than
    // inferring a bridge axis/position from which pixels happen to be
    // drawn) means every connection renders correctly regardless of how far
    // apart the layout solve left the two areas - some real areas (e.g.
    // Tourian) only avoid overlapping their neighbors by sitting a real
    // distance away, so a couple of these bridges are visibly longer than
    // others; that's an accurate reflection of the tradeoff, not a bug.
    // Each row is {areaA, ax, ay, areaB, bx, by} in shared-canvas tile
    // units, where (ax,ay)/(bx,by) are the two doors' own true positions.
    private static final float[][] WORLD_CONNECTORS = {
            {0, 6.07f, 12.00f, 1, 6.07f, 12.00f},    // Crateria (Elevator To Green Brinstar) <-> Brinstar (Green Brinstar Main Shaft) [exact]
            {1, 23.07f, 20.00f, 0, 23.07f, 21.00f},  // Brinstar (Morph Ball Room) <-> Crateria (Elevator To Blue Brinstar)
            {0, 18.07f, 21.00f, 5, 17.07f, 21.00f},  // Crateria (Old Tourian Shaft) <-> Tourian (Tourian Vertical Escape)
            {5, 17.07f, 13.00f, 0, 17.07f, 13.00f},  // Tourian (Tourian Elevator) <-> Crateria (Statue Room) [exact]
            {1, 34.07f, 16.00f, 0, 34.07f, 11.00f},  // Brinstar (Catapiller Room) <-> Crateria (Elevator To Red Brinstar)
            {1, 36.07f, 19.00f, 4, 30.07f, 21.00f},  // Brinstar (Catapiller Room) <-> Maridia (Red Fish Room)
            {0, 45.07f, 8.00f, 3, 45.07f, 8.00f},    // Crateria (West Ocean) <-> Wrecked Ship (Wrecked Ship Entrance) [exact]
            {0, 45.07f, 4.00f, 3, 45.07f, 4.00f},    // Crateria (West Ocean) <-> Wrecked Ship (Attic) [exact]
            {0, 45.07f, 5.00f, 3, 45.07f, 5.00f},    // Crateria (West Ocean) <-> Wrecked Ship (Bowling Alley) [exact]
            {0, 43.07f, 7.00f, 3, 43.07f, 7.00f},    // Crateria (West Ocean) <-> Wrecked Ship (Gravity Suit Room) [exact]
            {3, 54.07f, 8.00f, 0, 49.07f, 8.00f},    // Wrecked Ship (Electric Death Room) <-> Crateria (East Ocean)
            {0, 52.07f, 14.00f, 4, 52.07f, 14.00f},  // Crateria (Elevator To Maridia) <-> Maridia (Maridia Elevator Room) [exact]
            {4, 28.07f, 32.00f, 1, 34.07f, 30.00f},  // Maridia (West Maridia Tube) <-> Brinstar (Below Spazer)
            {4, 30.07f, 32.00f, 1, 38.07f, 30.00f},  // Maridia (East Maridia Tube) <-> Brinstar (Kraid Hideout Entrance)
            {1, 38.07f, 30.00f, 2, 38.07f, 30.00f},  // Brinstar (Kraid Hideout Entrance) <-> Norfair (Business Center) [exact]
            {0, 43.07f, 6.00f, 3, 43.07f, 6.00f},    // Crateria (West Ocean Bridge) <-> Wrecked Ship (Bowling Alley) [exact]
    };

    // Shared-canvas pixel dimensions covering all 6 areas' declared bboxes
    // (see WORLD_AREA_LAYOUT), i.e. the full extent of worldCompositeBitmap
    // below. Computed once from the layout table's own numbers.
    private static final int WORLD_CANVAS_TILES_W = 70, WORLD_CANVAS_TILES_H = 57;
    private static final int WORLD_CANVAS_PX_W = WORLD_CANVAS_TILES_W * 8;
    private static final int WORLD_CANVAS_PX_H = WORLD_CANVAS_TILES_H * 8;

    private static final int WORLD_REFRESH_STRIDE = 4;
    // World view (the connected, all-areas map) is the default/resting
    // state - pinch-in (or the +/- buttons) zooms into the current area's
    // own room-level detail view (drawMap), pinch back out returns here.
    private boolean worldView = true;

    private static final int COL_BG = Color.rgb(13, 15, 23);
    private static final int COL_ACCENT = Color.rgb(255, 158, 68);
    // Named palette for the SNES-menu-style bordered boxes (drawPixelBox)
    // and pixel-font text - consolidates what used to be inline
    // Color.rgb(...) literals scattered across the draw methods, plus one
    // new tone (COL_BORDER_HIGHLIGHT) for the box bevel effect.
    private static final int COL_PANEL_BG = Color.rgb(20, 22, 32);
    private static final int COL_BORDER_DARK = Color.rgb(45, 50, 70);
    private static final int COL_BORDER_HIGHLIGHT = Color.rgb(100, 108, 140);
    private static final int COL_LABEL_GRAY = Color.rgb(150, 155, 175);
    private static final int COL_TAB_ACTIVE_BG = Color.rgb(40, 44, 62);
    private static final int COL_TAB_LABEL = Color.rgb(200, 204, 220);
    private static final int COL_SLOT_BG = Color.rgb(30, 33, 46);
    private static final int COL_DIM_GRAY = Color.rgb(90, 94, 110);
    private static final int COL_SAMUS_DOT = Color.rgb(255, 70, 70);

    private static final String LOGO_TEXT = "METROID";

    // Footer tab-bar height and the content panel's own inset margin, both
    // as a fraction of view height/width - matches the tmc-android dual-
    // screen mod's look (QUEST/MAP/ITEMS tabs pinned to the bottom, content
    // area swaps above them) instead of the map bleeding to every screen
    // edge or duplicating the main screen's own HUD.
    private static final float HUD_BAR_HEIGHT_FRAC = 0.115f;
    private static final float PANEL_MARGIN_FRAC = 0.018f;

    // The 3 footer tabs: MAP (the existing live minimap), EQUIPMENT (a
    // pause-menu-style grid of every collected item/beam, real ROM icons
    // via GameState.renderItemIcon/renderBeamIcon), AMMO (tap Missile/
    // Super/Power Bomb to actually select it - writes hud_item_index via
    // GameState.setSelectedAmmo, same real effect as pressing Select on
    // the controller). Content area swaps per-tab; the tab bar itself is
    // always drawn last/on top, pinned to the bottom, same as
    // tmc-android's PaintTabBar-always-last-every-frame approach.
    private enum Tab { MAP, EQUIPMENT, AMMO }
    private Tab currentTab = Tab.MAP;
    private final RectF[] tabButtonRects = { new RectF(), new RectF(), new RectF() };
    private int tabTouchPointerId = -1;
    private int tabTouchDownIndex = -1;

    private final Paint bgPaint = new Paint();
    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint samusRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint samusDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBitmapPaint = new Paint();
    private final Paint dimPaint = new Paint();
    private final Paint logoLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomBtnIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Reused by drawPixelBox for its fill/border/highlight/corner strokes -
    // one mutable Paint instead of allocating new ones per box per frame.
    private final Paint pixelBoxPaint = new Paint();
    // Leader/callout lines from the wireframe Samus to each equipment box
    // (drawWireframeCallouts) - soft accent, semi-transparent so it doesn't
    // fight the wireframe's own bright green linework.
    private final Paint calloutLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int[] missileIconPixels = new int[24 * 16];
    private Bitmap missileIconBitmap;
    private boolean haveMissileIcon = false;
    private final int[] superMissileIconPixels = new int[16 * 16];
    private Bitmap superMissileIconBitmap;
    private boolean haveSuperMissileIcon = false;
    private final int[] powerBombIconPixels = new int[16 * 16];
    private Bitmap powerBombIconBitmap;
    private boolean havePowerBombIcon = false;
    private static final int WIREFRAME_PX_W = 64, WIREFRAME_PX_H = 136;
    private final int[] wireframePixels = new int[WIREFRAME_PX_W * WIREFRAME_PX_H];
    private Bitmap wireframeBitmap;
    private int wireframeCachedEquippedItems = Integer.MIN_VALUE;
    private final RectF panelRect = new RectF();
    private final RectF hudBarRect = new RectF();

    // ---- Real room art (realTextureMode, toggled via realTextureBtn) ----
    // In real-texture mode, the room view swaps from the schematic pause-
    // map tiles (mapBitmap, decoded via renderAreaMap) to the room's
    // actual in-game background art (renderCurrentRoomArt) - real walls/
    // floor/decoration, not colored rectangles. Only decoded on room
    // change (roomArtCachedRoom), not every frame - a fresh decompress+
    // composite pass over a whole room is real work, unlike the cheap
    // per-frame Samus-dot/pan math the rest of this view does.
    private final int[] roomArtPixels = new int[GameState.ROOM_ART_MAX_W * GameState.ROOM_ART_MAX_H];
    private final int[] roomArtDims = new int[2];
    private Bitmap roomArtBitmap;
    private int roomArtPxW = 0, roomArtPxH = 0;
    private int roomArtCachedRoom = -1;
    private boolean haveRoomArt = false;
    // Separate from frameCounter (which only increments in drawMap, never
    // called while real-texture mode is on) - lets drawRoomArt re-decode
    // periodically like the schematic map's own REFRESH_INTERVAL does, to
    // pick up newly-explored screens revealed while standing in a big,
    // multi-screen room without having to leave and re-enter it.
    private int roomArtFrameCounter = 0;
    // Manual drag pan (real room pixel units), added on top of the auto-fit
    // "whole room" baseline - lets you look around the full room instead of
    // being locked to Samus's own position, since a real room is often far
    // more detailed than the panel can show at once even zoomed to fit.
    // Reset on room change so entering a new room always starts centered.
    private float roomArtPanX = 0f, roomArtPanY = 0f;
    // Zoom on top of the auto-fit "whole room" baseline fitScale in
    // drawRoomArt - 1.0 shows the whole room (fitScale as-is), >1 zooms in
    // (matching zoomFactor's own convention for the schematic map), so the
    // shared pinch/+-button handlers can scale this the same way. Reset on
    // room change alongside roomArtPanX/Y for the same reason.
    private static final float MIN_ROOM_ART_ZOOM = 1f;
    private static final float MAX_ROOM_ART_ZOOM = 8f;
    private float roomArtZoom = MIN_ROOM_ART_ZOOM;

    // ---- Equipment tab ----
    // Grouped text-list layout matching SM's real pause-menu "SAMUS"
    // equipment screen (SUIT/MISC./BOOTS/BEAM boxes listing collected item
    // names) rather than an icon grid - the equipment-screen ROM icon
    // strips are wide/short (64x8px tile strips) and don't read well
    // forced into square grid cells, and the real screen doesn't use icons
    // for this list anyway. Bit values mirror the kSM2Item_*/kSM2Beam_*
    // enums in second_screen.h.
    private static final class EquipGroup {
        final String title;
        final int[] bits;
        final String[] labels;
        final boolean isBeam;  // which bitfield (collected_items vs collected_beams) this group's bits belong to
        EquipGroup(String title, int[] bits, String[] labels, boolean isBeam) {
            this.title = title;
            this.bits = bits;
            this.labels = labels;
            this.isBeam = isBeam;
        }
    }
    private static final EquipGroup EQUIP_SUIT = new EquipGroup("SUIT",
            new int[] { 0x0001, 0x0020 }, new String[] { "VARIA SUIT", "GRAVITY SUIT" }, false);
    private static final EquipGroup EQUIP_MISC = new EquipGroup("MISC.",
            new int[] { 0x0004, 0x1000, 0x0002, 0x0008 },
            new String[] { "MORPHING BALL", "BOMB", "SPRING BALL", "SCREW ATTACK" }, false);
    private static final EquipGroup EQUIP_BOOTS = new EquipGroup("BOOTS",
            new int[] { 0x0100, 0x0200, 0x2000 },
            new String[] { "HI-JUMP BOOTS", "SPACE JUMP", "SPEED BOOSTER" }, false);
    private static final EquipGroup EQUIP_BEAM = new EquipGroup("BEAM",
            new int[] { 0x1000, 0x0002, 0x0001, 0x0004, 0x0008 },
            new String[] { "CHARGE", "ICE", "WAVE", "SPAZER", "PLASMA" }, true);
    // Grapple/X-Ray have no box on the real pause screen either (vanilla SM
    // just shows them via the HUD icon strip, not the SAMUS equipment
    // page) - omitted here to match, not an oversight.

    // ---- Ammo-select tab ----
    private static final int[] AMMO_SLOTS = {
            GameState.AMMO_MISSILES, GameState.AMMO_SUPER_MISSILES, GameState.AMMO_POWER_BOMBS,
    };
    private static final String[] AMMO_LABELS = { "MISSILE", "SUPER", "POWER BOMB" };
    private final RectF[] ammoSlotRects = { new RectF(), new RectF(), new RectF() };
    private int ammoTouchPointerId = -1;

    private final RectF zoomInBtn = new RectF();
    private final RectF zoomOutBtn = new RectF();
    private int zoomButtonPointerId = -1;
    private boolean zoomButtonIsIn;

    // Explicit schematic-vs-real-texture mode toggle (bottom-left of the
    // map panel, mirroring the zoom buttons' own bottom-right placement) -
    // a deliberate switch rather than an automatic zoom-past-threshold
    // trigger, so the two modes are always reachable independent of
    // zoomFactor. Real-texture mode reuses drawRoomArt for its single-room
    // view; its own world-composite view is a later follow-up (not built
    // yet - toggling into real-texture mode while zoomed out currently
    // just shows the current room, same as fully zoomed in).
    private final RectF realTextureBtn = new RectF();
    private boolean realTextureMode = false;
    private int realTextureBtnPointerId = -1;

    // Recenter/reset-camera button (next to realTextureBtn) - snaps
    // roomArtPanX/Y back to 0, i.e. back to Samus-centered, without
    // needing to leave and re-enter the room (which was the only other
    // way to reset the pan before this).
    private final RectF resetCameraBtn = new RectF();
    private int resetCameraBtnPointerId = -1;

    private final int[] samusTile = new int[2];
    private final int[] samusMapPosFixed = new int[2];

    private final int[] mapPixels = new int[MAP_PX_W * MAP_PX_H];
    private final Bitmap mapBitmap;
    private final int[] labelPixels = new int[LABEL_PX_W * LABEL_PX_H];
    private final Bitmap labelBitmap;
    private boolean haveLabel = false;
    private int cachedArea = -1;
    private int frameCounter = 0;

    // World-view (zoomed all the way out) state: ALL 6 areas are decoded
    // once each (round-robin, one per WORLD_REFRESH_STRIDE frames) directly
    // into their real position on one shared, cached composite bitmap
    // (worldCompositeBitmap) - connector bridges are baked in at the same
    // time, right when both sides of a junction first become visible. This
    // mirrors the zelda3-android dual-screen mod's approach (decode the
    // whole map once into one static bitmap, then just pan/zoom/draw it
    // every frame) rather than re-positioning/re-cropping separate per-area
    // bitmaps every frame, which was fragile: since each area's OWN crop
    // shrinks to its live-explored extent independently, a connector drawn
    // at the two areas' true (fixed) door position could end up floating in
    // the gap, disconnected from either area's actual (smaller) drawn
    // region. Baking everything into one persistent bitmap sidesteps that -
    // once a tile is drawn, it stays drawn, so there's no per-frame
    // crop/connector mismatch to get out of sync.
    private Bitmap worldCompositeBitmap;
    // Per-pixel ownership backing worldCompositeBitmap: since area bboxes
    // routinely overlap (see WORLD_AREA_LAYOUT's comment - Zebes' room data
    // was never authored to be globally consistent), redrawing an area's
    // bitmap on its round-robin turn would otherwise flicker any overlap
    // zone between whichever two areas' art last happened to draw there.
    // The first area to ever paint a canvas pixel claims it permanently in
    // worldPixelOwner (-1 = unclaimed); later areas skip pixels they don't
    // own (see ensureWorldAreaFresh's manual per-pixel composite), so
    // overlap zones settle onto one area's art instead of cycling forever.
    private int[] worldCompositePixels;
    private byte[] worldPixelOwner;
    private final Bitmap[] worldLabelBitmaps = new Bitmap[6];
    private final boolean[] haveWorldLabel = new boolean[6];
    private final boolean[] worldAreaDrawn = new boolean[6];
    private final boolean[] worldConnectorDrawn = new boolean[WORLD_CONNECTORS.length];
    private int worldAreaCursor = 0;
    private int worldFrameCounter = 0;

    private boolean nativeBroken = false;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;

    public MapStatusView(Context context) {
        super(context);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (realTextureMode) {
                    float prospective = roomArtZoom * detector.getScaleFactor();
                    roomArtZoom = Math.max(MIN_ROOM_ART_ZOOM, Math.min(MAX_ROOM_ART_ZOOM, prospective));
                    return true;
                }
                if (worldView) {
                    float prospective = worldZoomFactor * detector.getScaleFactor();
                    if (prospective > MAX_WORLD_ZOOM) {
                        // Zoomed in past the world view's own range - cross
                        // over into the single-area room view, centered on
                        // wherever the world view was currently showing
                        // (Samus's area) rather than resetting world zoom,
                        // so pinching in feels continuous across the
                        // transition instead of snapping back out first.
                        worldView = false;
                        worldZoomFactor = MIN_WORLD_ZOOM;
                        worldPanOffsetX = 0f;
                        worldPanOffsetY = 0f;
                        zoomFactor = MIN_ZOOM;
                    } else {
                        worldZoomFactor = Math.max(MIN_WORLD_ZOOM, prospective);
                    }
                    return true;
                }
                float prospective = zoomFactor * detector.getScaleFactor();
                if (prospective < MIN_ZOOM) {
                    zoomFactor = MIN_ZOOM;
                    enterWorldView();
                } else {
                    zoomFactor = Math.min(MAX_ZOOM, prospective);
                }
                return true;
            }
        });
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (realTextureMode) {
                    roomArtZoom = MIN_ROOM_ART_ZOOM;
                    roomArtPanX = 0f;
                    roomArtPanY = 0f;
                    return true;
                }
                worldView = false;
                zoomFactor = DEFAULT_ZOOM;
                panOffsetX = 0f;
                panOffsetY = 0f;
                worldZoomFactor = MIN_WORLD_ZOOM;
                worldPanOffsetX = 0f;
                worldPanOffsetY = 0f;
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (realTextureMode && haveRoomArt) {
                    // Real room pixel units here (tilesPerPixelX/Y is
                    // measured in 8px units for this view - see
                    // drawRoomArt's own comment - so undo that 8x scaling
                    // to get back to plain pixels for roomArtPanX/Y).
                    roomArtPanX += distanceX * tilesPerPixelX * 8f;
                    roomArtPanY += distanceY * tilesPerPixelY * 8f;
                } else if (worldView) {
                    worldPanOffsetX += distanceX * worldTilesPerPixelX;
                    worldPanOffsetY += distanceY * worldTilesPerPixelY;
                } else {
                    panOffsetX += distanceX * tilesPerPixelX;
                    panOffsetY += distanceY * tilesPerPixelY;
                }
                return true;
            }
        });

        bgPaint.setColor(COL_BG);

        samusRingPaint.setColor(Color.WHITE);
        samusRingPaint.setStyle(Paint.Style.STROKE);
        samusRingPaint.setStrokeWidth(2.5f);
        samusDotPaint.setColor(COL_SAMUS_DOT);

        labelBitmapPaint.setFilterBitmap(false);  // crisp pixel-art scaling, no blur
        labelBitmapPaint.setAlpha(150);  // translucent - identifies the area without covering the room layout under it

        dimPaint.setColor(Color.BLACK);

        logoLinePaint.setColor(COL_ACCENT);
        logoLinePaint.setStrokeWidth(3);

        zoomBtnIconPaint.setColor(Color.WHITE);
        zoomBtnIconPaint.setStrokeWidth(4);
        zoomBtnIconPaint.setStrokeCap(Paint.Cap.ROUND);

        panelBorderPaint.setColor(COL_BORDER_DARK);
        panelBorderPaint.setStyle(Paint.Style.STROKE);
        panelBorderPaint.setStrokeWidth(3f);

        calloutLinePaint.setColor(COL_ACCENT);
        calloutLinePaint.setAlpha(160);
        calloutLinePaint.setStyle(Paint.Style.STROKE);
        calloutLinePaint.setStrokeWidth(2f);

        // The map bitmap is native 8px/tile SNES art scaled way up on
        // screen - keep it crisp/unfiltered (same as the label/room
        // graphics) so pixel edges stay sharp at higher zoom levels instead
        // of going soft from bilinear interpolation.
        mapPaint.setFilterBitmap(false);

        mapBitmap = Bitmap.createBitmap(MAP_PX_W, MAP_PX_H, Bitmap.Config.ARGB_8888);
        labelBitmap = Bitmap.createBitmap(LABEL_PX_W, LABEL_PX_H, Bitmap.Config.ARGB_8888);

        worldCompositeBitmap = Bitmap.createBitmap(WORLD_CANVAS_PX_W, WORLD_CANVAS_PX_H, Bitmap.Config.ARGB_8888);
        worldCompositePixels = new int[WORLD_CANVAS_PX_W * WORLD_CANVAS_PX_H];
        worldPixelOwner = new byte[WORLD_CANVAS_PX_W * WORLD_CANVAS_PX_H];
        java.util.Arrays.fill(worldPixelOwner, (byte) -1);

        missileIconBitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888);
        superMissileIconBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        powerBombIconBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        wireframeBitmap = Bitmap.createBitmap(WIREFRAME_PX_W, WIREFRAME_PX_H, Bitmap.Config.ARGB_8888);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        float hudBarH = h * HUD_BAR_HEIGHT_FRAC;
        float panelMargin = Math.min(w, h) * PANEL_MARGIN_FRAC;
        float mapBottom = h - hudBarH;
        panelRect.set(panelMargin, panelMargin, w - panelMargin, mapBottom - panelMargin * 0.5f);
        hudBarRect.set(panelMargin, mapBottom + panelMargin * 0.5f, w - panelMargin, h - panelMargin);

        // 3 equal-width tab buttons filling the footer strip, small gaps
        // between them - same layout idea as tmc-android's PaintTabBar.
        float tabGap = hudBarRect.width() * 0.012f;
        float tabW = (hudBarRect.width() - tabGap * 2) / 3f;
        for (int i = 0; i < 3; i++) {
            float tx0 = hudBarRect.left + i * (tabW + tabGap);
            tabButtonRects[i].set(tx0, hudBarRect.top, tx0 + tabW, hudBarRect.bottom);
        }

        float size = Math.min(w, h) * 0.11f;
        float margin = Math.min(w, h) * 0.03f;
        float right = panelRect.right - margin, bottom = panelRect.bottom - margin;
        zoomOutBtn.set(right - size, bottom - size, right, bottom);
        zoomInBtn.set(right - size, bottom - size * 2 - margin * 0.6f, right, bottom - size - margin * 0.6f);

        float left2 = panelRect.left + margin;
        realTextureBtn.set(left2, bottom - size, left2 + size, bottom);
        resetCameraBtn.set(left2, bottom - size * 2 - margin * 0.6f, left2 + size, bottom - size - margin * 0.6f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float x = event.getX(), y = event.getY();

            for (int i = 0; i < tabButtonRects.length; i++) {
                if (tabButtonRects[i].contains(x, y)) {
                    tabTouchPointerId = event.getPointerId(0);
                    tabTouchDownIndex = i;
                    return true;
                }
            }

            if (currentTab == Tab.AMMO) {
                for (int i = 0; i < ammoSlotRects.length; i++) {
                    if (ammoSlotRects[i].contains(x, y)) {
                        ammoTouchPointerId = event.getPointerId(0);
                        return true;
                    }
                }
            }

            if (currentTab != Tab.MAP) {
                // Map-only gestures (pinch/pan/double-tap) don't apply on
                // the other tabs - nothing else to hit-test there.
                return true;
            }

            if (zoomInBtn.contains(x, y) || zoomOutBtn.contains(x, y)) {
                zoomButtonPointerId = event.getPointerId(0);
                zoomButtonIsIn = zoomInBtn.contains(x, y);
                return true;
            }

            if (realTextureBtn.contains(x, y)) {
                realTextureBtnPointerId = event.getPointerId(0);
                return true;
            }

            if (resetCameraBtn.contains(x, y)) {
                resetCameraBtnPointerId = event.getPointerId(0);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP && realTextureBtnPointerId != -1) {
            if (realTextureBtn.contains(event.getX(), event.getY())) {
                realTextureMode = !realTextureMode;
            }
            realTextureBtnPointerId = -1;
            return true;
        } else if (action == MotionEvent.ACTION_UP && resetCameraBtnPointerId != -1) {
            if (resetCameraBtn.contains(event.getX(), event.getY())) {
                roomArtPanX = 0f;
                roomArtPanY = 0f;
            }
            resetCameraBtnPointerId = -1;
            return true;
        } else if (action == MotionEvent.ACTION_UP && tabTouchPointerId != -1) {
            int idx = tabTouchDownIndex;
            if (idx >= 0 && idx < tabButtonRects.length && tabButtonRects[idx].contains(event.getX(), event.getY())) {
                currentTab = Tab.values()[idx];
            }
            tabTouchPointerId = -1;
            tabTouchDownIndex = -1;
            return true;
        } else if (action == MotionEvent.ACTION_UP && ammoTouchPointerId != -1) {
            float x = event.getX(), y = event.getY();
            int[] maxCounts = { GameState.getMaxMissiles(), GameState.getMaxSuperMissiles(), GameState.getMaxPowerBombs() };
            int currentlySelected = GameState.getSelectedAmmo();
            for (int i = 0; i < ammoSlotRects.length; i++) {
                if (ammoSlotRects[i].contains(x, y)) {
                    // Only a collected (max > 0) ammo type can actually be
                    // armed - mirrors the real Select-button handler
                    // (SwitchToHudHandler_* in sm_90.c), which skips over
                    // unowned slots rather than letting them be selected.
                    // Tapping the ALREADY-selected slot instead deselects it
                    // (back to AMMO_NONE/plain beam) - on real hardware,
                    // undoing a Select-button arm means cycling Select
                    // through every other slot until you land back on
                    // "none"; a direct second tap here is a much faster
                    // shortcut to the same end state, not a new game rule.
                    if (maxCounts[i] > 0) {
                        boolean alreadySelected = currentlySelected == AMMO_SLOTS[i];
                        GameState.setSelectedAmmo(alreadySelected ? GameState.AMMO_NONE : AMMO_SLOTS[i]);
                    }
                    break;
                }
            }
            ammoTouchPointerId = -1;
            return true;
        } else if (action == MotionEvent.ACTION_UP && zoomButtonPointerId != -1) {
            RectF btn = zoomButtonIsIn ? zoomInBtn : zoomOutBtn;
            if (btn.contains(event.getX(), event.getY())) {
                if (realTextureMode) {
                    if (zoomButtonIsIn) {
                        roomArtZoom = Math.min(MAX_ROOM_ART_ZOOM, roomArtZoom * ZOOM_BUTTON_STEP);
                    } else {
                        roomArtZoom = Math.max(MIN_ROOM_ART_ZOOM, roomArtZoom / ZOOM_BUTTON_STEP);
                    }
                } else if (zoomButtonIsIn) {
                    if (worldView) {
                        float prospective = worldZoomFactor * ZOOM_BUTTON_STEP;
                        if (prospective > MAX_WORLD_ZOOM) {
                            worldView = false;
                            worldZoomFactor = MIN_WORLD_ZOOM;
                            worldPanOffsetX = 0f;
                            worldPanOffsetY = 0f;
                            zoomFactor = MIN_ZOOM;
                        } else {
                            worldZoomFactor = prospective;
                        }
                    } else {
                        zoomFactor = Math.min(MAX_ZOOM, zoomFactor * ZOOM_BUTTON_STEP);
                    }
                } else if (worldView) {
                    worldZoomFactor = Math.max(MIN_WORLD_ZOOM, worldZoomFactor / ZOOM_BUTTON_STEP);
                } else {
                    float prospective = zoomFactor / ZOOM_BUTTON_STEP;
                    if (prospective < MIN_ZOOM) {
                        zoomFactor = MIN_ZOOM;
                        enterWorldView();
                    } else {
                        zoomFactor = prospective;
                    }
                }
            }
            zoomButtonPointerId = -1;
            return true;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            zoomButtonPointerId = -1;
            tabTouchPointerId = -1;
            tabTouchDownIndex = -1;
            ammoTouchPointerId = -1;
            realTextureBtnPointerId = -1;
            resetCameraBtnPointerId = -1;
        }

        if (currentTab == Tab.MAP && zoomButtonPointerId == -1) {
            scaleDetector.onTouchEvent(event);
            tapDetector.onTouchEvent(event);
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, bgPaint);

        if (!nativeBroken) {
            try {
                drawPixelBox(canvas, panelRect, COL_PANEL_BG, COL_BORDER_DARK, COL_BORDER_HIGHLIGHT, false);

                switch (currentTab) {
                    case MAP:
                        // Skip drawing the map entirely outside real live
                        // gameplay (title/file-select, intro cinematic,
                        // demo attract-mode) instead of drawing real-but-
                        // stale content and only dimming it afterward - the
                        // demo's own explored-tile state is real, valid
                        // game data (SM's attract-mode demo actually walks
                        // through areas), so it previously showed through
                        // as a misleadingly "100% explored" map on a brand
                        // new file, before the player has even started.
                        if (GameState.isPlayingLive()) {
                            if (realTextureMode) {
                                // No real-texture WORLD composite yet (a
                                // bigger follow-up - stitching every explored
                                // room's real art together, not just one) -
                                // real-texture mode always shows the current
                                // single room for now, regardless of
                                // worldView's own zoomed-in/out state.
                                drawRoomArt(canvas, panelRect.left, panelRect.top, panelRect.right, panelRect.bottom);
                            } else if (worldView) {
                                drawWorldView(canvas, panelRect.left, panelRect.top, panelRect.right, panelRect.bottom);
                            } else {
                                drawMap(canvas, panelRect.left, panelRect.top, panelRect.right, panelRect.bottom);
                            }
                        }
                        break;
                    case EQUIPMENT:
                        drawEquipmentTab(canvas);
                        break;
                    case AMMO:
                        drawAmmoTab(canvas);
                        break;
                }
                // Re-stroke the border on top of whatever tab content just
                // drew, same as the original draw order - keeps the panel's
                // own frame from ever being covered by map/room-art bitmaps
                // that fill right up to panelRect's edges.
                float panelR = Math.min(panelRect.width(), panelRect.height()) * 0.02f + 1.5f;
                canvas.drawRect(panelRect.left + panelR / 2f, panelRect.top + panelR / 2f,
                        panelRect.right - panelR / 2f, panelRect.bottom - panelR / 2f, panelBorderPaint);
                if (currentTab == Tab.MAP) {
                    drawZoomButtons(canvas);
                    drawRealTextureButton(canvas);
                    if (realTextureMode) drawResetCameraButton(canvas);
                }
                if (!GameState.isPlayingLive()) {
                    drawDimOverlay(canvas, w, h);
                }

                drawTabBar(canvas);
            } catch (UnsatisfiedLinkError e) {
                nativeBroken = true;
            }
        }

        if (isAttachedToWindow()) postInvalidateOnAnimation();
    }

    private static final String[] TAB_LABELS = { "MAP", "ITEMS", "AMMO" };

    // Persistent 3-button footer tab bar, always drawn last/on top so it
    // reads as chrome rather than page content - same "content swaps,
    // chrome persists" approach as tmc-android's PaintTabBar (called every
    // frame after whichever panel is active). The active tab gets a
    // highlighted background plus an accent keyline border.
    private void drawTabBar(Canvas canvas) {
        for (int i = 0; i < 3; i++) {
            RectF rect = tabButtonRects[i];
            boolean active = currentTab.ordinal() == i;
            int fill = active ? COL_TAB_ACTIVE_BG : COL_PANEL_BG;
            int border = active ? COL_ACCENT : COL_BORDER_DARK;
            drawPixelBox(canvas, rect, fill, border, COL_BORDER_HIGHLIGHT, true);

            float textSize = rect.height() * 0.4f;
            PixelFont.drawText(canvas, TAB_LABELS[i], rect.centerX(), rect.centerY() - textSize / 2f,
                    PixelFont.pixelSizeForHeight(textSize), COL_TAB_LABEL, Paint.Align.CENTER);
        }
    }

    // Grouped text-list equipment screen - SUIT/MISC./BOOTS boxes on the
    // left/right (mirroring the real pause screen's layout) plus a BEAM
    // box, each listing every item in that group with a filled or hollow
    // bullet marker showing collected/not-collected, matching the
    // reference photo's own look exactly (no icons - see EQUIP_GROUPS's
    // comment on why icons were dropped).
    // SNES-menu-style bordered box: solid fill, a dark outer border, a
    // lighter "highlight" bevel on the top+left inner edge, and (unless
    // simple=true) small solid corner-accent squares - replaces the plain
    // drawRoundRect look used everywhere previously. simple=true skips the
    // corner accents for small controls (zoom/texture/reset buttons) where
    // they'd look cluttered rather than deliberate.
    private void drawPixelBox(Canvas canvas, RectF rect, int fillColor, int borderColor,
                               int highlightColor, boolean simple) {
        float inset = Math.min(rect.width(), rect.height()) * 0.02f + 1.5f;
        pixelBoxPaint.setStyle(Paint.Style.FILL);
        pixelBoxPaint.setColor(fillColor);
        canvas.drawRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, pixelBoxPaint);

        pixelBoxPaint.setStyle(Paint.Style.STROKE);
        pixelBoxPaint.setStrokeWidth(inset);
        pixelBoxPaint.setColor(borderColor);
        canvas.drawRect(rect.left + inset / 2f, rect.top + inset / 2f, rect.right - inset / 2f, rect.bottom - inset / 2f, pixelBoxPaint);

        // Bevel highlight: top and left inner edges only, one step further
        // inset than the border itself.
        pixelBoxPaint.setStrokeWidth(inset * 0.6f);
        pixelBoxPaint.setColor(highlightColor);
        float hi = inset * 1.6f;
        canvas.drawLine(rect.left + hi, rect.top + hi, rect.right - hi, rect.top + hi, pixelBoxPaint);
        canvas.drawLine(rect.left + hi, rect.top + hi, rect.left + hi, rect.bottom - hi, pixelBoxPaint);

        if (!simple) {
            float accent = Math.max(3f, inset * 1.8f);
            pixelBoxPaint.setStyle(Paint.Style.FILL);
            pixelBoxPaint.setColor(borderColor);
            canvas.drawRect(rect.left, rect.top, rect.left + accent, rect.top + accent, pixelBoxPaint);
            canvas.drawRect(rect.right - accent, rect.top, rect.right, rect.top + accent, pixelBoxPaint);
            canvas.drawRect(rect.left, rect.bottom - accent, rect.left + accent, rect.bottom, pixelBoxPaint);
            canvas.drawRect(rect.right - accent, rect.bottom - accent, rect.right, rect.bottom, pixelBoxPaint);
        }
    }

    private void drawEquipmentTab(Canvas canvas) {
        float pad = panelRect.width() * 0.03f;
        float left = panelRect.left + pad, right = panelRect.right - pad;
        float top = panelRect.top + pad, bottom = panelRect.bottom - pad;
        float colGap = pad * 0.8f;

        // 3 columns: SUIT/MISC. boxes | wireframe body | BOOTS/BEAM boxes -
        // mirrors the real pause screen's layout (side boxes flanking the
        // green wireframe Samus). The wireframe's own native aspect ratio
        // (64x136, roughly 1:2.1) sizes its column; the two box columns
        // split whatever width remains.
        float wireframeAspect = WIREFRAME_PX_W / (float) WIREFRAME_PX_H;
        float wireframeW = Math.min((right - left) * 0.26f, (bottom - top) * wireframeAspect);
        float colW = (right - left - colGap * 2 - wireframeW) / 2f;

        RectF[] leftBoxes = drawEquipColumn(canvas, left, top, colW, bottom - top, new EquipGroup[] { EQUIP_SUIT, EQUIP_MISC });
        RectF wireframeDest = drawWireframe(canvas, left + colW + colGap, top, wireframeW, bottom - top);
        RectF[] rightBoxes = drawEquipColumn(canvas, left + colW + colGap + wireframeW + colGap, top, colW, bottom - top,
                new EquipGroup[] { EQUIP_BOOTS, EQUIP_BEAM });

        // leftBoxes = {SUIT, MISC}, rightBoxes = {BOOTS, BEAM} - matches the
        // group arrays passed into drawEquipColumn just above.
        drawWireframeCallouts(canvas, wireframeDest, leftBoxes[0], leftBoxes[1], rightBoxes[0], rightBoxes[1]);
    }

    // Draws Samus's equipment-screen body graphic, centered in the given
    // rect at its native aspect ratio (no stretch). Uses the "Redux Suit"
    // full-color art (renderReduxSuit - extracted from the real
    // Super-Metroid-Redux ROM, see redux_suit_data.h) instead of vanilla's
    // flat green wireframe (renderSamusWireframe, still available natively
    // but no longer called here) - same 64x136 layout and 4-variant
    // suit-state selection either way, so no other layout math changes.
    // Re-decoded only when equipped_items actually changes (a new suit is
    // collected), since the art behind a given suit-state combo never
    // changes otherwise - same lazy-cache idea as the missile/super/PB
    // icons. Returns the actual drawn rect (may be narrower/shorter than
    // the given w/h since the aspect ratio is preserved) so
    // drawWireframeCallouts can anchor lines to real points on the sprite.
    private RectF drawWireframe(Canvas canvas, float x, float top, float w, float h) {
        int equippedItems = GameState.getEquippedItems();
        if (equippedItems != wireframeCachedEquippedItems
                && GameState.renderReduxSuit(equippedItems, wireframePixels)) {
            wireframeBitmap.setPixels(wireframePixels, 0, WIREFRAME_PX_W, 0, 0, WIREFRAME_PX_W, WIREFRAME_PX_H);
            wireframeCachedEquippedItems = equippedItems;
        }
        float aspect = WIREFRAME_PX_W / (float) WIREFRAME_PX_H;
        float drawH = h, drawW = drawH * aspect;
        if (drawW > w) {
            drawW = w;
            drawH = drawW / aspect;
        }
        float cx = x + w / 2f, cy = top + h / 2f;
        RectF dest = new RectF(cx - drawW / 2f, cy - drawH / 2f, cx + drawW / 2f, cy + drawH / 2f);
        canvas.drawBitmap(wireframeBitmap, null, dest, mapPaint);
        return dest;
    }

    // Returns each group's own box rect, in the same order as the `groups`
    // param - lets drawEquipmentTab wire them into drawWireframeCallouts.
    private RectF[] drawEquipColumn(Canvas canvas, float x, float top, float w, float h, EquipGroup[] groups) {
        int collectedItems = GameState.getCollectedItems();
        int collectedBeams = GameState.getCollectedBeams();

        int totalEntries = 0;
        for (EquipGroup g : groups) totalEntries += g.bits.length;
        float gap = h * 0.04f;
        float unitH = (h - gap * (groups.length - 1)) / totalEntries;

        // One shared entry/title size for the WHOLE column (both groups),
        // not computed per-group - groups have different row counts (SUIT:
        // 2, MISC: 4), so sizing from each group's own boxH/rowCount made
        // SUIT's text noticeably bigger than MISC's, which read as
        // inconsistent/broken rather than a deliberate hierarchy. unitH (one
        // row's height, the same for every group since it's shared column-
        // wide) is the base size; reduced further below if the single
        // longest label across every group in this column wouldn't fit the
        // box width otherwise.
        float titleSize = unitH * 0.34f;
        float entrySize = unitH * 0.40f;
        float maxLabelW = w * 0.80f;  // leaves room for the left-side bullet dot + margins
        float entryPixelSize = PixelFont.pixelSizeForHeight(entrySize);
        float widestLabelW = 0f;
        for (EquipGroup g : groups) {
            for (String label : g.labels) {
                widestLabelW = Math.max(widestLabelW, PixelFont.measureWidth(label, entryPixelSize));
            }
        }
        if (widestLabelW > maxLabelW) {
            float shrink = maxLabelW / widestLabelW;
            entryPixelSize *= shrink;
            entrySize *= shrink;
        }

        RectF[] boxes = new RectF[groups.length];
        float y = top;
        for (int gi = 0; gi < groups.length; gi++) {
            EquipGroup g = groups[gi];
            int bits = g.isBeam ? collectedBeams : collectedItems;
            float boxH = unitH * g.bits.length;
            RectF box = new RectF(x, y, x + w, y + boxH);
            boxes[gi] = box;
            drawPixelBox(canvas, box, COL_SLOT_BG, COL_BORDER_DARK, COL_BORDER_HIGHLIGHT, false);

            PixelFont.drawText(canvas, g.title, box.left + w * 0.06f, box.top + titleSize * 0.5f,
                    PixelFont.pixelSizeForHeight(titleSize), COL_ACCENT, Paint.Align.LEFT);

            float rowH = (boxH - titleSize * 1.6f) / g.bits.length;
            float rowY = box.top + titleSize * 1.6f;
            for (int i = 0; i < g.bits.length; i++) {
                boolean collected = (bits & g.bits[i]) != 0;
                float cy = rowY + i * rowH + rowH * 0.65f;
                float dotR = entrySize * 0.28f;
                float dotCx = box.left + w * 0.09f, dotCy = cy - entrySize * 0.32f;
                if (collected) {
                    canvas.drawCircle(dotCx, dotCy, dotR, slotSelectedBorderPaintFilled());
                } else {
                    canvas.drawCircle(dotCx, dotCy, dotR, panelBorderPaint);
                }
                int textColor = collected ? Color.WHITE : COL_DIM_GRAY;
                PixelFont.drawText(canvas, g.labels[i], box.left + w * 0.16f, cy - entrySize * 0.7f,
                        entryPixelSize, textColor, Paint.Align.LEFT);
            }
            y += boxH + gap;
        }
        return boxes;
    }

    // Leader/callout lines from fixed anchor points on the wireframe sprite
    // (head/chest/hip/feet) to their corresponding equipment box, matching
    // the zelda3-android reference's callout-line style on its own item
    // screen. There's no per-item anchor data from the ROM to derive real
    // hitboxes from, so these are reasonable fixed fractions of the
    // wireframe's own drawn rect (dest) rather than anything data-driven.
    private void drawWireframeCallouts(Canvas canvas, RectF dest, RectF suitBox, RectF miscBox,
                                        RectF bootsBox, RectF beamBox) {
        float cx = dest.centerX();
        float beamY = dest.top + dest.height() * 0.08f;    // head/visor
        float suitY = dest.top + dest.height() * 0.30f;    // chest
        float miscY = dest.top + dest.height() * 0.55f;    // hip
        float bootsY = dest.top + dest.height() * 0.92f;   // feet

        // Left column (SUIT/MISC) is to the left of the wireframe - lines
        // run from the sprite to that box's RIGHT edge. Right column
        // (BOOTS/BEAM) lines run to that box's LEFT edge.
        drawCalloutLine(canvas, cx, suitY, suitBox.right, suitBox.centerY());
        drawCalloutLine(canvas, cx, miscY, miscBox.right, miscBox.centerY());
        drawCalloutLine(canvas, cx, bootsY, bootsBox.left, bootsBox.centerY());
        drawCalloutLine(canvas, cx, beamY, beamBox.left, beamBox.centerY());
    }

    private void drawCalloutLine(Canvas canvas, float sx, float sy, float ex, float ey) {
        canvas.drawLine(sx, sy, ex, ey, calloutLinePaint);
        float node = 3.5f;
        pixelBoxPaint.setStyle(Paint.Style.FILL);
        pixelBoxPaint.setColor(COL_ACCENT);
        canvas.drawRect(sx - node, sy - node, sx + node, sy + node, pixelBoxPaint);
    }

    // The collected-item bullet dot in drawEquipColumn needs a small filled
    // accent-colored circle - cached here instead of allocating a new Paint
    // every frame.
    private Paint cachedFillAccentPaint;
    private Paint slotSelectedBorderPaintFilled() {
        if (cachedFillAccentPaint == null) {
            cachedFillAccentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cachedFillAccentPaint.setColor(COL_ACCENT);
            cachedFillAccentPaint.setStyle(Paint.Style.FILL);
        }
        return cachedFillAccentPaint;
    }

    // Weapon-select screen: tap Missile/Super Missile/Power Bomb to make
    // it the actively-armed ammo (GameState.setSelectedAmmo writes
    // hud_item_index directly - the same real effect as pressing Select on
    // the controller until that slot is reached). The currently-armed slot
    // is highlighted; slots with zero ammo capacity (not yet collected)
    // are dimmed and not selectable, matching the real Select-button
    // behavior of skipping unowned ammo types.
    private void drawAmmoTab(Canvas canvas) {
        float pad = panelRect.width() * 0.04f;
        float top = panelRect.top + pad, bottom = panelRect.bottom - pad;
        float left = panelRect.left + pad, right = panelRect.right - pad;
        float gap = pad * 0.6f;
        float slotW = (right - left - gap * 2) / 3f;

        int[] counts = { GameState.getMissiles(), GameState.getSuperMissiles(), GameState.getPowerBombs() };
        int[] maxCounts = { GameState.getMaxMissiles(), GameState.getMaxSuperMissiles(), GameState.getMaxPowerBombs() };
        int selected = GameState.getSelectedAmmo();

        if (!haveMissileIcon && GameState.renderMissileIcon(missileIconPixels)) {
            missileIconBitmap.setPixels(missileIconPixels, 0, 24, 0, 0, 24, 16);
            haveMissileIcon = true;
        }
        if (!haveSuperMissileIcon && GameState.renderSuperMissileIcon(superMissileIconPixels)) {
            superMissileIconBitmap.setPixels(superMissileIconPixels, 0, 16, 0, 0, 16, 16);
            haveSuperMissileIcon = true;
        }
        if (!havePowerBombIcon && GameState.renderPowerBombIcon(powerBombIconPixels)) {
            powerBombIconBitmap.setPixels(powerBombIconPixels, 0, 16, 0, 0, 16, 16);
            havePowerBombIcon = true;
        }
        Bitmap[] icons = { missileIconBitmap, superMissileIconBitmap, powerBombIconBitmap };
        boolean[] haveIcon = { haveMissileIcon, haveSuperMissileIcon, havePowerBombIcon };
        float[] iconAspect = { 24f / 16f, 1f, 1f };  // missile icon is 24x16, supers/PBs are 16x16

        for (int i = 0; i < 3; i++) {
            float sx0 = left + i * (slotW + gap);
            RectF slot = new RectF(sx0, top, sx0 + slotW, bottom);
            ammoSlotRects[i].set(slot);

            boolean owned = maxCounts[i] > 0;
            boolean isSelected = owned && selected == AMMO_SLOTS[i];
            int borderColor = isSelected ? COL_ACCENT : COL_BORDER_DARK;
            int fillColor = owned ? COL_SLOT_BG : Color.rgb(22, 24, 34);
            drawPixelBox(canvas, slot, fillColor, borderColor, COL_BORDER_HIGHLIGHT, false);

            if (haveIcon[i]) {
                float iconH = slot.height() * 0.3f, iconW = iconH * iconAspect[i];
                float iconLeft = slot.centerX() - iconW / 2f, iconTop = slot.top + slot.height() * 0.16f;
                RectF dest = new RectF(iconLeft, iconTop, iconLeft + iconW, iconTop + iconH);
                if (!owned) mapPaint.setAlpha(70);
                canvas.drawBitmap(icons[i], null, dest, mapPaint);
                if (!owned) mapPaint.setAlpha(255);
            }

            int labelColor = owned ? COL_TAB_LABEL : COL_DIM_GRAY;
            PixelFont.drawText(canvas, AMMO_LABELS[i], slot.centerX(), slot.top + slot.height() * 0.58f,
                    PixelFont.pixelSizeForHeight(slot.width() * 0.12f), labelColor, Paint.Align.CENTER);

            String countText = counts[i] + "/" + maxCounts[i];
            int countColor = owned ? Color.WHITE : COL_DIM_GRAY;
            PixelFont.drawText(canvas, countText, slot.centerX(), slot.bottom - slot.height() * 0.12f - slot.width() * 0.14f,
                    PixelFont.pixelSizeForHeight(slot.width() * 0.14f), countColor, Paint.Align.CENTER);
        }
    }

    private void drawMap(Canvas canvas, float left, float top, float right, float bottom) {
        GameState.getSamusMapTile(samusTile);
        // Sub-tile-precise position for smooth camera/marker motion -
        // samusTile alone only changes once every 256 room-pixels (one
        // whole map tile), which reads as the map "snapping" every couple
        // of seconds of walking rather than tracking Samus continuously.
        GameState.getSamusMapPosFixed(samusMapPosFixed);
        float samusSmoothX = samusMapPosFixed[0] / 256f;
        float samusSmoothY = samusMapPosFixed[1] / 256f;

        int area = GameState.getArea();
        frameCounter++;
        boolean areaChanged = area != cachedArea;
        if (areaChanged) {
            // A pan offset from the previous area's coordinate space is
            // meaningless once Samus has moved to a different area -
            // reset back to Samus-centered.
            panOffsetX = 0f;
            panOffsetY = 0f;
        }
        if (areaChanged || frameCounter % REFRESH_INTERVAL == 0) {
            if (GameState.renderAreaMap(area, mapPixels)) {
                tintAreaPixels(mapPixels, remapAreaForColor(area));
                mapBitmap.setPixels(mapPixels, 0, MAP_PX_W, 0, 0, MAP_PX_W, MAP_PX_H);
            }
            if (areaChanged && GameState.renderAreaLabel(area, labelPixels)) {
                labelBitmap.setPixels(labelPixels, 0, LABEL_PX_W, 0, 0, LABEL_PX_W, LABEL_PX_H);
                haveLabel = true;
            }
            if (GameState.decodeExploredGrid(exploredGrid)) {
                updateExploredBounds();
            }
            cachedArea = area;
        }

        // Auto-fit baseline: the explored-tile bbox (with margin, floored to
        // a minimum size) instead of the full 64x32 grid, so revealed rooms
        // fill the panel from the start rather than sitting as a speck in a
        // mostly-black void. zoomFactor then zooms in further from there.
        float fitCenterX = (exploredMinX + exploredMaxX) / 2f;
        float fitCenterY = (exploredMinY + exploredMaxY) / 2f;
        float fitTilesW = Math.max(MIN_AUTOFIT_TILES_W, (exploredMaxX - exploredMinX) * (1f + AUTOFIT_MARGIN_FRAC));
        float fitTilesH = Math.max(MIN_AUTOFIT_TILES_H, (exploredMaxY - exploredMinY) * (1f + AUTOFIT_MARGIN_FRAC));
        // Keep aspect ratio matching the grid so the map doesn't stretch.
        if (fitTilesW / fitTilesH > (float) GRID_W / GRID_H) {
            fitTilesH = fitTilesW * GRID_H / GRID_W;
        } else {
            fitTilesW = fitTilesH * GRID_W / GRID_H;
        }
        fitTilesW = Math.min(fitTilesW, GRID_W);
        fitTilesH = Math.min(fitTilesH, GRID_H);

        // Viewport: a zoomFactor-sized window within the auto-fit region,
        // centered on Samus (or the auto-fit center if her position isn't
        // valid yet), clamped so it never scrolls past the map edges. At
        // MIN_ZOOM this covers exactly the auto-fit region.
        int sx = samusTile[0], sy = samusTile[1];
        boolean samusOnGrid = sx >= 0 && sx < GRID_W && sy >= 0 && sy < GRID_H;
        float centerX = samusOnGrid ? samusSmoothX : fitCenterX;
        float centerY = samusOnGrid ? samusSmoothY : fitCenterY;

        // Manual drag pan, added on top of the Samus-centered baseline -
        // see panOffsetX/Y's field comment. Clamping happens naturally
        // below via srcLeft/srcTop's own clamp against the map bounds, so
        // an over-dragged pan just settles at the map edge.
        centerX += panOffsetX;
        centerY += panOffsetY;

        float viewTilesW = fitTilesW / zoomFactor;
        float viewTilesH = fitTilesH / zoomFactor;

        int srcW = clampInt(Math.round(viewTilesW * 8), 1, MAP_PX_W);
        int srcH = clampInt(Math.round(viewTilesH * 8), 1, MAP_PX_H);

        float vx0 = centerX * 8 - srcW / 2f;
        float vy0 = centerY * 8 - srcH / 2f;
        int srcLeft = clampInt(Math.round(vx0), 0, MAP_PX_W - srcW);
        int srcTop = clampInt(Math.round(vy0), 0, MAP_PX_H - srcH);
        int srcRight = srcLeft + srcW, srcBottom = srcTop + srcH;

        Rect src = new Rect(srcLeft, srcTop, srcRight, srcBottom);
        Rect dest = new Rect((int) left, (int) top, (int) right, (int) bottom);
        canvas.drawBitmap(mapBitmap, src, dest, mapPaint);

        float scaleX = (right - left) / (float) srcW;
        float scaleY = (bottom - top) / (float) srcH;
        float cellW = scaleX * 8, cellH = scaleY * 8;
        // Cache tiles-per-screen-pixel for onScroll to convert drag deltas
        // by (see tilesPerPixelX/Y's field comment).
        tilesPerPixelX = 1f / cellW;
        tilesPerPixelY = 1f / cellH;

        if (samusOnGrid) {
            float cx = left + (samusSmoothX * 8 - srcLeft) * scaleX;
            float cy = top + (samusSmoothY * 8 - srcTop) * scaleY;
            float radius = Math.min(cellW, cellH) * 0.55f;
            canvas.drawCircle(cx, cy, radius, samusDotPaint);
            canvas.drawCircle(cx, cy, radius, samusRingPaint);
        }

        if (haveLabel) drawAreaLabel(canvas, left, top, right, area);
    }

    // Real-texture mode (realTextureBtn toggled on): shows the CURRENT
    // room's real in-game background art (GameState.renderCurrentRoomArt - actual
    // walls/floor/decoration tiles) rather than the schematic pause-map
    // rectangles drawMap otherwise shows. Only re-decoded on room change,
    // not every frame - unlike the schematic map (cheap ROM-tile lookups),
    // this does a real decompress+composite pass over the whole room.
    // Samus's own position is drawn using her SUB-tile position within the
    // room (samus_x_pos/samus_y_pos, not just the map-tile-granularity
    // samusTile), since real room art has far more resolution than the
    // pause-map grid to place her against.
    private void drawRoomArt(Canvas canvas, float left, float top, float right, float bottom) {
        int room = GameState.getRoom();
        roomArtFrameCounter++;
        boolean roomChanged = room != roomArtCachedRoom;
        if (roomChanged || roomArtFrameCounter % REFRESH_INTERVAL == 0) {
            if (GameState.renderCurrentRoomArt(roomArtPixels, roomArtDims)) {
                roomArtPxW = Math.min(roomArtDims[0], GameState.ROOM_ART_MAX_W);
                roomArtPxH = Math.min(roomArtDims[1], GameState.ROOM_ART_MAX_H);
                if (roomArtBitmap == null || roomArtBitmap.getWidth() != roomArtPxW || roomArtBitmap.getHeight() != roomArtPxH) {
                    roomArtBitmap = Bitmap.createBitmap(roomArtPxW, roomArtPxH, Bitmap.Config.ARGB_8888);
                }
                // roomArtPixels is laid out with a fixed ROOM_ART_MAX_W
                // stride (see GameState.renderCurrentRoomArt's own doc) -
                // setPixels' own stride parameter handles that directly,
                // no need to repack into a tightly-packed array first.
                roomArtBitmap.setPixels(roomArtPixels, 0, GameState.ROOM_ART_MAX_W, 0, 0, roomArtPxW, roomArtPxH);
                haveRoomArt = true;
            } else {
                haveRoomArt = false;
            }
            roomArtCachedRoom = room;
            if (roomChanged) {
                // A pan offset from the previous room's own pixel space is
                // meaningless here - always start a freshly-entered room
                // centered on its own whole-room view. Only reset on an
                // actual room change, not every periodic refresh - otherwise
                // the camera would snap back to center every REFRESH_INTERVAL
                // frames even if the player had manually panned around.
                roomArtPanX = 0f;
                roomArtPanY = 0f;
                roomArtZoom = MIN_ROOM_ART_ZOOM;
            }
        }

        if (!haveRoomArt) {
            // Fall back out of real-texture mode rather than show nothing -
            // matches drawMap's own tolerance for the ROM not being ready.
            realTextureMode = false;
            return;
        }

        float availW = right - left, availH = bottom - top;
        // Fit the room to the panel using ONE shared scale on both axes
        // (no independent X/Y stretch, which produced visibly distorted
        // art - a room's real aspect ratio rarely matches the panel's).
        // MAX (not MIN) of the two ratios means: whichever axis is
        // narrower relative to the room gets fully covered with no
        // letterboxing, while the other axis shows a same-scale crop
        // rather than a squashed/stretched full view - exactly where
        // roomArtPanX/Y (drag-adjusted, reset on room change) becomes
        // useful, since that cropped axis is real room content you can
        // now pan across instead of seeing a distorted whole-room fit.
        // roomArtZoom (1.0 = whole room, matching MIN_ROOM_ART_ZOOM) zooms
        // in further on top of the whole-room fit - a bigger fitScale means
        // a SMALLER viewW/viewH (less source room shown per screen pixel),
        // so it divides rather than multiplies here.
        float fitScale = Math.max(availW / roomArtPxW, availH / roomArtPxH) * roomArtZoom;
        float viewW = availW / fitScale, viewH = availH / fitScale;

        // Center on Samus's own live position (matching drawMap's own
        // Samus-centered baseline), not the room's geometric center - a
        // static room-center baseline could put Samus outside the
        // cropped viewport entirely on whichever axis doesn't fully fit,
        // making her marker vanish rather than "follow" her. Falls back
        // to room-center only if her position isn't valid yet.
        // samus_x_pos/samus_y_pos are plain absolute room-pixel coordinates,
        // NOT fixed-point - confirmed by cross-referencing this decomp's own
        // enemy/actor code (sm_a3.c, sm_a5.c, sm_a6.c, sm_a8.c etc.), which
        // assigns samus_x_pos = E->base.x_pos interchangeably with real
        // actor x_pos fields (always plain room-pixel values used for
        // collision/drawing), and edge-wrap checks like
        // `(samus_x_pos & 0xF0) == 16` in sm_82.c's door-transition code,
        // which only make sense on a raw 0-255-ish pixel value. The earlier
        // /256*16 (=/16) conversion was based on a DIFFERENT call site
        // (sm_82.c:1274's pause-map cursor math, which intentionally right-
        // shifts to coarse MAP-TILE units for a different display) and
        // does not apply to in-room pixel position.
        int sxFullBaseline = GameState.getSamusX(), syFullBaseline = GameState.getSamusY();
        float samusPxXBaseline = sxFullBaseline, samusPxYBaseline = syFullBaseline;
        boolean samusValid = samusPxXBaseline >= 0 && samusPxXBaseline < roomArtPxW
                && samusPxYBaseline >= 0 && samusPxYBaseline < roomArtPxH;
        float baseCenterX = samusValid ? samusPxXBaseline : roomArtPxW / 2f;
        float baseCenterY = samusValid ? samusPxYBaseline : roomArtPxH / 2f;

        float centerPxX = baseCenterX + roomArtPanX;
        float centerPxY = baseCenterY + roomArtPanY;

        float vx0 = centerPxX - viewW / 2f, vy0 = centerPxY - viewH / 2f;
        int srcLeft = clampInt(Math.round(vx0), 0, Math.max(0, roomArtPxW - Math.round(viewW)));
        int srcTop = clampInt(Math.round(vy0), 0, Math.max(0, roomArtPxH - Math.round(viewH)));
        int srcW = clampInt(Math.round(viewW), 1, roomArtPxW);
        int srcH = clampInt(Math.round(viewH), 1, roomArtPxH);

        Rect src = new Rect(srcLeft, srcTop, srcLeft + srcW, srcTop + srcH);
        Rect dest = new Rect((int) left, (int) top, (int) right, (int) bottom);
        canvas.drawBitmap(roomArtBitmap, src, dest, mapPaint);

        float scaleX = (right - left) / (float) srcW;
        float scaleY = (bottom - top) / (float) srcH;
        // Cache tiles-per-screen-pixel for onScroll to convert drag deltas
        // by - reusing tilesPerPixelX/Y even though this view's "tiles" are
        // really just 8px units of real room pixels, matching drawMap's
        // own convention closely enough for onScroll's shared handling.
        tilesPerPixelX = 1f / (scaleX * 8);
        tilesPerPixelY = 1f / (scaleY * 8);

        int sxFull = GameState.getSamusX(), syFull = GameState.getSamusY();
        float samusPxX = sxFull;
        float samusPxY = syFull;
        if (samusPxX >= srcLeft && samusPxX < srcLeft + srcW && samusPxY >= srcTop && samusPxY < srcTop + srcH) {
            float cx = left + (samusPxX - srcLeft) * scaleX;
            float cy = top + (samusPxY - srcTop) * scaleY;
            float radius = Math.min(scaleX, scaleY) * 6f;
            canvas.drawCircle(cx, cy, radius, samusDotPaint);
            canvas.drawCircle(cx, cy, radius, samusRingPaint);
        }
    }

    // Recomputes exploredMinX/Y/MaxX/Y (half-open) from exploredGrid. Falls
    // back to the whole grid if nothing's explored yet (shouldn't normally
    // happen once gameplay starts, but keeps the auto-fit math sane if it
    // ever does).
    private void updateExploredBounds() {
        int[] b = computeBounds(exploredGrid);
        if (b[0] > b[2]) {
            exploredMinX = 0; exploredMinY = 0; exploredMaxX = GRID_W; exploredMaxY = GRID_H;
        } else {
            exploredMinX = b[0]; exploredMinY = b[1]; exploredMaxX = b[2]; exploredMaxY = b[3];
        }
    }

    // Scans a 64x32 (0/1)-per-tile grid and returns {minX, minY, maxX, maxY}
    // (half-open). If nothing's set, returns minX/minY > maxX/maxY (an
    // empty/invalid range) - callers decide their own fallback.
    private static int[] computeBounds(byte[] grid) {
        int minX = GRID_W, minY = GRID_H, maxX = 0, maxY = 0;
        for (int y = 0; y < GRID_H; y++) {
            int row = y * GRID_W;
            for (int x = 0; x < GRID_W; x++) {
                if (grid[row + x] != 0) {
                    if (x < minX) minX = x;
                    if (x + 1 > maxX) maxX = x + 1;
                    if (y < minY) minY = y;
                    if (y + 1 > maxY) maxY = y + 1;
                }
            }
        }
        return new int[] {minX, minY, maxX, maxY};
    }

    // Small +/- buttons in the bottom-right corner, an explicit alternative
    // to pinch-zoom for adjusting zoomFactor. Hit-tested in onTouchEvent.
    private void drawZoomButtons(Canvas canvas) {
        drawPixelBox(canvas, zoomInBtn, COL_PANEL_BG, COL_BORDER_DARK, COL_BORDER_HIGHLIGHT, true);
        drawPixelBox(canvas, zoomOutBtn, COL_PANEL_BG, COL_BORDER_DARK, COL_BORDER_HIGHLIGHT, true);

        float half = Math.min(zoomInBtn.width(), zoomInBtn.height()) * 0.28f;
        float cx1 = zoomInBtn.centerX(), cy1 = zoomInBtn.centerY();
        canvas.drawLine(cx1 - half, cy1, cx1 + half, cy1, zoomBtnIconPaint);
        canvas.drawLine(cx1, cy1 - half, cx1, cy1 + half, zoomBtnIconPaint);

        float cx2 = zoomOutBtn.centerX(), cy2 = zoomOutBtn.centerY();
        canvas.drawLine(cx2 - half, cy2, cx2 + half, cy2, zoomBtnIconPaint);
    }

    // Schematic-vs-real-texture mode toggle button - a small square icon
    // suggesting "photo/image" (a picture-frame rectangle) when real-
    // texture mode is available to switch INTO, filled solid (accent
    // color) when it's currently active, so the button's own look tells
    // you which mode you're in without needing a text label.
    private void drawRealTextureButton(Canvas canvas) {
        int fill = realTextureMode ? COL_TAB_ACTIVE_BG : COL_PANEL_BG;
        int border = realTextureMode ? COL_ACCENT : COL_BORDER_DARK;
        drawPixelBox(canvas, realTextureBtn, fill, border, COL_BORDER_HIGHLIGHT, true);
        float pad = realTextureBtn.width() * 0.28f;
        RectF icon = new RectF(realTextureBtn.left + pad, realTextureBtn.top + pad,
                realTextureBtn.right - pad, realTextureBtn.bottom - pad);
        canvas.drawRect(icon, zoomBtnIconPaint);
    }

    // Recenter/reset-camera button - a crosshair-in-a-box icon, snaps
    // roomArtPanX/Y back to 0 (Samus-centered) on tap. Only relevant (and
    // only drawn) in real-texture mode, since the schematic view already
    // has its own Samus-centered baseline with no separate reset needed.
    private void drawResetCameraButton(Canvas canvas) {
        drawPixelBox(canvas, resetCameraBtn, COL_PANEL_BG, COL_BORDER_DARK, COL_BORDER_HIGHLIGHT, true);
        float half = Math.min(resetCameraBtn.width(), resetCameraBtn.height()) * 0.22f;
        float cx = resetCameraBtn.centerX(), cy = resetCameraBtn.centerY();
        canvas.drawLine(cx - half, cy, cx + half, cy, zoomBtnIconPaint);
        canvas.drawLine(cx, cy - half, cx, cy + half, zoomBtnIconPaint);
        canvas.drawCircle(cx, cy, half * 1.5f, zoomBtnIconPaint);
    }

    private static int clampInt(int v, int lo, int hi) {
        if (hi < lo) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // Same as clampInt but for floats, and tolerant of lo > hi (returns
    // their midpoint instead of misbehaving) - used when the viewport
    // itself is wider than the canvas (e.g. early game with only one small
    // area explored so far), where a naive clamp would otherwise force
    // lo > hi.
    private static float clampFloat(float v, float lo, float hi) {
        if (hi < lo) return (lo + hi) / 2f;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // Ceres (6) and the debug area (7) aren't part of the 6-color world
    // palette - matches the native RemapArea's fallback to Crateria's data
    // for area 7 (see second_screen.c), and just reuses Crateria's color
    // for Ceres too since it's a one-off intro area with no map screen of
    // its own in vanilla SM.
    private static int remapAreaForColor(int area) {
        return (area < 0 || area >= 6) ? 0 : area;
    }

    // The flat fill color SM2_RenderAreaMap uses for unexplored tiles (see
    // kUnexploredColor in second_screen.c) - skipped by tintAreaPixels so
    // unexplored area stays a neutral, un-tinted background instead of
    // turning into a visible colored rectangle exactly at the explored
    // bbox's edges.
    private static final int UNEXPLORED_FILL = 0xFF14141E;

    // Tints the just-decoded map pixels (in place) with the given area's
    // accent color, so each area reads as its own distinct color on the map
    // (like the classic printed Zebes maps) instead of every area coming out
    // the same SNES blue/cyan. Multiplies each channel rather than replacing
    // it, so walls/floor/shading detail from the real ROM art is preserved -
    // only the hue shifts, brightness contrast stays intact. Unexplored-fill
    // pixels are left alone (see UNEXPLORED_FILL) so unexplored area reads
    // as neutral background, not a tinted box.
    private static void tintAreaPixels(int[] pixels, int area) {
        if (area < 0 || area >= WORLD_AREA_COLORS.length) return;
        int accent = WORLD_AREA_COLORS[area];
        float tr = Color.red(accent) / 255f, tg = Color.green(accent) / 255f, tb = Color.blue(accent) / 255f;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            if (p == UNEXPLORED_FILL) continue;
            int r = (int) (((p >> 16) & 0xFF) * tr);
            int g = (int) (((p >> 8) & 0xFF) * tg);
            int b = (int) ((p & 0xFF) * tb);
            pixels[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
    }

    // Draws the real ROM area-name graphic (e.g. "BRINSTAR") small and
    // translucent in the top-left corner of the map, instead of a big banner
    // across the top - just there to identify the area without covering any
    // of the actual room layout underneath it.
    private void drawAreaLabel(Canvas canvas, float left, float top, float right, int area) {
        float width = right - left;
        float labelW = width * 0.32f;
        float labelH = labelW * (LABEL_PX_H / (float) LABEL_PX_W);
        float margin = width * 0.035f;
        drawLabelBitmap(canvas, labelBitmap, left + margin + labelW / 2f, top + margin + labelH / 2f, labelW);
    }

    // Shared by the single-area corner label above and each world-view
    // area's label: scales a 96x8px area-name graphic to labelW wide
    // (preserving its 12:1 aspect ratio), centered on (centerX, centerY), no
    // background box - drawn directly over the map at reduced opacity so it
    // reads as a subtle identifier rather than covering the room layout.
    private void drawLabelBitmap(Canvas canvas, Bitmap label, float centerX, float centerY, float labelW) {
        float labelH = labelW * (LABEL_PX_H / (float) LABEL_PX_W);
        Rect dest = new Rect((int) (centerX - labelW / 2f), (int) (centerY - labelH / 2f),
                (int) (centerX + labelW / 2f), (int) (centerY + labelH / 2f));
        canvas.drawBitmap(label, null, dest, labelBitmapPaint);
    }

    private void enterWorldView() {
        worldView = true;
        worldFrameCounter = 0;
        panOffsetX = 0f;
        panOffsetY = 0f;
        worldZoomFactor = MIN_WORLD_ZOOM;
        worldPanOffsetX = 0f;
        worldPanOffsetY = 0f;
        int area = GameState.getArea();
        // Ceres (6) and the unused debug area (7) aren't part of the 6-area
        // world grid - start the round-robin from Crateria in that case.
        worldAreaCursor = (area >= 0 && area <= 5) ? area : 0;
    }

    // Keeps the world map fresh without spending a whole frame decoding
    // everything: one area is re-decoded every WORLD_REFRESH_STRIDE frames,
    // round-robin, and painted directly into the persistent
    // worldCompositeBitmap at its real position (only its actually-explored
    // pixels - unexplored tiles are left transparent so the shared dark
    // background shows through and previously-drawn neighboring areas or
    // connectors are never overwritten). Once a tile is drawn it stays
    // drawn - re-decoding the same area later just redraws its (now
    // possibly larger) explored region on top, so nothing already on the
    // composite is ever erased. Labels are cheap (12 tiles each) so all 6
    // are just decoded once, as soon as the ROM is available.
    private void ensureWorldAreaFresh() {
        for (int a = 0; a < 6; a++) {
            if (!haveWorldLabel[a] && GameState.renderAreaLabel(a, labelPixels)) {
                if (worldLabelBitmaps[a] == null) {
                    worldLabelBitmaps[a] = Bitmap.createBitmap(LABEL_PX_W, LABEL_PX_H, Bitmap.Config.ARGB_8888);
                }
                worldLabelBitmaps[a].setPixels(labelPixels, 0, LABEL_PX_W, 0, 0, LABEL_PX_W, LABEL_PX_H);
                haveWorldLabel[a] = true;
            }
        }

        worldFrameCounter++;
        if (worldFrameCounter % WORLD_REFRESH_STRIDE == 0) {
            int a = worldAreaCursor;
            worldAreaCursor = (worldAreaCursor + 1) % 6;
            if (GameState.areaHasAnyExploredTile(a) && GameState.renderAreaMap(a, mapPixels)) {
                tintAreaPixels(mapPixels, a);
                makeUnexploredTransparent(mapPixels);

                float[] l = WORLD_AREA_LAYOUT[a];
                int declMinX = (int) l[0], declMinY = (int) l[1], declMaxX = (int) l[2], declMaxY = (int) l[3];
                int srcPx0 = declMinX * 8, srcPy0 = declMinY * 8;
                int destPx0 = (int) (l[4] * 8), destPy0 = (int) (l[5] * 8);
                int w = (declMaxX - declMinX) * 8, h = (declMaxY - declMinY) * 8;

                // Manual per-pixel composite instead of a plain blit: area
                // bboxes routinely overlap (see WORLD_AREA_LAYOUT/
                // worldPixelOwner's comments), and each area gets redrawn on
                // its own round-robin turn - a plain drawBitmap would
                // repaint any overlap zone with whichever area's turn it
                // currently is, flickering between the two forever. The
                // first area to ever paint a given canvas pixel claims it
                // permanently in worldPixelOwner; later areas skip pixels
                // they don't own, so overlap zones settle instead of
                // fighting.
                for (int y = 0; y < h; y++) {
                    int destRow = (destPy0 + y) * WORLD_CANVAS_PX_W + destPx0;
                    int srcRow = (srcPy0 + y) * MAP_PX_W + srcPx0;
                    for (int x = 0; x < w; x++) {
                        int srcPixel = mapPixels[srcRow + x];
                        if ((srcPixel >>> 24) == 0) continue;  // transparent = unexplored
                        int destIdx = destRow + x;
                        byte owner = worldPixelOwner[destIdx];
                        // Smaller-box-wins tiebreak (see AREA_BBOX_TILES's
                        // comment) - only skip this pixel if it's already
                        // claimed by a different area with an equal or
                        // smaller declared box than ours; a larger area
                        // can still be preempted by a smaller one drawing
                        // later.
                        if (owner != -1 && owner != a && AREA_BBOX_TILES[a] >= AREA_BBOX_TILES[owner]) continue;
                        worldCompositePixels[destIdx] = srcPixel;
                        worldPixelOwner[destIdx] = (byte) a;
                    }
                }
                worldCompositeBitmap.setPixels(worldCompositePixels, 0, WORLD_CANVAS_PX_W, 0, 0, WORLD_CANVAS_PX_W, WORLD_CANVAS_PX_H);
                worldAreaDrawn[a] = true;

                // Bake in any connector whose both endpoints are now drawn,
                // right away - a junction only needs to be drawn once, ever.
                // WORLD_CONNECTORS row layout: {areaA, ax, ay, areaB, bx, by}.
                for (int i = 0; i < WORLD_CONNECTORS.length; i++) {
                    if (worldConnectorDrawn[i]) continue;
                    float[] c = WORLD_CONNECTORS[i];
                    int areaA = (int) c[0], areaB = (int) c[3];
                    if (worldAreaDrawn[areaA] && worldAreaDrawn[areaB]) {
                        float gap = (float) Math.hypot(c[1] - c[4], c[2] - c[5]);
                        if (gap <= CONNECTOR_MAX_GAP_TILES) {
                            drawWorldConnector(c);
                        }
                        worldConnectorDrawn[i] = true;
                    }
                }
            }
        }
    }

    // Sets every UNEXPLORED_FILL pixel's alpha to 0 (in place), so drawing
    // this area's decoded bitmap onto the shared composite only paints its
    // actually-explored tiles, leaving everything else (background, other
    // areas, connectors already drawn) untouched.
    private static void makeUnexploredTransparent(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            if ((pixels[i] & 0x00FFFFFF) == (UNEXPLORED_FILL & 0x00FFFFFF)) pixels[i] = 0;
        }
    }

    // The fully-zoomed-out view: one persistent, incrementally-updated
    // composite bitmap (worldCompositeBitmap - see ensureWorldAreaFresh)
    // covering all 6 areas at their real relative positions, panned/scaled
    // to frame whatever's been explored so far - a single drawBitmap call,
    // same as zelda3-android's own overworld minimap, rather than
    // repositioning/cropping separate per-area bitmaps every frame.
    private void drawWorldView(Canvas canvas, float left, float top, float right, float bottom) {
        GameState.getSamusMapTile(samusTile);
        GameState.getSamusMapPosFixed(samusMapPosFixed);
        float samusSmoothX = samusMapPosFixed[0] / 256f;
        float samusSmoothY = samusMapPosFixed[1] / 256f;
        ensureWorldAreaFresh();

        int currentArea = GameState.getArea();
        // -1 (no match) while in Ceres/debug, which aren't part of the world
        // map - Samus's marker just won't appear anywhere then.
        int remappedCurrent = (currentArea >= 0 && currentArea <= 5) ? currentArea : -1;

        float visMinX = Float.MAX_VALUE, visMinY = Float.MAX_VALUE;
        float visMaxX = -Float.MAX_VALUE, visMaxY = -Float.MAX_VALUE;
        for (int area = 0; area < 6; area++) {
            // Skip areas with zero real exploration progress, so a stray
            // leftover reveal (e.g. from an earlier save/session) can't show
            // an area the player hasn't actually set foot in this run.
            if (!GameState.areaHasAnyExploredTile(area)) continue;
            float[] l = WORLD_AREA_LAYOUT[area];
            visMinX = Math.min(visMinX, l[4]);
            visMinY = Math.min(visMinY, l[5]);
            visMaxX = Math.max(visMaxX, l[4] + (l[2] - l[0]));
            visMaxY = Math.max(visMaxY, l[5] + (l[3] - l[1]));
        }
        if (visMinX > visMaxX) return;  // nothing explored anywhere yet

        // Frame just the explored areas' combined extent, not the full
        // 6-area canvas - early on, with only one or two areas visible,
        // this fills the screen instead of leaving most of it black for
        // areas not reached yet. A small margin keeps edges from touching
        // the screen border.
        float margin = Math.max(visMaxX - visMinX, visMaxY - visMinY) * 0.06f;
        visMinX -= margin; visMinY -= margin; visMaxX += margin; visMaxY += margin;
        float fitCenterX = (visMinX + visMaxX) / 2f, fitCenterY = (visMinY + visMaxY) / 2f;
        float fitCanvasW = visMaxX - visMinX, fitCanvasH = visMaxY - visMinY;

        // worldZoomFactor shrinks the visible window around a pan-adjusted
        // center (mirrors drawMap's own zoomFactor/panOffsetX/Y handling
        // for the per-area view) - at MIN_WORLD_ZOOM this covers exactly
        // the auto-fit region above, same as before this feature existed.
        float centerX = fitCenterX + worldPanOffsetX;
        float centerY = fitCenterY + worldPanOffsetY;
        float canvasW = fitCanvasW / worldZoomFactor;
        float canvasH = fitCanvasH / worldZoomFactor;

        float availW = right - left, availH = bottom - top;
        float scale = Math.min(availW / canvasW, availH / canvasH);

        // Clamp the pan-adjusted center so the viewport can't scroll past
        // the shared canvas's real edges (mirrors drawMap's own srcLeft/Top
        // clamp against MAP_PX_W/H, just expressed as a center-point clamp
        // here since drawWorldView scales-to-fit rather than cropping a
        // source Rect).
        float halfW = canvasW / 2f, halfH = canvasH / 2f;
        centerX = clampFloat(centerX, halfW, WORLD_CANVAS_TILES_W - halfW);
        centerY = clampFloat(centerY, halfH, WORLD_CANVAS_TILES_H - halfH);
        float viewMinX = centerX - halfW, viewMinY = centerY - halfH;

        float originX = left + (availW - canvasW * scale) / 2f - viewMinX * scale;
        float originY = top + (availH - canvasH * scale) / 2f - viewMinY * scale;
        // Cache tiles-per-screen-pixel for onScroll to convert drag deltas
        // by (see worldTilesPerPixelX/Y's field comment).
        worldTilesPerPixelX = 1f / scale;
        worldTilesPerPixelY = 1f / scale;

        Rect src = new Rect(0, 0, WORLD_CANVAS_PX_W, WORLD_CANVAS_PX_H);
        Rect dest = new Rect((int) originX, (int) originY,
                (int) (originX + WORLD_CANVAS_TILES_W * scale), (int) (originY + WORLD_CANVAS_TILES_H * scale));
        canvas.drawBitmap(worldCompositeBitmap, src, dest, mapPaint);

        for (int area = 0; area < 6; area++) {
            if (!worldAreaDrawn[area] || !haveWorldLabel[area]) continue;
            float[] l = WORLD_AREA_LAYOUT[area];
            float dx0 = originX + l[4] * scale, dy0 = originY + l[5] * scale;
            float dx1 = dx0 + (l[2] - l[0]) * scale;
            float labelW = Math.min((dx1 - dx0) * 0.55f, (right - left) * 0.16f);
            float labelH = labelW * (LABEL_PX_H / (float) LABEL_PX_W);
            float labelMargin = (right - left) * 0.012f;
            drawLabelBitmap(canvas, worldLabelBitmaps[area],
                    dx0 + labelMargin + labelW / 2f, dy0 + labelMargin + labelH / 2f, labelW);
        }

        if (remappedCurrent >= 0) {
            int sx = samusTile[0], sy = samusTile[1];
            float[] l = WORLD_AREA_LAYOUT[remappedCurrent];
            int declMinX = (int) l[0], declMinY = (int) l[1], declMaxX = (int) l[2], declMaxY = (int) l[3];
            if (sx >= declMinX && sx < declMaxX && sy >= declMinY && sy < declMaxY) {
                float mx = originX + (l[4] + (samusSmoothX - declMinX)) * scale;
                float my = originY + (l[5] + (samusSmoothY - declMinY)) * scale;
                float radius = scale * 0.55f;
                canvas.drawCircle(mx, my, radius, samusDotPaint);
                canvas.drawCircle(mx, my, radius, samusRingPaint);
            }
        }
    }

    // Draws one connector bridge strip directly onto the persistent
    // composite bitmap, once, the moment both endpoint areas have been
    // drawn at least once (see ensureWorldAreaFresh) - so the composited
    // map reads as one connected Zebes instead of separate floating area
    // islands. Colored as a blend of both areas' accent tints so the
    // bridge itself hints at the transition between them.
    //
    // Draws a straight strip directly between the door's own two true
    // landing points (c = {areaA, ax, ay, areaB, bx, by}, both endpoints
    // already known exactly from the room/door graph extraction - see
    // WORLD_CONNECTORS's comment), rather than inferring a bridge axis/
    // position from which pixels happen to already be drawn. This is
    // correct by construction for every connection regardless of gap size
    // or direction, unlike a pixel-scan search which can miss areas placed
    // diagonally or farther apart than its search radius.
    // Drawn as a filled tube with a brighter outline (dark fill + light
    // border, same two-tone look the real SM map tiles use for corridors)
    // rather than a flat single-color line, so it reads as an actual
    // passage connecting the two areas instead of a debug/annotation
    // stroke. Width matches CONNECTOR_HALF_WIDTH*2 in room-tile scale.
    private static final float CONNECTOR_HALF_WIDTH = 5f;

    // Several WORLD_CONNECTORS rows are secondary/duplicate doors between
    // two areas already linked elsewhere in the table by an exact (zero-
    // gap) connection under the BFS spanning-tree layout - their own two
    // endpoints can land several tiles apart despite being real doors,
    // since only one connection per area pair got to anchor that area's
    // position. Drawn as a long straight bridge across mostly-unrelated
    // canvas space, those read as a visibly wrong stray line rather than a
    // corridor - better to omit them than fake a connection the layout
    // doesn't actually support visually.
    private static final float CONNECTOR_MAX_GAP_TILES = 3f;

    // Rasterizes directly into worldCompositePixels (the same backing array
    // ensureWorldAreaFresh's per-pixel composite writes into) rather than
    // drawing via Canvas onto worldCompositeBitmap - keeping every write to
    // the composite on one path means there's no way for the two to fall
    // out of sync. If this drew via Canvas instead, a later area's redraw
    // (ensureWorldAreaFresh re-pushing worldCompositePixels wholesale via
    // setPixels) would silently erase whatever Canvas.drawLine had painted,
    // since the array never learned about those pixels.
    private void drawWorldConnector(float[] c) {
        int areaA = (int) c[0], areaB = (int) c[3];
        float ax = c[1] * 8, ay = c[2] * 8;
        float bx = c[4] * 8, by = c[5] * 8;
        int blend = blendColors(WORLD_AREA_COLORS[areaA], WORLD_AREA_COLORS[areaB]);

        // Dark fill first (slightly darker than the blended accent, matching
        // real room interiors), then a bright outline stroke on top so the
        // corridor has the same "dark floor, lit border" read as actual
        // decoded map rooms - a flat line by contrast looks like a UI
        // annotation, not part of the level geometry.
        int fillColor = darken(blend, 0.35f) | 0xFF000000;
        int borderColor = darken(blend, 0.6f) | 0xFF000000;
        rasterizeThickLine(ax, ay, bx, by, CONNECTOR_HALF_WIDTH, fillColor);
        // Border inset is a fixed ~0.7px trim (matching the thin wall
        // border seen in real decoded room art, roughly 1 source pixel
        // relative to typical room width - not proportional to
        // CONNECTOR_HALF_WIDTH, which previously made the border read as a
        // thick outline rather than a wall) rather than the connector's own
        // half-width, so it stays a thin trim regardless of tube width.
        rasterizeThickLine(ax, ay, bx, by, CONNECTOR_HALF_WIDTH - 0.7f, borderColor);
        worldCompositeBitmap.setPixels(worldCompositePixels, 0, WORLD_CANVAS_PX_W, 0, 0, WORLD_CANVAS_PX_W, WORLD_CANVAS_PX_H);
    }

    // Fills every pixel within `halfWidth` of the segment (ax,ay)-(bx,by)
    // with `color`, directly into worldCompositePixels - a plain-array
    // equivalent of Canvas.drawLine(..., Paint.Cap.ROUND) with a solid
    // stroke, scanning the segment's bounding box and testing each pixel's
    // distance to the segment (point-to-segment distance via clamped
    // projection). Connector pixels aren't tracked in worldPixelOwner since
    // they're not owned by either endpoint area - they're always redrawn
    // fresh each time this is called (once per connector, ever, since
    // ensureWorldAreaFresh only calls this the first time both endpoint
    // areas are drawn).
    private void rasterizeThickLine(float ax, float ay, float bx, float by, float halfWidth, int color) {
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, bx) - halfWidth));
        int maxX = Math.min(WORLD_CANVAS_PX_W - 1, (int) Math.ceil(Math.max(ax, bx) + halfWidth));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, by) - halfWidth));
        int maxY = Math.min(WORLD_CANVAS_PX_H - 1, (int) Math.ceil(Math.max(ay, by) + halfWidth));

        float dx = bx - ax, dy = by - ay;
        float lenSq = dx * dx + dy * dy;
        float hw2 = halfWidth * halfWidth;

        for (int y = minY; y <= maxY; y++) {
            int row = y * WORLD_CANVAS_PX_W;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float t = lenSq > 0 ? ((px - ax) * dx + (py - ay) * dy) / lenSq : 0f;
                t = Math.max(0f, Math.min(1f, t));
                float cx = ax + t * dx, cy = ay + t * dy;
                float ddx = px - cx, ddy = py - cy;
                if (ddx * ddx + ddy * ddy <= hw2) {
                    worldCompositePixels[row + x] = color;
                }
            }
        }
    }

    // Multiplies each RGB channel by `factor` (0..1), darkening a color
    // while preserving its hue - used to derive the corridor's dark
    // interior fill and mid-tone border from the same blended accent color.
    private static int darken(int color, float factor) {
        int r = (int) (Color.red(color) * factor);
        int g = (int) (Color.green(color) * factor);
        int b = (int) (Color.blue(color) * factor);
        return Color.rgb(r, g, b);
    }

    // Simple 50/50 RGB average of two area accent colors, for the bridge
    // strip's color at each junction.
    private static int blendColors(int a, int b) {
        int r = (Color.red(a) + Color.red(b)) / 2;
        int g = (Color.green(a) + Color.green(b)) / 2;
        int bl = (Color.blue(a) + Color.blue(b)) / 2;
        return Color.rgb(r, g, bl);
    }

    // Dims the whole screen down to near-black and shows a faint, dimmed
    // Metroid wordmark, for menus/pause/cutscenes/death/demo - anything
    // where the main screen already has its own thing going on and this
    // panel isn't actually being looked at, so it should read as "off",
    // not draw attention with a bright logo. Mirrors the zelda3-android
    // dual-screen mod's title/cutscene treatment (Triforce logo over a
    // dark screen), but dimmed rather than bright since this panel is idle.
    private void drawDimOverlay(Canvas canvas, int w, int h) {
        dimPaint.setAlpha(248);
        canvas.drawRect(0, 0, w, h, dimPaint);

        float cx = w / 2f, cy = h / 2f;
        float textHeight = w * 0.11f;
        logoLinePaint.setAlpha(70);
        int fadedAccent = Color.argb(70, Color.red(COL_ACCENT), Color.green(COL_ACCENT), Color.blue(COL_ACCENT));
        float textWidth = PixelFont.measureWidth(LOGO_TEXT, PixelFont.pixelSizeForHeight(textHeight));
        float lineHalf = textWidth * 0.55f;
        float lineY1 = cy - textHeight * 0.9f;
        float lineY2 = cy + textHeight * 0.7f;
        canvas.drawLine(cx - lineHalf, lineY1, cx + lineHalf, lineY1, logoLinePaint);
        canvas.drawLine(cx - lineHalf, lineY2, cx + lineHalf, lineY2, logoLinePaint);
        PixelFont.drawText(canvas, LOGO_TEXT, cx, cy - textHeight / 2f,
                PixelFont.pixelSizeForHeight(textHeight), fadedAccent, Paint.Align.CENTER);
    }
}
