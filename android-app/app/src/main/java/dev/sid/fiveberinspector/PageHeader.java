package dev.sid.fiveberinspector;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;

/** Compact shared navigation: the page title leads, with no separate branding row. */
final class PageHeader extends LinearLayout {
  PageHeader(Activity activity, String title, String backLabel, Runnable back, Runnable settings) {
    super(activity);
    setOrientation(HORIZONTAL);
    setGravity(Gravity.CENTER_VERTICAL);
    setBackgroundColor(MainActivity.BG);
    setPadding(dp(24), dp(8), dp(24), dp(8));
    setMinimumHeight(dp(64));
    if (back != null)
      addView(slot(activity, "‹", backLabel, back), new LayoutParams(dp(48), dp(48)));
    TextView heading = label(activity, title, 22, MainActivity.TEXT);
    heading.setAccessibilityHeading(true);
    LayoutParams titleParams = new LayoutParams(0, -2, 1);
    if (back != null) titleParams.leftMargin = dp(8);
    if (settings != null) titleParams.rightMargin = dp(8);
    addView(heading, titleParams);
    if (settings != null)
      addView(slot(activity, "⚙", "Settings", settings), new LayoutParams(dp(48), dp(48)));
  }
  private View slot(Activity activity, String glyph, String description, Runnable click) {
    if (click == null) {
      View spacer = new View(activity);
      spacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      return spacer;
    }
    Button button = new Button(activity);
    // Navigation glyphs are icons inside a fixed 48dp accessible target. Keep
    // their icon size stable while ordinary labels follow the user's font scale.
    button.setText(glyph);button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, glyph.equals("‹") ? 32 : 24);
    button.setTextColor(MainActivity.TEXT);button.setAllCaps(false);
    button.setContentDescription(description);button.setPadding(0, 0, 0, 0);
    button.setMinWidth(0);button.setMinHeight(0);button.setStateListAnimator(null);button.setElevation(0);
    android.util.TypedValue ripple = new android.util.TypedValue();
    activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
    button.setBackgroundResource(ripple.resourceId);
    button.setOnClickListener(v -> click.run());
    return button;
  }
  private TextView label(Activity activity, String text, int size, int color) {
    TextView label = new TextView(activity);
    label.setText(text);label.setTextSize(size);label.setTextColor(color);
    label.setTypeface(null, Typeface.BOLD);label.setLineSpacing(dp(3), 1);
    return label;
  }
  static void applyInsets(View root) {
    root.setOnApplyWindowInsetsListener((view, insets) -> {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.graphics.Insets bars = insets.getInsets(
            WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      } else {
        // Android 9/10: legacy system insets include the resized keyboard area.
        view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
      }
      return insets;
    });
  }
  private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
