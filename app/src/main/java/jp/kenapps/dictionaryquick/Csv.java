package jp.kenapps.dictionaryquick;

import java.util.ArrayList;
import java.util.List;

public final class Csv {
    private Csv() {}

    public static List<String[]> parse(String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuote = false;
        boolean afterQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuote = false;
                        afterQuote = true;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                afterQuote = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row.toArray(new String[0]));
                row.clear();
                afterQuote = false;
            } else if (c == '"') {
                if (cell.length() > 0 || afterQuote) throw new IllegalArgumentException("引用符の位置が不正です");
                inQuote = true;
            } else {
                if (afterQuote) throw new IllegalArgumentException("引用符の後に区切り以外の文字があります");
                cell.append(c);
            }
        }
        if (inQuote) throw new IllegalArgumentException("引用符が閉じられていません");
        if (cell.length() > 0 || afterQuote || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }

    public static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
