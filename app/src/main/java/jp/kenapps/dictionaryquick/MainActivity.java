package jp.kenapps.dictionaryquick;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    static final String GBOARD = DictionaryNavigator.PACKAGE;
    static final int REQ_CSV = 10, REQ_BACKUP = 11, REQ_ZIP = 12;
    static final int MENU_NAV = 1, MENU_ZIP = 2, MENU_BACKUP = 3, MENU_DIAG = 4, MENU_UPDATE = 5;

    final int teal = Color.rgb(17, 111, 104);
    final int ink = Color.rgb(28, 43, 54);
    final List<View> actionViews = new ArrayList<>();
    boolean busy = false;
    Thread csvWorker;
    int workGeneration = 0;
    JSONArray entries;
    JSONArray exportEntries;
    CsvImport preview;
    LinearLayout root;
    TextView status;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        entries = load();
        try {
            exportEntries = new JSONArray(getPreferences(MODE_PRIVATE).getString("lastExport", entries.toString()));
        } catch (Exception e) {
            exportEntries = entries;
        }
        int bg = Color.rgb(245, 247, 248);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setNavigationBarColor(bg);
        getWindow().setStatusBarColor(bg);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(24));
        scroll.addView(root);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("らくらく辞書", 27);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView more = label("⋮", 26);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("設定とバックアップ");
        more.setBackground(ripple(Color.TRANSPARENT));
        more.setFocusable(true);
        more.setClickable(true);
        buttonRole(more);
        header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        more.setOnClickListener(v -> { if (!busy) showMenu(more); });
        actionViews.add(more);
        root.addView(header);

        TextView sub = label("Gboardの日本語辞書へ、すぐに。  v" + BuildConfig.VERSION_NAME, 14);
        sub.setTextColor(Color.rgb(88, 106, 114));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(9);
        root.addView(sub, sp);

        String initial = "";
        if (saved != null) initial = saved.getString("status", "");
        else if (!DictionaryNavigator.enabled(this)) initial = "初回は右上の⋮で、自動移動を設定。";
        status = label(initial, 14);
        status.setLineSpacing(dp(4), 1f);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(20);
        root.addView(status, stp);

        // 片手操作用：ボタンは画面の下にまとめ、いちばん押す「新しく登録」を最下段（親指の位置）に置く
        root.addView(new View(this), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView note = label("単語の保存はGboardの画面で行います。", 12);
        note.setTextColor(Color.rgb(100, 117, 122));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(20);
        np.bottomMargin = dp(8);
        root.addView(note, np);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        root.addView(actions, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        actions.addView(action("↓", "CSVで一括登録", "内容を確認してZIPに変換", false, this::chooseCsv));
        actions.addView(action("≡", "日本語の単語リスト", "登録内容を確認・編集", false, () -> openDictionary(false)));
        actions.addView(action("＋", "新しく登録", "単語とよみを入力", true, () -> openDictionary(true)));
        if (saved != null && saved.getBoolean("wasBusy")) {
            status.setText("CSVの読み込みを中断しました。もう一度CSVを選んでください。");
        }
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof CsvImport) {
            preview = (CsvImport) retained;
            root.post(() -> showCsvPreview(preview));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (status == null) return;
        SharedPreferences nav = getSharedPreferences("navigation", MODE_PRIVATE);
        String result = nav.getString("result", null);
        if (result != null) {
            status.setText(result);
            nav.edit().remove("result").apply();
        } else if (DictionaryNavigator.deadline > 0) {
            String progress = nav.getString("progress", "自動移動中");
            DictionaryNavigator.cancel();
            status.setText("アプリに戻ったため自動移動を終了しました。\n" + progress + "\n登録画面が開いていない場合はもう一度お試しください。");
        }
    }

    @Override protected void onDestroy() {
        workGeneration++;
        if (csvWorker != null) csvWorker.interrupt();
        super.onDestroy();
    }

    @Override public Object onRetainNonConfigurationInstance() { return preview; }

    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("status", status.getText().toString());
        out.putBoolean("wasBusy", busy);
    }

    // ---------- メニュー ----------

    void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, MENU_NAV, 0, "自動移動の設定");
        if (entries.length() > 0) {
            m.getMenu().add(0, MENU_ZIP, 1, "前回の辞書ZIPを再出力");
            m.getMenu().add(0, MENU_BACKUP, 2, "読み込んだCSVのバックアップ");
        }
        m.getMenu().add(0, MENU_UPDATE, 3, "更新を確認");
        if (Diag.file(this).exists()) m.getMenu().add(0, MENU_DIAG, 4, "診断ログを共有");
        m.setOnMenuItemClickListener(item -> {
            if (busy) return true;
            switch (item.getItemId()) {
                case MENU_NAV: enableNavigation(); break;
                case MENU_ZIP: createZip(); break;
                case MENU_BACKUP: {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("text/csv");
                    i.putExtra(Intent.EXTRA_TITLE, "rakuraku-dictionary.csv");
                    startActivityForResult(i, REQ_BACKUP);
                    break;
                }
                case MENU_UPDATE: checkUpdate(); break;
                case MENU_DIAG: shareDiag(); break;
            }
            return true;
        });
        m.show();
    }

    void shareDiag() {
        String text = Diag.load(this);
        if (text == null) { status.setText("診断ログはまだありません。"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "らくらく辞書 診断ログ");
        i.putExtra(Intent.EXTRA_TEXT, text);
        launch(Intent.createChooser(i, "診断ログを共有"));
    }

    void checkUpdate() {
        status.setText("更新を確認しています…");
        new Thread(() -> {
            try {
                Updater.Result r = Updater.check(BuildConfig.VERSION_NAME);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!r.newer) {
                        status.setText("最新版です（v" + BuildConfig.VERSION_NAME + "）。");
                        return;
                    }
                    status.setText("新しい版 v" + r.version + " があります。");
                    String url = r.apkUrl != null ? r.apkUrl : Updater.RELEASES_PAGE;
                    new AlertDialog.Builder(this)
                            .setTitle("更新があります")
                            .setMessage("v" + BuildConfig.VERSION_NAME + " → v" + r.version
                                    + "\n\nAPKをダウンロードして開くと、上書きでインストールできます。")
                            .setNegativeButton("あとで", null)
                            .setPositiveButton("ダウンロード", (d, w) -> launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url))))
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("更新を確認できませんでした。通信状態を確認してください。"));
            }
        }).start();
    }

    void enableNavigation() {
        new AlertDialog.Builder(this)
                .setTitle("Gboard画面への自動移動")
                .setMessage("この機能はAndroidのユーザー補助を使います。\n設定で「らくらく辞書・画面移動」を有効にしてください。\n\n"
                        + "このサービスはGboardの画面を読み取って、単語リスト→日本語→＋を選びます。アプリでボタンを押した時だけ動作し、20秒で停止します。文字入力・保存・削除は行いません。\n\n"
                        + "POCO・Xiaomiでは、このアプリの「自動起動」を許可し、バッテリーを「制限なし」にすると止まりにくくなります。\n\n"
                        + "有効にしたらこのアプリに戻って、登録画面を開くボタンを押してください。")
                .setNegativeButton("閉じる", null)
                .setPositiveButton("ユーザー補助の設定を開く", (d, w) -> launch(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .show();
    }

    // ---------- Gboardを開く ----------

    void openDictionary(boolean wantAdd) {
        if (DictionaryNavigator.enabled(this)) {
            DictionaryNavigator.request(this, wantAdd);
            if (!openGboardSettings()) {
                DictionaryNavigator.cancel();
                status.setText("Gboard設定を開けませんでした。");
            } else {
                status.setText(wantAdd ? "日本語の登録画面へ移動しています…" : "日本語の単語リストへ移動しています…");
            }
            return;
        }
        if (wantAdd && launch(addExtras(new Intent("com.android.settings.USER_DICTIONARY_INSERT").setPackage(GBOARD)))) {
            status.setText("Gboardの登録画面を開きました。入力内容を確認し、Gboard側で保存してください。");
            return;
        }
        try {
            PackageInfo info = getPackageManager().getPackageInfo(GBOARD, PackageManager.GET_ACTIVITIES);
            if (info.activities != null) {
                List<ActivityInfo> list = new ArrayList<>();
                for (ActivityInfo a : info.activities) {
                    String n = a.name.toLowerCase(Locale.ROOT);
                    if (a.exported && a.enabled && (n.contains("userdictionary") || n.contains("personaldictionary"))) list.add(a);
                }
                list.sort((a, b) -> {
                    boolean x = a.name.toLowerCase(Locale.ROOT).contains("add");
                    boolean y = b.name.toLowerCase(Locale.ROOT).contains("add");
                    return wantAdd ? Boolean.compare(y, x) : Boolean.compare(x, y);
                });
                for (ActivityInfo a : list) {
                    Intent i = new Intent().setComponent(new ComponentName(GBOARD, a.name));
                    if (wantAdd) addExtras(i);
                    if (launch(i)) {
                        status.setText("Gboardの辞書画面を開きました。単語とよみを確認し、必要なら貼り付けて保存してください。");
                        return;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (launch(new Intent("android.settings.USER_DICTIONARY_SETTINGS").setPackage(GBOARD))) {
            status.setText("Gboardの単語リストを開きました。日本語を選んで登録・インポートしてください。");
            return;
        }
        status.setText("直接移動はこのGboardで利用できません。右上メニューの「自動移動の設定」を有効にすると、日本語の登録画面まで移動できます。");
        enableNavigation();
    }

    boolean openGboardSettings() {
        return DictionaryNavigator.openGboard(this);
    }

    Intent addExtras(Intent i) { i.putExtra("locale", "ja"); return i; }

    boolean launch(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------- CSV ----------

    void chooseCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_CSV);
    }

    void readCsv(Uri uri) {
        setBusy(true);
        status.setText("CSVの内容を確認しています…");
        int gen = ++workGeneration;
        csvWorker = new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("ファイルを開けませんでした");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted()) return;
                    bo.write(buf, 0, r);
                    if (bo.size() > 2_000_000) throw new IOException("ファイルは2MB以内で選んでください");
                }
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bo.toByteArray())).toString();
                CsvImport result = CsvImport.from(text);
                runOnUiThread(() -> {
                    if (!usable(gen)) return;
                    setBusy(false);
                    if (result.rows.isEmpty()) status.setText("取り込める行がありません。UTF-8のCSVで、1列目によみ、2列目に単語を入れてください。");
                    else showCsvPreview(result);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!usable(gen)) return;
                    setBusy(false);
                    String detail = (e instanceof IllegalArgumentException || e instanceof IOException) ? e.getMessage() : "別のファイルをお試しください。";
                    status.setText("CSVを読み込めませんでした。\nUTF-8のCSVで、1列目によみ、2列目に単語を入れてください。\n" + detail);
                });
            }
        });
        csvWorker.start();
    }

    boolean usable(int gen) { return !isFinishing() && !isDestroyed() && gen == workGeneration; }

    void showCsvPreview(CsvImport p) {
        if (p == null || isFinishing() || isDestroyed()) return;
        preview = p;
        status.setText("CSVの確認が終わりました。");
        StringBuilder sb = new StringBuilder("登録する単語：" + p.rows.size() + "件\n重複：" + p.duplicates + "件 / 形式が合わない行：" + p.invalid + "件\n\n");
        for (int i = 0; i < Math.min(3, p.rows.size()); i++) {
            String[] r = p.rows.get(i);
            sb.append(shorten(r[0])).append(" → ").append(shorten(r[1])).append('\n');
        }
        sb.append("\n続いて辞書ZIPの保存先を選びます。");
        new AlertDialog.Builder(this)
                .setTitle("取り込み内容を確認")
                .setMessage(sb.toString())
                .setNegativeButton("キャンセル", (d, w) -> { preview = null; status.setText("CSVの取り込みをキャンセルしました。"); })
                .setOnCancelListener(d -> { preview = null; status.setText("CSVの取り込みをキャンセルしました。"); })
                .setPositiveButton("辞書ZIPを保存", (d, w) -> { preview = null; importRows(p.rows); })
                .show();
    }

    String shorten(String s) { return s.length() <= 22 ? s : s.substring(0, 22) + "…"; }

    void importRows(List<String[]> rows) {
        if (rows.isEmpty()) {
            status.setText("取り込める行がありません。UTF-8・よみ,単語の2列CSVを選んでください。");
            return;
        }
        JSONArray before = load();
        JSONArray export = new JSONArray();
        Set<List<String>> seen = new HashSet<>();
        try {
            for (String[] r : rows) {
                add(r[0], r[1]);
                if (seen.add(Arrays.asList(r[0], r[1]))) {
                    export.put(new JSONObject().put("reading", r[0]).put("word", r[1]));
                }
            }
        } catch (Exception e) {
            entries = before;
            status.setText("取り込みに失敗しました。");
            return;
        }
        if (getPreferences(MODE_PRIVATE).edit().putString("entries", entries.toString()).putString("lastExport", export.toString()).commit()) {
            exportEntries = export;
            status.setText("CSVを読み込みました。辞書ZIPの保存先を選んでください。");
            createZip();
        } else {
            entries = before;
            status.setText("取り込みに失敗しました。");
        }
    }

    boolean add(String reading, String word) throws Exception {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject o = entries.optJSONObject(i);
            if (o != null && reading.equals(o.optString("reading")) && word.equals(o.optString("word"))) return false;
        }
        entries.put(new JSONObject().put("reading", reading).put("word", word));
        return true;
    }

    JSONArray load() {
        try {
            return new JSONArray(getPreferences(MODE_PRIVATE).getString("entries", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    void createZip() {
        if (exportEntries.length() == 0) { status.setText("CSVを選んでください。"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, "PersonalDictionary.zip");
        startActivityForResult(i, REQ_ZIP);
    }

    void writeGboardZip(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("保存先を開けません");
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < exportEntries.length(); i++) {
                JSONObject o = exportEntries.optJSONObject(i);
                rows.add(new String[]{o.optString("reading"), o.optString("word")});
            }
            GboardZip.write(out, rows);
            status.setText("Gboard用ZIPを保存しました（" + rows.size() + "件）。まだGboardには登録されていません。");
            new AlertDialog.Builder(this)
                    .setTitle("Gboardに取り込む")
                    .setMessage("Gboardの設定で、単語リスト（辞書）→日本語→右上の︙→インポートを開き、保存したPersonalDictionary.zipを選んでください。\n端末により単語リストを2回選ぶ場合があります。\n既に取り込んだ単語は、再度取り込む前にGboard側で確認してください。")
                    .setNegativeButton("閉じる", null)
                    .setPositiveButton("Gboardの単語リストを開く", (d, w) -> openDictionary(false))
                    .show();
        } catch (Exception e) {
            status.setText("ZIPを書き出せませんでした。\n" + e.getMessage());
        }
    }

    void writeBackup(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException();
            StringBuilder sb = new StringBuilder("﻿よみ,単語\r\n");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject o = entries.optJSONObject(i);
                sb.append(Csv.quote(o.optString("reading"))).append(',').append(Csv.quote(o.optString("word"))).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            status.setText("読み込んだCSVのバックアップを保存しました。Gboardの辞書全体を保存するものではありません。");
        } catch (Exception e) {
            status.setText("CSVを書き出せませんでした。");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (req == REQ_ZIP) writeGboardZip(uri);
            else if (req == REQ_BACKUP) writeBackup(uri);
            else if (req == REQ_CSV) readCsv(uri);
            return;
        }
        if (req == REQ_CSV) status.setText("CSVの選択をキャンセルしました。");
        else if (req == REQ_ZIP) status.setText("ZIPの保存をキャンセルしました。右上メニューから再出力できます。");
        else if (req == REQ_BACKUP) status.setText("バックアップをキャンセルしました。");
    }

    // ---------- 見た目 ----------

    View action(String icon, String titleText, String subText, boolean primary, Runnable run) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(20), dp(18), dp(20));
        row.setBackground(ripple(primary ? teal : Color.WHITE));
        row.setMinimumHeight(dp(96));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(titleText + "。" + subText);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        buttonRole(row);

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon.equals("＋") ? R.drawable.icon_add : icon.equals("≡") ? R.drawable.icon_list : R.drawable.icon_import);
        iv.setColorFilter(primary ? Color.WHITE : teal);
        iv.setPadding(dp(11), dp(11), dp(11), dp(11));
        iv.setBackground(bg(primary ? Color.rgb(44, 135, 126) : Color.rgb(231, 243, 239), 13));
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView t = label(titleText, 18);
        t.setTextColor(primary ? Color.WHITE : ink);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        texts.addView(t);
        TextView s = label(subText, 13);
        s.setTextColor(primary ? Color.rgb(219, 240, 235) : Color.rgb(88, 106, 114));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(7);
        texts.addView(s, slp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(14);
        row.addView(texts, tlp);

        TextView chevron = label("›", 24);
        chevron.setTextColor(primary ? Color.WHITE : Color.rgb(110, 134, 138));
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(32)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> {
            if (busy) return;
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            run.run();
        });
        actionViews.add(row);
        return row;
    }

    void buttonRole(View v) {
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
    }

    void setBusy(boolean b) {
        busy = b;
        for (View v : actionViews) {
            v.setEnabled(!b);
            v.setAlpha(b ? 0.55f : 1f);
        }
    }

    TextView label(String text, int size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(ink);
        t.setIncludeFontPadding(false);
        return t;
    }

    GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    RippleDrawable ripple(int color) {
        return new RippleDrawable(ColorStateList.valueOf(Color.rgb(196, 225, 218)), bg(color, 18), bg(Color.WHITE, 18));
    }

    int dp(int v) { return (int) (getResources().getDisplayMetrics().density * v + 0.5f); }
}
