package com.raekwon.supermetroid;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Map;

// A small hand-drawn bitmap-style font, used everywhere MapStatusView draws
// text, in place of Typeface.MONOSPACE - gives the retro SNES-menu look the
// second screen is going for without needing SM's own ROM font (which isn't
// decoded anywhere in this codebase's native layer - see chat history/plan).
// Each glyph is a 5-wide x 7-tall bitmask (one byte per row, bit 4..0 = left
// to right column) covering A-Z, 0-9, and the punctuation this app's own
// strings actually use (. / - space). Unknown characters draw as blank
// space rather than throwing, so an unexpected character never crashes a
// frame.
public final class PixelFont {
    private PixelFont() {}

    private static final int GLYPH_W = 5, GLYPH_H = 7;
    private static final Map<Character, byte[]> GLYPHS = new HashMap<>();

    private static void g(char c, String... rows) {
        byte[] bits = new byte[GLYPH_H];
        for (int y = 0; y < GLYPH_H && y < rows.length; y++) {
            String row = rows[y];
            byte b = 0;
            for (int x = 0; x < GLYPH_W && x < row.length(); x++) {
                if (row.charAt(x) != ' ') b |= (byte) (1 << (GLYPH_W - 1 - x));
            }
            bits[y] = b;
        }
        GLYPHS.put(c, bits);
    }

    static {
        g('A', "  #  ", " # # ", "#   #", "#   #", "#####", "#   #", "#   #");
        g('B', "#### ", "#   #", "#   #", "#### ", "#   #", "#   #", "#### ");
        g('C', " ####", "#    ", "#    ", "#    ", "#    ", "#    ", " ####");
        g('D', "#### ", "#   #", "#   #", "#   #", "#   #", "#   #", "#### ");
        g('E', "#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#####");
        g('F', "#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#    ");
        g('G', " ####", "#    ", "#    ", "# ###", "#   #", "#   #", " ####");
        g('H', "#   #", "#   #", "#   #", "#####", "#   #", "#   #", "#   #");
        g('I', "#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "#####");
        g('J', "  ###", "   # ", "   # ", "   # ", "   # ", "#  # ", " ##  ");
        g('K', "#   #", "#  # ", "# #  ", "##   ", "# #  ", "#  # ", "#   #");
        g('L', "#    ", "#    ", "#    ", "#    ", "#    ", "#    ", "#####");
        g('M', "#   #", "## ##", "# # #", "# # #", "#   #", "#   #", "#   #");
        g('N', "#   #", "##  #", "# # #", "# # #", "#  ##", "#   #", "#   #");
        g('O', " ### ", "#   #", "#   #", "#   #", "#   #", "#   #", " ### ");
        g('P', "#### ", "#   #", "#   #", "#### ", "#    ", "#    ", "#    ");
        g('Q', " ### ", "#   #", "#   #", "#   #", "# # #", "#  # ", " ## #");
        g('R', "#### ", "#   #", "#   #", "#### ", "# #  ", "#  # ", "#   #");
        g('S', " ####", "#    ", "#    ", " ### ", "    #", "    #", "#### ");
        g('T', "#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ");
        g('U', "#   #", "#   #", "#   #", "#   #", "#   #", "#   #", " ### ");
        g('V', "#   #", "#   #", "#   #", "#   #", "#   #", " # # ", "  #  ");
        g('W', "#   #", "#   #", "#   #", "# # #", "# # #", "## ##", "#   #");
        g('X', "#   #", "#   #", " # # ", "  #  ", " # # ", "#   #", "#   #");
        g('Y', "#   #", "#   #", " # # ", "  #  ", "  #  ", "  #  ", "  #  ");
        g('Z', "#####", "    #", "   # ", "  #  ", " #   ", "#    ", "#####");
        g('0', " ### ", "#   #", "#  ##", "# # #", "##  #", "#   #", " ### ");
        g('1', "  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", "#####");
        g('2', " ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####");
        g('3', "#### ", "    #", "    #", "  ## ", "    #", "    #", "#### ");
        g('4', "   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # ");
        g('5', "#####", "#    ", "#    ", "#### ", "    #", "    #", "#### ");
        g('6', " ### ", "#    ", "#    ", "#### ", "#   #", "#   #", " ### ");
        g('7', "#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   ");
        g('8', " ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### ");
        g('9', " ### ", "#   #", "#   #", " ####", "    #", "    #", " ### ");
        g('.', "     ", "     ", "     ", "     ", "     ", "  #  ", "  #  ");
        g('/', "    #", "    #", "   # ", "  #  ", " #   ", "#    ", "#    ");
        g('-', "     ", "     ", "     ", "#####", "     ", "     ", "     ");
        g(':', "     ", "  #  ", "  #  ", "     ", "  #  ", "  #  ", "     ");
        g('\'', " #   ", " #   ", "     ", "     ", "     ", "     ", "     ");
        g(' ', "     ", "     ", "     ", "     ", "     ", "     ", "     ");
    }

    private static final Paint scratchPaint = new Paint();
    private static final RectF scratchRect = new RectF();

    // Width, in "pixels" (glyph units), a string would occupy at pixelSize=1
    // - i.e. chars * (GLYPH_W + 1 gap) - 1 (no trailing gap).
    public static float measureWidth(String text, float pixelSize) {
        String up = text.toUpperCase();
        return (up.length() * (GLYPH_W + 1) - 1) * pixelSize;
    }

    public static float glyphHeight(float pixelSize) {
        return GLYPH_H * pixelSize;
    }

    // The pixelSize that makes a glyph's total height equal targetHeight -
    // callers that think in terms of "how many px tall should this text be"
    // (matching Paint.setTextSize's own convention) use this instead of
    // picking a pixelSize directly.
    public static float pixelSizeForHeight(float targetHeight) {
        return targetHeight / GLYPH_H;
    }

    // Draws text as a grid of filled squares, each pixelSize screen-px wide/
    // tall - the "pixel size" IS the font size here, there's no separate
    // point-size concept. (x,y) is the LEFT, TOP-of-glyph-cap corner
    // (matches Canvas.drawText's baseline-left convention closely enough
    // for this UI's own layout math, which already treats its old
    // drawText calls as top-anchored via manual offsets).
    public static void drawText(Canvas canvas, String text, float x, float y, float pixelSize,
                                 int color, Paint.Align align) {
        String up = text.toUpperCase();
        float totalW = measureWidth(up, pixelSize);
        float startX = x;
        if (align == Paint.Align.CENTER) startX = x - totalW / 2f;
        else if (align == Paint.Align.RIGHT) startX = x - totalW;

        scratchPaint.setColor(color);
        scratchPaint.setStyle(Paint.Style.FILL);
        scratchPaint.setAntiAlias(false);

        float cx = startX;
        for (int i = 0; i < up.length(); i++) {
            byte[] bits = GLYPHS.get(up.charAt(i));
            if (bits != null) {
                for (int row = 0; row < GLYPH_H; row++) {
                    int b = bits[row];
                    for (int col = 0; col < GLYPH_W; col++) {
                        if ((b & (1 << (GLYPH_W - 1 - col))) == 0) continue;
                        float px = cx + col * pixelSize, py = y + row * pixelSize;
                        scratchRect.set(px, py, px + pixelSize, py + pixelSize);
                        canvas.drawRect(scratchRect, scratchPaint);
                    }
                }
            }
            cx += (GLYPH_W + 1) * pixelSize;
        }
    }

    // Renders text into a small, tightly-cropped ARGB8888 bitmap - for
    // strings that rarely change (tab labels, equipment titles, the logo),
    // so a busy frame doesn't re-rasterize the same glyphs from scratch 60
    // times a second. Transparent background; blit with drawBitmap after.
    public static Bitmap renderToBitmap(String text, float pixelSize, int color) {
        int w = Math.max(1, Math.round(measureWidth(text, pixelSize)));
        int h = Math.max(1, Math.round(glyphHeight(pixelSize)));
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        drawText(c, text, 0, 0, pixelSize, color, Paint.Align.LEFT);
        return bmp;
    }
}
