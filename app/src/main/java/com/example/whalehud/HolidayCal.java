package com.example.whalehud;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;

/**
 * 从系统日历里判断"今天是不是法定节假日 / 是不是调休上班"。
 * 国内 ROM 一般自带"中国节假日 / 节假日"日历源，事件标题形如：
 *   「国庆节」「中秋节」「春节」「元旦」「劳动节」…（放假）
 *   「10月11日上班」「补班」「调休上班」…（调休）
 * 查不到就返回 UNKNOWN，交给手填的节假日清单兜底。
 */
public final class HolidayCal {

    public static final int UNKNOWN = 0;
    public static final int HOLIDAY = 1;    // 法定节假日 → 全天按空闲
    public static final int WORKDAY = 2;    // 调休上班 → 按工作日算高峰窗口

    public static boolean granted(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** begin/end 为查询区间（一般取当天 00:00 ~ 次日 00:00），单位毫秒 */
    public static int query(Context c, long begin, long end) {
        if (!granted(c)) return UNKNOWN;
        Cursor cur = null;
        try {
            String[] proj = {
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.ALL_DAY
            };
            cur = CalendarContract.Instances.query(c.getContentResolver(), proj, begin, end);
            if (cur == null) return UNKNOWN;
            boolean holiday = false, work = false;
            while (cur.moveToNext()) {
                String title = cur.getString(0);
                if (title == null) continue;
                String t = title.trim();
                if (isWorkTitle(t)) work = true;
                else if (isHolidayTitle(t)) holiday = true;
            }
            if (holiday) return HOLIDAY;
            if (work) return WORKDAY;
            return UNKNOWN;
        } catch (Exception e) {
            return UNKNOWN;
        } finally {
            if (cur != null) {
                try { cur.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** 查询结果的可读描述（走资源，支持多语言） */
    public static String describe(Context c, int info) {
        if (info == HOLIDAY) return c.getString(R.string.cal_state_holiday);
        if (info == WORKDAY) return c.getString(R.string.cal_state_workday);
        return c.getString(R.string.cal_state_unknown);
    }

    private static boolean isWorkTitle(String t) {
        return t.contains("上班") || t.contains("补班") || t.contains("工作日")
                || t.contains("调休上") || t.equals("班");
    }

    private static boolean isHolidayTitle(String t) {
        String lower = t.toLowerCase();
        if (lower.contains("holiday")) return true;
        return t.contains("节") || t.contains("放假") || t.contains("假日")
                || t.contains("元旦") || t.contains("春节") || t.contains("清明")
                || t.contains("劳动") || t.contains("端午") || t.contains("中秋")
                || t.contains("国庆") || t.contains("休假");
    }
}
