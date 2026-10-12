package com.trainrush.lite;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the list of themes.
 *
 * Sources, later ones override earlier ones with the same id:
 *   1. built-in themes (always available, works offline)
 *   2. themes.json downloaded on first run (saved in the app's private storage)
 *   3. themes.json pushed by hand to Android/data/com.trainrush.lite/files/ (adb push)
 *
 * The download only ever fetches a small JSON data file, never code.
 */
final class ThemeManager {
    interface Listener {
        void onThemesChanged();
    }

    private static final String FILE = "themes.json";
    private static final String PREF_LAST_OK = "themes_last_ok";
    private static final long REFRESH_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_BYTES = 128 * 1024;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ArrayList<Theme> list = new ArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Listener listener;

    /** Short message for the on-screen display ("Downloading themes..."). */
    volatile String status = "";

    ThemeManager(Context c, SharedPreferences prefs) {
        this.ctx = c.getApplicationContext();
        this.prefs = prefs;
        reload();
    }

    void setListener(Listener l) {
        listener = l;
    }

    synchronized int count() {
        return list.size();
    }

    synchronized Theme get(int i) {
        return list.get(Math.max(0, Math.min(list.size() - 1, i)));
    }

    synchronized int indexOf(String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) return i;
        }
        return 0;
    }

    // ------------------------------------------------------------ loading

    void reload() {
        ArrayList<Theme> n = new ArrayList<>(Theme.builtins());
        merge(n, new File(ctx.getFilesDir(), FILE));
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) merge(n, new File(ext, FILE));
        synchronized (this) {
            list.clear();
            list.addAll(n);
        }
    }

    private static void merge(List<Theme> out, File f) {
        if (!f.isFile() || f.length() > MAX_BYTES) return;
        try {
            List<Theme> add = Theme.parsePack(new String(readAll(new FileInputStream(f)), "UTF-8"));
            for (Theme t : add) {
                boolean replaced = false;
                for (int i = 0; i < out.size(); i++) {
                    if (out.get(i).id.equals(t.id)) {
                        out.set(i, t);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) out.add(t);
            }
        } catch (Exception ignored) {
            // A broken file must never stop the game: built-in themes stay available.
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) {
                bo.write(buf, 0, r);
                if (bo.size() > MAX_BYTES) throw new IOException("too big");
            }
            return bo.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ----------------------------------------------------------- download

    /** Downloads the theme pack in the background if it was never fetched or is a week old. */
    void syncAsync() {
        String url = BuildConfig.THEME_URL;
        if (url.contains("OWNER/REPO")) {
            return; // not configured yet: built-in themes only
        }
        long last = prefs.getLong(PREF_LAST_OK, 0L);
        boolean haveFile = new File(ctx.getFilesDir(), FILE).isFile();
        if (haveFile && System.currentTimeMillis() - last < REFRESH_MS) return;
        if (!busy.compareAndSet(false, true)) return;

        final String u = url;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    notifyStatus("Downloading themes...");
                    byte[] data = download(u);
                    List<Theme> test = Theme.parsePack(new String(data, "UTF-8"));
                    if (test.isEmpty()) throw new IOException("empty pack");

                    File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
                    FileOutputStream fo = new FileOutputStream(tmp);
                    try {
                        fo.write(data);
                    } finally {
                        fo.close();
                    }
                    File dst = new File(ctx.getFilesDir(), FILE);
                    if (!tmp.renameTo(dst)) {
                        dst.delete();
                        if (!tmp.renameTo(dst)) throw new IOException("rename failed");
                    }
                    prefs.edit().putLong(PREF_LAST_OK, System.currentTimeMillis()).apply();
                    reload();
                    notifyStatus("Themes ready: " + count());
                } catch (Exception e) {
                    notifyStatus(haveFileNow() ? "" : "Offline - using built-in themes");
                } finally {
                    busy.set(false);
                }
            }
        }, "theme-sync");
        t.setDaemon(true);
        t.start();
    }

    private boolean haveFileNow() {
        return new File(ctx.getFilesDir(), FILE).isFile();
    }

    private static byte[] download(String url) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("https only");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "TrainRushLite");
            if (c.getResponseCode() != 200) throw new IOException("http " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private void notifyStatus(String s) {
        status = s;
        Listener l = listener;
        if (l != null) l.onThemesChanged();
    }
}
