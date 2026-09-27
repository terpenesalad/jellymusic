package io.github.terpenesalad.jellymusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.session.MediaButtonReceiver;

/**
 * Keeps playback alive when the app isn't on screen.
 *
 * A WebView playing audio is, as far as Android is concerned, just a browser
 * tab: once the activity stops the process drops to a low-priority tier and
 * the system is free to freeze it — which is exactly the "music dies when the
 * phone locks" behaviour this whole thing exists to fix. A foreground service
 * pins the process at foreground priority, and the media notification it's
 * required to post is the same one that gives you lock-screen and bluetooth
 * controls. So the fix and the feature are the same object.
 *
 * The service deliberately keeps running while paused. Android 12+ blocks
 * starting a foreground service from the background, so if we tore it down on
 * pause, hitting play from the lock screen a minute later would fail — the
 * service would need starting at precisely the moment we're not allowed to
 * start it. Staying resident costs nothing and avoids that entirely.
 */
public class PlaybackService extends Service {

    private static final String TAG = "PlaybackService";
    private static final String CHANNEL_ID = "playback";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_SYNC = "io.github.terpenesalad.jellymusic.SYNC";
    public static final String ACTION_PLAY = "io.github.terpenesalad.jellymusic.PLAY";
    public static final String ACTION_PAUSE = "io.github.terpenesalad.jellymusic.PAUSE";
    public static final String ACTION_NEXT = "io.github.terpenesalad.jellymusic.NEXT";
    public static final String ACTION_PREV = "io.github.terpenesalad.jellymusic.PREV";
    public static final String ACTION_STOP = "io.github.terpenesalad.jellymusic.STOP";

    /** Commands the service needs the web page to carry out. */
    public interface CommandListener {
        void onCommand(String action, long argMs);
    }

    private static volatile CommandListener listener;

    public static void setCommandListener(CommandListener l) {
        listener = l;
    }

    private static void emit(String action, long argMs) {
        CommandListener l = listener;
        if (l != null) {
            try {
                l.onCommand(action, argMs);
            } catch (Throwable t) {
                Log.w(TAG, "command failed: " + action, t);
            }
        }
    }

    /** Ask the service to re-read {@link NowPlaying} and refresh everything. */
    public static void sync(Context ctx) {
        Intent i = new Intent(ctx, PlaybackService.class).setAction(ACTION_SYNC);
        try {
            ctx.startForegroundService(i);
        } catch (Throwable t) {
            // Can throw if we're in the background and not currently allowed to
            // start a foreground service. Nothing to recover — playback carries
            // on, we just can't refresh the notification this instant.
            Log.w(TAG, "sync failed", t);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, PlaybackService.class).setAction(ACTION_STOP));
        } catch (Throwable ignored) {
        }
    }

    private MediaSessionCompat session;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean startedForeground = false;

    /** Headphones unplugged / bluetooth disconnected — pause, don't blast. */
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                emit("pause", 0);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        session = new MediaSessionCompat(this, "MusicArchive");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { emit("play", 0); }
            @Override public void onPause() { emit("pause", 0); }
            @Override public void onSkipToNext() { emit("next", 0); }
            @Override public void onSkipToPrevious() { emit("prev", 0); }
            @Override public void onSeekTo(long pos) { emit("seek", pos); }
            @Override public void onStop() { emit("pause", 0); }
        });
        session.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicArchive::playback");
        wakeLock.setReferenceCounted(false);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MusicArchive::stream");
            wifiLock.setReferenceCounted(false);
        }

        // Apps targeting Android 14+ must say whether a runtime-registered
        // receiver is exported. System broadcasts are technically exempt, but
        // being explicit costs nothing and removes any chance of a
        // SecurityException at startup.
        ContextCompat.registerReceiver(this, noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        // Hardware / bluetooth media buttons arrive here as MEDIA_BUTTON intents.
        if (intent != null && Intent.ACTION_MEDIA_BUTTON.equals(action)) {
            MediaButtonReceiver.handleIntent(session, intent);
            pushForeground();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            emit("pause", 0);
            teardown();
            return START_NOT_STICKY;
        }

        if (ACTION_PLAY.equals(action)) { emit("play", 0); }
        else if (ACTION_PAUSE.equals(action)) { emit("pause", 0); }
        else if (ACTION_NEXT.equals(action)) { emit("next", 0); }
        else if (ACTION_PREV.equals(action)) { emit("prev", 0); }

        pushForeground();
        // Deliberately NOT sticky: see the note above — a service resurrected
        // without its WebView is a notification for a player that isn't there.
        return START_NOT_STICKY;
    }

    /** Rebuild the session metadata + notification from {@link NowPlaying}. */
    private void pushForeground() {
        boolean playing = NowPlaying.playing;

        if (playing) acquireLocks();
        else releaseLocks();

        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, NowPlaying.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, NowPlaying.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, NowPlaying.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, NowPlaying.durationMs);
        if (NowPlaying.artwork != null) {
            meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, NowPlaying.artwork);
        }
        session.setMetadata(meta.build());

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;

        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                        playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        NowPlaying.positionMs,
                        playing ? NowPlaying.speed : 0f,
                        android.os.SystemClock.elapsedRealtime())
                .build());

        Notification n = buildNotification(playing);

        if (!startedForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            startedForeground = true;
        } else {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(boolean playing) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = NowPlaying.title.isEmpty() ? getString(R.string.app_name) : NowPlaying.title;
        String artist = NowPlaying.artist;

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText(NowPlaying.album)
                .setContentIntent(contentIntent)
                .setDeleteIntent(servicePendingIntent(ACTION_STOP, 5))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                // Ongoing while playing so it can't be swiped away mid-song;
                // dismissible once paused, which is how you'd expect to get rid
                // of it when you're done listening.
                .setOngoing(playing);

        if (NowPlaying.artwork != null) {
            b.setLargeIcon(NowPlaying.artwork);
        }

        b.addAction(new NotificationCompat.Action(
                android.R.drawable.ic_media_previous,
                getString(R.string.notif_prev),
                servicePendingIntent(ACTION_PREV, 1)));
        b.addAction(new NotificationCompat.Action(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                getString(playing ? R.string.notif_pause : R.string.notif_play),
                servicePendingIntent(playing ? ACTION_PAUSE : ACTION_PLAY, 2)));
        b.addAction(new NotificationCompat.Action(
                android.R.drawable.ic_media_next,
                getString(R.string.notif_next),
                servicePendingIntent(ACTION_NEXT, 3)));

        b.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));

        return b.build();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(
                this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Media controls for whatever is playing");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    /* ── Why there is no audio-focus handling here ──
       There was, and it was the cause of a vicious half-second stutter.

       The audio is played by the WebView, and Chromium already requests
       audio focus on its own behalf whenever a media element plays — that
       is how a browser makes other apps duck or pause. This service then
       requested focus as well, and although both requests come from the
       same app, the system still treats them as competing: granting ours
       revoked Chromium's, so Chromium paused the element. The keepalive
       noticed and started it again, which made Chromium re-request focus,
       which revoked ours, and so on several times a second.

       The element would play for a fraction of a second at a time, and
       nothing in the page's own logs showed a pause, because nothing in the
       page had asked for one.

       The service's real job — keeping the process alive, owning the media
       session and posting the notification — needs no audio focus at all.
       Ducking and interruptions are left to the WebView, which was always
       the component actually producing sound. */

    private void acquireLocks() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "lock acquire failed", t);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable t) {
            Log.w(TAG, "lock release failed", t);
        }
    }

    private void teardown() {
        releaseLocks();
        if (session != null) session.setActive(false);
        stopForeground(true);
        startedForeground = false;
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The task is gone, so the WebView that was producing the audio is gone
        // too. Leaving a notification behind for a player that no longer exists
        // would just be a dead widget on the lock screen.
        teardown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Throwable ignored) {
        }
        releaseLocks();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
