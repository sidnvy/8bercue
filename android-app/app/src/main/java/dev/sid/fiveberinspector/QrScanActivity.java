package dev.sid.fiveberinspector;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import java.util.List;
import java.util.concurrent.*;

/** Local camera QR decoder. No image persistence, cloud decoding or implicit installation. */
@SuppressWarnings("deprecation")
public final class QrScanActivity extends Activity implements SurfaceHolder.Callback {
  private static final int CAMERA_PERMISSION = 44;
  private Camera camera;
  private SurfaceView preview;
  private PreviewFrame previewFrame;
  private ScanCorners corners;
  private TextView previewMessage, statusTitle, statusDetail;
  private Button retry;
  private final ExecutorService decoder = Executors.newSingleThreadExecutor();
  private volatile boolean decoding, finished;
  private boolean surfaceReady, resumed, permissionRequested;
  private int cameraSession;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    getWindow().setStatusBarColor(MainActivity.BG);
    getWindow().setNavigationBarColor(MainActivity.BG);
    getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    permissionRequested = state != null && state.getBoolean("permissionRequested", false);
    buildScreen();
    if (!hasCameraPermission()) {
      showPermissionState();
      if (!permissionRequested)
        requestCameraPermission();
    }
  }

  private void buildScreen() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(MainActivity.BG);
    PageHeader.applyInsets(root);
    root.addView(new PageHeader(this, "Scan QR code", "Back to Add eSIM", this::finish, null));
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(24), 0, dp(24), dp(16));
    TextView help =
        text("Place the operator’s QR code inside the frame.", 14, MainActivity.MUTED, false);
    body.addView(help, margin(14));

    previewFrame = new PreviewFrame();
    previewFrame.setBackgroundColor(MainActivity.TEXT);
    // At large text sizes, let the page scroll instead of squeezing out the camera
    // or clipping the permission/retry controls below the screen.
    boolean largeText = getResources().getConfiguration().fontScale > 1.3f;
    LinearLayout.LayoutParams frameLayout =
        new LinearLayout.LayoutParams(-1, largeText ? dp(280) : 0, largeText ? 0 : 1);
    frameLayout.topMargin = dp(20);
    frameLayout.bottomMargin = dp(20);
    body.addView(previewFrame, frameLayout);
    preview = new SurfaceView(this);
    preview.setContentDescription("Live camera preview");
    previewFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    corners = new ScanCorners();
    corners.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    corners.setVisibility(View.INVISIBLE);
    previewFrame.addView(corners, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    previewMessage = text("Preparing camera…", 16, 0xffffffff, false);
    previewMessage.setGravity(Gravity.CENTER);
    previewMessage.setPadding(dp(24), dp(16), dp(24), dp(16));
    previewFrame.addView(previewMessage, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

    statusTitle = text("Preparing camera", 17, MainActivity.TEXT, true);
    statusTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    body.addView(statusTitle);
    statusDetail = text("Your code is read on this phone. Installation starts only after your "
            + "review and confirmation.",
        14, MainActivity.MUTED, false);
    body.addView(statusDetail, margin(6));
    retry = button("Try again", true);
    retry.setVisibility(View.GONE);
    body.addView(retry, margin(14));
    Button alternative = button("Use image or code", false);
    alternative.setOnClickListener(v -> finish());
    body.addView(alternative, margin(10));
    if (largeText) {
      ScrollView scroll = new ScrollView(this);
      scroll.setFillViewport(true);
      scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
      root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    } else
      root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    preview.getHolder().addCallback(this);
  }

  private boolean hasCameraPermission() {
    return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
  }
  private void requestCameraPermission() {
    permissionRequested = true;
    requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION);
  }
  private void showPermissionState() {
    boolean settingsRequired =
        permissionRequested && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
    showUnavailable("Camera access needed", "Camera access is off",
        "Allow camera access to scan. You can also go back to import an image or enter a code.");
    retry.setText(settingsRequired ? "Open app settings" : "Allow camera");
    retry.setOnClickListener(v -> {
      if (hasCameraPermission()) {
        start();
        return;
      }
      if (settingsRequired) {
        try {
          startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
          statusDetail.setText("Open Android Settings → Apps → "
              + getApplicationInfo().loadLabel(getPackageManager())
              + " → Permissions to allow camera access, or go back to import an image.");
        }
      } else
        requestCameraPermission();
    });
  }
  private void showUnavailable(String title, String previewText, String detail) {
    corners.setVisibility(View.INVISIBLE);
    previewMessage.setVisibility(View.VISIBLE);
    previewMessage.setText(previewText);
    statusTitle.setText(title);
    statusDetail.setText(detail);
    retry.setVisibility(View.VISIBLE);
  }
  private void cameraUnavailable() {
    showUnavailable("Camera unavailable", "Camera preview unavailable",
        "Another app may be using the camera. Close it and try again, or go back to import an "
            + "image or enter a code.");
    retry.setText("Try camera again");
    retry.setOnClickListener(v -> {
      stop();
      start();
    });
  }
  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request != CAMERA_PERMISSION)
      return;
    if (hasCameraPermission())
      start();
    else
      showPermissionState();
  }
  @Override
  public void onSaveInstanceState(Bundle out) {
    out.putBoolean("permissionRequested", permissionRequested);
    super.onSaveInstanceState(out);
  }
  public void surfaceCreated(SurfaceHolder holder) {
    surfaceReady = true;
    start();
  }
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
  public void surfaceDestroyed(SurfaceHolder holder) {
    surfaceReady = false;
    stop();
  }
  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (hasCameraPermission())
      start();
    else
      showPermissionState();
  }
  @Override
  protected void onPause() {
    resumed = false;
    stop();
    super.onPause();
  }
  private void start() {
    if (!resumed || !surfaceReady || camera != null || finished)
      return;
    if (!hasCameraPermission()) {
      showPermissionState();
      return;
    }
    try {
      if (Camera.getNumberOfCameras() == 0)
        throw new IllegalStateException("No camera");
      int cameraId = 0;
      Camera.CameraInfo info = new Camera.CameraInfo();
      for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
        Camera.getCameraInfo(i, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
          cameraId = i;
          break;
        }
      }
      Camera.getCameraInfo(cameraId, info);
      camera = Camera.open(cameraId);
      final int session = ++cameraSession;
      Camera.Parameters p = camera.getParameters();
      List<String> focus = p.getSupportedFocusModes();
      if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
        p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
      camera.setParameters(p);
      int rotation = getWindowManager().getDefaultDisplay().getRotation();
      int degrees = rotation == Surface.ROTATION_90 ? 90
          : rotation == Surface.ROTATION_180        ? 180
          : rotation == Surface.ROTATION_270        ? 270
                                                    : 0;
      int orientation = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
          ? (360 - (info.orientation + degrees) % 360) % 360
          : (info.orientation - degrees + 360) % 360;
      camera.setDisplayOrientation(orientation);
      final Camera.Size size = camera.getParameters().getPreviewSize();
      previewFrame.setAspect(orientation % 180 == 0 ? (float) size.width / size.height
                                                    : (float) size.height / size.width);
      camera.setPreviewDisplay(preview.getHolder());
      camera.setErrorCallback((error, failedCamera) -> {
        if (session != cameraSession || finished)
          return;
        stop();
        if (resumed)
          cameraUnavailable();
      });
      camera.setPreviewCallback((bytes, c) -> {
        if (decoding || finished || !resumed || session != cameraSession || bytes == null)
          return;
        decoding = true;
        byte[] copy = bytes.clone();
        decoder.execute(() -> {
          String result = null;
          try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                copy, size.width, size.height, 0, 0, size.width, size.height, false);
            result = QrDecoder.read(source);
          } catch (Exception ignored) {
          }
          final String code = result;
          runOnUiThread(() -> {
            decoding = false;
            if (code != null && !finished && resumed && surfaceReady && session == cameraSession) {
              finished = true;
              setResult(RESULT_OK, new Intent().putExtra("activation", code));
              finish();
            }
          });
        });
      });
      camera.startPreview();
      corners.setVisibility(View.VISIBLE);
      previewMessage.setVisibility(View.GONE);
      retry.setVisibility(View.GONE);
      statusTitle.setText("Ready to scan");
      statusDetail.setText("Keep the full code in view and hold steady. Your code stays on this "
          + "phone until you review and confirm installation.");
    } catch (Exception e) {
      stop();
      cameraUnavailable();
    }
  }
  private void stop() {
    cameraSession++;
    corners.setVisibility(View.INVISIBLE);
    if (camera != null) {
      try {
        camera.setPreviewCallback(null);
        camera.setErrorCallback(null);
      } catch (RuntimeException ignored) {
      }
      try {
        camera.stopPreview();
      } catch (RuntimeException ignored) {
      }
      try {
        camera.release();
      } catch (RuntimeException ignored) {
      }
      camera = null;
    }
  }
  static String decode(Bitmap image) throws Exception {
    int w = image.getWidth(), h = image.getHeight();
    int[] pixels = new int[w * h];
    image.getPixels(pixels, 0, w, 0, 0, w, h);
    return QrDecoder.read(new RGBLuminanceSource(w, h, pixels));
  }
  @Override
  protected void onDestroy() {
    finished = true;
    stop();
    decoder.shutdown();
    super.onDestroy();
  }

  /** Aspect-fit, never stretch or crop the camera feed. Letterboxing is intentional. */
  private final class PreviewFrame extends FrameLayout {
    private float aspect = 0.75f;
    PreviewFrame() {
      super(QrScanActivity.this);
    }
    void setAspect(float value) {
      if (value > 0 && !Float.isInfinite(value) && aspect != value) {
        aspect = value;
        requestLayout();
      }
    }
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int width = Math.max(0, MeasureSpec.getSize(widthSpec)),
          height = Math.max(0, MeasureSpec.getSize(heightSpec));
      setMeasuredDimension(width, height);
      int feedWidth = width, feedHeight = Math.round(width / aspect);
      if (feedHeight > height) {
        feedHeight = height;
        feedWidth = Math.round(height * aspect);
      }
      // A weighted layout can temporarily measure zero while calculating remaining
      // space. Keep the SurfaceView dimensions valid during that intermediate pass.
      feedWidth = Math.max(1, Math.min(Math.max(1, width), feedWidth));
      feedHeight = Math.max(1, Math.min(Math.max(1, height), feedHeight));
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child == previewMessage)
          child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
              MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        else
          child.measure(MeasureSpec.makeMeasureSpec(feedWidth, MeasureSpec.EXACTLY),
              MeasureSpec.makeMeasureSpec(feedHeight, MeasureSpec.EXACTLY));
      }
    }
  }
  /** A positioning guide only. The actual camera image remains visible. */
  private final class ScanCorners extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    ScanCorners() {
      super(QrScanActivity.this);
      paint.setColor(MainActivity.ACCENT);
      paint.setStrokeWidth(dp(3));
      paint.setStrokeCap(Paint.Cap.ROUND);
    }
    @Override
    protected void onDraw(Canvas canvas) {
      float side = Math.min(getWidth(), getHeight()) * 0.74f;
      float left = (getWidth() - side) / 2, top = (getHeight() - side) / 2, right = left + side,
            bottom = top + side;
      float arm = Math.min(dp(28), side / 5);
      canvas.drawLine(left, top + arm, left, top, paint);
      canvas.drawLine(left, top, left + arm, top, paint);
      canvas.drawLine(right - arm, top, right, top, paint);
      canvas.drawLine(right, top, right, top + arm, paint);
      canvas.drawLine(left, bottom - arm, left, bottom, paint);
      canvas.drawLine(left, bottom, left + arm, bottom, paint);
      canvas.drawLine(right - arm, bottom, right, bottom, paint);
      canvas.drawLine(right, bottom, right, bottom - arm, paint);
    }
  }
  private TextView text(String value, int size, int color, boolean bold) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setLineSpacing(dp(2), 1);
    if (bold)
      view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return view;
  }
  private Button button(String label, boolean primary) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(15);
    button.setMinHeight(dp(48));
    button.setPadding(dp(16), dp(10), dp(16), dp(10));
    button.setTextColor(primary ? 0xffffffff : MainActivity.TEXT);
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(primary ? MainActivity.ACCENT : MainActivity.BG);
    shape.setCornerRadius(dp(14));
    if (!primary)
      shape.setStroke(dp(1), 0xffddddda);
    button.setBackground(shape);
    button.setStateListAnimator(null);
    button.setElevation(0);
    return button;
  }
  private LinearLayout.LayoutParams margin(int top) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(top);
    return p;
  }
  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
