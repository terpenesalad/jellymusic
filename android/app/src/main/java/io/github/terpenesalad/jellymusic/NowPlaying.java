package io.github.terpenesalad.jellymusic;

import android.graphics.Bitmap;

/**
 * What's playing right now, shared between the WebView and the playback
 * service.
 *
 * This is a static holder rather than data passed through Intent extras
 * because of the artwork: a cover bitmap can run to a few hundred KB, and
 * Binder transactions are capped around 1 MB. Pushing bitmaps through Intents
 * works right up until a large cover blows the transaction limit and takes the
 * whole process down with it. The service is signalled with an empty intent
 * and reads the current values from here instead.
 */
final class NowPlaying {
    private NowPlaying() {}

    static volatile String title = "";
    static volatile String artist = "";
    static volatile String album = "";
    static volatile Bitmap artwork = null;
    /** URL the current artwork came from, so we only decode each cover once. */
    static volatile String artworkKey = "";

    static volatile boolean playing = false;
    static volatile long positionMs = 0;
    static volatile long durationMs = 0;
    static volatile float speed = 1f;

    /** True once the page has reported any track at all. */
    static volatile boolean hasTrack = false;

    static void clear() {
        title = "";
        artist = "";
        album = "";
        artwork = null;
        artworkKey = "";
        playing = false;
        positionMs = 0;
        durationMs = 0;
        speed = 1f;
        hasTrack = false;
    }
}
