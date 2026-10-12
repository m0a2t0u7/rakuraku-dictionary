package jp.kenapps.dictionaryquick;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** GitHub Releases の最新版を確認する（バックグラウンドスレッドで呼ぶ） */
public final class Updater {
    static final String REPO = "m0a2t0u7/rakuraku-dictionary";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";

    public static final class Result {
        public String version;   // 例 0.6.1
        public String apkUrl;    // APKの直接ダウンロードURL（無ければnull）
        public boolean newer;
    }

    private Updater() {}

    static Result check(String currentVersion) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
            JSONObject json = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            Result res = new Result();
            res.version = json.optString("tag_name", "").replaceFirst("^v", "");
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) { res.apkUrl = a.optString("browser_download_url"); break; }
                }
            }
            res.newer = compare(res.version, currentVersion) > 0;
            return res;
        } finally {
            con.disconnect();
        }
    }

    /** "0.6.10" と "0.6.9" のような版番号を数値で比較 */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }
}
