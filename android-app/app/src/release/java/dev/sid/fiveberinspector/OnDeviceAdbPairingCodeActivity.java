package dev.sid.fiveberinspector;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;

/**
 * Standard Android numeric IME fallback when SystemUI's RemoteInput does not
 * display the keyboard. Invoked only via the user's notification action.
 *
 * Never persists, logs, copies to clipboard or displays the pairing code in
 * other notifications. The pairing server remains open in Android Settings.
 */
public final class OnDeviceAdbPairingCodeActivity extends Activity {
    private EditText code;

    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(AppLanguage.wrap(base));}

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        getWindow().setDimAmount(.12f);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND | android.view.WindowManager.LayoutParams.FLAG_SECURE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 28, 40, 24);
        TextView prompt = new TextView(this);
        prompt.setText(getString(R.string.nav_pair_code_prompt));
        prompt.setTextSize(16);
        layout.addView(prompt);
        code = new EditText(this);
        code.setSingleLine(true);
        code.setHint(getString(R.string.nav_pairing_code));
        code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        code.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        layout.addView(code, new LinearLayout.LayoutParams(-1, -2));
        Button confirm = new Button(this);
        confirm.setText(getString(R.string.nav_pair_confirm));
        confirm.setOnClickListener(v -> submit());
        layout.addView(confirm);
        setContentView(layout);
        code.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
        code.requestFocus();
        code.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                imm.showSoftInput(code, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);
    }

    private void submit() {
        if (code == null) return;
        android.text.Editable editable = code.getText();
        if (editable == null || editable.length() != 6) {
            code.setError(getString(R.string.nav_pair_code_incomplete));
            return;
        }
        char[] digits = new char[6];
        for (int i=0; i<6; i++) digits[i] = editable.charAt(i);
        editable.clear();
        if (!OnDeviceAdbPairingExchange.validCode(digits)) {
            Arrays.fill(digits, '\0');
            code.setError(getString(R.string.nav_pair_code_digits_only));
            return;
        }
        try {
            Intent intent = new Intent(this, OnDeviceAdbPairingService.class)
                    .setAction(OnDeviceAdbPairingService.ACTION_CODE_FROM_ACTIVITY)
                    .putExtra(OnDeviceAdbPairingService.EXTRA_CODE_FROM_ACTIVITY, digits);
            startService(intent);
        } finally {
            Arrays.fill(digits, '\0');
        }
        finish();
    }

    @Override protected void onDestroy() {
        if (code != null && code.getText() != null) code.getText().clear();
        super.onDestroy();
    }
}
