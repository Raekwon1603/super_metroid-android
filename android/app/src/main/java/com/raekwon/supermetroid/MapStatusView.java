package com.raekwon.supermetroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
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

    // zoomFactor 1 = whole 64x32 area visible, filling the screen just like
    // the in-game pause map does - that's the default. Higher = fewer tiles
    // visible, each drawn bigger, for zooming in on the room Samus is in;
    // pinch, double-tap-to-reset, or the on-screen +/- buttons all adjust it.
    // Zooming out past MIN_ZOOM instead flips into worldView (see below).
    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 6f;
    private static final float DEFAULT_ZOOM = MIN_ZOOM;
    private static final float ZOOM_BUTTON_STEP = 1.4f;
    private float zoomFactor = DEFAULT_ZOOM;

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
    // These aren't guessed: derived by walking this ROM's actual room/door
    // graph (RoomDefHeader.area_index_/x_coordinate_on_map/
    // y_coordinate_on_map and each room's door-out list, starting from
    // kLoadStationLists' seed room per area) to find the real inter-area
    // doors - preferring, per area pair, whichever candidate door has the
    // most axis-dominant center-to-center delta (the cleanest elevator/
    // corridor-style connection over an ambiguous diagonal one) - then
    // nudging each area away from its parent (in the resulting connectivity
    // tree, rooted at Crateria) ONLY along that connection's dominant axis,
    // via binary search for the minimal push that clears every already-
    // placed area's bbox. Pushing only the dominant axis keeps the other
    // axis exactly door-aligned - e.g. Crateria's elevator down into
    // Brinstar stays lined up vertically - while real Zebes geometry's
    // overlapping "depth" still gets resolved without a gap.
    private static final float[][] WORLD_AREA_LAYOUT = {
            {6, 0, 46, 19, 2.50f, 13.00f},    // Crateria
            {5, 0, 58, 20, 0.00f, 32.00f},    // Brinstar
            {2, 0, 38, 18, 53.00f, 47.50f},   // Norfair
            {12, 10, 22, 20, 42.50f, 12.50f}, // Wrecked Ship
            {10, 0, 43, 20, 53.00f, 20.50f},  // Maridia
            {11, 9, 21, 22, 7.50f, 0.00f},    // Tourian
    };

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

    private static final int WORLD_REFRESH_STRIDE = 4;
    private boolean worldView = false;

    private static final int COL_BG = Color.rgb(13, 15, 23);
    private static final int COL_ACCENT = Color.rgb(255, 158, 68);
    private static final int COL_LABEL_BG = Color.rgb(13, 15, 23);

    private static final String LOGO_TEXT = "METROID";

    private final Paint bgPaint = new Paint();
    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint samusRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint samusDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBitmapPaint = new Paint();
    private final Paint dimPaint = new Paint();
    private final Paint logoTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomBtnIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF zoomInBtn = new RectF();
    private final RectF zoomOutBtn = new RectF();
    private int zoomButtonPointerId = -1;
    private boolean zoomButtonIsIn;

    private final int[] samusTile = new int[2];

    private final int[] mapPixels = new int[MAP_PX_W * MAP_PX_H];
    private final Bitmap mapBitmap;
    private final int[] labelPixels = new int[LABEL_PX_W * LABEL_PX_H];
    private final Bitmap labelBitmap;
    private boolean haveLabel = false;
    private int cachedArea = -1;
    private int frameCounter = 0;

    // World-view (zoomed all the way out) state: one cached bitmap/label per
    // area, refreshed a single area at a time (round-robin) while the world
    // view is showing, rather than re-decoding all 6 areas every frame.
    private final Bitmap[] worldAreaBitmaps = new Bitmap[6];
    private final Bitmap[] worldLabelBitmaps = new Bitmap[6];
    private final boolean[] haveWorldLabel = new boolean[6];
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
                if (worldView) {
                    if (detector.getScaleFactor() > 1f) {
                        worldView = false;
                        zoomFactor = MIN_ZOOM;
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
                worldView = false;
                zoomFactor = DEFAULT_ZOOM;
                return true;
            }
        });

        bgPaint.setColor(COL_BG);

        labelBorderPaint.setStyle(Paint.Style.STROKE);
        labelBorderPaint.setStrokeWidth(2.5f);

        samusRingPaint.setColor(Color.WHITE);
        samusRingPaint.setStyle(Paint.Style.STROKE);
        samusRingPaint.setStrokeWidth(2.5f);
        samusDotPaint.setColor(Color.rgb(255, 70, 70));

        labelBgPaint.setColor(COL_LABEL_BG);
        labelBgPaint.setAlpha(210);
        labelBitmapPaint.setFilterBitmap(false);  // crisp pixel-art scaling, no blur

        dimPaint.setColor(Color.BLACK);

        logoTextPaint.setColor(COL_ACCENT);
        logoTextPaint.setFakeBoldText(true);
        logoTextPaint.setTextAlign(Paint.Align.CENTER);
        if (Build.VERSION.SDK_INT >= 21) {
            logoTextPaint.setLetterSpacing(0.18f);
        }
        logoLinePaint.setColor(COL_ACCENT);
        logoLinePaint.setStrokeWidth(3);

        zoomBtnBgPaint.setColor(Color.BLACK);
        zoomBtnBgPaint.setAlpha(140);
        zoomBtnIconPaint.setColor(Color.WHITE);
        zoomBtnIconPaint.setStrokeWidth(4);
        zoomBtnIconPaint.setStrokeCap(Paint.Cap.ROUND);

        // The map bitmap is native 8px/tile SNES art scaled way up on
        // screen - bilinear-filter it (unlike the label/room graphics) so
        // it reads as a smooth grid like the in-game pause map instead of
        // going visibly blocky at higher zoom levels.
        mapPaint.setFilterBitmap(true);

        mapBitmap = Bitmap.createBitmap(MAP_PX_W, MAP_PX_H, Bitmap.Config.ARGB_8888);
        labelBitmap = Bitmap.createBitmap(LABEL_PX_W, LABEL_PX_H, Bitmap.Config.ARGB_8888);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h) * 0.11f;
        float margin = Math.min(w, h) * 0.03f;
        float right = w - margin, bottom = h - margin;
        zoomOutBtn.set(right - size, bottom - size, right, bottom);
        zoomInBtn.set(right - size, bottom - size * 2 - margin * 0.6f, right, bottom - size - margin * 0.6f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float x = event.getX(), y = event.getY();
            if (zoomInBtn.contains(x, y) || zoomOutBtn.contains(x, y)) {
                zoomButtonPointerId = event.getPointerId(0);
                zoomButtonIsIn = zoomInBtn.contains(x, y);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP && zoomButtonPointerId != -1) {
            RectF btn = zoomButtonIsIn ? zoomInBtn : zoomOutBtn;
            if (btn.contains(event.getX(), event.getY())) {
                if (zoomButtonIsIn) {
                    if (worldView) {
                        worldView = false;
                        zoomFactor = MIN_ZOOM;
                    } else {
                        zoomFactor = Math.min(MAX_ZOOM, zoomFactor * ZOOM_BUTTON_STEP);
                    }
                } else if (!worldView) {
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
        }

        if (zoomButtonPointerId == -1) {
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
                if (worldView) {
                    drawWorldView(canvas, 0, 0, w, h);
                } else {
                    drawMap(canvas, 0, 0, w, h);
                }
                drawZoomButtons(canvas);
                if (!GameState.isPlayingLive()) {
                    drawDimOverlay(canvas, w, h);
                }
            } catch (UnsatisfiedLinkError e) {
                nativeBroken = true;
            }
        }

        if (isAttachedToWindow()) postInvalidateOnAnimation();
    }

    private void drawMap(Canvas canvas, float left, float top, float right, float bottom) {
        GameState.getSamusMapTile(samusTile);

        int area = GameState.getArea();
        frameCounter++;
        boolean areaChanged = area != cachedArea;
        if (areaChanged || frameCounter % REFRESH_INTERVAL == 0) {
            if (GameState.renderAreaMap(area, mapPixels)) {
                mapBitmap.setPixels(mapPixels, 0, MAP_PX_W, 0, 0, MAP_PX_W, MAP_PX_H);
            }
            if (areaChanged && GameState.renderAreaLabel(area, labelPixels)) {
                labelBitmap.setPixels(labelPixels, 0, LABEL_PX_W, 0, 0, LABEL_PX_W, LABEL_PX_H);
                haveLabel = true;
            }
            cachedArea = area;
        }

        // Viewport: a zoomFactor-sized window of the 64x32 grid, centered on
        // Samus (or the grid center if her position isn't valid yet),
        // clamped so it never scrolls past the map edges. At MIN_ZOOM this
        // covers the whole grid, matching the old fixed full-map behavior.
        int sx = samusTile[0], sy = samusTile[1];
        float centerX = (sx >= 0 && sx < GRID_W) ? sx + 0.5f : GRID_W / 2f;
        float centerY = (sy >= 0 && sy < GRID_H) ? sy + 0.5f : GRID_H / 2f;

        float viewTilesW = GRID_W / zoomFactor;
        float viewTilesH = GRID_H / zoomFactor;

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

        if (sx >= 0 && sx < GRID_W && sy >= 0 && sy < GRID_H) {
            float cx = left + ((sx + 0.5f) * 8 - srcLeft) * scaleX;
            float cy = top + ((sy + 0.5f) * 8 - srcTop) * scaleY;
            float radius = Math.min(cellW, cellH) * 0.55f;
            canvas.drawCircle(cx, cy, radius, samusDotPaint);
            canvas.drawCircle(cx, cy, radius, samusRingPaint);
        }

        if (haveLabel) drawAreaLabel(canvas, left, top, right, area);
    }

    // Small +/- buttons in the bottom-right corner, an explicit alternative
    // to pinch-zoom for adjusting zoomFactor. Hit-tested in onTouchEvent.
    private void drawZoomButtons(Canvas canvas) {
        float r = Math.min(zoomInBtn.width(), zoomInBtn.height()) * 0.3f;
        canvas.drawRoundRect(zoomInBtn, r, r, zoomBtnBgPaint);
        canvas.drawRoundRect(zoomOutBtn, r, r, zoomBtnBgPaint);

        float half = Math.min(zoomInBtn.width(), zoomInBtn.height()) * 0.28f;
        float cx1 = zoomInBtn.centerX(), cy1 = zoomInBtn.centerY();
        canvas.drawLine(cx1 - half, cy1, cx1 + half, cy1, zoomBtnIconPaint);
        canvas.drawLine(cx1, cy1 - half, cx1, cy1 + half, zoomBtnIconPaint);

        float cx2 = zoomOutBtn.centerX(), cy2 = zoomOutBtn.centerY();
        canvas.drawLine(cx2 - half, cy2, cx2 + half, cy2, zoomBtnIconPaint);
    }

    private static int clampInt(int v, int lo, int hi) {
        if (hi < lo) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // Draws the real ROM area-name graphic (e.g. "BRINSTAR") as a banner
    // across the top of the map, scaled up from its native 96x8px while
    // keeping its 12:1 aspect ratio, over a translucent backdrop so it stays
    // legible against whatever's underneath on the map. Outlined in the same
    // per-area accent color as the world view, so the area's color identity
    // carries through when zooming in instead of only showing up zoomed out.
    private void drawAreaLabel(Canvas canvas, float left, float top, float right, int area) {
        float width = right - left;
        float maxH = width * 0.075f;
        float labelH = Math.min(width * 0.8f * (LABEL_PX_H / (float) LABEL_PX_W), maxH);
        float margin = width * 0.04f;
        int accent = (area >= 0 && area < WORLD_AREA_COLORS.length) ? WORLD_AREA_COLORS[area] : 0;
        drawLabelBitmap(canvas, labelBitmap, (left + right) / 2f, top + margin + labelH / 2f,
                width * 0.8f, maxH, accent);
    }

    // Shared by the single-area banner above and each world-view area's
    // centered label: scales a 96x8px area-name graphic to fit widthBudget
    // wide (clamped to maxHeight tall, preserving aspect), centered on
    // (centerX, centerY), over a translucent backdrop so it stays legible
    // against whatever's underneath. If accentColor is non-zero, outlines
    // the backdrop in that color so areas stay tellable apart by color, not
    // just position.
    private void drawLabelBitmap(Canvas canvas, Bitmap label, float centerX, float centerY,
                                  float widthBudget, float maxHeight, int accentColor) {
        float labelW = widthBudget;
        float labelH = labelW * (LABEL_PX_H / (float) LABEL_PX_W);
        if (labelH > maxHeight) {
            labelH = maxHeight;
            labelW = labelH * (LABEL_PX_W / (float) LABEL_PX_H);
        }

        float bx0 = centerX - labelW / 2f, bx1 = centerX + labelW / 2f;
        float by0 = centerY - labelH / 2f, by1 = centerY + labelH / 2f;

        float padX = labelH * 0.7f, padY = labelH * 0.4f;
        RectF backdrop = new RectF(bx0 - padX, by0 - padY, bx1 + padX, by1 + padY);
        float r = labelH * 0.35f;
        canvas.drawRoundRect(backdrop, r, r, labelBgPaint);
        if (accentColor != 0) {
            labelBorderPaint.setColor(accentColor);
            canvas.drawRoundRect(backdrop, r, r, labelBorderPaint);
        }

        Rect dest = new Rect((int) bx0, (int) by0, (int) bx1, (int) by1);
        canvas.drawBitmap(label, null, dest, labelBitmapPaint);
    }

    private void enterWorldView() {
        worldView = true;
        worldFrameCounter = 0;
        int area = GameState.getArea();
        // Ceres (6) and the unused debug area (7) aren't part of the 6-area
        // world grid - start the round-robin from Crateria in that case.
        worldAreaCursor = (area >= 0 && area <= 5) ? area : 0;
    }

    // Keeps the 6 area thumbnails reasonably fresh without spending a whole
    // frame decoding all of them: one area's map bitmap is re-decoded every
    // WORLD_REFRESH_STRIDE frames, round-robin, so the cost per frame stays
    // the same as the single-area view's. Labels are cheap (12 tiles each)
    // so all 6 are just decoded once, as soon as the ROM is available.
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
            if (GameState.renderAreaMap(a, mapPixels)) {
                if (worldAreaBitmaps[a] == null) {
                    worldAreaBitmaps[a] = Bitmap.createBitmap(MAP_PX_W, MAP_PX_H, Bitmap.Config.ARGB_8888);
                }
                worldAreaBitmaps[a].setPixels(mapPixels, 0, MAP_PX_W, 0, 0, MAP_PX_W, MAP_PX_H);
            }
        }
    }

    // The fully-zoomed-out view: all 6 areas composited into one shared
    // canvas at their real relative positions (WORLD_AREA_LAYOUT), each
    // showing that area's real map art cropped to its own explored-room
    // bounding box - same underlying SM2_RenderAreaMap data as the
    // single-area view, just positioned to form one connected map instead
    // of a grid of separate panels. Samus's marker only appears in
    // whichever area she's actually in.
    private void drawWorldView(Canvas canvas, float left, float top, float right, float bottom) {
        GameState.getSamusMapTile(samusTile);
        ensureWorldAreaFresh();

        int currentArea = GameState.getArea();
        // -1 (no match) while in Ceres/debug, which aren't part of the world
        // map - Samus's marker just won't appear anywhere then.
        int remappedCurrent = (currentArea >= 0 && currentArea <= 5) ? currentArea : -1;

        boolean[] visible = new boolean[6];
        float visMinX = Float.MAX_VALUE, visMinY = Float.MAX_VALUE;
        float visMaxX = -Float.MAX_VALUE, visMaxY = -Float.MAX_VALUE;
        for (int area = 0; area < 6; area++) {
            // Skip areas with zero real exploration progress, so a stray
            // leftover reveal (e.g. from an earlier save/session) can't show
            // an area the player hasn't actually set foot in this run.
            if (!GameState.areaHasAnyExploredTile(area)) continue;
            visible[area] = true;
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
        float canvasW = visMaxX - visMinX, canvasH = visMaxY - visMinY;

        float availW = right - left, availH = bottom - top;
        float scale = Math.min(availW / canvasW, availH / canvasH);
        float originX = left + (availW - canvasW * scale) / 2f - visMinX * scale;
        float originY = top + (availH - canvasH * scale) / 2f - visMinY * scale;

        for (int area = 0; area < 6; area++) {
            if (!visible[area]) continue;

            float[] l = WORLD_AREA_LAYOUT[area];
            int minX = (int) l[0], minY = (int) l[1], maxX = (int) l[2], maxY = (int) l[3];
            float canvasX = l[4], canvasY = l[5];

            float dx0 = originX + canvasX * scale, dy0 = originY + canvasY * scale;
            float dx1 = dx0 + (maxX - minX) * scale, dy1 = dy0 + (maxY - minY) * scale;

            if (worldAreaBitmaps[area] != null) {
                Rect src = new Rect(minX * 8, minY * 8, maxX * 8, maxY * 8);
                Rect dest = new Rect((int) dx0, (int) dy0, (int) dx1, (int) dy1);
                canvas.drawBitmap(worldAreaBitmaps[area], src, dest, mapPaint);
            }

            if (haveWorldLabel[area]) {
                drawLabelBitmap(canvas, worldLabelBitmaps[area], (dx0 + dx1) / 2f, (dy0 + dy1) / 2f,
                        (dx1 - dx0) * 0.42f, (dy1 - dy0) * 0.13f, WORLD_AREA_COLORS[area]);
            }

            if (area == remappedCurrent) {
                int sx = samusTile[0], sy = samusTile[1];
                if (sx >= minX && sx < maxX && sy >= minY && sy < maxY) {
                    float mx = originX + (canvasX + (sx - minX) + 0.5f) * scale;
                    float my = originY + (canvasY + (sy - minY) + 0.5f) * scale;
                    float radius = scale * 0.55f;
                    canvas.drawCircle(mx, my, radius, samusDotPaint);
                    canvas.drawCircle(mx, my, radius, samusRingPaint);
                }
            }
        }
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
        logoTextPaint.setTextSize(w * 0.11f);
        logoTextPaint.setAlpha(70);
        logoLinePaint.setAlpha(70);
        float textWidth = logoTextPaint.measureText(LOGO_TEXT);
        float lineHalf = textWidth * 0.55f;
        float lineY1 = cy - logoTextPaint.getTextSize() * 0.9f;
        float lineY2 = cy + logoTextPaint.getTextSize() * 0.7f;
        canvas.drawLine(cx - lineHalf, lineY1, cx + lineHalf, lineY1, logoLinePaint);
        canvas.drawLine(cx - lineHalf, lineY2, cx + lineHalf, lineY2, logoLinePaint);
        canvas.drawText(LOGO_TEXT, cx, cy + logoTextPaint.getTextSize() * 0.32f, logoTextPaint);
    }
}
