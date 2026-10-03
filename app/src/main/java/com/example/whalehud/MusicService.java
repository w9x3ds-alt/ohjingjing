package com.example.whalehud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 背景音乐播放服务。
 *
 * 设计要点：
 *  · 前台服务，锁屏 / 切后台 / 息屏都继续播
 *  · 曲目来源两种：内置（assets/music/）+ 用户导入（SAF 持久授权的 content:// Uri）
 *  · 三种播放模式：自动连播 / 单曲循环 / 单次播放
 *  · 播放状态通过 {@link Listener} 回调给界面，不依赖广播
 */
public class MusicService extends Service {

    public static final String ACTION_PLAY_PAUSE = "com.example.whalehud.music.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.example.whalehud.music.NEXT";
    public static final String ACTION_PREV = "com.example.whalehud.music.PREV";
    public static final String ACTION_STOP = "com.example.whalehud.music.STOP";
    public static final String EXTRA_INDEX = "index";

    /** 自动连播：一首完了接着下一首 */
    public static final int MODE_SEQUENCE = 0;
    /** 单曲循环：一首反复播 */
    public static final int MODE_LOOP_ONE = 1;
    /** 单次播放：播完就停 */
    public static final int MODE_ONCE = 2;
    public static final int MODE_COUNT = 3;

    private static final String CHANNEL_ID = "music";
    private static final int NOTI_ID = 2;

    /** 曲目 */
    public static class Track {
        public final String title;
        public final String assetName;   // 内置曲目：assets/music 下的文件名；导入曲目为 null
        public final Uri uri;            // 导入曲目：content:// Uri；内置曲目为 null

        Track(String title, String assetName, Uri uri) {
            this.title = title;
            this.assetName = assetName;
            this.uri = uri;
        }

        public boolean isAsset() { return assetName != null; }
    }

    /** 界面订阅播放状态变化 */
    public interface Listener {
        void onMusicStateChanged();
    }

    private static Listener listener;
    public static void setListener(Listener l) { listener = l; }

    /** 供界面直接读取的播放状态（服务可能没启动，所以用静态快照） */
    public static volatile boolean playing = false;
    public static volatile int currentIndex = -1;
    public static volatile int playMode = MODE_SEQUENCE;
    public static volatile String currentTitle = "";

    private MediaPlayer player;
    private final List<Track> playlist = new ArrayList<Track>();
    private boolean prepared = false;
    private boolean starting = false;

    /** 供 Activity 取回服务实例 */
    public class LocalBinder extends Binder {
        public MusicService get() { return MusicService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        reloadPlaylist();
        playMode = Prefs.musicMode(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent == null) ? null : intent.getAction();
        if (action == null) {
            // 只是把服务拉起来，不自动播放
            startForegroundSafely();
            return START_STICKY;
        }
        switch (action) {
            case ACTION_PLAY_PAUSE:
                if (intent.hasExtra(EXTRA_INDEX)) {
                    playIndex(intent.getIntExtra(EXTRA_INDEX, 0));
                } else {
                    toggle();
                }
                break;
            case ACTION_NEXT: next(true); break;
            case ACTION_PREV: prev(); break;
            case ACTION_STOP: stopPlayback(); break;
            default: break;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        releasePlayer();
        playing = false;
        notifyListener();
        super.onDestroy();
    }

    // ---------------- 播放列表 ----------------

    /** 内置曲目 + 用户导入曲目 */
    public void reloadPlaylist() {
        playlist.clear();
        // 内置：扫 assets/music/
        try {
            String[] names = getAssets().list("music");
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String n : names) {
                    String lower = n.toLowerCase();
                    if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac")) {
                        playlist.add(new Track(prettyName(n), n, null));
                    }
                }
            }
        } catch (Exception ignored) { }

        // 导入
        for (Prefs.ImportedTrack t : Prefs.importedTracks(this)) {
            playlist.add(new Track(t.title, null, Uri.parse(t.uri)));
        }
    }

    private static String prettyName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public List<Track> tracks() { return playlist; }

    // ---------------- 播放控制 ----------------

    public void toggle() {
        if (player == null) {
            if (playlist.isEmpty()) return;
            playIndex(currentIndex >= 0 ? currentIndex : 0);
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            playing = false;
            notifyListener();
            updateNotification();
        } else {
            player.start();
            playing = true;
            startForegroundSafely();
            notifyListener();
            updateNotification();
        }
    }

    public void playIndex(int idx) {
        if (idx < 0 || idx >= playlist.size()) return;
        currentIndex = idx;
        Track t = playlist.get(idx);
        releasePlayer();
        prepared = false;
        starting = true;

        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);

        try {
            if (t.isAsset()) {
                AssetFileDescriptor fd = getAssets().openFd("music/" + t.assetName);
                player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                fd.close();
            } else {
                player.setDataSource(this, t.uri);
            }
            player.prepare();
        } catch (Exception e) {
            starting = false;
            currentTitle = t.title;
            notifyListener();
            return;
        }

        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer mp) {
                onTrackFinished();
            }
        });

        currentTitle = t.title;
        prepared = true;
        starting = false;
        player.start();
        playing = true;
        startForegroundSafely();
        notifyListener();
        updateNotification();
    }

    private void onTrackFinished() {
        switch (playMode) {
            case MODE_LOOP_ONE:
                if (player != null) { player.seekTo(0); player.start(); }
                break;
            case MODE_ONCE:
                playing = false;
                notifyListener();
                updateNotification();
                break;
            case MODE_SEQUENCE:
            default:
                next(false);
                break;
        }
    }

    public void next(boolean manual) {
        if (playlist.isEmpty()) return;
        int n = (currentIndex + 1) % playlist.size();
        playIndex(n);
    }

    public void prev() {
        if (playlist.isEmpty()) return;
        int n = (currentIndex - 1 + playlist.size()) % playlist.size();
        playIndex(n);
    }

    public void setMode(int mode) {
        playMode = mode;
        Prefs.setMusicMode(this, mode);
        notifyListener();
        updateNotification();
    }

    public void stopPlayback() {
        releasePlayer();
        playing = false;
        currentTitle = "";
        notifyListener();
        stopForeground(true);
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        prepared = false;
    }

    private void notifyListener() {
        Listener l = listener;
        if (l != null) l.onMusicStateChanged();
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                Prefs.wrap(this).getString(R.string.noti_music_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundSafely() {
        try {
            startForeground(NOTI_ID, buildNotification());
        } catch (Exception ignored) { }
    }

    private void updateNotification() {
        if (!playing && currentTitle.isEmpty()) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try { nm.notify(NOTI_ID, buildNotification()); } catch (Exception ignored) { }
    }

    private Notification buildNotification() {
        Context c = Prefs.wrap(this);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        int flags = PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pp = PendingIntent.getService(this, 1,
                new Intent(this, MusicService.class).setAction(ACTION_PLAY_PAUSE), flags);
        PendingIntent nx = PendingIntent.getService(this, 2,
                new Intent(this, MusicService.class).setAction(ACTION_NEXT), flags);
        PendingIntent pv = PendingIntent.getService(this, 3,
                new Intent(this, MusicService.class).setAction(ACTION_PREV), flags);

        String title = currentTitle.isEmpty() ? c.getString(R.string.music_nothing) : currentTitle;
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(c.getString(playing ? R.string.music_playing : R.string.music_paused))
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_previous, c.getString(R.string.music_prev), pv).build())
                .addAction(new Notification.Action.Builder(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        c.getString(playing ? R.string.music_pause : R.string.music_play), pp).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_next, c.getString(R.string.music_next), nx).build())
                .build();
    }
}
