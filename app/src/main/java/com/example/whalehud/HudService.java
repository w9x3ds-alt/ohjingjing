package com.example.whalehud;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 悬浮窗本体：人物形象 + 一块半透明黑底余额牌。
 *
 * 交互：
 *   · 单击人物        → duang 一下 + 头顶冒随机气泡
 *   · 余额变少        → Minecraft 同款受伤反馈：整体泛红淡出 + 视角歪斜 + 位移冲击 + 受伤音效
 *   · 拖动            → 自由放置（松手只做边界收敛）
 *   · 长按            → 回设置页
 *
 * 已移除：Token 折算、今日/本次消耗、时段单价、状态文字。
 */
public class HudService extends Service {

    public static final String ACTION_SHOW = "com.example.whalehud.action.SHOW";
    public static final String ACTION_HIDE = "com.example.whalehud.action.HIDE";
    public static final String ACTION_REFRESH = "com.example.whalehud.action.REFRESH";
    public static final String ACTION_RESTYLE = "com.example.whalehud.action.RESTYLE";
    public static final String ACTION_EXIT = "com.example.whalehud.action.EXIT";

    private static final String CHANNEL_ID = "hud";
    private static final int NOTI_ID = 1;
    private static final float EPS = 0.0001f;

    public static volatile boolean visible = false;

    private WindowManager wm;
    private View view;
    private WindowManager.LayoutParams lp;
    private TextView tvBalance, tvBubble, tvDelta;
    private ImageView ivImg;

    private MediaPlayer hurtSnd;   // 掉血音效（hit.mp3）
    private MediaPlayer tapSnd;    // 点击音效（duang.mp3）

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Random rnd = new Random();
    private boolean polling = false;

    private int lastBubble = -1;
    private float lastTotal = -1f;

    private final Runnable hideBubble = new Runnable() {
        @Override public void run() {
            if (tvBubble == null) return;
            tvBubble.animate().alpha(0f).translationY(-dp(6))
                    .setDuration(Motion.FAST).setInterpolator(Motion.exit())
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (tvBubble != null) {
                                tvBubble.setVisibility(View.GONE);
                                tvBubble.setTranslationY(0f);
                            }
                        }
                    }).start();
        }
    };

    private final Runnable hideDelta = new Runnable() {
        @Override public void run() {
            if (tvDelta == null) return;
            tvDelta.animate().alpha(0f).translationY(-dp(16))
                    .setDuration(Motion.SLOW).setInterpolator(Motion.exit())
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (tvDelta != null) {
                                tvDelta.setVisibility(View.GONE);
                                tvDelta.setTranslationY(0f);
                            }
                        }
                    }).start();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            if (polling) ui.postDelayed(this, nextDelayMs());
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction()) && polling) {
                ui.removeCallbacks(tick);
                ui.post(tick);
            }
        }
    };

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Prefs.wrap(base));
    }

    /**
     * 按当前设置的语言取资源。
     *
     * 悬浮窗是常驻 Service，attachBaseContext 只在创建时跑一次——用户在设置里换了语言，
     * 这个 Service 的 Resources 仍然是旧语言。所以凡是要现给用户看的文案（语录、通知），
     * 都必须每次用这个方法现取，不能缓存在字段里。
     */
    private Context loc() {
        return Prefs.wrap(this);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        AppLog.i("Hud", "服务创建");
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        try {
            hurtSnd = MediaPlayer.create(this, R.raw.hit);
        } catch (Exception ignored) { hurtSnd = null; }
        try {
            tapSnd = MediaPlayer.create(this, R.raw.duang);
        } catch (Exception ignored) { tapSnd = null; }
        createChannel();
        startForeground(NOTI_ID, buildNotification());
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent == null) ? ACTION_SHOW : intent.getAction();
        if (action == null) action = ACTION_SHOW;
        if (ACTION_HIDE.equals(action)) {
            hide();
        } else if (ACTION_EXIT.equals(action)) {
            stopSelf();
        } else if (ACTION_RESTYLE.equals(action)) {
            show();
            applyStyle();                 // 立刻换尺寸，无需重启悬浮窗
        } else if (ACTION_REFRESH.equals(action)) {
            show();
            applyStyle();
            updateNotification();   // 语言可能刚变过，通知文案跟着换
            refresh();
        } else {
            show();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        hide();
        polling = false;
        if (hurtSnd != null) {
            try { hurtSnd.release(); } catch (Exception ignored) { }
            hurtSnd = null;
        }
        if (tapSnd != null) {
            try { tapSnd.release(); } catch (Exception ignored) { }
            tapSnd = null;
        }
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        ui.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        super.onDestroy();
    }

    // ---------------- 悬浮窗 ----------------

    private void show() {
        if (view == null) {
            view = LayoutInflater.from(this).inflate(R.layout.hud_window, null);
            tvBalance = view.findViewById(R.id.hud_balance);
            tvBubble = view.findViewById(R.id.hud_bubble);
            tvDelta = view.findViewById(R.id.hud_delta);
            ivImg = view.findViewById(R.id.hud_img);
            applyStyle();
            attachTouch();
        }
        if (!visible) {
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Prefs.posX(this);
            lp.y = Prefs.posY(this);
            try {
                wm.addView(view, lp);
                visible = true;
            } catch (Exception e) {
                visible = false;
                return;
            }
        }
        startPoll();
    }

    private void hide() {
        polling = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hideBubble);
        ui.removeCallbacks(hideDelta);
        if (view != null && visible) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        visible = false;
    }

    /** style 0 = 大，1 = 小。可热更新。 */
    private void applyStyle() {
        if (ivImg == null) return;
        int w = (Prefs.style(this) == 0) ? 134 : 104;
        int h = Math.round(w * 732f / 694f);
        ivImg.getLayoutParams().width = dp(w);
        ivImg.getLayoutParams().height = dp(h);
        ivImg.setImageResource(R.drawable.whale_char);
        ivImg.requestLayout();
    }

    private void attachTouch() {
        view.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            long downTime;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        downTime = System.currentTimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        if (!moved && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) moved = true;
                        if (moved) {
                            lp.x = startX + dx;
                            lp.y = startY + dy;
                            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            clampToScreen();
                        } else if (System.currentTimeMillis() - downTime > 550) {
                            openSettings();
                        } else {
                            poke();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void clampToScreen() {
        if (view == null || lp == null) return;
        int w = view.getWidth();
        int h = view.getHeight();
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (lp.x > sw - w) lp.x = Math.max(0, sw - w);
        if (lp.x < 0) lp.x = 0;
        if (lp.y > sh - h) lp.y = Math.max(0, sh - h);
        if (lp.y < 0) lp.y = 0;
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        Prefs.setPos(this, lp.x, lp.y);
    }

    private void openSettings() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- 动画 ----------------

    /**
     * 掉血冲击：从右上撞到左下，轻微回弹。
     * 这一档刻意保留 2.0 的顿挫手感（用户要求「掉血动画不变」），所以不复用 gentle 曲线。
     */
    private void impactShake() {
        if (ivImg == null) return;
        final float d = dp(9);
        PropertyValuesHolder px = PropertyValuesHolder.ofFloat("translationX", d, -d * 0.72f, d * 0.16f, 0f);
        PropertyValuesHolder py = PropertyValuesHolder.ofFloat("translationY", -d, d * 0.72f, -d * 0.16f, 0f);
        PropertyValuesHolder rot = PropertyValuesHolder.ofFloat("rotation", -6.5f, 4.5f, -1.2f, 0f);
        ObjectAnimator oa = ObjectAnimator.ofPropertyValuesHolder(ivImg, px, py, rot);
        oa.setDuration(Motion.SLOW + Motion.FAST);
        oa.setInterpolator(Motion.impact());
        oa.start();
    }

    /**
     * 点击反馈：一次轻柔的位移加轻微挤压，无过冲、不旋转。
     * 读起来是「被戳了一下」而不是「被撞了一下」——比 2.0 的对称抖动自然平和得多。
     */
    private void tapNudge() {
        if (ivImg == null) return;
        final float d = dp(4);
        PropertyValuesHolder px = PropertyValuesHolder.ofFloat("translationX", 0f, -d, d * 0.55f, 0f);
        PropertyValuesHolder py = PropertyValuesHolder.ofFloat("translationY", 0f, d * 0.6f, -d * 0.3f, 0f);
        PropertyValuesHolder sx = PropertyValuesHolder.ofFloat("scaleX", 1f, 1.035f, 1f);
        PropertyValuesHolder sy = PropertyValuesHolder.ofFloat("scaleY", 1f, 0.965f, 1f);
        ObjectAnimator oa = ObjectAnimator.ofPropertyValuesHolder(ivImg, px, py, sx, sy);
        oa.setDuration(Motion.SLOW);
        oa.setInterpolator(Motion.gentle());
        oa.start();
    }

    private void playTapSound() {
        if (tapSnd == null) return;
        try {
            tapSnd.seekTo(0);
            tapSnd.start();
        } catch (Exception ignored) { }
    }

    /** Minecraft 同款受伤：整体泛红后快速褪去（红屏 overlay 的微缩版） */
    private void hurtFlash() {
        if (ivImg == null) return;
        ValueAnimator va = ValueAnimator.ofInt(165, 0);
        va.setDuration(Motion.LONG + Motion.FAST);
        va.setInterpolator(Motion.impact());
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                if (ivImg == null) return;
                int al = (int) a.getAnimatedValue();
                if (al <= 0) ivImg.clearColorFilter();
                else ivImg.setColorFilter(Color.argb(al, 255, 32, 32), PorterDuff.Mode.SRC_ATOP);
            }
        });
        va.start();
    }

    private void playHurtSound() {
        if (hurtSnd == null) return;
        try {
            hurtSnd.seekTo(0);
            hurtSnd.start();
        } catch (Exception ignored) { }
    }

    /** 完整的掉血反馈：音效 + 泛红 + 冲击位移（与 2.0 一致，不做柔和化） */
    private void hurt() {
        playHurtSound();
        hurtFlash();
        impactShake();
    }

    private void poke() {
        tapNudge();
        playTapSound();
        showBubble();
    }

    private void showBubble() {
        if (tvBubble == null) return;
        // 现取，保证切换语言后立刻用新语言说话
        String[] bubbles = loc().getResources().getStringArray(R.array.bubble_texts);
        if (bubbles == null || bubbles.length == 0) return;
        int i = rnd.nextInt(bubbles.length);
        if (bubbles.length > 1) {
            int guard = 0;
            while (i == lastBubble && guard++ < 8) i = rnd.nextInt(bubbles.length);
        }
        lastBubble = i;
        tvBubble.setText(bubbles[i]);

        ui.removeCallbacks(hideBubble);
        tvBubble.animate().cancel();
        tvBubble.setVisibility(View.VISIBLE);
        tvBubble.setAlpha(0f);
        tvBubble.setScaleX(0.55f);
        tvBubble.setScaleY(0.55f);
        tvBubble.setTranslationY(dp(6));
        tvBubble.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(Motion.BASE).setInterpolator(Motion.enter()).start();
        ui.postDelayed(hideBubble, 2400);
    }

    private void popDelta(float amount) {
        if (tvDelta == null) return;
        tvDelta.setText("−¥" + fmt(amount));
        ui.removeCallbacks(hideDelta);
        tvDelta.animate().cancel();
        tvDelta.setVisibility(View.VISIBLE);
        tvDelta.setAlpha(0f);
        tvDelta.setScaleX(0.45f);
        tvDelta.setScaleY(0.45f);
        tvDelta.setTranslationY(dp(12));
        tvDelta.animate().alpha(1f).scaleX(1.06f).scaleY(1.06f).translationY(0f)
                .setDuration(Motion.BASE).setInterpolator(Motion.enter())
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (tvDelta == null) return;
                        tvDelta.animate().scaleX(1f).scaleY(1f)
                                .setDuration(Motion.FAST).setInterpolator(Motion.standard()).start();
                    }
                }).start();
        ui.postDelayed(hideDelta, 2000);
    }

    // ---------------- 轮询 ----------------

    private void startPoll() {
        if (!polling) {
            polling = true;
            ui.removeCallbacks(tick);
            ui.post(tick);
        }
    }

    private long nextDelayMs() {
        int sec = Prefs.interval(this);
        if (!isInteractive()) sec = Math.max(sec, 60);
        return sec * 1000L;
    }

    private boolean isInteractive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private void refresh() {
        final String key = Prefs.key(this);
        if (key.trim().isEmpty()) {
            if (tvBalance != null) tvBalance.setText("¥--.--");
            return;
        }
        final String url = Prefs.url(this);
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    final DeepSeek.Balance b = DeepSeek.fetch(key, url);
                    ui.post(new Runnable() {
                        @Override public void run() { apply(b); }
                    });
                } catch (final Exception e) {
                    AppLog.e("Hud", "取余额失败", e);
                    // 出错就保持上一次的数字，不再往悬浮窗上堆状态文字
                }
            }
        });
    }

    private void apply(DeepSeek.Balance b) {
        if (tvBalance == null) return;
        float total = b.total;
        tvBalance.setText("¥" + fmt(total));

        boolean changed = lastTotal >= 0f && Math.abs(total - lastTotal) >= EPS;
        if (changed) {
            float delta = total - lastTotal;
            if (delta < 0f) {
                final float drop = -delta;
                AppLog.i("Hud", "余额减少 -" + fmt(drop));
                hurt();                                    // 受伤反馈：音效 + 泛红 + 位移
                ui.postDelayed(new Runnable() {            // 随后头顶弹数字
                    @Override public void run() { if (visible && drop > 0f) popDelta(drop); }
                }, 170);
            }
            flash(delta);
        }
        lastTotal = total;
    }

    private void flash(float delta) {
        if (tvBalance == null) return;
        tvBalance.setTextColor(delta < 0f ? 0xFFFF9E4D : 0xFF6FE38A);
        tvBalance.setScaleX(1.06f);
        tvBalance.setScaleY(1.06f);
        tvBalance.animate().scaleX(1f).scaleY(1f)
                .setDuration(Motion.SLOW).setInterpolator(Motion.standard()).start();
        ui.removeCallbacks(resetColor);
        ui.postDelayed(resetColor, 1500);
    }

    private final Runnable resetColor = new Runnable() {
        @Override public void run() {
            if (tvBalance != null) tvBalance.setTextColor(0xFFFFFFFF);
        }
    };

    private static String fmt(float v) {
        return String.format(Locale.US, "%.2f", v);
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                loc().getString(R.string.noti_channel), NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Context c = loc();
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(c.getString(R.string.noti_title))
                .setContentText(c.getString(R.string.noti_text))
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    /** 语言等设置变化后，用新语言重建通知 */
    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(NOTI_ID, buildNotification());
        } catch (Exception ignored) { }
    }
}
