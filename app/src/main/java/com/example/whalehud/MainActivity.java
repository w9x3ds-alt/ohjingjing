package com.example.whalehud;

import android.Manifest;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：单页首页 + 右上角「⋮」弹出菜单（设置 / 关于 / 语言）。
 *
 * 2.1.1 起不再使用底部三页导航：菜单以右上角展开的长方形面板呈现，
 * 设置与关于变成二级页面，左上角提供返回。
 */
public class MainActivity extends Activity {

    private static final int[] INTERVALS = {3, 5, 10, 30, 60};
    private static final long STAGGER_MS = 36L;

    private static final int VIEW_HOME = 0;
    private static final int VIEW_SETTINGS = 1;
    private static final int VIEW_ABOUT = 2;

    private WebView settingsView;
    private ScrollView pageHome, pageInfo;
    private LinearLayout menuPanel, langPanel;
    private ImageView btnMenu, btnBack;
    private View menuScrim;
    private TextView tvEndpoint, tvIntervalInfo, tvKeyState, tvStyleSub, btnPerm, hudBalance;

    private int curView = -1;
    private boolean entranceDone = false;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    /** 切语言后由系统回调，必须在这里重新包装 Context 才生效 */
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Prefs.wrap(base));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        pageHome = findViewById(R.id.page_home);
        pageInfo = findViewById(R.id.page_info);
        settingsView = findViewById(R.id.page_settings);
        menuPanel = findViewById(R.id.menu_panel);
        langPanel = findViewById(R.id.lang_panel);
        menuScrim = findViewById(R.id.menu_scrim);
        btnMenu = findViewById(R.id.btn_menu);
        btnBack = findViewById(R.id.btn_back);

        tvEndpoint = findViewById(R.id.tv_endpoint);
        tvIntervalInfo = findViewById(R.id.tv_interval_info);
        tvKeyState = findViewById(R.id.tv_key_state);
        tvStyleSub = findViewById(R.id.tv_style_sub);
        btnPerm = findViewById(R.id.btn_perm);
        hudBalance = findViewById(R.id.hud_balance);

        ((TextView) findViewById(R.id.tv_contrib_core)).setText(R.string.contributors_core);
        ((TextView) findViewById(R.id.tv_contrib_thanks)).setText(R.string.contributors_thanks);

        setupSettingsWebView();
        setupMenu();

        switchView(VIEW_HOME, false);

        btnPerm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askOverlayPermission(); }
        });
        findViewById(R.id.btn_show).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                send(HudService.ACTION_SHOW);
                toast(getString(R.string.msg_show_requested));
            }
        });
        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                send(HudService.ACTION_EXIT);
                toast(getString(R.string.msg_stopped));
            }
        });
        findViewById(R.id.btn_style).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int s = (Prefs.style(MainActivity.this) == 0) ? 1 : 0;
                Prefs.setStyle(MainActivity.this, s);
                updateHomeInfo();
                send(HudService.ACTION_RESTYLE);
                toast(getString(s == 0 ? R.string.msg_size_big : R.string.msg_size_small));
            }
        });

        Prefs.setUseCalendar(this, HolidayCal.granted(this));
        updateHomeInfo();
    }

    @Override protected void onResume() {
        super.onResume();
        updateHomeInfo();
        syncSettingsView();
    }

    @Override public void onBackPressed() {
        if (isMenuOpen()) {
            hideMenus();
            return;
        }
        if (curView != VIEW_HOME) {
            switchView(VIEW_HOME, true);
            return;
        }
        super.onBackPressed();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 9) {
            boolean ok = results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            Prefs.setUseCalendar(this, ok);
            toast(getString(ok ? R.string.msg_cal_ok : R.string.msg_cal_no));
            syncSettingsView();
        }
    }

    // ---------------- 右上角菜单 ----------------

    private void setupMenu() {
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (isMenuOpen()) hideMenus();
                else showPanel(menuPanel);
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchView(VIEW_HOME, true); }
        });
        // 点空白处收起菜单
        menuScrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideMenus(); }
        });
        setupScrollHide();

        findViewById(R.id.menu_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideMenus();
                switchView(VIEW_SETTINGS, true);
            }
        });
        findViewById(R.id.menu_about).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideMenus();
                switchView(VIEW_ABOUT, true);
            }
        });
        findViewById(R.id.menu_language).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                menuPanel.setVisibility(View.GONE);
                showPanel(langPanel);
            }
        });

        int[] langIds = {R.id.lang_system, R.id.lang_zh, R.id.lang_en, R.id.lang_fr, R.id.lang_ru};
        final String[] langCodes = {"", "zh", "en", "fr", "ru"};
        for (int i = 0; i < langIds.length; i++) {
            final String code = langCodes[i];
            findViewById(langIds[i]).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    hideMenus();
                    if (!code.equals(Prefs.lang(MainActivity.this))) {
                        Prefs.setLang(MainActivity.this, code);
                        // 悬浮窗是常驻 Service，它的资源不会随 Activity 重建而变化，
                        // 所以这里主动通知它刷新（重新读语录与通知文案）
                        send(HudService.ACTION_REFRESH);
                        recreate();          // attachBaseContext 会重新包装语言
                    }
                }
            });
        }
    }

    private boolean isMenuOpen() {
        return menuPanel.getVisibility() == View.VISIBLE || langPanel.getVisibility() == View.VISIBLE;
    }

    /** 面板从右上角展开：淡入 + 轻微缩放，用 BASE 档与 enter 曲线，与全局动效一致 */
    private void showPanel(View panel) {
        hideMenus();
        menuScrim.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-dp(6));
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(Motion.BASE)
                .setInterpolator(Motion.enter())
                .start();
    }

    private void hideMenus() {
        menuPanel.animate().cancel();
        langPanel.animate().cancel();
        menuPanel.setVisibility(View.GONE);
        langPanel.setVisibility(View.GONE);
        menuScrim.setVisibility(View.GONE);
    }

    /**
     * 向下滚动时把「⋮」收起来，向上滚动再放出来。
     * 按钮本身固定在右上角（不随内容滚动），这里只做淡入淡出与位移。
     */
    private void setupScrollHide() {
        pageHome.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int sx, int sy, int oldSx, int oldSy) {
                if (isMenuOpen()) return;
                if (sy > oldSy + 6) setMenuButtonVisible(false);
                else if (sy < oldSy - 6) setMenuButtonVisible(true);
            }
        });
    }

    private void setMenuButtonVisible(boolean show) {
        if (show) {
            if (btnMenu.getVisibility() == View.VISIBLE && btnMenu.getAlpha() == 1f) return;
            btnMenu.animate().cancel();
            btnMenu.setVisibility(View.VISIBLE);
            btnMenu.setAlpha(0f);
            btnMenu.setTranslationY(-dp(8));
            btnMenu.animate().alpha(1f).translationY(0f)
                    .setDuration(Motion.BASE)
                    .setInterpolator(Motion.enter())
                    .start();
        } else {
            if (btnMenu.getVisibility() != View.VISIBLE) return;
            btnMenu.animate().cancel();
            btnMenu.animate().alpha(0f).translationY(-dp(8))
                    .setDuration(Motion.FAST)
                    .setInterpolator(Motion.exit())
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (btnMenu.getAlpha() == 0f) btnMenu.setVisibility(View.GONE);
                        }
                    }).start();
        }
    }

    // ---------------- 视图切换 ----------------

    private void switchView(int idx, boolean animate) {
        if (curView == idx && !animate) {
            // 首次进入仍需设置可见性
        }
        View[] views = {pageHome, settingsView, pageInfo};
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (i == idx) {
                v.setVisibility(View.VISIBLE);
                if (animate) {
                    v.setAlpha(0f);
                    v.setTranslationY(dp(14));
                    v.animate().alpha(1f).translationY(0f)
                            .setDuration(Motion.SLOW)
                            .setInterpolator(Motion.enter())
                            .start();
                } else {
                    v.setAlpha(1f);
                    v.setTranslationY(0f);
                }
            } else {
                v.animate().cancel();
                v.setVisibility(View.GONE);
            }
        }
        boolean home = (idx == VIEW_HOME);
        btnMenu.animate().cancel();
        btnMenu.setAlpha(1f);
        btnMenu.setTranslationY(0f);
        btnMenu.setVisibility(home ? View.VISIBLE : View.GONE);
        btnBack.setVisibility(home ? View.GONE : View.VISIBLE);
        curView = idx;

        if (idx == VIEW_SETTINGS) syncSettingsView();
        if (idx == VIEW_HOME && !entranceDone) {
            entranceDone = true;
            playEntrance();
        }
    }

    private void playEntrance() {
        if (pageHome.getChildCount() == 0) return;
        ViewGroup col = (ViewGroup) pageHome.getChildAt(0);
        int n = col.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = col.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(18));
            v.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(Motion.INSTANT + i * STAGGER_MS)
                    .setDuration(Motion.SLOW)
                    .setInterpolator(Motion.enter())
                    .start();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- 设置页（WebView） ----------------

    private void setupSettingsWebView() {
        settingsView.setBackgroundColor(isDark() ? Color.BLACK : Color.parseColor("#F4F5F7"));
        settingsView.getSettings().setJavaScriptEnabled(true);
        settingsView.getSettings().setDomStorageEnabled(false);
        settingsView.getSettings().setAllowFileAccess(false);
        settingsView.addJavascriptInterface(new Bridge(), "Android");
        settingsView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pushBootstrap();
            }
        });
        settingsView.loadUrl("file:///android_asset/settings.html");
    }

    private void pushBootstrap() {
        if (settingsView == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("dark", isDark());

            JSONObject s = new JSONObject();
            s.put("lang", Prefs.lang(this).isEmpty()
                    ? Locale.getDefault().getLanguage() : Prefs.lang(this));
            s.put("page_settings", getString(R.string.page_settings));
            s.put("group_account", getString(R.string.group_account));
            s.put("label_api_key", getString(R.string.label_api_key));
            s.put("hint_key_local", getString(R.string.hint_key_local));
            s.put("label_endpoint_url", getString(R.string.label_endpoint_url));
            s.put("hint_endpoint_note", getString(R.string.hint_endpoint_note));
            s.put("btn_test", getString(R.string.btn_test));
            s.put("group_pricing", getString(R.string.group_pricing));
            s.put("label_model", getString(R.string.label_model));
            s.put("label_price_miss", getString(R.string.label_price_miss));
            s.put("label_price_hit", getString(R.string.label_price_hit));
            s.put("label_price_out", getString(R.string.label_price_out));
            s.put("label_discount", getString(R.string.label_discount));
            s.put("label_discount_sub", getString(R.string.label_discount_sub));
            s.put("unit_per_million", getString(R.string.unit_per_million));
            s.put("group_peak", getString(R.string.group_peak));
            s.put("label_peak_mode", getString(R.string.label_peak_mode));
            s.put("label_calendar", getString(R.string.label_calendar));
            s.put("label_holidays", getString(R.string.label_holidays));
            s.put("group_run", getString(R.string.group_run));
            s.put("label_interval", getString(R.string.label_interval));
            s.put("label_appearance", getString(R.string.label_appearance));
            s.put("more_soon", getString(R.string.more_soon));
            root.put("strings", s);

            JSONObject p = new JSONObject();
            p.put("key", Prefs.key(this));
            p.put("url", Prefs.url(this));
            p.put("model", Prefs.model(this));
            p.put("miss", Calc.money(Prefs.missPeak(this)));
            p.put("hit", Calc.money(Prefs.hitPeak(this)));
            p.put("out", Calc.money(Prefs.outPeak(this)));
            p.put("discount", Calc.money(Prefs.discount(this)));
            p.put("holidays", Prefs.holidays(this));
            p.put("peakMode", Prefs.peakMode(this));
            p.put("interval", intervalIndex(Prefs.interval(this)));
            p.put("appearance", Prefs.appearance(this));
            p.put("peakModes", arrayToJson(R.array.peak_modes));
            p.put("intervalNames", arrayToJson(R.array.interval_names));
            p.put("appearanceNames", arrayToJson(R.array.appearance_names));
            p.put("calText", calText());
            p.put("msg", "");
            root.put("prefs", p);

            settingsView.evaluateJavascript(
                    "window.__hudSettings.bind(" + JSONObject.quote(root.toString()) + ")", null);
        } catch (Exception ignored) { }
    }

    private void syncSettingsView() {
        if (settingsView == null) return;
        settingsView.evaluateJavascript(
                "window.__hudSettings && window.__hudSettings.setTheme(" + isDark() + ")", null);
        settingsView.evaluateJavascript(
                "window.__hudSettings && window.__hudSettings.setCalState("
                        + JSONObject.quote(calText()) + ")", null);
    }

    /**
     * 字符串数组 → 真正的 JSON 数组。
     *
     * 注意：这里必须返回 JSONArray，不能返回拼接出来的字符串。
     * 早期版本用字符串拼 "[\"a\",\"b\"]"，嵌进 JSON 后 JS 收到的是 String 而不是 Array，
     * 于是 list[i] 取到的是单个字符（界面上表现为选项被逐字竖排展开）。
     */
    private org.json.JSONArray arrayToJson(int arrayRes) {
        org.json.JSONArray ja = new org.json.JSONArray();
        String[] arr = getResources().getStringArray(arrayRes);
        for (String s : arr) ja.put(s);
        return ja;
    }

    private int intervalIndex(int seconds) {
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == seconds) return i;
        }
        return 1;
    }

    private boolean isDark() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 设置页与 Java 之间的桥 */
    public class Bridge {

        @JavascriptInterface
        public void save(String json) {
            try {
                JSONObject o = new JSONObject(json);
                Prefs.setKey(MainActivity.this, o.optString("key", "").trim());
                Prefs.setModel(MainActivity.this, or(o.optString("model", ""), "V4.1 Flash"));
                String url = o.optString("url", "").trim();
                Prefs.setUrl(MainActivity.this, url.isEmpty() ? DeepSeek.DEFAULT_URL : url);
                Prefs.setPeakPrices(MainActivity.this,
                        f(o, "miss", 2f), f(o, "hit", 0.04f), f(o, "out", 8f));
                Prefs.setDiscount(MainActivity.this, f(o, "discount", 0.5f));
                Prefs.setHolidays(MainActivity.this, o.optString("holidays", "").trim());
                Prefs.setPeakMode(MainActivity.this, clamp(o.optInt("peakMode", 0), 0, 2));
                Prefs.setInterval(MainActivity.this,
                        INTERVALS[clamp(o.optInt("interval", 1), 0, INTERVALS.length - 1)]);
                runOnUiThread(new Runnable() {
                    @Override public void run() { updateHomeInfo(); }
                });
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void action(final String name) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if ("test".equals(name)) test();
                    else if ("calendar".equals(name)) askCalendar();
                }
            });
        }

        @JavascriptInterface
        public void onPrefChanged(final String id, final int value) {
            if (!"appearance".equals(id)) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Prefs.setAppearance(MainActivity.this, value);
                    applyAppearance(value);
                }
            });
        }
    }

    private static String or(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private static float f(JSONObject o, String key, float def) {
        try {
            float v = Float.parseFloat(o.optString(key, ""));
            return v < 0f ? 0f : v;
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------------- 外观 ----------------

    private void applyAppearance(int mode) {
        if (Build.VERSION.SDK_INT >= 31) {
            UiModeManager um = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            if (um != null) {
                int m = UiModeManager.MODE_NIGHT_AUTO;
                if (mode == 1) m = UiModeManager.MODE_NIGHT_NO;
                else if (mode == 2) m = UiModeManager.MODE_NIGHT_YES;
                try {
                    um.setApplicationNightMode(m);
                } catch (Exception e) {
                    webMsg(getString(R.string.msg_night_unsupported));
                }
            }
        }
    }

    // ---------------- 数据 ----------------

    private void updateHomeInfo() {
        if (tvEndpoint == null) return;
        String url = Prefs.url(this);
        tvEndpoint.setText(url.replace("https://", "").replace("/user/balance", ""));
        tvIntervalInfo.setText(getString(R.string.interval_seconds, Prefs.interval(this)));
        tvKeyState.setText(Prefs.key(this).trim().isEmpty() ? R.string.key_empty : R.string.key_filled);
        tvStyleSub.setText(Prefs.style(this) == 0 ? R.string.size_big : R.string.size_small);
        if (btnPerm != null) {
            boolean ok = overlayGranted();
            btnPerm.setText(ok ? R.string.perm_granted : R.string.perm_denied);
            btnPerm.setBackgroundResource(ok ? R.drawable.pill_accent : R.drawable.pill_soft);
            btnPerm.setTextColor(getResources().getColor(ok ? R.color.text_on_accent : R.color.text_secondary));
        }
    }

    private boolean overlayGranted() {
        return Settings.canDrawOverlays(this);
    }

    private void send(String action) {
        Intent i = new Intent(this, HudService.class);
        i.setAction(action);
        startForegroundService(i);
    }

    private void askOverlayPermission() {
        if (overlayGranted()) {
            toast(getString(R.string.msg_perm_ok));
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            toast(getString(R.string.msg_perm_go));
        } catch (Exception e) {
            toast(getString(R.string.msg_perm_manual));
        }
    }

    private void askCalendar() {
        if (HolidayCal.granted(this)) {
            Prefs.setUseCalendar(this, true);
            syncSettingsView();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 9);
        }
    }

    private String calText() {
        if (!HolidayCal.granted(this)) return getString(R.string.cal_denied);
        Prefs.setUseCalendar(this, true);
        Calendar d = Calendar.getInstance();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        long start = d.getTimeInMillis();
        int info = HolidayCal.query(this, start, start + 24L * 60 * 60 * 1000);
        return HolidayCal.describe(this, info);
    }

    private void test() {
        final String key = Prefs.key(this);
        final String url = Prefs.url(this);
        webMsg(getString(R.string.msg_connecting));
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    final DeepSeek.Balance b = DeepSeek.fetch(key, url);
                    final String s = getString(R.string.msg_connect_ok,
                            b.currency, String.format(Locale.US, "%.2f", b.total), b.path)
                            + (b.available ? "" : "\n" + getString(R.string.msg_connect_bad));
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            webMsg(s);
                            if (hudBalance != null) {
                                hudBalance.setText("¥" + String.format(Locale.US, "%.2f", b.total));
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            webMsg(getString(R.string.msg_connect_fail, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void webMsg(String s) {
        toast(s);
        if (settingsView != null) {
            settingsView.evaluateJavascript(
                    "window.__hudSettings && window.__hudSettings.setMsg(" + JSONObject.quote(s) + ")", null);
        }
    }
}
