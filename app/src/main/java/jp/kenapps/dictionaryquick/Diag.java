package jp.kenapps.dictionaryquick;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 自動移動が失敗したときの画面情報を保存・読み出しする */
public final class Diag {
    private Diag() {}

    static File file(Context c) { return new File(c.getFilesDir(), "diag.txt"); }

    static void save(Context c, String text) {
        String header = "らくらく辞書 " + BuildConfig.VERSION_NAME + " / Android " + android.os.Build.VERSION.RELEASE
                + " / " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n"
                + "time=" + new java.util.Date() + "\n";
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write((header + text).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    static String load(Context c) {
        try {
            File f = file(c);
            if (!f.exists()) return null;
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
