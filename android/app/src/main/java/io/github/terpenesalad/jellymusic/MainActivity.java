package io.github.terpenesalad.jellymusic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.graphics.Color;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String TAG = "MusicArchive";
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIF = 1002;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String bridgeJs = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean keepingScreenOn = false;

    // Last safe-area sizes in CSS px (== dp), handed to the page on every
    // load and whenever they change.
    private volatile int safeTopPx = 0;
    private volatile int safeBottomPx = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bridgeJs = readAsset("bridge.js");

        web = new WebView(this);

        // Full-bleed, like Spotify: the page draws all the way to the top and
        // bottom edges of the glass, the status bar (clock, signal, battery) is
        // hidden while the app is in front, and the gesture/nav bar floats
        // transparently over the page's own bottom nav. The page is told how
        // big the camera cutout and gesture area are (see pushSafeArea) so it
        // can keep its controls clear of them without wasting any space.
        enterImmersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0A0A);
        root.addView(web);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeOpen = insets.isVisible(WindowInsetsCompat.Type.ime());

            // Edge-to-edge switches off adjustResize, so the keyboard has to
            // be made room for by hand or it covers the search box.
            v.setPadding(Math.max(cut.left, nav.left), 0,
                    Math.max(cut.right, nav.right), imeOpen ? ime.bottom : 0);

            // The status bar is hidden, so only the camera cutout counts at the
            // top. A transient swipe-down overlays the page rather than
            // shoving it around.
            int top = cut.top;
            int bottom = imeOpen ? 0 : Math.max(nav.bottom, cut.bottom);
            pushSafeArea(top, bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage + IndexedDB
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Without this the page can't start audio on its own, which breaks
        // auto-advancing to the next track and resuming a saved queue — the
        // browser would demand a fresh tap for every single song.
        s.setMediaPlaybackRequiresUserGesture(false);

        // The site is https (GitHub Pages) but a home Jellyfin server usually
        // isn't. A browser blocks that combination outright as mixed content;
        // here we allow it, which is a large part of why the wrapper is worth
        // having at all.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setBackgroundColor(0xFF0A0A0A);
        web.addJavascriptInterface(new WebAppBridge(this), "MAHost");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost();
                String appHost = Uri.parse(BuildConfig.APP_URL).getHost();
                // Keep the app on its own site; anything else (a link someone
                // pasted, an external service) belongs in the real browser.
                if (host != null && appHost != null && host.equalsIgnoreCase(appHost)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "No app can open that link", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                inject();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                // Only the main document matters; a failed cover image shouldn't
                // replace the whole app with an error page.
                if (req != null && req.isForMainFrame()) {
                    showOfflinePage();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // WebView silently swallows alert/confirm unless they're handled,
            // which would make "Sign out?" and "Delete album?" appear to do
            // nothing at all.
            @Override
            public boolean onJsAlert(WebView v, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String message, String defaultValue,
                                      JsPromptResult result) {
                final android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                input.setText(defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            // Playlist and album cover uploads are <input type="file">, which
            // does nothing in a WebView unless the picker is wired up by hand.
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i = params.createIntent();
                    // Asking for "application/json" makes the system picker
                    // hide the very file we want on a lot of devices, because
                    // a .json in Downloads is frequently typed as
                    // octet-stream. Widen it and let the page validate.
                    String[] accept = params.getAcceptTypes();
                    if (accept != null) {
                        for (String a : accept) {
                            if (a != null && a.toLowerCase().contains("json")) {
                                i.setType("*/*");
                                break;
                            }
                        }
                    }
                    startActivityForResult(i, REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Couldn't open the file picker",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        PlaybackService.setCommandListener((action, argMs) ->
                main.post(() -> dispatchToPage(action, argMs)));

        askForNotificationPermission();
        web.loadUrl(BuildConfig.APP_URL);
    }

    /** Hand a notification / lock-screen / headset command to the web app. */
    private void dispatchToPage(String action, long argMs) {
        if (web == null) return;
        String js = "window.__MA_cmd && window.__MA_cmd("
                + jsString(action) + "," + argMs + ")";
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "dispatch failed", t);
        }
    }

    private void inject() {
        applySafeAreaToPage();
        if (web == null || bridgeJs.isEmpty()) return;
        try {
            web.evaluateJavascript(bridgeJs, null);
        } catch (Throwable t) {
            Log.w(TAG, "bridge injection failed", t);
        }
    }

    /**
     * Mirrors the page's wake-lock behaviour natively. The Wake Lock API isn't
     * available to a WebView, so the site's own request silently no-ops here;
     * this does the same job and is honoured reliably.
     */
    void setKeepScreenOn(final boolean on) {
        main.post(() -> {
            if (keepingScreenOn == on) return;
            keepingScreenOn = on;
            if (on) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    /** Hide the status bar, make the nav bar transparent, draw under both. */
    private void enterImmersive() {
        android.view.Window w = getWindow();
        WindowCompat.setDecorFitsSystemWindows(w, false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Otherwise Android paints a translucent scrim behind 3-button nav.
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setAppearanceLightStatusBars(false);
        c.setAppearanceLightNavigationBars(false);
        // A swipe from the top edge peeks the status bar, then it tucks away
        // again on its own — same as a video player.
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.statusBars());
    }

    private void pushSafeArea(int topPx, int bottomPx) {
        float d = getResources().getDisplayMetrics().density;
        int t = Math.round(topPx / d);
        int b = Math.round(bottomPx / d);
        if (t == safeTopPx && b == safeBottomPx) return;
        safeTopPx = t;
        safeBottomPx = b;
        applySafeAreaToPage();
    }

    /** Read by the page at startup, before any native push can land. */
    String safeAreaJson() {
        return "{\"top\":" + safeTopPx + ",\"bottom\":" + safeBottomPx + "}";
    }

    private void applySafeAreaToPage() {
        if (web == null) return;
        String js = "(function(r){if(!r)return;"
                + "r.style.setProperty('--ma-safe-top','" + safeTopPx + "px');"
                + "r.style.setProperty('--ma-safe-bottom','" + safeBottomPx + "px');"
                + "r.classList.add('ma-app');})(document.documentElement)";
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "safe-area push failed", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Dialogs, the share sheet and the notification shade can all bring
        // the status bar back; put it away again whenever we're in front.
        enterImmersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    private void showOfflinePage() {
        String html = "<!doctype html><html><head><meta name=viewport "
                + "content='width=device-width,initial-scale=1'><style>"
                + "html,body{height:100%;margin:0;background:#0A0A0A;color:#fff;"
                + "font-family:system-ui,-apple-system,sans-serif;display:flex;"
                + "align-items:center;justify-content:center;text-align:center;padding:24px}"
                + "h1{font-size:19px;margin:0 0 8px}p{font-size:14px;color:#8a8a8a;margin:0 0 20px;line-height:1.6}"
                + "button{background:#E8B84B;color:#000;border:0;border-radius:50px;padding:13px 26px;"
                + "font-size:15px;font-weight:800;font-family:inherit}"
                + "</style></head><body><div><h1>Can&rsquo;t reach the library</h1>"
                + "<p>No connection, and nothing cached yet.<br>Downloaded music still works once the app has loaded once.</p>"
                + "<button onclick=\"location.href='" + BuildConfig.APP_URL + "'\">Try again</button>"
                + "</div></body></html>";
        web.loadDataWithBaseURL(BuildConfig.APP_URL, html, "text/html", "utf-8", null);
    }

    private void askForNotificationPermission() {
        // Without it there's no media notification, and without that Android
        // won't let the app hold a media foreground service for long.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        results = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        // Never finish() here. Destroying the activity tears down the WebView,
        // and the WebView *is* the audio player — backing out of the app would
        // kill the music. Dropping the task to the background keeps it alive,
        // which is what every other music app does.
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        PlaybackService.setCommandListener(null);
        setKeepScreenOn(false);
        if (web != null) {
            web.removeJavascriptInterface("MAHost");
            web.destroy();
            web = null;
        }
        NowPlaying.clear();
        super.onDestroy();
    }

    // Note there is deliberately no onPause/onStop handling that calls
    // web.onPause() or pauseTimers(). Those are the usual WebView-wrapper
    // reflex and they are exactly what stops audio the moment the screen
    // turns off.

    /**
     * Saves bytes into the public Downloads folder.
     *
     * MediaStore is used on Android 10+ because it needs no storage
     * permission at all; older versions fall back to the app's own external
     * directory, which also needs no permission but is less discoverable, so
     * the path is shown in a toast.
     */
    boolean saveToDownloads(String filename, String base64) {
        if (filename == null || filename.isEmpty()) return false;
        byte[] bytes;
        try {
            bytes = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
        } catch (Throwable t) {
            return false;
        }
        if (bytes.length == 0) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                Uri item = getContentResolver().insert(collection, cv);
                if (item == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(item)) {
                    if (out == null) return false;
                    out.write(bytes);
                    out.flush();
                }
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, cv, null, null);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore save failed", t);
                return false;
            }
        }

        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return false;
            if (!dir.exists() && !dir.mkdirs()) return false;
            File f = new File(dir, filename);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            }
            final String path = f.getAbsolutePath();
            main.post(() -> Toast.makeText(this, "Saved to " + path, Toast.LENGTH_LONG).show());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "legacy save failed", t);
            return false;
        }
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e(TAG, "missing asset " + name, e);
            return "";
        }
    }

    static String jsString(String raw) {
        if (raw == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    static Bitmap decodeBase64(String b64) {
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            // Lock-screen art is displayed small; a full-size cover would waste
            // several MB of heap for no visible gain.
            o.inSampleSize = 1;
            Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (bm != null && bm.getWidth() > 640) {
                int h = (int) (bm.getHeight() * (640f / bm.getWidth()));
                Bitmap scaled = Bitmap.createScaledBitmap(bm, 640, Math.max(1, h), true);
                if (scaled != bm) bm.recycle();
                return scaled;
            }
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }
}
