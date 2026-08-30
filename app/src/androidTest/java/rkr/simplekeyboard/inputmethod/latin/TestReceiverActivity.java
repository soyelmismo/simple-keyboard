package rkr.simplekeyboard.inputmethod.latin;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;

public class TestReceiverActivity extends Activity {
    public EditText editText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        editText = new EditText(this);
        editText.setId(android.R.id.text1);
        editText.setHint("Test Receiver Input");
        editText.setText("BASELINE_RECEIVER_123");
        layout.addView(editText);

        setContentView(layout);
        editText.requestFocus();
    }
}