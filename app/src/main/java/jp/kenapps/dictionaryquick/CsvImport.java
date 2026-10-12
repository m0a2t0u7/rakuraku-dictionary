package jp.kenapps.dictionaryquick;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CsvImport {
    public final List<String[]> rows = new ArrayList<>();
    public int invalid = 0;
    public int duplicates = 0;

    public static CsvImport from(String text) {
        CsvImport result = new CsvImport();
        List<String[]> parsed = Csv.parse(text);
        Set<List<String>> seen = new HashSet<>();
        int count = 0;
        for (int i = 0; i < parsed.size(); i++) {
            String[] r = parsed.get(i);
            if (r.length == 1 && r[0].trim().isEmpty()) continue;
            if (i == 0 && r.length == 2) {
                String a = r[0].trim(), b = r[1].trim();
                if ((a.equals("よみ") && b.equals("単語")) || (a.equalsIgnoreCase("reading") && b.equalsIgnoreCase("word"))) continue;
            }
            if (r.length == 2 && valid(r[0].trim(), r[1])) {
                count++;
                if (count > 2000) throw new IllegalArgumentException("一度に取り込めるのは2000行までです");
                String reading = r[0].trim();
                String word = r[1];
                if (seen.add(Arrays.asList(reading, word))) result.rows.add(new String[]{reading, word});
                else result.duplicates++;
            } else {
                result.invalid++;
            }
        }
        return result;
    }

    public static boolean valid(String reading, String word) {
        if (reading.trim().isEmpty() || word.trim().isEmpty()) return false;
        if (reading.length() > 100 || word.length() > 200) return false;
        String bad = "(?s).*[\\t\\r\\n\\x00].*";
        return !reading.matches(bad) && !word.matches(bad);
    }
}
