package io.github.terpenesalad.jellymusic;

import android.graphics.Bitmap;
import android.webkit.JavascriptInterface;

/**
 * The surface the web page calls into.
 *
 * Every method here runs on the WebView's JavaScript thread, not the main
 * thread, so nothing in here may touch a View directly — anything that needs
 * the UI goes back through the activity's handler.
 */
public class WebAppBridge {

    private final MainActivity activity;

    WebAppBridge(MainActivity activity) {
        this.activity = activity;
    }

    /** Camera-cutout and gesture-bar sizes in CSS px, as JSON {top,bottom}. */
    @JavascriptInterface
    public String safeArea() {
        return activity.safeAreaJson();
    }

    /** Called when the track changes. */
    @JavascriptInterface
    public void setMetadata(String title, String artist, String album) {
        NowPlaying.title = title == null ? "" : title;
        NowPlaying.artist = artist == null ? "" : artist;
        NowPlaying.album = album == null ? "" : album;
        NowPlaying.hasTrack = true;
        PlaybackService.sync(activity);
    }

    /**
     * Cover art, already fetched and base64-encoded by the page.
     *
     * The page does the fetching because the artwork URL carries the Jellyfin
     * API key and may point at a plain-http LAN address — re-requesting it from
     * native code would mean duplicating the auth and TLS handling for no
     * benefit. {@code key} is the source URL, used to skip work when the same
     * cover is set twice.
     */
    @JavascriptInterface
    public void setArtwork(String key, String base64) {
        String k = key == null ? "" : key;
        if (k.equals(NowPlaying.artworkKey)) return;
        NowPlaying.artworkKey = k;
        if (base64 == null || base64.isEmpty()) {
            NowPlaying.artwork = null;
        } else {
            Bitmap bm = MainActivity.decodeBase64(base64);
            if (bm != null) NowPlaying.artwork = bm;
        }
        PlaybackService.sync(activity);
    }

    /** Playing / paused, plus position for the lock-screen scrubber. */
    @JavascriptInterface
    public void setState(boolean playing, double positionSec, double durationSec, double speed) {
        boolean was = NowPlaying.playing;
        NowPlaying.playing = playing;
        NowPlaying.positionMs = (long) (Math.max(0, positionSec) * 1000);
        NowPlaying.durationMs = (long) (Math.max(0, durationSec) * 1000);
        NowPlaying.speed = (float) (speed > 0 ? speed : 1);
        NowPlaying.hasTrack = true;

        activity.setKeepScreenOn(playing);

        // Only poke the service when the play/pause state actually flips or
        // we haven't started it yet. Position updates arrive several times a
        // second and rebuilding the notification that often would be wasteful.
        if (was != playing) {
            PlaybackService.sync(activity);
        }
    }

    /** Position-only update, cheap enough to send often. */
    @JavascriptInterface
    public void setPosition(double positionSec, double durationSec) {
        NowPlaying.positionMs = (long) (Math.max(0, positionSec) * 1000);
        NowPlaying.durationMs = (long) (Math.max(0, durationSec) * 1000);
    }

    /** Playback finished or the player was torn down. */
    @JavascriptInterface
    public void stop() {
        NowPlaying.playing = false;
        activity.setKeepScreenOn(false);
        PlaybackService.sync(activity);
    }

    /**
     * Writes an exported backup to the device's Downloads folder.
     *
     * A page inside a WebView can't download anything on its own: a blob: URL
     * has no meaning to Android's download manager, so the ordinary
     * `<a download>` the site uses in a browser silently does nothing here.
     * The page hands over the bytes and the app writes the file.
     *
     * Returns true only once the file is actually on disk, so the page can
     * tell the user the truth about whether it saved.
     */
    @JavascriptInterface
    public boolean saveFile(String filename, String base64) {
        return activity.saveToDownloads(filename, base64);
    }

    /** Surfaces page-side problems in logcat while debugging. */
    @JavascriptInterface
    public void log(String message) {
        android.util.Log.d("MusicArchive/web", message == null ? "" : message);
    }
}
