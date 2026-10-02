package com.example.whalehud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

/**
 * 只做一件事：拿用户自己填的 API Key 去查余额。
 * 不内置任何 Key，不会把 Key 发到别的地方（请求只发往用户填写的地址）。
 * 返回格式：优先按 DeepSeek 官方格式解析，识别不了就通用启发式找余额字段，
 * 这样换别的兼容服务也能用。
 */
public final class DeepSeek {

    public static final String DEFAULT_URL = "https://api.deepseek.com/user/balance";

    /** 通用模式下按优先级去找的字段名 */
    private static final String[] CANDIDATE_KEYS = {
            "total_balance", "totalBalance", "balance", "available_balance", "availableBalance",
            "remaining", "remain", "remain_quota", "quota", "credits", "total_available", "amount"
    };

    public static class Balance {
        public float total;
        public String currency = "CNY";
        public boolean available = true;
        public String path = "";        // 余额取自哪个字段（便于排查）
    }

    private static class Hit {
        float value; String path;
        Hit(float v, String p) { value = v; path = p; }
    }

    public static Balance fetch(String key, String url) throws Exception {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalStateException("还没填 API Key");
        }
        String target = (url == null || url.trim().isEmpty()) ? DEFAULT_URL : url.trim();
        if (!target.startsWith("http")) throw new IllegalStateException("接口地址要以 http 开头");

        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Authorization", "Bearer " + key.trim());
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        conn.disconnect();

        String body = sb.toString();
        if (code != 200) throw new IllegalStateException("HTTP " + code + " " + shortBody(body));

        JSONObject obj = new JSONObject(body);
        Balance b = new Balance();
        b.available = obj.optBoolean("is_available", true);

        // 1) DeepSeek 官方格式
        JSONArray arr = obj.optJSONArray("balance_infos");
        if (arr != null && arr.length() > 0) {
            JSONObject o = arr.getJSONObject(0);
            Float v = num(o.opt("total_balance"));
            if (v != null) {
                b.total = v;
                b.currency = o.optString("currency", "CNY");
                b.path = "balance_infos[0].total_balance";
                return b;
            }
        }
        // 2) 通用启发式
        Hit h = search(obj, "", 0);
        if (h != null) {
            b.total = h.value;
            b.path = h.path;
            return b;
        }
        throw new IllegalStateException("认不出返回里的余额字段：" + shortBody(body));
    }

    private static Hit search(Object node, String path, int depth) {
        if (node == null || depth > 6) return null;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String k : CANDIDATE_KEYS) {
                if (o.has(k)) {
                    Float v = num(o.opt(k));
                    if (v != null) return new Hit(v, path.isEmpty() ? k : path + "." + k);
                }
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object child = o.opt(k);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    Hit h = search(child, path.isEmpty() ? k : path + "." + k, depth + 1);
                    if (h != null) return h;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object child = a.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    Hit h = search(child, path + "[" + i + "]", depth + 1);
                    if (h != null) return h;
                }
            }
        }
        return null;
    }

    private static Float num(Object o) {
        if (o instanceof Number) return ((Number) o).floatValue();
        if (o instanceof String) {
            String s = ((String) o).replace(",", "").replace("¥", "").replace("￥", "")
                    .replace("$", "").trim();
            try { return Float.parseFloat(s); } catch (Exception ignored) { return null; }
        }
        return null;
    }

    private static String shortBody(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
