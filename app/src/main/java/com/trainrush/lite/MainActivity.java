package com.trainrush.lite;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Wires everything together and translates TV-remote / gamepad / keyboard keys into game actions. */
public class MainActivity extends Activity implements Game.Host, ThemeManager.Listener {
    private GameView gameView;
    private HudView hudView;
    private Game game;
    private SoundEngine sound;
    private ThemeManager themes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences("trainrush", MODE_PRIVATE);
        themes = new ThemeManager(this, prefs);
        sound = new SoundEngine();
        game = new Game(themes, sound, prefs, this);
        themes.setListener(this);

        gameView = new GameView(this, game, new GameRenderer(game));
        hudView = new HudView(this, game, themes);

        FrameLayout root = new FrameLayout(this);
        root.addView(gameView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(hudView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemUi();

        // First run: download the theme pack in the background (no-op once it is saved).
        themes.syncAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        gameView.onResume();
        sound.start();
    }

    @Override
    protected void onPause() {
        // pause a running game when the app goes to the background
        if (game.state == Game.PLAYING) game.push(Game.A_PAUSE);
        sound.stop();
        gameView.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ Game.Host

    @Override
    public void hudChanged() {
        if (hudView != null) hudView.postInvalidate();
    }

    @Override
    public void qualityChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                gameView.applyQuality(game.quality);
            }
        });
    }

    @Override
    public void quit() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
    }

    // ------------------------------------------------------------------ ThemeManager.Listener

    @Override
    public void onThemesChanged() {
        game.onThemesChanged();
        hudChanged();
    }

    // ------------------------------------------------------------------ keys

    private static int map(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_A:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return Game.A_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return Game.A_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
                return Game.A_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_S:
                return Game.A_DOWN;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_SPACE:
                return Game.A_OK;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                return Game.A_BACK;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_P:
                return Game.A_PAUSE;
            default:
                return 0;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int a = map(keyCode);
        if (a == 0) return super.onKeyDown(keyCode, event);
        if (a == Game.A_UP) game.holdUp = true;
        if (a == Game.A_DOWN) game.holdDown = true;
        if (event.getRepeatCount() == 0) game.push(a);   // holding a key must not skip several tracks
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int a = map(keyCode);
        if (a == 0) return super.onKeyUp(keyCode, event);
        if (a == Game.A_UP) game.holdUp = false;
        if (a == Game.A_DOWN) game.holdDown = false;
        return true;
    }
}
