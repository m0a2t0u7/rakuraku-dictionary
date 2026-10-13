package jp.kenapps.dictionaryquick;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gboard設定内を 単語リスト → 日本語 → ＋ の順に自動で進むユーザー補助サービス。
 * 0.6.0の変更点:
 *  - スクロールをタップ回数の上限に数えない（タップ上限8回・スクロール上限12回を別管理）
 *  - ＋ボタンの判定を「含む」判定に緩和
 *  - 日本語の一覧の判定に「(品詞なし)」行の検出を追加
 *  - 失敗時に画面の要素一覧を診断ログとして保存
 * 0.7.0の変更点:
 *  - タップ後の固定待ちを0.8秒→0.35秒、判定待ちを0.2秒→0.08秒に短縮
 *  - 画面が切り替わったイベントを受けたら待たずに次の操作へ進む
 */
public class DictionaryNavigator extends AccessibilityService {
    static final String PACKAGE = "com.google.android.inputmethod.latin";
    static final int MAX_TAPS = 8;
    static final int MAX_SCROLLS = 12;
    static final int MAX_PLUS_ATTEMPTS = 4;
    static final long TIMEOUT_MS = 20000;
    static final long MIN_INTERVAL_MS = 350;
    static final long STEP_DELAY_MS = 80;

    static volatile long deadline = 0;
    static volatile boolean add = false;
    static volatile DictionaryNavigator instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable step = () -> { scheduled = false; navigate(); };
    private boolean scheduled = false;
    private long currentDeadline = 0;
    private boolean enteredGboard = false;
    private boolean gesturePending = false;
    private long lastAction = 0;
    private int taps = 0;
    private int scrolls = 0;
    private int plusAttempts = 0;
    private final StringBuilder trace = new StringBuilder();

    // ---------- 外部から呼ぶ ----------

    static boolean enabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = new ComponentName(c, DictionaryNavigator.class);
        for (String s : list.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    static void request(Context c, boolean wantAdd) {
        add = wantAdd;
        deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        c.getSharedPreferences("navigation", MODE_PRIVATE).edit()
                .remove("result").putString("progress", "Gboard設定を開いています。").apply();
        DictionaryNavigator s = instance;
        if (s != null) s.handler.post(s::schedule);
    }

    static void cancel() { deadline = 0; }

    /** Gboardの設定画面を開く。MainActivityとクイック設定タイルの両方から使う。 */
    static boolean openGboard(Context c) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        for (android.view.inputmethod.InputMethodInfo info : imm.getInputMethodList()) {
            if (PACKAGE.equals(info.getPackageName()) && info.getSettingsActivity() != null) {
                try {
                    c.startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .setComponent(new ComponentName(PACKAGE, info.getSettingsActivity()))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    return true;
                } catch (RuntimeException ignored) {
                }
            }
        }
        android.content.Intent i = c.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (i == null) return false;
        try { c.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
        catch (RuntimeException e) { return false; }
    }

    // ---------- ライフサイクル ----------

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (deadline > 0) schedule();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (deadline > 0 && e.getPackageName() != null && PACKAGE.contentEquals(e.getPackageName())) {
            // 新しい画面が出たら、固定の待ち時間を待たずに次へ進む
            if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !gesturePending) lastAction = 0;
            schedule();
        }
    }

    @Override public void onInterrupt() {
        cancel();
        handler.removeCallbacks(step);
        scheduled = false;
    }

    @Override public void onDestroy() {
        instance = null;
        cancel();
        handler.removeCallbacks(step);
        super.onDestroy();
    }

    void schedule() {
        if (!scheduled) {
            scheduled = true;
            handler.postDelayed(step, STEP_DELAY_MS);
        }
    }

    // ---------- 本体 ----------

    void navigate() {
        if (deadline == 0) return;
        long now = SystemClock.elapsedRealtime();
        if (currentDeadline != deadline) {
            currentDeadline = deadline;
            taps = 0; scrolls = 0; plusAttempts = 0; lastAction = 0;
            enteredGboard = false; gesturePending = false;
            trace.setLength(0);
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (now > deadline) {
            fail("自動移動が途中で止まりました。表示中のGboardで「単語リスト」→「日本語」→「＋」を選んでください。", root, "timeout");
            return;
        }
        if (root == null) { schedule(); return; }
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !PACKAGE.contentEquals(pkg)) {
            if (enteredGboard) finish("Gboardから離れたため、自動移動を終了しました。");
            else schedule();
            return;
        }
        enteredGboard = true;
        if (gesturePending || now - lastAction < MIN_INTERVAL_MS) { schedule(); return; }

        List<AccessibilityNodeInfo> nodes = nodes(root);

        // 1) 登録画面に着いたか
        if (isEditorScreen(nodes, root)) {
            finish("日本語の登録画面を開きました。単語とよみを入力してください。登録・削除の操作は自動では行いません。");
            return;
        }
        if (hasEditor(nodes) || hasDelete(nodes)) {
            finish("入力画面を検出したため、自動移動を終了しました。");
            return;
        }

        // 2) 日本語の一覧にいるか
        boolean onJapanese = hasJapaneseTitle(nodes, root) || isConfirmedList(nodes);
        if (onJapanese) {
            if (!add) { finish("日本語の単語リストを開きました。"); return; }
            progress("日本語の一覧まで移動しました。＋を開いています。");
            if (plusAttempts >= MAX_PLUS_ATTEMPTS || taps >= MAX_TAPS) {
                fail("日本語の一覧までは開けましたが、＋の操作を確認できませんでした。Gboardの右上の＋を押してください。", root, "plus-failed");
                return;
            }
            AccessibilityNodeInfo plus = findAdd(nodes);
            String how = "label";
            if (plus == null) { plus = findToolbarAdd(nodes, root); how = "toolbar"; }
            if (plus != null) {
                log("plus#" + plusAttempts + " via " + how + " " + describe(plus));
                clickAdd(plus);
                return;
            }
            Point p = fallbackPoint(nodes, root);
            if (p != null) {
                log("plus#" + plusAttempts + " via fallback " + p.x + "," + p.y);
                plusAttempts++;
                tap(p.x, p.y);
                return;
            }
            plusAttempts++;
            log("plus#" + plusAttempts + " not found");
            schedule();
            return;
        }

        // 3) 「日本語」の行を押す
        AccessibilityNodeInfo ja = findClickableExact(nodes, "日本語");
        if (ja != null) {
            progress("日本語の一覧を開いています。");
            log("click 日本語");
            click(ja);
            return;
        }

        // 4) 「単語リスト」を押す
        AccessibilityNodeInfo dict = findClickableExact(nodes, "単語リスト", "個人用辞書", "Dictionary", "Personal dictionary");
        if (dict != null) {
            progress("単語リストを開いています。");
            log("click 単語リスト");
            click(dict);
            return;
        }

        // 5) Gboard設定のトップなら下へスクロールして探す（タップ回数には数えない）
        if (scrolls < MAX_SCROLLS) {
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isVisibleToUser() && n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    scrolls++;
                    lastAction = now - MIN_INTERVAL_MS / 2;
                    log("scroll#" + scrolls);
                    break;
                }
            }
        }
        schedule();
    }

    // ---------- 判定 ----------

    boolean isEditorScreen(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo root) {
        boolean word = hasText(nodes, "単語を入力") || hasText(nodes, "Enter word") || hasText(nodes, "Word");
        boolean reading = hasText(nodes, "よみを入力します") || hasText(nodes, "よみ:") || hasText(nodes, "よみ：") || hasText(nodes, "Shortcut");
        if (word && reading) return true;
        return hasJapaneseTitle(nodes, root) && editorCount(nodes) >= 2;
    }

    boolean hasJapaneseTitle(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo root) {
        Rect rb = bounds(root);
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || n.getText() == null || !"日本語".contentEquals(n.getText())) continue;
            if (clickable(n) != null && insideList(n)) continue; // 一覧の行の「日本語」は除外
            Rect b = bounds(n);
            if (b.height() > 0 && b.top < rb.top + rb.height() * 0.35) return true;
        }
        return false;
    }

    boolean isConfirmedList(List<AccessibilityNodeInfo> nodes) {
        if (hasEditor(nodes) || hasDelete(nodes)) return false;
        int n = 0;
        for (AccessibilityNodeInfo x : nodes) {
            if (x.isVisibleToUser() && x.getText() != null && x.getText().toString().contains("品詞なし")) n++;
        }
        return n >= 2;
    }

    static AccessibilityNodeInfo findAdd(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || !n.isEnabled()) continue;
            String d = n.getContentDescription() != null ? n.getContentDescription().toString() : "";
            String t = n.getText() != null ? n.getText().toString() : "";
            String id = n.getViewIdResourceName() != null ? n.getViewIdResourceName() : "";
            id = id.substring(id.lastIndexOf('/') + 1);
            if (NavigationLabels.isAdd(d, t, id)) return n;
        }
        return null;
    }

    AccessibilityNodeInfo findToolbarAdd(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo root) {
        Rect rb = bounds(root);
        float dp = getResources().getDisplayMetrics().density;
        List<AccessibilityNodeInfo> cands = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || !n.isEnabled()) continue;
            String cls = n.getClassName() != null ? n.getClassName().toString() : "";
            if (!(cls.endsWith("ImageButton") || cls.endsWith("ImageView") || cls.endsWith("Button") || n.isClickable())) continue;
            String d = n.getContentDescription() != null ? n.getContentDescription().toString().toLowerCase(Locale.ROOT) : "";
            if (NavigationLabels.isExcluded(d)) continue;
            Rect b = bounds(n);
            if (NavigationLabels.isToolbarAddPosition(b.left, b.top, b.right, b.bottom,
                    rb.left, rb.top, rb.width(), (int) (120 * dp), (int) (72 * dp))) {
                boolean dup = false;
                for (AccessibilityNodeInfo c : cands) {
                    Rect cb = bounds(c);
                    if (Math.abs(cb.centerX() - b.centerX()) <= 8 * dp && Math.abs(cb.centerY() - b.centerY()) <= 8 * dp) { dup = true; break; }
                }
                if (!dup) cands.add(n);
            }
        }
        // ⋮(その他)より左にある一番右の候補を＋とみなす
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo c : cands) {
            if (best == null || bounds(c).centerX() > bounds(best).centerX()) best = c;
        }
        return cands.size() <= 2 ? best : null;
    }

    Point fallbackPoint(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo root) {
        if (!isConfirmedList(nodes)) return null;
        DisplayMetrics m = getResources().getDisplayMetrics();
        int w = m.widthPixels;
        if (w <= 0) return null;
        int x = Math.round(w * 572f / 691f);
        int y = Math.round(w * 132f / 691f);
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String d = n.getContentDescription() != null ? n.getContentDescription().toString().toLowerCase(Locale.ROOT) : "";
            Rect b = bounds(n);
            if ((d.contains("戻") || d.contains("back") || d.contains("その他") || d.contains("more"))
                    && b.height() > 0 && b.height() <= 72 * m.density && b.centerY() < 120 * m.density) {
                y = b.centerY();
                break;
            }
        }
        return new Point(x, y);
    }

    // ---------- 操作 ----------

    void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickable(n);
        if (c == null) { schedule(); return; }
        if (taps >= MAX_TAPS) {
            fail("自動移動を停止しました。Gboardの表示中の画面から続きを選んでください。", getRootInActiveWindow(), "tap-limit");
            return;
        }
        if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            taps++;
            lastAction = SystemClock.elapsedRealtime();
        } else {
            Rect b = bounds(c);
            if (b.width() > 0 && b.height() > 0) { tap(b.exactCenterX(), b.exactCenterY()); return; }
        }
        schedule();
    }

    void clickAdd(AccessibilityNodeInfo n) {
        int attempt = plusAttempts++;
        AccessibilityNodeInfo c = clickable(n);
        // 1回目は要素のクリック、2回目以降は座標タップ（要素クリックが空振りする端末向け）
        if (attempt == 0 && c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            taps++;
            lastAction = SystemClock.elapsedRealtime();
            schedule();
            return;
        }
        Rect b = bounds(c != null ? c : n);
        if (b.width() > 0 && b.height() > 0) tap(b.exactCenterX(), b.exactCenterY());
        else schedule();
    }

    boolean tap(float x, float y) {
        if (deadline == 0 || gesturePending || taps >= MAX_TAPS) { schedule(); return false; }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
        gesturePending = true;
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) {
                gesturePending = false;
                lastAction = SystemClock.elapsedRealtime();
                schedule();
            }
            @Override public void onCancelled(GestureDescription d) {
                gesturePending = false;
                schedule();
            }
        }, handler);
        if (ok) {
            taps++;
            lastAction = SystemClock.elapsedRealtime();
        } else {
            gesturePending = false;
        }
        schedule();
        return ok;
    }

    // ---------- 結果 ----------

    void progress(String s) {
        getSharedPreferences("navigation", MODE_PRIVATE).edit().putString("progress", s).apply();
    }

    void finish(String message) {
        cancel();
        handler.removeCallbacks(step);
        scheduled = false;
        getSharedPreferences("navigation", MODE_PRIVATE).edit().putString("result", message).apply();
    }

    void fail(String message, AccessibilityNodeInfo root, String reason) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("reason=").append(reason)
                    .append(" taps=").append(taps).append(" scrolls=").append(scrolls)
                    .append(" plus=").append(plusAttempts).append('\n');
            DisplayMetrics m = getResources().getDisplayMetrics();
            sb.append("screen=").append(m.widthPixels).append('x').append(m.heightPixels)
                    .append(" density=").append(m.density).append('\n');
            sb.append("--- trace ---\n").append(trace).append("--- nodes ---\n");
            if (root != null) {
                for (AccessibilityNodeInfo n : nodes(root)) sb.append(describe(n)).append('\n');
            } else {
                sb.append("(root=null)\n");
            }
            Diag.save(this, sb.toString());
        } catch (RuntimeException ignored) {
        }
        finish(message + "\n（診断ログを保存しました。右上の⋮→「診断ログを共有」で送れます）");
    }

    void log(String s) {
        if (trace.length() < 4000) trace.append(SystemClock.elapsedRealtime() % 100000).append(' ').append(s).append('\n');
    }

    static String describe(AccessibilityNodeInfo n) {
        Rect b = bounds(n);
        String cls = n.getClassName() != null ? n.getClassName().toString() : "";
        cls = cls.substring(cls.lastIndexOf('.') + 1);
        StringBuilder sb = new StringBuilder(cls);
        if (n.getText() != null) sb.append(" t=\"").append(trim(n.getText())).append('"');
        if (n.getContentDescription() != null) sb.append(" d=\"").append(trim(n.getContentDescription())).append('"');
        if (n.getHintText() != null) sb.append(" h=\"").append(trim(n.getHintText())).append('"');
        if (n.getViewIdResourceName() != null) sb.append(" id=").append(n.getViewIdResourceName());
        sb.append(" [").append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom).append(']');
        if (n.isClickable()) sb.append(" C");
        if (n.isScrollable()) sb.append(" S");
        if (n.isEditable()) sb.append(" E");
        if (!n.isVisibleToUser()) sb.append(" hidden");
        return sb.toString();
    }

    static String trim(CharSequence s) {
        String t = s.toString().replace('\n', ' ');
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    // ---------- 小物 ----------

    static Rect bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        if (n != null) n.getBoundsInScreen(r);
        return r;
    }

    static List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty() && out.size() < 600) {
            AccessibilityNodeInfo n = q.remove();
            out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return out;
    }

    static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        for (int i = 0; i < 5 && n != null; i++) {
            if (n.isVisibleToUser() && n.isEnabled() && n.isClickable()) return n;
            n = n.getParent();
        }
        return null;
    }

    static boolean insideList(AccessibilityNodeInfo n) {
        for (int i = 0; i < 8 && n != null; i++) {
            String cls = n.getClassName() != null ? n.getClassName().toString() : "";
            if (cls.endsWith("RecyclerView") || cls.endsWith("ListView")) return true;
            n = n.getParent();
        }
        return false;
    }

    static AccessibilityNodeInfo findClickableExact(List<AccessibilityNodeInfo> nodes, String... texts) {
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || n.getText() == null) continue;
            String t = n.getText().toString().trim();
            for (String s : texts) {
                if (t.equals(s) && clickable(n) != null) return n;
            }
        }
        return null;
    }

    static boolean hasText(List<AccessibilityNodeInfo> nodes, String s) {
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            if (n.getText() != null && s.contentEquals(n.getText())) return true;
            if (n.getHintText() != null && s.contentEquals(n.getHintText())) return true;
        }
        return false;
    }

    static boolean isEditorNode(AccessibilityNodeInfo n) {
        return n.isVisibleToUser() && (n.isEditable()
                || (n.getClassName() != null && n.getClassName().toString().endsWith("EditText")));
    }

    static boolean hasEditor(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) if (isEditorNode(n)) return true;
        return false;
    }

    static int editorCount(List<AccessibilityNodeInfo> nodes) {
        int c = 0;
        for (AccessibilityNodeInfo n : nodes) if (isEditorNode(n)) c++;
        return c;
    }

    static boolean hasDelete(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String d = n.getContentDescription() != null ? n.getContentDescription().toString().toLowerCase(Locale.ROOT) : "";
            String id = n.getViewIdResourceName() != null ? n.getViewIdResourceName().toLowerCase(Locale.ROOT) : "";
            if (d.contains("削除") || d.contains("delete") || id.endsWith("/action_delete") || id.endsWith("/delete")) return true;
        }
        return false;
    }
}
