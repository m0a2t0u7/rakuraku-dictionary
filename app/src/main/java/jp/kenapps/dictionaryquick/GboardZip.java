package jp.kenapps.dictionaryquick;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class GboardZip {
    private GboardZip() {}

    public static void write(OutputStream out, List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder("# Gboard Dictionary version:2\n# Gboard Dictionary format:shortcut\tword\tlanguage_tag\tpos_tag\n");
        for (String[] r : rows) {
            if (r.length != 2) throw new IllegalArgumentException("辞書の列数が不正です");
            for (String s : r) {
                if (s.isEmpty() || s.indexOf('\t') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("単語・よみに空欄、タブ、改行は使えません");
                }
            }
            sb.append(r[0]).append('\t').append(r[1]).append("\tja\t\n");
        }
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry("dictionary.txt"));
        zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        zip.flush();
    }
}
