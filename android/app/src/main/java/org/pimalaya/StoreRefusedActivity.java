package org.pimalaya;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * What the app shows instead of itself when its store is one it does not
 * open (pimdir STORAGE §6): another draft wrote it, or it is damaged, or the
 * phone does not sync its writer at every commit (§4.1).
 *
 * <p>Nothing in the store is read or written; but for the last, which says
 * so, the app has to be reinstalled, which starts a new store. The
 * refusal's code is shown for a report.
 */
public final class StoreRefusedActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_store_refused);
        JSONObject refusal = App.of(this).refusal();
        if (refusal != null && App.STORE_SYNC.equals(refusal.optString("refused"))) {
            ((TextView) findViewById(R.id.store_refused_title))
                    .setText(R.string.store_sync_refused_title);
            ((TextView) findViewById(R.id.store_refused_message))
                    .setText(R.string.store_sync_refused_message);
        }
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
