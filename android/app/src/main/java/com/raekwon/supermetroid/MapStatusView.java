package com.raekwon.supermetroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;

// The entire second-screen content: a live Zebes-area minimap - real SNES
// map tile graphics decoded from the ROM (SM2_RenderAreaMap in
// second_screen.c), not a schematic placeholder - on the left, and a
// read-only item/beam/ammo/health status panel on the right. Redraws every
// frame via onDraw() + postInvalidateOnAnimation(), pulling fresh state from
// GameState (JNI) each time - no separate polling thread, matching
// zelda3-android's MinimapView. The map bitmap itself is only re-decoded on
// an area change or every REFRESH_INTERVAL frames (to pick up newly
// explored tiles) rather than every frame, since decoding 2048 SNES tiles
// via JNI each frame would be wasteful.
public class MapStatusView extends View {
    private static final int GRID_W = 64, GRID_H = 32;
    private static final int MAP_PX_W = GRID_W * 8, MAP_PX_H = GRID_H * 8;
    private static final int REFRESH_INTERVAL = 20;

    // Palette - dark slate base with a warm "Samus HUD" amber accent for
    // active/equipped state and a cool cyan for the live position/room.
    private static final int COL_BG = Color.rgb(13, 15, 23);
    private static final int COL_CARD = Color.rgb(24, 27, 40);
    private static final int COL_CARD_BORDER = Color.rgb(40, 45, 64);
    private static final int COL_HEADER = Color.rgb(130, 138, 168);
    private static final int COL_TEXT = Color.rgb(230, 233, 242);
    private static final int COL_ACCENT = Color.rgb(255, 158, 68);
    private static final int COL_CYAN = Color.rgb(86, 210, 232);
    private static final int COL_DIM = Color.rgb(56, 61, 82);
    private static final int COL_DIM_TEXT = Color.rgb(90, 96, 120);

    private final Paint bgPaint = new Paint();
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roomFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roomBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint samusRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint samusDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barHpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barMidHpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barLowHpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int[] samusTile = new int[2];
    private final int[] roomRect = new int[4];

    private final int[] mapPixels = new int[MAP_PX_W * MAP_PX_H];
    private Bitmap mapBitmap;
    private int cachedArea = -1;
    private int frameCounter = 0;

    private boolean nativeBroken = false;

    private static final class Chip {
        final String label;
        final int bit;
        Chip(String label, int bit) { this.label = label; this.bit = bit; }
    }

    private static final Chip[] ITEM_CHIPS = {
        new Chip("VAR", 0x0001), new Chip("SPR", 0x0002), new Chip("MB", 0x0004),
        new Chip("SA", 0x0008), new Chip("GRA", 0x0020), new Chip("HJ", 0x0100),
        new Chip("SJ", 0x0200), new Chip("BOM", 0x1000), new Chip("SPD", 0x2000),
        new Chip("GRP", 0x4000), new Chip("XR", 0x8000),
    };
    private static final Chip[] BEAM_CHIPS = {
        new Chip("CHG", 0x1000), new Chip("ICE", 0x0002), new Chip("WAV", 0x0001),
        new Chip("SPZ", 0x0004), new Chip("PLM", 0x0008),
    };

    public MapStatusView(Context context) {
        super(context);

        bgPaint.setColor(COL_BG);

        cardPaint.setColor(COL_CARD);
        cardBorderPaint.setColor(COL_CARD_BORDER);
        cardBorderPaint.setStyle(Paint.Style.STROKE);
        cardBorderPaint.setStrokeWidth(2);

        roomFillPaint.setColor(COL_CYAN);
        roomFillPaint.setAlpha(40);
        roomBorderPaint.setColor(COL_CYAN);
        roomBorderPaint.setStyle(Paint.Style.STROKE);
        roomBorderPaint.setStrokeWidth(3);

        samusRingPaint.setColor(Color.WHITE);
        samusRingPaint.setStyle(Paint.Style.STROKE);
        samusRingPaint.setStrokeWidth(2.5f);
        samusDotPaint.setColor(Color.rgb(255, 70, 70));

        headerPaint.setColor(COL_HEADER);
        headerPaint.setFakeBoldText(true);
        textPaint.setColor(COL_TEXT);
        numberPaint.setColor(COL_TEXT);
        numberPaint.setFakeBoldText(true);
        headerPaint.setTextAlign(Paint.Align.LEFT);

        if (Build.VERSION.SDK_INT >= 21) {
            headerPaint.setLetterSpacing(0.12f);
        }


        barBgPaint.setColor(COL_DIM);
        barHpPaint.setColor(Color.rgb(70, 210, 120));
        barMidHpPaint.setColor(Color.rgb(240, 180, 60));
        barLowHpPaint.setColor(Color.rgb(230, 80, 80));

        mapBitmap = Bitmap.createBitmap(MAP_PX_W, MAP_PX_H, Bitmap.Config.ARGB_8888);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, bgPaint);

        if (!nativeBroken) {
            try {
                float pad = w * 0.02f;
                drawMapCard(canvas, pad, pad, w * 0.6f, h - pad);
                drawStatus(canvas, w * 0.6f + pad, pad, w - pad, h - pad);
            } catch (UnsatisfiedLinkError e) {
                nativeBroken = true;
            }
        }

        if (isAttachedToWindow()) postInvalidateOnAnimation();
    }

    private void drawMapCard(Canvas canvas, float left, float top, float right, float bottom) {
        float r = (right - left) * 0.03f;
        RectF cardRect = new RectF(left, top, right, bottom);
        canvas.drawRoundRect(cardRect, r, r, cardPaint);
        canvas.drawRoundRect(cardRect, r, r, cardBorderPaint);

        float inset = (right - left) * 0.015f;
        float mLeft = left + inset, mTop = top + inset, mRight = right - inset, mBottom = bottom - inset;
        float mr = r * 0.7f;

        GameState.getSamusMapTile(samusTile);
        GameState.getRoomMapRect(roomRect);

        int area = GameState.getArea();
        frameCounter++;
        if (area != cachedArea || frameCounter % REFRESH_INTERVAL == 0) {
            if (GameState.renderAreaMap(area, mapPixels)) {
                mapBitmap.setPixels(mapPixels, 0, MAP_PX_W, 0, 0, MAP_PX_W, MAP_PX_H);
            }
            cachedArea = area;
        }

        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(new RectF(mLeft, mTop, mRight, mBottom), mr, mr, Path.Direction.CW);
        canvas.clipPath(clip);

        Rect dest = new Rect((int) mLeft, (int) mTop, (int) mRight, (int) mBottom);
        canvas.drawBitmap(mapBitmap, null, dest, mapPaint);

        float cellW = (mRight - mLeft) / (float) GRID_W;
        float cellH = (mBottom - mTop) / (float) GRID_H;

        int roomX = roomRect[0], roomY = roomRect[1], roomWBlocks = roomRect[2], roomHBlocks = roomRect[3];
        if (roomWBlocks > 0 && roomHBlocks > 0) {
            RectF roomR = new RectF(mLeft + roomX * cellW, mTop + roomY * cellH,
                    mLeft + (roomX + roomWBlocks) * cellW, mTop + (roomY + roomHBlocks) * cellH);
            float rr = Math.min(cellW, cellH) * 0.4f;
            canvas.drawRoundRect(roomR, rr, rr, roomFillPaint);
            canvas.drawRoundRect(roomR, rr, rr, roomBorderPaint);
        }

        int sx = samusTile[0], sy = samusTile[1];
        if (sx >= 0 && sx < GRID_W && sy >= 0 && sy < GRID_H) {
            float cx = mLeft + (sx + 0.5f) * cellW, cy = mTop + (sy + 0.5f) * cellH;
            float radius = Math.min(cellW, cellH) * 0.55f;
            canvas.drawCircle(cx, cy, radius, samusDotPaint);
            canvas.drawCircle(cx, cy, radius, samusRingPaint);
        }

        canvas.restore();
    }

    private void drawStatus(Canvas canvas, float left, float top, float right, float bottom) {
        float unit = (right - left) * 0.09f;
        headerPaint.setTextSize(unit * 0.62f);
        textPaint.setTextSize(unit * 0.78f);
        numberPaint.setTextSize(unit * 0.78f);

        float y = top;
        float cardGap = unit * 0.5f;

        // Vitals card: energy + reserve bars.
        int hp = GameState.getHealth(), maxHp = GameState.getMaxHealth();
        int reserve = GameState.getReserveHealth(), maxReserve = GameState.getMaxReserveHealth();
        float vitalsH = unit * (maxReserve > 0 ? 3.6f : 2.3f);
        y = drawCardHeader(canvas, left, y, "VITALS");
        RectF vitalsCard = new RectF(left, y, right, y + vitalsH);
        drawCardBg(canvas, vitalsCard);
        float innerPad = unit * 0.35f;
        float by = y + innerPad + textPaint.getTextSize();
        canvas.drawText("ENERGY", left + innerPad, by, headerPaint);
        canvas.drawText(hp + " / " + maxHp, right - innerPad, by, rightAligned(numberPaint));
        by += unit * 0.18f;
        drawBar(canvas, left + innerPad, by, right - innerPad, by + unit * 0.35f,
                maxHp > 0 ? hp / (float) maxHp : 0, hpBarColor(hp, maxHp));
        if (maxReserve > 0) {
            by += unit * 0.75f;
            canvas.drawText("RESERVE", left + innerPad, by, headerPaint);
            canvas.drawText(reserve + " / " + maxReserve, right - innerPad, by, rightAligned(numberPaint));
            by += unit * 0.18f;
            drawBar(canvas, left + innerPad, by, right - innerPad, by + unit * 0.35f,
                    reserve / (float) maxReserve, barHpPaint);
        }
        y += vitalsH + cardGap;

        // Ammo card: missiles / supers / power bombs as compact bars. Height
        // must cover innerPad + 3 rows (each label+bar+gap) + bottom
        // innerPad - see drawAmmoRow's spacing constants.
        float ammoH = 2 * innerPad + textPaint.getTextSize() * 0.85f
                + 3 * (headerPaint.getTextSize() * 0.9f) + 2 * (headerPaint.getTextSize() * 1.1f);
        y = drawCardHeader(canvas, left, y, "AMMO");
        RectF ammoCard = new RectF(left, y, right, y + ammoH);
        drawCardBg(canvas, ammoCard);
        by = y + innerPad + textPaint.getTextSize() * 0.85f;
        by = drawAmmoRow(canvas, left + innerPad, right - innerPad, by, "MISSILES",
                GameState.getMissiles(), GameState.getMaxMissiles(), COL_ACCENT);
        by = drawAmmoRow(canvas, left + innerPad, right - innerPad, by, "SUPERS",
                GameState.getSuperMissiles(), GameState.getMaxSuperMissiles(), COL_CYAN);
        drawAmmoRow(canvas, left + innerPad, right - innerPad, by, "P.BOMBS",
                GameState.getPowerBombs(), GameState.getMaxPowerBombs(), Color.rgb(200, 120, 230));
        y += ammoH + cardGap;

        // Items / Beams cards: chip grids.
        y = drawCardHeader(canvas, left, y, "ITEMS");
        y = drawChipCard(canvas, left, y, right, ITEM_CHIPS, false, GameState.getCollectedItems(), GameState.getEquippedItems());
        y += cardGap;
        y = drawCardHeader(canvas, left, y, "BEAMS");
        drawChipCard(canvas, left, y, right, BEAM_CHIPS, true, GameState.getCollectedBeams(), GameState.getEquippedBeams());
    }

    private Paint rightAligned(Paint p) {
        p.setTextAlign(Paint.Align.RIGHT);
        return p;
    }

    private Paint hpBarColor(int hp, int maxHp) {
        if (maxHp <= 0) return barHpPaint;
        float frac = hp / (float) maxHp;
        return frac <= 0.25f ? barLowHpPaint : frac <= 0.5f ? barMidHpPaint : barHpPaint;
    }

    private float drawCardHeader(Canvas canvas, float left, float y, String label) {
        float ty = y + headerPaint.getTextSize();
        canvas.drawText(label, left + 2, ty, headerPaint);
        return ty + headerPaint.getTextSize() * 0.35f;
    }

    private void drawCardBg(Canvas canvas, RectF rect) {
        float r = rect.height() * 0.12f;
        canvas.drawRoundRect(rect, r, r, cardPaint);
        canvas.drawRoundRect(rect, r, r, cardBorderPaint);
    }

    // Ammo counts this low read better as individual pips ("5 missiles" = 5
    // little capsules, filled ones = what you have) than as an abstract
    // fraction bar; above this it'd just be visual clutter, so it falls
    // back to the bar for that case (e.g. a maxed-out late-game ammo count).
    private static final int MAX_PIPS = 30;

    private float drawAmmoRow(Canvas canvas, float left, float right, float y, String label, int cur, int max, int color) {
        canvas.drawText(label, left, y, headerPaint);
        String amount = cur + "/" + max;
        canvas.drawText(amount, right, y, rightAligned(numberPaint));
        float top = y + headerPaint.getTextSize() * 0.35f;
        float bottom = top + headerPaint.getTextSize() * 0.55f;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        if (max > 0 && max <= MAX_PIPS) {
            drawPips(canvas, left, top, right, bottom, cur, max, fill);
        } else {
            drawBar(canvas, left, top, right, bottom, max > 0 ? cur / (float) max : 0, fill);
        }
        return bottom + headerPaint.getTextSize() * 1.1f;
    }

    private void drawPips(Canvas canvas, float left, float top, float right, float bottom, int cur, int max, Paint fillPaint) {
        float gap = (bottom - top) * 0.35f;
        float pipW = (right - left - gap * (max - 1)) / max;
        Paint emptyOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
        emptyOutline.setStyle(Paint.Style.STROKE);
        emptyOutline.setStrokeWidth((bottom - top) * 0.12f);
        emptyOutline.setColor(COL_DIM);
        float x = left;
        for (int i = 0; i < max; i++) {
            RectF pip = new RectF(x, top, x + pipW, bottom);
            if (i < cur) {
                drawMissilePip(canvas, pip, fillPaint);
            } else {
                drawMissilePip(canvas, pip, emptyOutline);
            }
            x += pipW + gap;
        }
    }

    // A small missile silhouette (pointed nose + rounded body) instead of a
    // plain capsule, used by drawPips for the ammo rows.
    private void drawMissilePip(Canvas canvas, RectF r, Paint paint) {
        float h = r.height();
        float noseLen = Math.min(h * 0.9f, r.width() * 0.45f);
        RectF body = new RectF(r.left + noseLen, r.top, r.right, r.bottom);
        canvas.drawRoundRect(body, h / 2f, h / 2f, paint);

        Path nose = new Path();
        nose.moveTo(r.left, r.top + h / 2f);
        nose.lineTo(r.left + noseLen, r.top);
        nose.lineTo(r.left + noseLen, r.bottom);
        nose.close();
        canvas.drawPath(nose, paint);
    }

    private void drawBar(Canvas canvas, float left, float top, float right, float bottom, float frac, Paint fillPaint) {
        RectF bg = new RectF(left, top, right, bottom);
        float r = (bottom - top) / 2f;
        canvas.drawRoundRect(bg, r, r, barBgPaint);
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        if (frac > 0) {
            RectF fg = new RectF(left, top, left + Math.max((right - left) * frac, bottom - top), bottom);
            canvas.drawRoundRect(fg, r, r, fillPaint);
        }
    }

    // Draws a card containing a wrapped grid of square icon slots (hand-drawn
    // vector glyphs, not the real ROM label strips - those turned out to be
    // 8:1 aspect-ratio text tiles that can't be made legible at this panel's
    // size without rows far taller than fit for 16 items). Every item is
    // always drawn, tinted by state, so an uncollected item reads as a dim
    // grey glyph instead of an empty box. Returns the y position after the card.
    private float drawChipCard(Canvas canvas, float left, float y, float right, Chip[] chips, boolean isBeam, int collected, int equipped) {
        float pad = headerPaint.getTextSize() * 0.5f;
        float chipSize = headerPaint.getTextSize() * 2.6f;
        float gap = headerPaint.getTextSize() * 0.4f;
        int perRow = Math.max(1, (int) ((right - left - 2 * pad + gap) / (chipSize + gap)));
        int rows = (int) Math.ceil(chips.length / (float) perRow);
        float cardH = pad * 2 + rows * chipSize + (rows - 1) * gap;

        RectF card = new RectF(left, y, right, y + cardH);
        drawCardBg(canvas, card);

        Paint chipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint chipBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        chipBorder.setStyle(Paint.Style.STROKE);
        chipBorder.setStrokeWidth(2);
        Paint chipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        chipTextPaint.setTextAlign(Paint.Align.CENTER);
        chipTextPaint.setTextSize(chipSize * 0.32f);
        chipTextPaint.setFakeBoldText(true);

        float x = left + pad, cy = y + pad;
        float chipR = chipSize * 0.22f;
        int col = 0;
        for (Chip c : chips) {
            boolean isEquipped = (equipped & c.bit) != 0;
            boolean isCollected = (collected & c.bit) != 0;

            RectF chipRect = new RectF(x, cy, x + chipSize, cy + chipSize);
            if (isEquipped) {
                chipBg.setColor(COL_ACCENT);
                chipBg.setAlpha(46);
                canvas.drawRoundRect(chipRect, chipR, chipR, chipBg);
                chipBorder.setColor(COL_ACCENT);
            } else if (isCollected) {
                chipBorder.setColor(COL_HEADER);
            } else {
                chipBorder.setColor(COL_CARD_BORDER);
            }
            canvas.drawRoundRect(chipRect, chipR, chipR, chipBorder);

            int textColor = isEquipped ? COL_ACCENT : isCollected ? COL_TEXT : COL_DIM_TEXT;
            chipTextPaint.setColor(textColor);
            chipTextPaint.setAlpha(isCollected ? 255 : 130);
            canvas.drawText(c.label, x + chipSize / 2f, cy + chipSize / 2f + chipTextPaint.getTextSize() * 0.32f, chipTextPaint);

            col++;
            if (col >= perRow) {
                col = 0;
                x = left + pad;
                cy += chipSize + gap;
            } else {
                x += chipSize + gap;
            }
        }

        return y + cardH;
    }

}
