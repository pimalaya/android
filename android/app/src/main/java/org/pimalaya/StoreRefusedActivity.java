package org.pimalaya;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * What the app shows instead of itself when its store is one it does not
 * open (pimdir STORAGE §6): another draft wrote it, or it is damaged.
 *
 * <p>Nothing in the store is read or written; the app has to be
 * reinstalled, which starts a new store. The refusal's code is shown for a
 * report.
 */
public final class StoreRefusedActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_store_refused);
        JSONObject refusal = App.of(this).refusal();
        TextView code = findViewById(R.id.store_refused_code);
        code.setText(
                refusal == null
                        ? ""
                        : getString(
                                R.string.store_refused_code,
                                refusal.optString("refused"),
                                refusal.optString("detail")));
    }
}
