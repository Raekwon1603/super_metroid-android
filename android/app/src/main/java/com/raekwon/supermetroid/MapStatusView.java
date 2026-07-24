package com.raekwon.supermetroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;

// The entire second-screen content: a full-screen, live Zebes-area minimap -
// real SNES map tile graphics decoded from the ROM (SM2_RenderAreaMap in
// second_screen.c), not a schematic placeholder - with the real in-game
// area-name label graphic (SM2_RenderAreaLabel) banner across the top.
// Health/ammo/items aren't duplicated here since they're already in the
// main screen's gameplay HUD. During menus, pause, cutscenes, death, or
// demo attract-mode (anything GameState.isPlayingLive() says isn't live
// gameplay) the map dims out behind a centered Metroid wordmark, matching
// the zelda3-android dual-screen mod's title/cutscene treatment. Redraws
// every frame via onDraw() + postInvalidateOnAnimation(), pulling fresh
// state from GameState (JNI) each time - no separate polling thread. The
// map bitmap itself is only re-decoded on an area change or every
// REFRESH_INTERVAL frames (to pick up newly explored tiles) rather than
// every frame, since decoding 2048 SNES tiles via JNI each frame would be
// wasteful.
public class MapStatusView extends View {
    private static final int GRID_W = 64, GRID_H = 32;
    private static final int MAP_PX_W = GRID_W * 8, MAP_PX_H = GRID_H * 8;
    private static final int LABEL_PX_W = 96, LABEL_PX_H = 8;
    private static final int REFRESH_INTERVAL = 20;

    private static final int COL_BG = Color.rgb(13, 15, 23);
    private static final int COL_ACCENT = Color.rgb(255, 158, 68);
    private static final int COL_CYAN = Color.rgb(86, 210, 232);
    private static final int COL_LABEL_BG = Color.rgb(13, 15, 23);

    private static final String LOGO_TEXT = "METROID";

    private final Paint bgPaint = new Paint();
    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roomFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roomBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint samusRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint samusDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBitmapPaint = new Paint();
    private final Paint dimPaint = new Paint();
    private final Paint logoTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int[] samusTile = new int[2];
    private final int[] roomRect = new int[4];

    private final int[] mapPixels = new int[MAP_PX_W * MAP_PX_H];
    private final Bitmap mapBitmap;
    private final int[] labelPixels = new int[LABEL_PX_W * LABEL_PX_H];
    private final Bitmap labelBitmap;
    private boolean haveLabel = false;
    private int cachedArea = -1;
    private int frameCounter = 0;

    private boolean nativeBroken = false;

    public MapStatusView(Context context) {
        super(context);

        bgPaint.setColor(COL_BG);

        roomFillPaint.setColor(COL_CYAN);
        roomFillPaint.setAlpha(40);
        roomBorderPaint.setColor(COL_CYAN);
        roomBorderPaint.setStyle(Paint.Style.STROKE);
        roomBorderPaint.setStrokeWidth(3);

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

        mapBitmap = Bitmap.createBitmap(MAP_PX_W, MAP_PX_H, Bitmap.Config.ARGB_8888);
        labelBitmap = Bitmap.createBitmap(LABEL_PX_W, LABEL_PX_H, Bitmap.Config.ARGB_8888);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, bgPaint);

        if (!nativeBroken) {
            try {
                drawMap(canvas, 0, 0, w, h);
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
        GameState.getRoomMapRect(roomRect);

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

        Rect dest = new Rect((int) left, (int) top, (int) right, (int) bottom);
        canvas.drawBitmap(mapBitmap, null, dest, mapPaint);

        float cellW = (right - left) / (float) GRID_W;
        float cellH = (bottom - top) / (float) GRID_H;

        int roomX = roomRect[0], roomY = roomRect[1], roomWBlocks = roomRect[2], roomHBlocks = roomRect[3];
        if (roomWBlocks > 0 && roomHBlocks > 0) {
            RectF roomR = new RectF(left + roomX * cellW, top + roomY * cellH,
                    left + (roomX + roomWBlocks) * cellW, top + (roomY + roomHBlocks) * cellH);
            float rr = Math.min(cellW, cellH) * 0.4f;
            canvas.drawRoundRect(roomR, rr, rr, roomFillPaint);
            canvas.drawRoundRect(roomR, rr, rr, roomBorderPaint);
        }

        int sx = samusTile[0], sy = samusTile[1];
        if (sx >= 0 && sx < GRID_W && sy >= 0 && sy < GRID_H) {
            float cx = left + (sx + 0.5f) * cellW, cy = top + (sy + 0.5f) * cellH;
            float radius = Math.min(cellW, cellH) * 0.55f;
            canvas.drawCircle(cx, cy, radius, samusDotPaint);
            canvas.drawCircle(cx, cy, radius, samusRingPaint);
        }

        if (haveLabel) drawAreaLabel(canvas, left, top, right);
    }

    // Draws the real ROM area-name graphic (e.g. "BRINSTAR") as a banner
    // across the top of the map, scaled up from its native 96x8px while
    // keeping its 12:1 aspect ratio, over a translucent backdrop so it stays
    // legible against whatever's underneath on the map.
    private void drawAreaLabel(Canvas canvas, float left, float top, float right) {
        float width = right - left;
        float labelW = width * 0.8f;
        float labelH = labelW * (LABEL_PX_H / (float) LABEL_PX_W);
        float maxH = width * 0.075f;
        if (labelH > maxH) {
            labelH = maxH;
            labelW = labelH * (LABEL_PX_W / (float) LABEL_PX_H);
        }

        float cx = (left + right) / 2f;
        float bx0 = cx - labelW / 2f, bx1 = cx + labelW / 2f;
        float margin = width * 0.04f;
        float by0 = top + margin, by1 = by0 + labelH;

        float padX = labelH * 0.7f, padY = labelH * 0.4f;
        RectF backdrop = new RectF(bx0 - padX, by0 - padY, bx1 + padX, by1 + padY);
        float r = labelH * 0.35f;
        canvas.drawRoundRect(backdrop, r, r, labelBgPaint);

        Rect dest = new Rect((int) bx0, (int) by0, (int) bx1, (int) by1);
        canvas.drawBitmap(labelBitmap, null, dest, labelBitmapPaint);
    }

    // Dims the whole screen and shows a centered Metroid wordmark, for
    // menus/pause/cutscenes/death/demo - anything where the main screen
    // already has its own thing going on, so a stale map here would just be
    // clutter. Mirrors the zelda3-android dual-screen mod's title/cutscene
    // treatment (Triforce logo over a dark screen).
    private void drawDimOverlay(Canvas canvas, int w, int h) {
        dimPaint.setAlpha(215);
        canvas.drawRect(0, 0, w, h, dimPaint);

        float cx = w / 2f, cy = h / 2f;
        logoTextPaint.setTextSize(w * 0.11f);
        float textWidth = logoTextPaint.measureText(LOGO_TEXT);
        float lineHalf = textWidth * 0.55f;
        float lineY1 = cy - logoTextPaint.getTextSize() * 0.9f;
        float lineY2 = cy + logoTextPaint.getTextSize() * 0.7f;
        canvas.drawLine(cx - lineHalf, lineY1, cx + lineHalf, lineY1, logoLinePaint);
        canvas.drawLine(cx - lineHalf, lineY2, cx + lineHalf, lineY2, logoLinePaint);
        canvas.drawText(LOGO_TEXT, cx, cy + logoTextPaint.getTextSize() * 0.32f, logoTextPaint);
    }
}
