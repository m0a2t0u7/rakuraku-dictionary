package jp.kenapps.dictionaryquick;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** 画面を出さずに、Gboardの登録画面への自動移動を始める（タイル用）。 */
public class QuickAddActivity extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (DictionaryNavigator.enabled(this)) {
            DictionaryNavigator.request(this, true);
            if (!DictionaryNavigator.openGboard(this)) {
                DictionaryNavigator.cancel();
                Toast.makeText(this, "Gboard設定を開けませんでした", Toast.LENGTH_SHORT).show();
            }
        } else {
            // 自動移動が未設定ならアプリ本体を開く
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
