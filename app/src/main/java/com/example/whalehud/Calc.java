package com.example.whalehud;

import android.content.Context;

import java.util.Calendar;
import java.util.Locale;

/**
 * 时段判定（只回答一个问题：现在算高峰还是空闲）。
 *
 *   高峰时段 = 北京时间 周一~周五 09:00-12:00 与 14:00-18:00（法定节假日除外）
 *   其余时间（含周末、夜间、午休、节假日）= 空闲时段
 *
 * 注：Token 折算、混合单价、消耗统计等已在 2.0 全部移除。
 */
public final class Calc {

    public static final int MODE_AUTO = 0;
    public static final int MODE_FORCE_OFF = 1;
    public static final int MODE_FORCE_PEAK = 2;

    private static final int[][] PEAK_WINDOWS = {{9 * 60, 12 * 60}, {14 * 60, 18 * 60}};

    // ---------- 时段 ----------

    public static boolean isOffPeak(Context c, long now) {
        int mode = Prefs.peakMode(c);
        if (mode == MODE_FORCE_OFF) return true;
        if (mode == MODE_FORCE_PEAK) return false;

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int cur = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        int calInfo = HolidayCal.UNKNOWN;
        if (Prefs.useCalendar(c)) {
            Calendar dayStart = (Calendar) cal.clone();
            dayStart.set(Calendar.HOUR_OF_DAY, 0);
            dayStart.set(Calendar.MINUTE, 0);
            dayStart.set(Calendar.SECOND, 0);
            dayStart.set(Calendar.MILLISECOND, 0);
            calInfo = HolidayCal.query(c, dayStart.getTimeInMillis(),
                    dayStart.getTimeInMillis() + 24L * 60 * 60 * 1000);
        }
        if (calInfo == HolidayCal.HOLIDAY) return true;
        if (calInfo != HolidayCal.WORKDAY && isHoliday(c, cal)) return true;

        if (calInfo == HolidayCal.WORKDAY) return !inPeakWindow(cur);

        int dow = cal.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return true;

        return !inPeakWindow(cur);
    }

    private static boolean inPeakWindow(int minutes) {
        for (int[] w : PEAK_WINDOWS) {
            if (minutes >= w[0] && minutes < w[1]) return true;
        }
        return false;
    }

    /** 手填的节假日清单：命中当天全天按空闲算 */
    public static boolean isHoliday(Context c, Calendar cal) {
        String list = Prefs.holidays(c);
        if (list == null || list.trim().isEmpty()) return false;
        String today = String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        String[] parts = list.split("[,，;；、\\s]+");
        for (String p : parts) {
            if (today.equals(p.trim())) return true;
        }
        return false;
    }

    // ---------- 显示 ----------

    /** 去掉多余的小数零：2.0 → "2"，0.0400 → "0.04" */
    public static String money(float v) {
        if (v == Math.floor(v) && Math.abs(v) < 1e9) return String.valueOf((int) v);
        String s = String.format(Locale.US, "%.4f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
