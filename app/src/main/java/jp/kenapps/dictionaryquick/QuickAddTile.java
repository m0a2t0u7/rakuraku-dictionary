package jp.kenapps.dictionaryquick;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 通知パネルのクイック設定から「新しく登録」を呼び出すタイル。 */
public class QuickAddTile extends TileService {
    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("単語を登録");
        t.setIcon(Icon.createWithResource(this, R.drawable.icon_add));
        t.setState(Tile.STATE_ACTIVE);
        t.updateTile();
    }

    @Override public void onClick() {
        Intent i = new Intent(this, QuickAddActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
