package com.trainrush.lite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Text and menus drawn with the normal Android canvas on top of the 3D view.
 * It only redraws about 10 times per second, so it costs almost nothing.
 */
final class HudView extends View {
    private static final String[] QUALITY = {"Low  (540p, 30 fps)", "Medium  (720p)", "High  (full resolution)"};

    private final Game g;
    private final ThemeManager themes;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    HudView(Context c, Game game, ThemeManager tm) {
        super(c);
        g = game;
        themes = tm;
        text.setTypeface(Typeface.DEFAULT_BOLD);
        setFocusable(false);
    }

    // ------------------------------------------------------------------ helpers

    private void label(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setShadowLayer(size * 0.08f, 0f, size * 0.05f, 0xCC000000);
        text.setColor(color);
        c.drawText(s, x, y, text);
        text.clearShadowLayer();
    }

    private void panel(Canvas c, float l, float t, float r, float b, int color) {
        fill.setColor(color);
        rect.set(l, t, r, b);
        float rad = (b - t) * 0.08f;
        c.drawRoundRect(rect, rad, rad, fill);
    }

    /** dir: -1 = points left, +1 = points right */
    private void arrow(Canvas c, float cx, float cy, float size, int dir, int color) {
        fill.setColor(color);
        path.reset();
        path.moveTo(cx + dir * size, cy);
        path.lineTo(cx - dir * size * 0.6f, cy - size);
        path.lineTo(cx - dir * size * 0.6f, cy + size);
        path.close();
        c.drawPath(path, fill);
    }

    private void heart(Canvas c, float cx, float cy, float s, int color) {
        fill.setColor(color);
        c.drawCircle(cx - s * 0.5f, cy - s * 0.3f, s * 0.55f, fill);
        c.drawCircle(cx + s * 0.5f, cy - s * 0.3f, s * 0.55f, fill);
        path.reset();
        path.moveTo(cx - s * 1.0f, cy - s * 0.1f);
        path.lineTo(cx + s * 1.0f, cy - s * 0.1f);
        path.lineTo(cx, cy + s * 1.1f);
        path.close();
        c.drawPath(path, fill);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float mx = w * 0.05f, my = h * 0.06f;   // TV safe area (overscan)

        int st = g.state;
        if (st == Game.MENU) {
            drawMenu(c, w, h);
        } else {
            drawHud(c, w, h, mx, my);
            if (st == Game.PAUSED) {
                panel(c, 0, 0, w, h, 0x88000000);
                label(c, "PAUSED", w / 2f, h * 0.47f, h * 0.13f, 0xFFFFFFFF, Paint.Align.CENTER);
                label(c, "OK = continue      BACK = menu", w / 2f, h * 0.58f, h * 0.045f, 0xFFDDDDDD, Paint.Align.CENTER);
            } else if (st == Game.OVER) {
                panel(c, 0, 0, w, h, 0x99000000);
                label(c, "GAME OVER", w / 2f, h * 0.36f, h * 0.14f, 0xFFFF6B5A, Paint.Align.CENTER);
                label(c, "Score  " + g.score, w / 2f, h * 0.52f, h * 0.075f, 0xFFFFFFFF, Paint.Align.CENTER);
                String b = g.score >= g.best && g.score > 0 ? "New best score!" : "Best  " + g.best;
                label(c, b, w / 2f, h * 0.62f, h * 0.05f, 0xFFFFD23F, Paint.Align.CENTER);
                label(c, "OK = play again      BACK = menu", w / 2f, h * 0.76f, h * 0.045f, 0xFFDDDDDD, Paint.Align.CENTER);
            }
        }

        String s = themes.status;
        if (s != null && s.length() > 0) {
            label(c, s, w - mx, h - my * 0.6f, h * 0.035f, 0xFFFFFFFF, Paint.Align.RIGHT);
        }
    }

    private void drawHud(Canvas c, int w, int h, float mx, float my) {
        label(c, String.valueOf(g.score), mx, my + h * 0.08f, h * 0.09f, 0xFFFFFFFF, Paint.Align.LEFT);
        label(c, "SCORE", mx, my + h * 0.115f, h * 0.03f, 0xFFDDDDDD, Paint.Align.LEFT);

        label(c, String.valueOf(g.coins), w - mx, my + h * 0.07f, h * 0.07f, 0xFFFFD700, Paint.Align.RIGHT);
        label(c, "COINS", w - mx, my + h * 0.105f, h * 0.03f, 0xFFDDDDDD, Paint.Align.RIGHT);

        float hs = h * 0.022f;
        for (int i = 0; i < 3; i++) {
            heart(c, w / 2f + (i - 1) * hs * 3.4f, my + hs * 1.4f, hs, i < g.lives ? 0xFFFF4D4D : 0x55FFFFFF);
        }

        label(c, g.kmh + " km/h", mx, h - my, h * 0.05f, 0xFFFFFFFF, Paint.Align.LEFT);

        float boost = g.boostLeft;
        if (boost > 0f) {
            float bw = w * 0.22f, bh = h * 0.025f;
            float l = (w - bw) / 2f, t = h - my - bh;
            panel(c, l, t, l + bw, t + bh, 0x66000000);
            panel(c, l, t, l + bw * Math.min(1f, boost / 4.5f), t + bh, 0xFF33D9FF);
            label(c, "BOOST", w / 2f, t - h * 0.012f, h * 0.035f, 0xFF33D9FF, Paint.Align.CENTER);
        }
    }

    private void drawMenu(Canvas c, int w, int h) {
        float cx = w / 2f;
        label(c, "TRAIN RUSH", cx, h * 0.2f, h * 0.15f, 0xFFFFFFFF, Paint.Align.CENTER);
        label(c, "LITE 3D", cx, h * 0.27f, h * 0.05f, 0xFFFFD23F, Paint.Align.CENTER);

        float pl = w * 0.25f, pr = w * 0.75f, pt = h * 0.36f, rowH = h * 0.1f;
        panel(c, pl, pt, pr, pt + rowH * 3f + h * 0.04f, 0x99000000);

        Theme t = g.theme;
        String[] names = {"Theme", "Quality", "Music"};
        String[] vals = {t.name, QUALITY[g.quality], g.music ? "On" : "Off"};
        for (int i = 0; i < 3; i++) {
            float ry = pt + h * 0.02f + i * rowH;
            boolean sel = g.menuRow == i;
            if (sel) panel(c, pl + w * 0.01f, ry, pr - w * 0.01f, ry + rowH, 0x55FFFFFF);
            int col = sel ? 0xFFFFFFFF : 0xFFBBBBBB;
            label(c, names[i], pl + w * 0.03f, ry + rowH * 0.66f, h * 0.045f, col, Paint.Align.LEFT);
            label(c, vals[i], pr - w * 0.09f, ry + rowH * 0.66f, h * 0.045f, col, Paint.Align.RIGHT);
            if (sel) {
                arrow(c, pr - w * 0.075f + w * 0.0f, ry + rowH * 0.5f, h * 0.022f, 1, 0xFFFFD23F);
                arrow(c, pl + w * 0.30f, ry + rowH * 0.5f, h * 0.022f, -1, 0xFFFFD23F);
            }
        }

        float pulse = 0.6f + 0.4f * (float) Math.sin(g.time * 4f);
        int a = (int) (255 * pulse);
        label(c, "Press OK to start", cx, h * 0.8f, h * 0.06f, (a << 24) | 0xFFFFFF, Paint.Align.CENTER);
        label(c, "UP/DOWN pick option   LEFT/RIGHT change   BACK exit", cx, h * 0.87f, h * 0.032f, 0xFFDDDDDD, Paint.Align.CENTER);
        label(c, "In game:  LEFT/RIGHT change track   UP faster   DOWN brake   OK horn   BACK pause",
                cx, h * 0.915f, h * 0.032f, 0xFFDDDDDD, Paint.Align.CENTER);
        label(c, "Best  " + g.best, w * 0.05f, h * 0.06f + h * 0.05f, h * 0.045f, 0xFFFFD23F, Paint.Align.LEFT);
    }
}
