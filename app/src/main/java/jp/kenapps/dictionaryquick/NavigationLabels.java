package jp.kenapps.dictionaryquick;

import java.util.Locale;

/** ＋ボタン判定などの純粋ロジック（Android非依存） */
public final class NavigationLabels {
    private NavigationLabels() {}

    /** 0.6.0: 完全一致から「含む」判定へ緩和。削除・戻る・その他は除外 */
    public static boolean isAdd(String desc, String text, String id) {
        String d = desc.trim().toLowerCase(Locale.ROOT);
        String t = text.trim();
        String i = id.trim().toLowerCase(Locale.ROOT);
        if (isExcluded(d)) return false;
        if (t.equals("+") || t.equals("＋")) return true;
        if (d.contains("追加") || d.contains("新規") || d.contains("新しい単語")
                || d.equals("add") || d.startsWith("add ") || d.contains("new word")) return true;
        return i.equals("add") || i.contains("action_add") || i.contains("menu_add")
                || i.contains("add_word") || i.contains("add_button") || i.equals("fab") || i.contains("fab_add");
    }

    public static boolean isExcluded(String descLower) {
        return descLower.contains("削除") || descLower.contains("戻") || descLower.contains("その他")
                || descLower.contains("オプション") || descLower.contains("検索")
                || descLower.contains("delete") || descLower.contains("back")
                || descLower.contains("more") || descLower.contains("search");
    }

    public static boolean isToolbarAddPosition(int l, int t, int r, int b, int rootLeft, int rootTop,
                                               int rootWidth, int bandHeight, int maxSize) {
        int cx = (l + r) / 2, cy = (t + b) / 2;
        if (r <= l || b <= t) return false;
        if (r - l > maxSize || b - t > maxSize) return false;
        if (cx <= rootLeft + rootWidth * 0.55 || cx >= rootLeft + rootWidth * 0.93) return false;
        return cy >= rootTop && cy < rootTop + bandHeight;
    }
}
