package com.example.whalehud;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本机配置存储：Key 只写在这里，不随安装包分发、不上传。
 * 计价默认 = DeepSeek V4.1 Flash 高峰价（￥/百万 tokens）：
 *   缓存未命中输入 2 / 缓存命中输入 0.04 / 输出 8
 * 空闲时段价格 = 高峰价 × discount（默认 0.5，即减半）
 */
public final class Prefs {
    private static final String NAME = "whalehud";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // -------- 语言 --------

    /** 空字符串 = 跟随系统；否则为 ISO 语言码（zh / en / fr / ru） */
    public static String lang(Context c) { return sp(c).getString("lang", ""); }
    public static void setLang(Context c, String v) { sp(c).edit().putString("lang", v).apply(); }

    /**
     * 按保存的语言包装 Context。Activity 与 Service 都在 attachBaseContext 里调用它，
     * 这样切语言后 recreate() 就能立刻生效，不用重启进程。
     */
    public static Context wrap(Context base) {
        String code = base.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("lang", "");
        if (code == null || code.trim().isEmpty()) return base;
        java.util.Locale locale = new java.util.Locale(code);
        java.util.Locale.setDefault(locale);
        android.content.res.Configuration cfg =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        return base.createConfigurationContext(cfg);
    }

    // -------- 账号 --------
    public static String key(Context c) { return sp(c).getString("key", ""); }
    public static void setKey(Context c, String v) { sp(c).edit().putString("key", v).apply(); }

    public static String url(Context c) { return sp(c).getString("url", DeepSeek.DEFAULT_URL); }
    public static void setUrl(Context c, String v) { sp(c).edit().putString("url", v).apply(); }

    // -------- 计价 --------
    public static String model(Context c) { return sp(c).getString("model", "V4.1 Flash"); }
    public static void setModel(Context c, String v) { sp(c).edit().putString("model", v).apply(); }

    /** 高峰单价 */
    public static float missPeak(Context c) { return sp(c).getFloat("missPeak", 2f); }
    public static float hitPeak(Context c) { return sp(c).getFloat("hitPeak", 0.04f); }
    public static float outPeak(Context c) { return sp(c).getFloat("outPeak", 8f); }
    public static void setPeakPrices(Context c, float miss, float hit, float out) {
        sp(c).edit().putFloat("missPeak", miss).putFloat("hitPeak", hit).putFloat("outPeak", out).apply();
    }

    /** 空闲折扣：空闲价 = 高峰价 × 该系数（0.5 = 减半） */
    public static float discount(Context c) { return sp(c).getFloat("discount", 0.5f); }
    public static void setDiscount(Context c, float v) { sp(c).edit().putFloat("discount", v).apply(); }

    // -------- 时段 --------
    /** 0 = 自动判定，1 = 强制空闲，2 = 强制高峰 */
    public static int peakMode(Context c) { return sp(c).getInt("peakMode", 0); }
    public static void setPeakMode(Context c, int v) { sp(c).edit().putInt("peakMode", v).apply(); }

    /** 手填的节假日清单（可选），逗号/空格分隔 yyyy-MM-dd，命中当天全天按空闲算 */
    public static String holidays(Context c) { return sp(c).getString("holidays", ""); }
    public static void setHolidays(Context c, String v) { sp(c).edit().putString("holidays", v).apply(); }

    /** 是否读系统日历自动识别法定节假日 / 调休上班 */
    public static boolean useCalendar(Context c) { return sp(c).getBoolean("useCal", true); }
    public static void setUseCalendar(Context c, boolean v) { sp(c).edit().putBoolean("useCal", v).apply(); }

    // -------- 外观 --------
    /** 0 = 跟随系统，1 = 浅色，2 = 深色（Android 12+ 可原地切换） */
    public static int appearance(Context c) { return sp(c).getInt("appearance", 0); }
    public static void setAppearance(Context c, int v) { sp(c).edit().putInt("appearance", v).apply(); }

    // -------- 音乐 --------

    /** 播放模式：0 = 自动连播，1 = 单曲循环，2 = 单次播放 */
    public static int musicMode(Context c) { return sp(c).getInt("musicMode", 0); }
    public static void setMusicMode(Context c, int v) { sp(c).edit().putInt("musicMode", v).apply(); }

    /** 用户导入的曲目 */
    public static class ImportedTrack {
        public String title;
        public String uri;
        public ImportedTrack(String t, String u) { title = t; uri = u; }
    }

    public static java.util.List<ImportedTrack> importedTracks(Context c) {
        java.util.List<ImportedTrack> out = new java.util.ArrayList<ImportedTrack>();
        String raw = sp(c).getString("musicImported", "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                out.add(new ImportedTrack(o.optString("title"), o.optString("uri")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static void addImportedTrack(Context c, String title, String uri) {
        java.util.List<ImportedTrack> list = importedTracks(c);
        for (ImportedTrack t : list) {
            if (uri.equals(t.uri)) return;      // 去重
        }
        list.add(new ImportedTrack(title, uri));
        saveImported(c, list);
    }

    public static void removeImportedTrack(Context c, String uri) {
        java.util.List<ImportedTrack> list = importedTracks(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (uri.equals(list.get(i).uri)) list.remove(i);
        }
        saveImported(c, list);
    }

    private static void saveImported(Context c, java.util.List<ImportedTrack> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (ImportedTrack t : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("title", t.title);
                o.put("uri", t.uri);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        sp(c).edit().putString("musicImported", arr.toString()).apply();
    }

    // -------- 悬浮窗行为 --------
    /** 刷新间隔（秒），默认 5 秒求"实时感" */
    public static int interval(Context c) { return sp(c).getInt("interval", 5); }
    public static void setInterval(Context c, int v) { sp(c).edit().putInt("interval", v).apply(); }

    /** 0 = 带气泡整图，1 = 只留头像 */
    public static int style(Context c) { return sp(c).getInt("style", 0); }
    public static void setStyle(Context c, int v) { sp(c).edit().putInt("style", v).apply(); }

    public static int posX(Context c) { return sp(c).getInt("posx", 0); }
    public static int posY(Context c) { return sp(c).getInt("posy", 420); }
    public static void setPos(Context c, int x, int y) {
        sp(c).edit().putInt("posx", x).putInt("posy", y).apply();
    }

}
