package com.example.whalehud;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 应用内日志。
 *
 * 为什么要自己记一份：普通应用读不到系统 logcat（需要 READ_LOGS 权限或 root），
 * 一旦线上出问题，用户没有任何办法把现场交出来——只能说"它崩了"。
 * 所以关键路径自己留痕，用户可以一键复制或分享。
 *
 * 三重保障：
 *   1. 内存环形缓冲（最近 800 条），随时可看
 *   2. 追加写入 filesDir/whalehud.log，超过 256 KB 自动滚动
 *   3. 全局未捕获异常处理器 —— 闪退也有记录
 */
public final class AppLog {

    private static final String FILE_NAME = "whalehud.log";
    private static final int MAX_MEM = 800;
    private static final long MAX_FILE = 256 * 1024;

    private static final ArrayDeque<String> BUF = new ArrayDeque<String>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static Context ctx;
    private static boolean installed = false;

    private AppLog() { }

    /** 在 Application / Activity 最早的地方调用一次 */
    public static void init(Context c) {
        if (installed) return;
        installed = true;
        ctx = c.getApplicationContext();
        installCrashHandler();
        i("App", "===== 日志启动 =====");
        i("App", "device=" + Build.MANUFACTURER + " " + Build.MODEL
                + "  android=" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        i("App", "app=" + versionName());
    }

    public static void d(String tag, String msg) { log("D", tag, msg); }
    public static void i(String tag, String msg) { log("I", tag, msg); }
    public static void w(String tag, String msg) { log("W", tag, msg); }
    public static void e(String tag, String msg) { log("E", tag, msg); }

    public static void e(String tag, String msg, Throwable t) {
        log("E", tag, msg + " :: " + stack(t));
    }

    private static void log(String level, String tag, String msg) {
        String line = FMT.format(new Date()) + " " + level + "/" + tag + ": " + msg;

        synchronized (BUF) {
            BUF.addLast(line);
            while (BUF.size() > MAX_MEM) BUF.removeFirst();
        }
        // 系统日志也写一份，接电脑调试时能看到
        try {
            if ("E".equals(level)) Log.e(tag, msg);
            else if ("W".equals(level)) Log.w(tag, msg);
            else Log.i(tag, msg);
        } catch (Throwable ignored) { }

        appendToFile(line);
    }

    private static void appendToFile(String line) {
        if (ctx == null) return;
        try {
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            if (f.length() > MAX_FILE) {
                File old = new File(ctx.getFilesDir(), FILE_NAME + ".1");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            FileWriter w = new FileWriter(f, true);
            w.write(line);
            w.write('\n');
            w.close();
        } catch (Throwable ignored) { }
    }

    /** 全部日志（内存缓冲 + 上一份滚动文件） */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        if (ctx != null) {
            File old = new File(ctx.getFilesDir(), FILE_NAME + ".1");
            if (old.exists()) {
                try {
                    sb.append(readFile(old)).append('\n');
                } catch (Throwable ignored) { }
            }
        }
        synchronized (BUF) {
            for (String s : BUF) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (BUF) { BUF.clear(); }
        if (ctx == null) return;
        try {
            new File(ctx.getFilesDir(), FILE_NAME).delete();
            new File(ctx.getFilesDir(), FILE_NAME + ".1").delete();
        } catch (Throwable ignored) { }
        i("App", "日志已清空");
    }

    public static int size() {
        synchronized (BUF) { return BUF.size(); }
    }

    // ---------------- 内部 ----------------

    private static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                // 先把现场落盘，再交回系统
                try {
                    log("E", "CRASH", "线程 " + t.getName() + " 未捕获异常 :: " + stack(e));
                    log("E", "CRASH", "完整堆栈:\n" + full(e));
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private static String stack(Throwable t) {
        if (t == null) return "(null)";
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        // 只取前几行，够定位即可
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, lines.length); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }

    private static String full(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String readFile(File f) {
        try {
            byte[] d = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(d);
            in.close();
            return new String(d, 0, Math.max(0, n), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String versionName() {
        if (ctx == null) return "?";
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }
}
