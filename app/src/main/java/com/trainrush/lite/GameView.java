package com.trainrush.lite;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

/**
 * The 3D surface. Uses a 16-bit colour and 16-bit depth buffer (about half the memory of the
 * default) and can render below the screen resolution; the TV scales the picture up for free.
 */
final class GameView extends GLSurfaceView {
    private final Game game;
    private int quality = 1;

    GameView(Context c, Game game, GameRenderer renderer) {
        super(c);
        this.game = game;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(5, 6, 5, 0, 16, 0);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(false);
    }

    /** Must run on the UI thread. */
    void applyQuality(int q) {
        quality = q;
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        int targetH = q == 0 ? 540 : (q == 1 ? 720 : 0);
        if (targetH == 0 || h <= targetH) {
            getHolder().setSizeFromLayout();
        } else {
            float k = targetH / (float) h;
            getHolder().setFixedSize(Math.max(2, (int) (w * k)), targetH);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyQuality(quality);
    }

    /** Touch controls so the game is also playable on a phone/tablet: left/right thirds and centre. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float x = e.getX() / Math.max(1, getWidth());
            if (x < 0.33f) game.push(Game.A_LEFT);
            else if (x > 0.67f) game.push(Game.A_RIGHT);
            else game.push(Game.A_OK);
        }
        return true;
    }
}
