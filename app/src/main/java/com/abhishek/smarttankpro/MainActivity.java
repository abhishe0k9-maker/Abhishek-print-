package com.abhishek.smarttankpro;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.hardware.usb.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.*;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.*;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends AppCompatActivity {
    private String connMode = "Wi-Fi", printerIp = "192.168.1.100", printerName = "HP Smart Tank 589";
    private int colorMode = 0, presetMode = 0, copyCount = 1, orientationMode = 0;
    private boolean inkSaverOn = false, advancedOpen = false, editorOpen = false, darkUi = false;

    private int editBright = 0, editContrast = 100, editSat = 100, editWarmth = 0, bgCleanMode = 0, rotationDeg = 0;

    private LinearLayout outerRoot, rootBox, stickyBar, advContentBox, editorBox, recentRowBox, thumbStripBox, idToolsRow1, idToolsRow2;
    private ScrollView mainScroll;
    private TextView statusTxt, pageTxt, badgeTxt, copyValTxt, advToggleTxt, editToggleTxt, estBadgeTxt;
    private ImageView previewImg;
    private EditText pageRangeEdit;
    private SeekBar sbBright, sbContrast, sbSat, sbWarmth;
    private final Button[] modeBtns = new Button[3], colorBtns = new Button[3], presetBtns = new Button[3], orientBtns = new Button[2], bgBtns = new Button[4];
    private Button themeBtn, allPagesBtn, clearDocBtn, zoomPreviewBtn;
    private CheckBox inkSaverCheck, revCheck;
    private Spinner sizeSp, qualSp, scaleSp, marginSp, duplexSp, multiSp;

    private ParcelFileDescriptor pdfFd;
    private PdfRenderer pdfRen;
    private int curPage = 0, totalPages = 0;
    private final List<Bitmap> pdfThumbs = new ArrayList<>();
    private final Set<Integer> selectedPages = new LinkedHashSet<>();
    private Bitmap rawBmp, idFront, idBack, lastIdSourceBmp;
    private int pickTarget = 0; // 0=Normal, 1=AutoID, 2=IDBack2ndFile, 3=Passport
    private NsdManager nsdMgr;
    private NsdManager.DiscoveryListener ippListener, scanListener;
    private UsbDevice usbPrinter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                    if (pickTarget == 1) { autoExtractIdFromUri(uri); return; }
                    if (pickTarget == 2) { loadSecondIdBackUri(uri); return; }
                    saveRecentUri(uri);
                    loadUri(uri);
                }
            });

    private final ActivityResultLauncher<Intent> camLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == RESULT_OK && res.getData() != null && res.getData().getExtras() != null) {
                    Bitmap b = (Bitmap) res.getData().getExtras().get("data");
                    if (b != null) {
                        closePdf(); totalPages = 1; rawBmp = b; resetEditsOnly(); setColor(2);
                        updateLoadedUiState("Camera Scan Ready — Tap image to Zoom, Edit or Print");
                    }
                }
            });

    private final ActivityResultLauncher<String> camPermLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) camLauncher.launch(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
                else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            });

    private void launchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            camLauncher.launch(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
        else camPermLauncher.launch(Manifest.permission.CAMERA);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        showEmptyHintPreview();
        refreshRecentBar();
        bindToWifiNetwork(true);
        startAutoDiscovery();
        checkUsb();
        handleIncomingIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri sharedUri = null;
        if (Intent.ACTION_SEND.equals(action)) {
            sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (sharedUri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
                sharedUri = intent.getClipData().getItemAt(0).getUri();
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            sharedUri = intent.getData();
        }
        if (sharedUri != null) {
            pickTarget = 0;
            loadUri(sharedUri);
            Toast.makeText(this, "File loaded in Aman Print!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        bindToWifiNetwork(false);
        stopAutoDiscovery();
        closePdf();
    }

    // Bind app sockets & WebView to Wi-Fi so Wi-Fi Direct (192.168.223.1) works even when Mobile Data 5G is ON
    private void bindToWifiNetwork(boolean enable) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            if (!enable) {
                cm.bindProcessToNetwork(null);
                return;
            }
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(net);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    // Trust self-signed HTTPS certificate of local HP Smart Tank 589 printer (192.168.x.x)
    private SSLSocketFactory getLocalPrinterSslFactory() {
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tm, new SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            return null;
        }
    }

    private HttpURLConnection openPrinterHttp(String urlStr, String method, int connectMs, int readMs) throws IOException {
        bindToWifiNetwork(true);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            SSLSocketFactory sf = getLocalPrinterSslFactory();
            if (sf != null) https.setSSLSocketFactory(sf);
            https.setHostnameVerifier((h, s) -> true);
        }
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setRequestMethod(method);
        return conn;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private GradientDrawable bg(int fill, int stroke, int sw, float r) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill);
        if (sw > 0) g.setStroke(sw, stroke); g.setCornerRadius(dp(r)); return g;
    }
    private GradientDrawable grad(int c1, int c2, float r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(dp(r)); return g;
    }
    private void bounce(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start();
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
            return false;
        });
    }
    private void closePdf() {
        try { if (pdfRen != null) { pdfRen.close(); pdfRen = null; } if (pdfFd != null) { pdfFd.close(); pdfFd = null; } } catch (Exception ignored) {}
        pdfThumbs.clear(); selectedPages.clear();
        if (thumbStripBox != null) thumbStripBox.removeAllViews();
        if (allPagesBtn != null) allPagesBtn.setVisibility(View.GONE);
    }

    private void clearWorkspaceForNewPrint() {
        closePdf();
        rawBmp = null; idFront = null; idBack = null; lastIdSourceBmp = null;
        totalPages = 0; curPage = 0; copyCount = 1;
        if (copyValTxt != null) copyValTxt.setText("1");
        if (pageRangeEdit != null) pageRangeEdit.setText("");
        if (idToolsRow1 != null) idToolsRow1.setVisibility(View.GONE);
        if (idToolsRow2 != null) idToolsRow2.setVisibility(View.GONE);
        if (clearDocBtn != null) clearDocBtn.setVisibility(View.GONE);
        if (zoomPreviewBtn != null) zoomPreviewBtn.setVisibility(View.GONE);
        resetEditsOnly();
        showEmptyHintPreview();
        if (pageTxt != null) pageTxt.setText("फ़ाइल चुनें या प्रिंटर से स्कैन करें");
        updateEstBadge();
    }

    private void updateLoadedUiState(String label) {
        pageTxt.setText(label);
        clearDocBtn.setVisibility(View.VISIBLE);
        zoomPreviewBtn.setVisibility(View.VISIBLE);
        updateEstBadge();
    }

    private Drawable makeVectorIcon(int type, int color) {
        int s = dp(18);
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.7f));
        float m = dp(2.5f);
        if (type == 1) {
            c.drawRoundRect(m, m * 0.6f, s - m, s - m * 0.6f, dp(2), dp(2), p);
            c.drawLine(m * 1.8f, s * 0.4f, s - m * 1.8f, s * 0.4f, p);
            c.drawLine(m * 1.8f, s * 0.65f, s * 0.65f, s * 0.65f, p);
        } else if (type == 2) {
            c.drawRoundRect(m, s * 0.45f, s - m, s - m, dp(2), dp(2), p);
            c.drawLine(m, s * 0.45f, s - m * 1.4f, m, p);
        } else if (type == 3) {
            c.drawRoundRect(m, s * 0.3f, s - m, s - m, dp(2.5f), dp(2.5f), p);
            c.drawCircle(s / 2f, s * 0.58f, dp(2.6f), p);
        } else if (type == 4) {
            c.drawRoundRect(m * 0.7f, s * 0.22f, s - m * 0.7f, s * 0.78f, dp(2), dp(2), p);
            c.drawCircle(s * 0.33f, s * 0.48f, dp(1.8f), p);
            c.drawLine(s * 0.55f, s * 0.42f, s * 0.8f, s * 0.42f, p);
            c.drawLine(s * 0.55f, s * 0.58f, s * 0.75f, s * 0.58f, p);
        } else if (type == 5) {
            c.drawRect(m, m, s / 2f - dp(1), s / 2f - dp(1), p);
            c.drawRect(s / 2f + dp(1), m, s - m, s / 2f - dp(1), p);
            c.drawRect(m, s / 2f + dp(1), s / 2f - dp(1), s - m, p);
            c.drawRect(s / 2f + dp(1), s / 2f + dp(1), s - m, s - m, p);
        } else if (type == 6) {
            Path path = new Path();
            path.moveTo(s / 2f, m); path.quadTo(s - m, s * 0.6f, s / 2f, s - m); path.quadTo(m, s * 0.6f, s / 2f, m);
            c.drawPath(path, p);
        } else if (type == 7) {
            c.drawRect(m * 1.4f, m, s - m * 1.4f, s * 0.38f, p);
            c.drawRoundRect(m * 0.7f, s * 0.38f, s - m * 0.7f, s * 0.72f, dp(2), dp(2), p);
            c.drawRect(m * 1.4f, s * 0.62f, s - m * 1.4f, s - m, p);
        } else if (type == 8) {
            c.drawLine(s / 2f, m, s / 2f, s * 0.68f, p);
            c.drawLine(s * 0.3f, s * 0.5f, s / 2f, s * 0.68f, p);
            c.drawLine(s * 0.7f, s * 0.5f, s / 2f, s * 0.68f, p);
            c.drawLine(m, s - m, s - m, s - m, p);
        } else {
            c.drawLine(m, s * 0.32f, s - m, s * 0.32f, p);
            c.drawLine(m, s * 0.68f, s - m, s * 0.68f, p);
            c.drawCircle(s * 0.38f, s * 0.32f, dp(2), p);
            c.drawCircle(s * 0.65f, s * 0.68f, dp(2), p);
        }
        return new BitmapDrawable(getResources(), bmp);
    }

    private Bitmap makeAmanLogoBitmap(int sizePx) {
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        c.drawRoundRect(0, 0, sizePx, sizePx, sizePx * 0.24f, sizePx * 0.24f, p);
        float u = sizePx / 100f;
        p.setStyle(Paint.Style.STROKE); p.setColor(Color.parseColor("#0F5A9C")); p.setStrokeWidth(3.2f * u);
        c.drawRect(32 * u, 16 * u, 68 * u, 36 * u, p);
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(16 * u, 34 * u, 84 * u, 68 * u, 9 * u, 9 * u, p);
        p.setColor(Color.parseColor("#98E2C6"));
        c.drawCircle(73 * u, 44 * u, 3.2f * u, p);
        p.setColor(Color.WHITE);
        c.drawRect(28 * u, 58 * u, 72 * u, 84 * u, p);
        p.setStyle(Paint.Style.STROKE); p.setColor(Color.parseColor("#0F5A9C")); p.setStrokeWidth(3.2f * u);
        c.drawRect(28 * u, 58 * u, 72 * u, 84 * u, p);
        p.setColor(Color.parseColor("#74AEE5")); p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(36 * u, 67 * u, 64 * u, 67 * u, p);
        c.drawLine(36 * u, 75 * u, 56 * u, 75 * u, p);
        return b;
    }

    // Pinch-to-Zoom + Pan ImageView for inspecting PDF pages and photos up close
    private class ZoomImageView extends ImageView {
        private final Matrix matrix = new Matrix();
        private float currentScale = 1f;
        private final ScaleGestureDetector scaleDetector;
        private float lastX, lastY;
        private boolean dragging = false;

        ZoomImageView(Context ctx) {
            super(ctx);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
        }

        void resetZoom() {
            Drawable d = getDrawable();
            if (d == null || getWidth() == 0 || getHeight() == 0) return;
            float vw = getWidth(), vh = getHeight();
            float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;
            float fit = Math.min(vw / dw, vh / dh);
            matrix.reset();
            matrix.postScale(fit, fit);
            matrix.postTranslate((vw - dw * fit) / 2f, (vh - dh * fit) / 2f);
            currentScale = 1f;
            setImageMatrix(matrix);
        }

        void zoomBy(float factor, float fx, float fy) {
            float target = currentScale * factor;
            if (target < 1f) factor = 1f / currentScale;
            else if (target > 6f) factor = 6f / currentScale;
            currentScale *= factor;
            matrix.postScale(factor, factor, fx, fy);
            setImageMatrix(matrix);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (changed) resetZoom();
        }

        @Override public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            post(this::resetZoom);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            scaleDetector.onTouchEvent(e);
            if (e.getPointerCount() == 1) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        lastX = e.getX(); lastY = e.getY(); dragging = true; break;
                    case MotionEvent.ACTION_MOVE:
                        if (dragging && !scaleDetector.isInProgress() && currentScale > 1.02f) {
                            matrix.postTranslate(e.getX() - lastX, e.getY() - lastY);
                            setImageMatrix(matrix);
                            lastX = e.getX(); lastY = e.getY();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false; break;
                }
            }
            return true;
        }
    }

    private void buildUi() {
        outerRoot = new LinearLayout(this);
        outerRoot.setOrientation(LinearLayout.VERTICAL);
        outerRoot.setBackgroundColor(Color.parseColor("#EEF2F6"));

        mainScroll = new ScrollView(this);
        mainScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        rootBox.setPadding(dp(14), dp(12), dp(14), dp(18));
        mainScroll.addView(rootBox);
        outerRoot.addView(mainScroll);

        stickyBar = new LinearLayout(this);
        stickyBar.setOrientation(LinearLayout.HORIZONTAL);
        stickyBar.setGravity(Gravity.CENTER_VERTICAL);
        stickyBar.setBackgroundColor(Color.WHITE);
        stickyBar.setPadding(dp(14), dp(10), dp(14), dp(12));
        stickyBar.setElevation(dp(12));

        estBadgeTxt = new TextView(this);
        estBadgeTxt.setText("1 Copy\nA4 Normal");
        estBadgeTxt.setTextSize(11); estBadgeTxt.setTypeface(null, Typeface.BOLD);
        estBadgeTxt.setTextColor(Color.parseColor("#475569"));
        estBadgeTxt.setPadding(0, 0, dp(10), 0);
        stickyBar.addView(estBadgeTxt);

        Button printBtn = new Button(this);
        printBtn.setText("PRINT NOW");
        printBtn.setAllCaps(false); printBtn.setTextSize(16); printBtn.setTypeface(null, Typeface.BOLD);
        printBtn.setTextColor(Color.WHITE);
        printBtn.setBackground(grad(Color.parseColor("#0F5A9C"), Color.parseColor("#0077C8"), 14));
        printBtn.setCompoundDrawablesWithIntrinsicBounds(makeVectorIcon(7, Color.WHITE), null, null, null);
        printBtn.setCompoundDrawablePadding(dp(8));
        printBtn.setPadding(dp(20), 0, dp(20), 0);
        printBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(50), 1f));
        bounce(printBtn); printBtn.setOnClickListener(v -> openInAppPrintSheet());
        stickyBar.addView(printBtn);
        outerRoot.addView(stickyBar);

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.VERTICAL);
        hdr.setBackground(grad(Color.parseColor("#0F5A9C"), Color.parseColor("#0077C8"), 18));
        hdr.setPadding(dp(16), dp(14), dp(16), dp(14));
        hdr.setElevation(dp(5));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logoImg = new ImageView(this);
        logoImg.setImageBitmap(makeAmanLogoBitmap(dp(34)));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        logoLp.setMargins(0, 0, dp(10), 0); logoImg.setLayoutParams(logoLp);
        top.addView(logoImg);

        TextView ttl = new TextView(this);
        ttl.setText("Aman Print");
        ttl.setTextSize(20); ttl.setTypeface(null, Typeface.BOLD); ttl.setTextColor(Color.WHITE);
        ttl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(ttl);

        themeBtn = new Button(this);
        themeBtn.setText("Dark UI"); themeBtn.setAllCaps(false); themeBtn.setTextSize(11); themeBtn.setTypeface(null, Typeface.BOLD);
        themeBtn.setTextColor(Color.WHITE); themeBtn.setBackground(bg(Color.parseColor("#083B66"), 0, 0, 14));
        themeBtn.setPadding(dp(10), dp(4), dp(10), dp(4)); themeBtn.setMinHeight(0); themeBtn.setMinimumHeight(0);
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        thLp.setMargins(0, 0, dp(6), 0); themeBtn.setLayoutParams(thLp);
        themeBtn.setOnClickListener(v -> toggleAppTheme());
        top.addView(themeBtn);

        badgeTxt = new TextView(this);
        badgeTxt.setText("Wi-Fi"); badgeTxt.setTextSize(11); badgeTxt.setTypeface(null, Typeface.BOLD);
        badgeTxt.setTextColor(Color.parseColor("#0F5A9C")); badgeTxt.setBackground(bg(Color.parseColor("#E0F2FE"), 0, 0, 14));
        badgeTxt.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(badgeTxt);
        hdr.addView(top);

        statusTxt = new TextView(this);
        statusTxt.setText("HP Smart Tank 589 — Tap here to set IP");
        statusTxt.setTextSize(12); statusTxt.setTextColor(Color.parseColor("#E0F2FE"));
        statusTxt.setPadding(0, dp(6), 0, dp(10));
        statusTxt.setOnClickListener(v -> promptPrinterIp());
        hdr.addView(statusTxt);

        LinearLayout mBar = new LinearLayout(this);
        mBar.setBackground(bg(Color.parseColor("#083B66"), 0, 0, 12));
        mBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        String[] modes = {"Wi-Fi", "Wi-Fi Direct", "USB OTG"};
        for (int i = 0; i < 3; i++) {
            final String m = modes[i];
            Button b = new Button(this);
            b.setText(m); b.setAllCaps(false); b.setSingleLine(true); b.setTextSize(12); b.setTypeface(null, Typeface.BOLD);
            b.setStateListAnimator(null); b.setPadding(dp(2), 0, dp(2), 0); b.setMinHeight(0); b.setMinimumHeight(0);
            b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(36), 1f));
            bounce(b); b.setOnClickListener(v -> setMode(m));
            modeBtns[i] = b; mBar.addView(b);
        }
        hdr.addView(mBar);
        rootBox.addView(hdr);
        refreshModeUi();

        LinearLayout act = card();
        LinearLayout r1 = new LinearLayout(this);
        r1.addView(iconTile("Open File", 1, "#EFF6FF", "#1D4ED8", v -> { pickTarget = 0; picker.launch(new String[]{"application/pdf", "image/*"}); }));
        gap(r1, 8);
        r1.addView(iconTile("Printer Scan", 2, "#ECFEFF", "#0E7490", v -> openPrinterScannerDialog()));
        act.addView(r1);

        LinearLayout r2 = new LinearLayout(this); r2.setPadding(0, dp(8), 0, 0);
        r2.addView(iconTile("Camera Scan", 3, "#F0FDF4", "#15803D", v -> launchCamera()));
        gap(r2, 8);
        r2.addView(iconTile("ID Card (Auto)", 4, "#FEF3C7", "#B45309", v -> idDialog()));
        act.addView(r2);

        LinearLayout r3 = new LinearLayout(this); r3.setPadding(0, dp(8), 0, 0);
        r3.addView(iconTile("Passport Sheet", 5, "#FCE7F3", "#BE185D", v -> startPassportSheet()));
        gap(r3, 8);
        r3.addView(iconTile("Ink & Nozzle", 6, "#F3E8FF", "#6D28D9", v -> openInkAndDashboard()));
        act.addView(r3);

        recentRowBox = new LinearLayout(this);
        recentRowBox.setOrientation(LinearLayout.HORIZONTAL);
        recentRowBox.setPadding(0, dp(8), 0, 0);
        act.addView(recentRowBox);
        rootBox.addView(act);

        // Preview Card with Zoom Inspect + Clear/New + All Pages
        LinearLayout pCard = card();
        LinearLayout pTop = new LinearLayout(this); pTop.setGravity(Gravity.CENTER_VERTICAL);
        pageTxt = new TextView(this);
        pageTxt.setText("फाइल चुनें या प्रिंटर से स्कैन करें");
        pageTxt.setTextSize(13); pageTxt.setTypeface(null, Typeface.BOLD); pageTxt.setTextColor(Color.parseColor("#0F172A"));
        pageTxt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pTop.addView(pageTxt);

        zoomPreviewBtn = makeSmallChip("Zoom", "#E0F2FE", "#0F5A9C", v -> openZoomInspector(curPage));
        zoomPreviewBtn.setVisibility(View.GONE);
        pTop.addView(zoomPreviewBtn);

        allPagesBtn = makeSmallChip("Pages", "#EFF6FF", "#1D4ED8", v -> openAllPagesListDialog());
        allPagesBtn.setVisibility(View.GONE);
        pTop.addView(allPagesBtn);

        clearDocBtn = makeSmallChip("Clear", "#FEE2E2", "#991B1B", v -> clearWorkspaceForNewPrint());
        clearDocBtn.setVisibility(View.GONE);
        pTop.addView(clearDocBtn);
        pCard.addView(pTop);

        previewImg = new ImageView(this);
        previewImg.setMinimumHeight(dp(210)); previewImg.setMaxHeight(dp(300)); previewImg.setAdjustViewBounds(true);
        previewImg.setBackground(bg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), dp(1), 12));
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvLp.setMargins(0, dp(8), 0, 0); previewImg.setLayoutParams(pvLp);
        previewImg.setPadding(dp(8), dp(8), dp(8), dp(8));
        previewImg.setOnClickListener(v -> {
            if (rawBmp == null && pdfRen == null) { pickTarget = 0; picker.launch(new String[]{"application/pdf", "image/*"}); }
            else openZoomInspector(curPage);
        });
        pCard.addView(previewImg);

        HorizontalScrollView thumbScroll = new HorizontalScrollView(this);
        thumbScroll.setHorizontalScrollBarEnabled(false);
        thumbStripBox = new LinearLayout(this);
        thumbStripBox.setOrientation(LinearLayout.HORIZONTAL);
        thumbStripBox.setPadding(0, dp(8), 0, dp(2));
        thumbScroll.addView(thumbStripBox);
        pCard.addView(thumbScroll);

        idToolsRow1 = new LinearLayout(this);
        idToolsRow1.setOrientation(LinearLayout.HORIZONTAL);
        idToolsRow1.setPadding(0, dp(8), 0, 0);
        idToolsRow1.setVisibility(View.GONE);
        idToolsRow1.addView(cleanBtn("Side-by-Side (CKYC)", "#FEF3C7", "#92400E", v -> applyIdSplitPreset(0)));
        gap(idToolsRow1, 8);
        idToolsRow1.addView(cleanBtn("e-Aadhaar / e-PAN", "#E0F2FE", "#075985", v -> applyIdSplitPreset(1)));
        pCard.addView(idToolsRow1);

        idToolsRow2 = new LinearLayout(this);
        idToolsRow2.setOrientation(LinearLayout.HORIZONTAL);
        idToolsRow2.setPadding(0, dp(6), 0, 0);
        idToolsRow2.setVisibility(View.GONE);
        idToolsRow2.addView(cleanBtn("Swap Front/Back", "#F1F5F9", "#334155", v -> { Bitmap t = idFront; idFront = idBack; idBack = t; buildIdSheet(); }));
        gap(idToolsRow2, 8);
        idToolsRow2.addView(cleanBtn("Manual Crop / +2nd", "#DCFCE7", "#166534", v -> openIdAdjustMenu()));
        pCard.addView(idToolsRow2);

        LinearLayout saveRow = new LinearLayout(this); saveRow.setPadding(0, dp(8), 0, 0);
        saveRow.addView(iconTile("Save JPG", 8, "#DCFCE7", "#166534", v -> saveCurrentAsJpg()));
        gap(saveRow, 8);
        saveRow.addView(iconTile("Save PDF", 8, "#DBEAFE", "#1E40AF", v -> saveCurrentAsPdf()));
        pCard.addView(saveRow);
        rootBox.addView(pCard);

        // Photo & Page Studio Editor
        LinearLayout editCard = card();
        editToggleTxt = new TextView(this);
        editToggleTxt.setText("Photo & Page Editor (Light, Tone, BG Change)  +");
        editToggleTxt.setTextSize(14); editToggleTxt.setTypeface(null, Typeface.BOLD); editToggleTxt.setTextColor(Color.parseColor("#0F5A9C"));
        editToggleTxt.setCompoundDrawablesWithIntrinsicBounds(makeVectorIcon(9, Color.parseColor("#0F5A9C")), null, null, null);
        editToggleTxt.setCompoundDrawablePadding(dp(8));
        editCard.addView(editToggleTxt);

        editorBox = new LinearLayout(this);
        editorBox.setOrientation(LinearLayout.VERTICAL);
        editorBox.setVisibility(View.GONE);
        editorBox.setPadding(0, dp(8), 0, 0);

        editToggleTxt.setOnClickListener(v -> {
            TransitionManager.beginDelayedTransition(rootBox, new AutoTransition().setDuration(180));
            editorOpen = !editorOpen;
            editorBox.setVisibility(editorOpen ? View.VISIBLE : View.GONE);
            editToggleTxt.setText(editorOpen ? "Photo & Page Editor (Hide)  -" : "Photo & Page Editor (Light, Tone, BG Change)  +");
        });

        editorBox.addView(lbl("Background Change / Clean:"));
        LinearLayout bgBar = new LinearLayout(this);
        String[] bgNames = {"Original", "White Paper", "Studio White", "Blue BG"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Button bb = new Button(this);
            bb.setText(bgNames[i]); bb.setAllCaps(false); bb.setSingleLine(true); bb.setTextSize(11); bb.setTypeface(null, Typeface.BOLD);
            bb.setPadding(dp(4), 0, dp(4), 0); bb.setMinHeight(0); bb.setMinimumHeight(0);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            if (i < 3) blp.setMargins(0, 0, dp(4), 0);
            bb.setLayoutParams(blp);
            bb.setOnClickListener(v -> { bgCleanMode = idx; refreshBgBtnsUi(); applyEditsToPreview(); });
            bgBtns[i] = bb; bgBar.addView(bb);
        }
        editorBox.addView(bgBar);
        refreshBgBtnsUi();

        sbBright = addEditorSlider(editorBox, "Lighting / Brightness", 200, 100, val -> { editBright = val - 100; applyEditsToPreview(); });
        sbContrast = addEditorSlider(editorBox, "Dark / Light Contrast", 150, 50, val -> { editContrast = val + 50; applyEditsToPreview(); });
        sbSat = addEditorSlider(editorBox, "Color / Saturation", 200, 100, val -> { editSat = val; applyEditsToPreview(); });
        sbWarmth = addEditorSlider(editorBox, "Color Tone (Cool <-> Warm)", 100, 50, val -> { editWarmth = val - 50; applyEditsToPreview(); });

        LinearLayout edActRow = new LinearLayout(this); edActRow.setPadding(0, dp(8), 0, 0);
        edActRow.addView(cleanBtn("Rotate 90°", "#EFF6FF", "#1D4ED8", v -> { rotationDeg = (rotationDeg + 90) % 360; applyEditsToPreview(); }));
        gap(edActRow, 8);
        edActRow.addView(cleanBtn("Auto Enhance", "#DCFCE7", "#166534", v -> {
            editBright = 12; editContrast = 125; editSat = 115; bgCleanMode = 1;
            sbBright.setProgress(112); sbContrast.setProgress(75); sbSat.setProgress(115); refreshBgBtnsUi(); applyEditsToPreview();
        }));
        gap(edActRow, 8);
        edActRow.addView(cleanBtn("Reset", "#FEE2E2", "#991B1B", v -> { resetEditsOnly(); applyEditsToPreview(); }));
        editorBox.addView(edActRow);

        editCard.addView(editorBox);
        rootBox.addView(editCard);

        // Quick Presets, Color, Copies, Duplex & Orientation Card
        LinearLayout quickCard = card();
        quickCard.addView(secTitle("Quick Presets & Print Controls"));

        LinearLayout preBar = new LinearLayout(this);
        preBar.setBackground(bg(Color.parseColor("#F1F5F9"), Color.parseColor("#CBD5E1"), dp(1), 12));
        preBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        String[] preNames = {"Document", "Photo", "ID Card"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button pb = new Button(this);
            pb.setText(preNames[i]); pb.setAllCaps(false); pb.setSingleLine(true); pb.setTextSize(12); pb.setTypeface(null, Typeface.BOLD);
            pb.setStateListAnimator(null); pb.setPadding(dp(4), 0, dp(4), 0); pb.setMinHeight(0); pb.setMinimumHeight(0);
            pb.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1f));
            bounce(pb); pb.setOnClickListener(v -> applyPreset(idx));
            presetBtns[i] = pb; preBar.addView(pb);
        }
        quickCard.addView(preBar);
        refreshPresetUi();

        quickCard.addView(lbl("Color Mode:"));
        LinearLayout cBar = new LinearLayout(this);
        cBar.setBackground(bg(Color.parseColor("#F1F5F9"), Color.parseColor("#CBD5E1"), dp(1), 12));
        cBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        String[] cNames = {"Full Color", "B&W", "Sharp B&W"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button cb = new Button(this);
            cb.setText(cNames[i]); cb.setAllCaps(false); cb.setSingleLine(true); cb.setTextSize(12); cb.setTypeface(null, Typeface.BOLD);
            cb.setStateListAnimator(null); cb.setPadding(dp(2), 0, dp(2), 0); cb.setMinHeight(0); cb.setMinimumHeight(0);
            cb.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1f));
            bounce(cb); cb.setOnClickListener(v -> setColor(idx));
            colorBtns[i] = cb; cBar.addView(cb);
        }
        quickCard.addView(cBar);
        refreshColorUi();

        // Duplex Right in Quick Controls (with explicit Front All & Back All options!)
        quickCard.addView(lbl("Double-Sided / Duplex Mode:"));
        duplexSp = spin(new String[]{
                "One side only (Single)",
                "Front Pages Only — All Odd (1, 3, 5...)",
                "Back Pages Only — All Even Reverse (...6, 4, 2)",
                "Back Pages Only — All Even Normal (2, 4, 6...)",
                "Auto Manual Duplex (Odd -> Flip -> Even)"
        });
        quickCard.addView(duplexSp);

        LinearLayout cpRow = new LinearLayout(this); cpRow.setPadding(0, dp(10), 0, 0); cpRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView cpLbl = new TextView(this); cpLbl.setText("Copies:"); cpLbl.setTextSize(13); cpLbl.setTypeface(null, Typeface.BOLD); cpLbl.setTextColor(Color.parseColor("#334155"));
        cpRow.addView(cpLbl); gap(cpRow, 8);

        Button minusBtn = new Button(this); minusBtn.setText("-"); minusBtn.setTextSize(16); minusBtn.setTypeface(null, Typeface.BOLD);
        minusBtn.setBackground(bg(Color.parseColor("#E2E8F0"), 0, 0, 8)); minusBtn.setPadding(0, 0, 0, 0);
        minusBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        minusBtn.setOnClickListener(v -> { if (copyCount > 1) { copyCount--; copyValTxt.setText(String.valueOf(copyCount)); updateEstBadge(); } });
        cpRow.addView(minusBtn);

        copyValTxt = new TextView(this); copyValTxt.setText("1"); copyValTxt.setGravity(Gravity.CENTER);
        copyValTxt.setTextSize(15); copyValTxt.setTypeface(null, Typeface.BOLD); copyValTxt.setTextColor(Color.parseColor("#0F172A"));
        copyValTxt.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        cpRow.addView(copyValTxt);

        Button plusBtn = new Button(this); plusBtn.setText("+"); plusBtn.setTextSize(16); plusBtn.setTypeface(null, Typeface.BOLD);
        plusBtn.setBackground(bg(Color.parseColor("#E2E8F0"), 0, 0, 8)); plusBtn.setPadding(0, 0, 0, 0);
        plusBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        plusBtn.setOnClickListener(v -> { if (copyCount < 50) { copyCount++; copyValTxt.setText(String.valueOf(copyCount)); updateEstBadge(); } });
        cpRow.addView(plusBtn);

        gap(cpRow, 12);
        pageRangeEdit = new EditText(this);
        pageRangeEdit.setHint("Pages: All or 1-5");
        pageRangeEdit.setSingleLine(true); pageRangeEdit.setTextSize(13); pageRangeEdit.setTextColor(Color.parseColor("#0F172A"));
        pageRangeEdit.setBackground(bg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), dp(1), 10));
        pageRangeEdit.setPadding(dp(10), dp(8), dp(10), dp(8));
        pageRangeEdit.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        cpRow.addView(pageRangeEdit);
        quickCard.addView(cpRow);

        LinearLayout oriRow = new LinearLayout(this); oriRow.setPadding(0, dp(10), 0, 0); oriRow.setGravity(Gravity.CENTER_VERTICAL);
        String[] oriNames = {"Portrait", "Landscape"};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            Button ob = new Button(this);
            ob.setText(oriNames[i]); ob.setAllCaps(false); ob.setSingleLine(true); ob.setTextSize(12); ob.setTypeface(null, Typeface.BOLD);
            ob.setPadding(dp(8), 0, dp(8), 0); ob.setMinHeight(0); ob.setMinimumHeight(0);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (i == 0) olp.setMargins(0, 0, dp(6), 0);
            ob.setLayoutParams(olp);
            ob.setOnClickListener(v -> { orientationMode = idx; refreshOrientUi(); });
            orientBtns[i] = ob; oriRow.addView(ob);
        }
        refreshOrientUi();

        inkSaverCheck = new CheckBox(this);
        inkSaverCheck.setText("Ink Saver");
        inkSaverCheck.setTextSize(13); inkSaverCheck.setTypeface(null, Typeface.BOLD); inkSaverCheck.setTextColor(Color.parseColor("#15803D"));
        inkSaverCheck.setButtonTintList(ColorStateList.valueOf(Color.parseColor("#15803D")));
        inkSaverCheck.setPadding(dp(6), 0, 0, 0);
        inkSaverCheck.setOnCheckedChangeListener((b, chk) -> {
            inkSaverOn = chk;
            if (chk) qualSp.setSelection(1);
            updateEstBadge();
            applyEditsToPreview();
        });
        oriRow.addView(inkSaverCheck);
        quickCard.addView(oriRow);
        rootBox.addView(quickCard);

        // Collapsible Advanced Settings Card
        LinearLayout advCard = card();
        advToggleTxt = new TextView(this);
        advToggleTxt.setText("Advanced Settings (Paper, Margins, 2-in-1)  +");
        advToggleTxt.setTextSize(14); advToggleTxt.setTypeface(null, Typeface.BOLD); advToggleTxt.setTextColor(Color.parseColor("#0F5A9C"));
        advCard.addView(advToggleTxt);

        advContentBox = new LinearLayout(this);
        advContentBox.setOrientation(LinearLayout.VERTICAL);
        advContentBox.setVisibility(View.GONE);
        advContentBox.setPadding(0, dp(8), 0, 0);

        advToggleTxt.setOnClickListener(v -> {
            TransitionManager.beginDelayedTransition(rootBox, new AutoTransition().setDuration(180));
            advancedOpen = !advancedOpen;
            advContentBox.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
            advToggleTxt.setText(advancedOpen ? "Advanced Settings (Hide)  -" : "Advanced Settings (Paper, Margins, 2-in-1)  +");
        });

        advContentBox.addView(lbl("Paper Size:"));
        sizeSp = spin(new String[]{"A4 (210 x 297 mm)", "Custom Glossy (25 x 21 cm)", "Ruled/Punched Sheet (22 x 28 cm)", "4 x 6 inch Photo", "5 x 7 inch Photo", "A5 (148 x 210 mm)", "Legal (8.5 x 14 in)"});
        advContentBox.addView(sizeSp);

        advContentBox.addView(lbl("Paper Type & Quality:"));
        qualSp = spin(new String[]{"Plain Paper - Normal", "Plain Paper - Draft (Ink Saver)", "Glossy Photo Paper (180-300 GSM - Best)", "Matte Brochure Paper", "Heavy Cardstock (200 GSM)"});
        advContentBox.addView(qualSp);

        advContentBox.addView(lbl("Page Scaling:"));
        scaleSp = spin(new String[]{"Scale to fit", "Fill page (Borderless)", "Actual size (100%)"});
        advContentBox.addView(scaleSp);

        advContentBox.addView(lbl("Margins:"));
        marginSp = spin(new String[]{"By printable area (3mm)", "Borderless (0mm)", "Punched Left Margin (18mm)"});
        advContentBox.addView(marginSp);

        advContentBox.addView(lbl("Multi-Page Layout:"));
        multiSp = spin(new String[]{"Singly (1 page/sheet)", "Multiple: 2-in-1", "Multiple: 4-in-1", "Poster (2x2 A4 Split)"});
        advContentBox.addView(multiSp);

        revCheck = new CheckBox(this);
        revCheck.setText("Print in reverse order (Last page first)");
        revCheck.setTextColor(Color.parseColor("#0F172A")); revCheck.setTextSize(13);
        revCheck.setPadding(dp(4), dp(10), dp(4), dp(4));
        revCheck.setButtonTintList(ColorStateList.valueOf(Color.parseColor("#0F5A9C")));
        advContentBox.addView(revCheck);

        advCard.addView(advContentBox);
        rootBox.addView(advCard);
        setContentView(outerRoot);
    }

    private Button makeSmallChip(String text, String bgHex, String fgHex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setSingleLine(true); b.setTextSize(11); b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.parseColor(fgHex)); b.setBackground(bg(Color.parseColor(bgHex), 0, 0, 9));
        b.setPadding(dp(8), dp(4), dp(8), dp(4)); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        lp.setMargins(dp(5), 0, 0, 0); b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private interface SliderCb { void onVal(int v); }
    private SeekBar addEditorSlider(LinearLayout parent, String title, int max, int def, SliderCb cb) {
        parent.addView(lbl(title + ":"));
        SeekBar sb = new SeekBar(this);
        sb.setMax(max); sb.setProgress(def);
        sb.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#0F5A9C")));
        sb.setThumbTintList(ColorStateList.valueOf(Color.parseColor("#0F5A9C")));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser) cb.onVal(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        parent.addView(sb);
        return sb;
    }

    private void resetEditsOnly() {
        editBright = 0; editContrast = 100; editSat = 100; editWarmth = 0; bgCleanMode = 0; rotationDeg = 0;
        if (sbBright != null) { sbBright.setProgress(100); sbContrast.setProgress(50); sbSat.setProgress(100); sbWarmth.setProgress(50); refreshBgBtnsUi(); }
    }

    private void refreshBgBtnsUi() {
        for (int i = 0; i < 4; i++) {
            boolean act = (i == bgCleanMode);
            bgBtns[i].setBackground(bg(act ? Color.parseColor("#0F5A9C") : Color.parseColor("#F1F5F9"), 0, 0, 8));
            bgBtns[i].setTextColor(act ? Color.WHITE : Color.parseColor("#334155"));
        }
    }

    private void applyEditsToPreview() { if (rawBmp != null) previewImg.setImageBitmap(applyAllEdits(rawBmp)); }

    private Bitmap applyAllEdits(Bitmap src) {
        if (src == null) return null;
        Bitmap working = src;
        if (rotationDeg != 0) {
            Matrix m = new Matrix(); m.postRotate(rotationDeg);
            working = Bitmap.createBitmap(working, 0, 0, working.getWidth(), working.getHeight(), m, true);
        }
        int w = working.getWidth(), h = working.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation((colorMode != 0) ? 0f : (editSat / 100f));
        float con = (editContrast / 100f) * (colorMode == 2 ? 1.55f : 1f);
        float br = editBright * 1.4f;
        if (inkSaverOn) { con *= 0.85f; br += 36f; }
        float tr = (-0.5f * con + 0.5f) * 255f + br;
        float rWarm = 1f + (editWarmth > 0 ? editWarmth * 0.005f : 0f);
        float bCool = 1f + (editWarmth < 0 ? -editWarmth * 0.006f : 0f);
        cm.postConcat(new ColorMatrix(new float[]{con * rWarm,0,0,0,tr, 0,con,0,0,tr, 0,0,con * bCool,0,tr, 0,0,0,1,0}));
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(working, 0, 0, p);

        if (bgCleanMode > 0) {
            int[] px = new int[w * h];
            out.getPixels(px, 0, w, 0, 0, w, h);
            int c1 = px[0], c2 = px[Math.max(0, w - 1)];
            int brg = (((c1 >> 16) & 0xFF) + ((c2 >> 16) & 0xFF)) / 2;
            int bgg = (((c1 >> 8) & 0xFF) + ((c2 >> 8) & 0xFF)) / 2;
            int bbg = ((c1 & 0xFF) + (c2 & 0xFF)) / 2;
            int targetBg = (bgCleanMode == 3) ? Color.parseColor("#D6EAFF") : Color.WHITE;
            for (int i = 0; i < px.length; i++) {
                int v = px[i], r = (v >> 16) & 0xFF, g = (v >> 8) & 0xFF, b = v & 0xFF;
                if (bgCleanMode == 1) {
                    int lum = (r * 77 + g * 150 + b * 29) >> 8;
                    int dif = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                    if (lum > 182 && dif < 45) px[i] = Color.WHITE;
                } else if (Math.abs(r - brg) + Math.abs(g - bgg) + Math.abs(b - bbg) < 92) {
                    px[i] = targetBg;
                }
            }
            out.setPixels(px, 0, w, 0, 0, w, h);
        }
        return out;
    }

    private void toggleAppTheme() {
        darkUi = !darkUi;
        themeBtn.setText(darkUi ? "Light UI" : "Dark UI");
        outerRoot.setBackgroundColor(darkUi ? Color.parseColor("#0F172A") : Color.parseColor("#EEF2F6"));
        stickyBar.setBackgroundColor(darkUi ? Color.parseColor("#1E293B") : Color.WHITE);
        estBadgeTxt.setTextColor(darkUi ? Color.parseColor("#CBD5E1") : Color.parseColor("#475569"));
    }

    private void updateEstBadge() {
        int pages = (pdfRen != null) ? Math.max(1, selectedPages.size()) : 1;
        estBadgeTxt.setText(copyCount + " Copy • " + pages + " Pg\n" + (inkSaverOn ? "Ink Saver" : "Standard"));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(bg(Color.WHITE, Color.parseColor("#E2E8F0"), dp(1), 16));
        c.setPadding(dp(14), dp(14), dp(14), dp(14)); c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0); c.setLayoutParams(lp); return c;
    }

    private Button iconTile(String t, int iconId, String bgHex, String fgHex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setSingleLine(true); b.setTextSize(13); b.setTypeface(null, Typeface.BOLD);
        int fg = Color.parseColor(fgHex);
        b.setTextColor(fg); b.setBackground(bg(Color.parseColor(bgHex), 0, 0, 12));
        b.setCompoundDrawablesWithIntrinsicBounds(makeVectorIcon(iconId, fg), null, null, null);
        b.setCompoundDrawablePadding(dp(6));
        b.setPadding(dp(10), 0, dp(10), 0); b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        bounce(b); b.setOnClickListener(l); return b;
    }

    private Button cleanBtn(String t, String bgHex, String fgHex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setSingleLine(true); b.setTextSize(12); b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.parseColor(fgHex)); b.setBackground(bg(Color.parseColor(bgHex), 0, 0, 10));
        b.setPadding(dp(6), 0, dp(6), 0); b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1f));
        bounce(b); b.setOnClickListener(l); return b;
    }

    private void gap(LinearLayout r, int w) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1)); r.addView(v); }
    private TextView secTitle(String t) { TextView tv = new TextView(this); tv.setText(t); tv.setTextSize(14); tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(Color.parseColor("#0F172A")); tv.setPadding(0, 0, 0, dp(8)); return tv; }
    private TextView lbl(String t) { TextView tv = new TextView(this); tv.setText(t); tv.setTextSize(12); tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(Color.parseColor("#475569")); tv.setPadding(0, dp(8), 0, dp(4)); return tv; }

    private Spinner spin(String[] items) {
        Spinner s = new Spinner(this);
        s.setBackground(bg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), dp(1), 10));
        s.setPadding(dp(8), dp(4), dp(8), dp(4));
        s.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int p, View cv, ViewGroup pr) {
                TextView tv = (TextView) super.getView(p, cv, pr);
                tv.setTextColor(Color.parseColor("#0F172A")); tv.setTextSize(13); tv.setTypeface(null, Typeface.BOLD); return tv;
            }
            @Override public View getDropDownView(int p, View cv, ViewGroup pr) {
                TextView tv = (TextView) super.getDropDownView(p, cv, pr);
                tv.setTextColor(Color.parseColor("#0F172A")); tv.setBackgroundColor(Color.WHITE);
                tv.setPadding(dp(14), dp(12), dp(14), dp(12)); tv.setTextSize(13); return tv;
            }
        });
        return s;
    }

    private void showEmptyHintPreview() {
        Bitmap ph = Bitmap.createBitmap(720, 380, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(ph); c.drawColor(Color.parseColor("#F8FAFC"));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE); p.setColor(Color.parseColor("#94A3B8")); p.setStrokeWidth(3);
        p.setPathEffect(new DashPathEffect(new float[]{14, 10}, 0));
        c.drawRoundRect(30, 30, 690, 350, 24, 24, p);
        p.setPathEffect(null); p.setStyle(Paint.Style.FILL); p.setColor(Color.parseColor("#475569"));
        p.setTextAlign(Paint.Align.CENTER); p.setTextSize(28);
        c.drawText("फ़ाइल चुनें या प्रिंटर से स्कैन करें", 360, 180, p);
        p.setTextSize(20); p.setColor(Color.parseColor("#64748B"));
        c.drawText("Open File • Printer Scan • Passport • ID Card", 360, 225, p);
        previewImg.setImageBitmap(ph);
    }

    private void applyPreset(int idx) {
        presetMode = idx; refreshPresetUi();
        if (idx == 0) { sizeSp.setSelection(0); qualSp.setSelection(0); scaleSp.setSelection(0); setColor(0); idToolsRow1.setVisibility(View.GONE); idToolsRow2.setVisibility(View.GONE); }
        else if (idx == 1) { sizeSp.setSelection(3); qualSp.setSelection(2); scaleSp.setSelection(1); setColor(0); idToolsRow1.setVisibility(View.GONE); idToolsRow2.setVisibility(View.GONE); }
        else { sizeSp.setSelection(0); qualSp.setSelection(2); setColor(0); idDialog(); }
    }

    private void refreshPresetUi() {
        for (int i = 0; i < 3; i++) {
            boolean act = (i == presetMode);
            presetBtns[i].setBackground(bg(act ? Color.parseColor("#0F5A9C") : Color.TRANSPARENT, 0, 0, 9));
            presetBtns[i].setTextColor(act ? Color.WHITE : Color.parseColor("#334155"));
        }
    }

    private void refreshOrientUi() {
        for (int i = 0; i < 2; i++) {
            boolean act = (i == orientationMode);
            orientBtns[i].setBackground(bg(act ? Color.parseColor("#0F5A9C") : Color.parseColor("#F1F5F9"), 0, 0, 9));
            orientBtns[i].setTextColor(act ? Color.WHITE : Color.parseColor("#334155"));
        }
    }

    private void setMode(String m) {
        TransitionManager.beginDelayedTransition(rootBox, new AutoTransition().setDuration(160));
        connMode = m; badgeTxt.setText(m); refreshModeUi();
        if (m.equals("Wi-Fi Direct")) {
            bindToWifiNetwork(true);
            printerIp = "192.168.223.1";
            statusTxt.setText("Wi-Fi Direct IP: 192.168.223.1 (Tap to edit)");
        } else if (m.equals("USB OTG")) {
            bindToWifiNetwork(false);
            checkUsb();
        } else {
            bindToWifiNetwork(true);
            statusTxt.setText("Home Wi-Fi: " + printerName + " (" + printerIp + ")");
        }
    }

    private void refreshModeUi() {
        String[] m = {"Wi-Fi", "Wi-Fi Direct", "USB OTG"};
        for (int i = 0; i < 3; i++) {
            boolean act = m[i].equals(connMode);
            modeBtns[i].setBackground(bg(act ? Color.WHITE : Color.TRANSPARENT, 0, 0, 9));
            modeBtns[i].setTextColor(act ? Color.parseColor("#0F5A9C") : Color.parseColor("#BAE6FD"));
        }
    }

    private void setColor(int idx) { colorMode = idx; refreshColorUi(); applyEditsToPreview(); }
    private void refreshColorUi() {
        for (int i = 0; i < 3; i++) {
            boolean act = (i == colorMode);
            colorBtns[i].setBackground(bg(act ? (i == 0 ? Color.parseColor("#0077C8") : Color.parseColor("#1E293B")) : Color.TRANSPARENT, 0, 0, 9));
            colorBtns[i].setTextColor(act ? Color.WHITE : Color.parseColor("#334155"));
        }
    }

    private void saveRecentUri(Uri uri) {
        SharedPreferences sp = getSharedPreferences("aman_print_prefs", MODE_PRIVATE);
        String old = sp.getString("recents", "");
        List<String> list = new ArrayList<>(); list.add(uri.toString());
        for (String s : old.split("\\|")) if (!s.isEmpty() && !s.equals(uri.toString()) && list.size() < 3) list.add(s);
        sp.edit().putString("recents", TextUtils.join("|", list)).apply();
        refreshRecentBar();
    }

    private void refreshRecentBar() {
        recentRowBox.removeAllViews();
        String saved = getSharedPreferences("aman_print_prefs", MODE_PRIVATE).getString("recents", "");
        if (saved.isEmpty()) { recentRowBox.setVisibility(View.GONE); return; }
        recentRowBox.setVisibility(View.VISIBLE);
        int idx = 1;
        for (String uStr : saved.split("\\|")) {
            if (uStr.isEmpty()) continue;
            final Uri u = Uri.parse(uStr);
            Button rb = cleanBtn("Recent #" + idx++, "#F1F5F9", "#334155", v -> { pickTarget = 0; loadUri(u); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1f);
            lp.setMargins(0, 0, dp(6), 0); rb.setLayoutParams(lp);
            recentRowBox.addView(rb);
        }
    }

    private void loadUri(Uri uri) {
        try {
            idToolsRow1.setVisibility(View.GONE); idToolsRow2.setVisibility(View.GONE);
            String mime = getContentResolver().getType(uri);
            if (mime != null && mime.contains("pdf")) {
                closePdf();
                pdfFd = getContentResolver().openFileDescriptor(uri, "r");
                if (pdfFd == null) return;
                pdfRen = new PdfRenderer(pdfFd);
                totalPages = pdfRen.getPageCount();
                for (int i = 0; i < totalPages; i++) selectedPages.add(i);
                allPagesBtn.setVisibility(View.VISIBLE);
                allPagesBtn.setText("Pages (" + totalPages + ")");
                showPdfPage(0);
                buildPdfThumbnails();
                updateLoadedUiState("PDF Page 1/" + totalPages + " — Tap preview to Zoom");
            } else {
                Bitmap b; try (InputStream is = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(is); }
                if (b == null) return;
                if (pickTarget == 3) { pickTarget = 0; openPassportCustomDialog(b); return; }
                closePdf(); totalPages = 1; rawBmp = b; resetEditsOnly(); applyEditsToPreview();
                updateLoadedUiState("Photo Loaded — Tap preview to Zoom");
            }
        } catch (Exception e) { Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private void buildPdfThumbnails() {
        pdfThumbs.clear();
        if (pdfRen == null) return;
        for (int i = 0; i < totalPages; i++) {
            PdfRenderer.Page p = pdfRen.openPage(i);
            Bitmap th = Bitmap.createBitmap(150, 210, Bitmap.Config.ARGB_8888);
            th.eraseColor(Color.WHITE);
            p.render(th, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            p.close();
            pdfThumbs.add(th);
        }
        refreshThumbStrip();
    }

    private void refreshThumbStrip() {
        thumbStripBox.removeAllViews();
        for (int i = 0; i < pdfThumbs.size(); i++) {
            final int idx = i;
            boolean sel = selectedPages.contains(idx);
            boolean active = (idx == curPage);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
            item.setPadding(dp(5), dp(5), dp(5), dp(5));
            item.setBackground(bg(active ? Color.parseColor("#E0F2FE") : Color.parseColor("#F8FAFC"),
                    active ? Color.parseColor("#0F5A9C") : Color.parseColor("#CBD5E1"), dp(active ? 2 : 1), 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(6), 0); item.setLayoutParams(lp);

            ImageView iv = new ImageView(this);
            iv.setImageBitmap(pdfThumbs.get(idx));
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(60), dp(82)));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(iv);

            TextView lbl = new TextView(this);
            lbl.setText((sel ? "✓ P" : "P") + (idx + 1));
            lbl.setTextSize(11); lbl.setTypeface(null, Typeface.BOLD);
            lbl.setTextColor(sel ? Color.parseColor("#15803D") : Color.parseColor("#94A3B8"));
            item.addView(lbl);

            item.setOnClickListener(v -> showPdfPage(idx));
            item.setOnLongClickListener(v -> {
                if (selectedPages.contains(idx)) selectedPages.remove(idx); else selectedPages.add(idx);
                syncRangeTextFromSelection(); refreshThumbStrip(); updateEstBadge(); return true;
            });
            thumbStripBox.addView(item);
        }
    }

    private void syncRangeTextFromSelection() {
        if (selectedPages.size() == totalPages) { pageRangeEdit.setText(""); return; }
        List<String> nums = new ArrayList<>();
        for (int p : selectedPages) nums.add(String.valueOf(p + 1));
        pageRangeEdit.setText(TextUtils.join(",", nums));
    }

    // Full-Screen HD Pinch-to-Zoom Inspector for checking every page in detail
    private void openZoomInspector(int startIdx) {
        if (rawBmp == null && pdfRen == null) return;
        final int[] viewIdx = {Math.max(0, startIdx)};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#0F172A"));
        box.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView head = new TextView(this);
        head.setTextColor(Color.WHITE); head.setTextSize(14); head.setTypeface(null, Typeface.BOLD);
        head.setGravity(Gravity.CENTER); head.setPadding(0, dp(4), 0, dp(8));
        box.addView(head);

        ZoomImageView zoomView = new ZoomImageView(this);
        zoomView.setBackgroundColor(Color.parseColor("#1E293B"));
        zoomView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        box.addView(zoomView);

        CheckBox includeCb = new CheckBox(this);
        includeCb.setText("Include this page in Print");
        includeCb.setTextColor(Color.WHITE);
        includeCb.setButtonTintList(ColorStateList.valueOf(Color.parseColor("#38BDF8")));
        if (pdfRen != null) box.addView(includeCb);

        Runnable loadInspectPage = () -> {
            if (pdfRen != null) {
                showPdfPage(viewIdx[0]);
                head.setText("Page " + (viewIdx[0] + 1) + " of " + totalPages + " — Pinch or use +/- to Zoom");
                includeCb.setOnCheckedChangeListener(null);
                includeCb.setChecked(selectedPages.contains(viewIdx[0]));
                includeCb.setOnCheckedChangeListener((b, chk) -> {
                    if (chk) selectedPages.add(viewIdx[0]); else selectedPages.remove(viewIdx[0]);
                    syncRangeTextFromSelection(); refreshThumbStrip(); updateEstBadge();
                });
            } else {
                head.setText("HD Zoom Inspector — Pinch or use +/- to Zoom");
            }
            zoomView.setImageBitmap(applyAllEdits(rawBmp));
        };
        loadInspectPage.run();

        LinearLayout zoomBtns = new LinearLayout(this); zoomBtns.setPadding(0, dp(8), 0, 0);
        zoomBtns.addView(cleanBtn("Zoom -", "#334155", "#FFFFFF", v -> zoomView.zoomBy(0.75f, zoomView.getWidth() / 2f, zoomView.getHeight() / 2f)));
        gap(zoomBtns, 6);
        zoomBtns.addView(cleanBtn("Reset 100%", "#334155", "#FFFFFF", v -> zoomView.resetZoom()));
        gap(zoomBtns, 6);
        zoomBtns.addView(cleanBtn("Zoom +", "#0077C8", "#FFFFFF", v -> zoomView.zoomBy(1.4f, zoomView.getWidth() / 2f, zoomView.getHeight() / 2f)));
        box.addView(zoomBtns);

        if (pdfRen != null && totalPages > 1) {
            LinearLayout navBtns = new LinearLayout(this); navBtns.setPadding(0, dp(6), 0, 0);
            navBtns.addView(cleanBtn("◀ Prev Page", "#1E293B", "#E2E8F0", v -> { if (viewIdx[0] > 0) { viewIdx[0]--; loadInspectPage.run(); } }));
            gap(navBtns, 8);
            navBtns.addView(cleanBtn("Next Page ▶", "#1E293B", "#E2E8F0", v -> { if (viewIdx[0] < totalPages - 1) { viewIdx[0]++; loadInspectPage.run(); } }));
            box.addView(navBtns);
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setView(box).setPositiveButton("Close", null).show();
    }

    private void openAllPagesListDialog() {
        if (pdfThumbs.isEmpty()) return;
        ScrollView sv = new ScrollView(this);
        LinearLayout listBox = new LinearLayout(this); listBox.setOrientation(LinearLayout.VERTICAL); listBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        sv.addView(listBox);

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("All PDF Pages (" + totalPages + ") — Tap Zoom to Inspect").setView(sv)
                .setPositiveButton("Done", (d, w) -> { syncRangeTextFromSelection(); refreshThumbStrip(); updateEstBadge(); }).create();

        for (int i = 0; i < pdfThumbs.size(); i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(bg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), dp(1), 10));
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(8)); row.setLayoutParams(rlp);

            ImageView iv = new ImageView(this); iv.setImageBitmap(pdfThumbs.get(idx));
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(76)));
            iv.setOnClickListener(v -> { dlg.dismiss(); openZoomInspector(idx); });
            row.addView(iv); gap(row, 10);

            CheckBox cb = new CheckBox(this);
            cb.setText("Page " + (idx + 1)); cb.setChecked(selectedPages.contains(idx));
            cb.setTextColor(Color.parseColor("#0F172A")); cb.setTypeface(null, Typeface.BOLD);
            cb.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cb.setOnCheckedChangeListener((b, chk) -> { if (chk) selectedPages.add(idx); else selectedPages.remove(idx); });
            row.addView(cb);

            Button zoomBtn = cleanBtn("Zoom", "#E0F2FE", "#0F5A9C", v -> { dlg.dismiss(); openZoomInspector(idx); });
            zoomBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(34)));
            row.addView(zoomBtn);
            listBox.addView(row);
        }
        dlg.show();
    }

    private void showPdfPage(int idx) {
        if (pdfRen == null || idx < 0 || idx >= totalPages) return;
        curPage = idx;
        PdfRenderer.Page p = pdfRen.openPage(idx);
        Bitmap b = Bitmap.createBitmap((int)(p.getWidth() * 2.5f), (int)(p.getHeight() * 2.5f), Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); p.close();
        rawBmp = b; applyEditsToPreview();
        pageTxt.setText("PDF Page " + (idx + 1) + "/" + totalPages + " — Tap preview to Zoom");
        refreshThumbStrip();
    }

    // Smart Auto-Extract ID Card
    private void idDialog() {
        idFront = null; idBack = null; pickTarget = 1;
        picker.launch(new String[]{"application/pdf", "image/*"});
    }

    private void autoExtractIdFromUri(Uri uri) {
        pickTarget = 0;
        try {
            String mime = getContentResolver().getType(uri);
            if (mime != null && mime.contains("pdf")) {
                ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
                if (fd == null) return;
                PdfRenderer ren = new PdfRenderer(fd);
                int count = ren.getPageCount();
                if (count >= 2) {
                    idFront = autoTrimWhitespace(renderSinglePdfPage(ren, 0));
                    idBack = autoTrimWhitespace(renderSinglePdfPage(ren, 1));
                    lastIdSourceBmp = idFront;
                    ren.close(); fd.close();
                    buildIdSheet();
                } else {
                    Bitmap p0 = renderSinglePdfPage(ren, 0);
                    ren.close(); fd.close();
                    smartSplitSingleSource(p0);
                }
            } else {
                Bitmap b; try (InputStream is = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(is); }
                if (b != null) smartSplitSingleSource(b);
            }
        } catch (Exception e) { Toast.makeText(this, "ID Error: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private Bitmap renderSinglePdfPage(PdfRenderer ren, int idx) {
        PdfRenderer.Page p = ren.openPage(idx);
        Bitmap b = Bitmap.createBitmap((int)(p.getWidth() * 2.5f), (int)(p.getHeight() * 2.5f), Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); p.close();
        return b;
    }

    private void smartSplitSingleSource(Bitmap src) {
        lastIdSourceBmp = src;
        Bitmap trimmed = autoTrimWhitespace(src);
        int w = trimmed.getWidth(), h = trimmed.getHeight();
        if (w > h * 1.2f) {
            idFront = autoTrimWhitespace(Bitmap.createBitmap(trimmed, 0, 0, w / 2, h));
            idBack = autoTrimWhitespace(Bitmap.createBitmap(trimmed, w / 2, 0, w - (w / 2), h));
        } else {
            int sw = src.getWidth(), sh = src.getHeight();
            int topY = (int)(sh * 0.67f), boxH = (int)(sh * 0.29f);
            idFront = autoTrimWhitespace(Bitmap.createBitmap(src, (int)(sw * 0.04f), topY, (int)(sw * 0.45f), boxH));
            idBack = autoTrimWhitespace(Bitmap.createBitmap(src, (int)(sw * 0.51f), topY, (int)(sw * 0.45f), boxH));
        }
        buildIdSheet();
    }

    private void applyIdSplitPreset(int mode) {
        if (lastIdSourceBmp == null) return;
        if (mode == 0) {
            Bitmap trimmed = autoTrimWhitespace(lastIdSourceBmp);
            int w = trimmed.getWidth(), h = trimmed.getHeight();
            idFront = autoTrimWhitespace(Bitmap.createBitmap(trimmed, 0, 0, w / 2, h));
            idBack = autoTrimWhitespace(Bitmap.createBitmap(trimmed, w / 2, 0, w - (w / 2), h));
        } else {
            int sw = lastIdSourceBmp.getWidth(), sh = lastIdSourceBmp.getHeight();
            int topY = (int)(sh * 0.67f), boxH = (int)(sh * 0.29f);
            idFront = autoTrimWhitespace(Bitmap.createBitmap(lastIdSourceBmp, (int)(sw * 0.04f), topY, (int)(sw * 0.45f), boxH));
            idBack = autoTrimWhitespace(Bitmap.createBitmap(lastIdSourceBmp, (int)(sw * 0.51f), topY, (int)(sw * 0.45f), boxH));
        }
        buildIdSheet();
    }

    private void openIdAdjustMenu() {
        String[] opts = {"Add 2nd PDF / Photo as Back ID", "Top / Bottom Half Split", "Manual Crop Front ID", "Manual Crop Back ID"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Adjust ID Card")
                .setItems(opts, (d, w) -> {
                    if (w == 0) { pickTarget = 2; picker.launch(new String[]{"application/pdf", "image/*"}); }
                    else if (w == 1 && lastIdSourceBmp != null) {
                        Bitmap tr = autoTrimWhitespace(lastIdSourceBmp);
                        idFront = autoTrimWhitespace(Bitmap.createBitmap(tr, 0, 0, tr.getWidth(), tr.getHeight() / 2));
                        idBack = autoTrimWhitespace(Bitmap.createBitmap(tr, 0, tr.getHeight() / 2, tr.getWidth(), tr.getHeight() - tr.getHeight() / 2));
                        buildIdSheet();
                    } else if (w == 2) openManualCropDialog(true);
                    else if (w == 3) openManualCropDialog(false);
                }).show();
    }

    private void openManualCropDialog(boolean isFront) {
        if (lastIdSourceBmp == null) return;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), dp(12), dp(16), dp(8));
        ImageView cropPreview = new ImageView(this);
        cropPreview.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));
        cropPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(cropPreview);

        final int[] vals = {0, 0, 100, 100};
        Runnable updateCrop = () -> {
            int w = lastIdSourceBmp.getWidth(), h = lastIdSourceBmp.getHeight();
            int l = (w * vals[0]) / 100, t = (h * vals[1]) / 100;
            int r = Math.max(l + 20, (w * vals[2]) / 100), b = Math.max(t + 20, (h * vals[3]) / 100);
            cropPreview.setImageBitmap(Bitmap.createBitmap(lastIdSourceBmp, l, t, Math.min(w - l, r - l), Math.min(h - t, b - t)));
        };
        updateCrop.run();

        addEditorSlider(box, "Left Edge %", 90, 0, v -> { vals[0] = v; updateCrop.run(); });
        addEditorSlider(box, "Top Edge %", 90, 0, v -> { vals[1] = v; updateCrop.run(); });
        addEditorSlider(box, "Right Edge %", 100, 100, v -> { vals[2] = v; updateCrop.run(); });
        addEditorSlider(box, "Bottom Edge %", 100, 100, v -> { vals[3] = v; updateCrop.run(); });

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(isFront ? "Manual Crop — Front ID" : "Manual Crop — Back ID").setView(box)
                .setPositiveButton("Apply", (d, w) -> {
                    int sw = lastIdSourceBmp.getWidth(), sh = lastIdSourceBmp.getHeight();
                    int l = (sw * vals[0]) / 100, t = (sh * vals[1]) / 100;
                    int r = Math.max(l + 20, (sw * vals[2]) / 100), b = Math.max(t + 20, (sh * vals[3]) / 100);
                    Bitmap res = Bitmap.createBitmap(lastIdSourceBmp, l, t, Math.min(sw - l, r - l), Math.min(sh - t, b - t));
                    if (isFront) idFront = res; else idBack = res;
                    buildIdSheet();
                }).setNegativeButton("Cancel", null).show();
    }

    private void loadSecondIdBackUri(Uri uri) {
        pickTarget = 0;
        try {
            String mime = getContentResolver().getType(uri);
            Bitmap b;
            if (mime != null && mime.contains("pdf")) {
                ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
                if (fd == null) return;
                PdfRenderer ren = new PdfRenderer(fd);
                b = renderSinglePdfPage(ren, 0);
                ren.close(); fd.close();
            } else {
                try (InputStream is = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(is); }
            }
            if (b != null) {
                if (idFront == null && lastIdSourceBmp != null) idFront = autoTrimWhitespace(lastIdSourceBmp);
                idBack = autoTrimWhitespace(b);
                buildIdSheet();
            }
        } catch (Exception e) { Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private Bitmap autoTrimWhitespace(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int minX = w, minY = h, maxX = 0, maxY = 0;
        int[] row = new int[w];
        for (int y = 0; y < h; y += 3) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            int darkCount = 0, rowMinX = w, rowMaxX = 0;
            for (int x = 0; x < w; x += 3) {
                int px = row[x];
                if (((px >> 16) & 0xFF) < 235 || ((px >> 8) & 0xFF) < 235 || (px & 0xFF) < 235) {
                    darkCount++;
                    if (x < rowMinX) rowMinX = x;
                    if (x > rowMaxX) rowMaxX = x;
                }
            }
            if (darkCount >= 6) {
                if (rowMinX < minX) minX = rowMinX;
                if (rowMaxX > maxX) maxX = rowMaxX;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX <= minX || maxY <= minY) return src;
        minX = Math.max(0, minX - 12); minY = Math.max(0, minY - 12);
        maxX = Math.min(w - 1, maxX + 12); maxY = Math.min(h - 1, maxY + 12);
        return Bitmap.createBitmap(src, minX, minY, maxX - minX, maxY - minY);
    }

    private void buildIdSheet() {
        Bitmap sh = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sh); c.drawColor(Color.WHITE);
        Paint b = new Paint(Paint.ANTI_ALIAS_FLAG); b.setStyle(Paint.Style.STROKE); b.setColor(Color.parseColor("#94A3B8")); b.setStrokeWidth(3);
        Paint txtP = new Paint(Paint.ANTI_ALIAS_FLAG); txtP.setColor(Color.parseColor("#64748B")); txtP.setTextSize(32); txtP.setTextAlign(Paint.Align.CENTER);
        Rect r1 = new Rect(180, 160, 1060, 720), r2 = new Rect(180, 820, 1060, 1380);
        c.drawRect(r1, b); c.drawRect(r2, b);
        if (idFront != null) drawFit(c, idFront, r1, 0); else c.drawText("Front ID", 620, 450, txtP);
        if (idBack != null) drawFit(c, idBack, r2, 0); else c.drawText("Back ID", 620, 1110, txtP);
        closePdf(); totalPages = 1; rawBmp = sh; resetEditsOnly(); applyEditsToPreview();
        idToolsRow1.setVisibility(View.VISIBLE); idToolsRow2.setVisibility(View.VISIBLE);
        updateLoadedUiState("2-in-1 ID Card Ready — Tap preview to Zoom");
    }

    // Flexible Passport Photo Sheet: 1, 2, 3, 4 ... up to 30 photos on A4 or 4x6!
    private void startPassportSheet() { pickTarget = 3; picker.launch(new String[]{"image/*"}); }

    private void openPassportCustomDialog(Bitmap photo) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(14), dp(18), dp(10));
        box.addView(lbl("1. Paper Size:"));
        Spinner ppPaperSp = spin(new String[]{"A4 Sheet (210 x 297 mm)", "4 x 6 inch Photo Paper"});
        box.addView(ppPaperSp);

        box.addView(lbl("2. Photo Size:"));
        Spinner ppTypeSp = spin(new String[]{"Indian Passport (3.5 x 4.5 cm)", "Stamp Size (2.5 x 3.0 cm)"});
        box.addView(ppTypeSp);

        box.addView(lbl("3. Kitni Photos Print Karni Hain? (1 to 30):"));
        final int[] count = {1};
        LinearLayout cntRow = new LinearLayout(this); cntRow.setGravity(Gravity.CENTER_VERTICAL); cntRow.setPadding(0, dp(4), 0, dp(8));
        TextView cntTxt = new TextView(this); cntTxt.setText("1 Photo"); cntTxt.setTextSize(16); cntTxt.setTypeface(null, Typeface.BOLD);
        cntTxt.setTextColor(Color.parseColor("#0F172A")); cntTxt.setGravity(Gravity.CENTER);
        cntTxt.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));

        Button mBtn = cleanBtn("- 1", "#E2E8F0", "#0F172A", v -> { if (count[0] > 1) { count[0]--; cntTxt.setText(count[0] + " Photo" + (count[0] > 1 ? "s" : "")); } });
        mBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(40)));
        Button pBtn = cleanBtn("+ 1", "#0F5A9C", "#FFFFFF", v -> { if (count[0] < 30) { count[0]++; cntTxt.setText(count[0] + " Photos"); } });
        pBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(40)));
        cntRow.addView(mBtn); cntRow.addView(cntTxt); cntRow.addView(pBtn);
        box.addView(cntRow);

        // Quick count chips: 1, 2, 3, 4, 6, 8, 12, 24
        LinearLayout qRow1 = new LinearLayout(this);
        int[] q1 = {1, 2, 3, 4};
        for (int n : q1) {
            Button qb = cleanBtn(String.valueOf(n), "#EFF6FF", "#1D4ED8", v -> { count[0] = n; cntTxt.setText(n + " Photo" + (n > 1 ? "s" : "")); });
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, dp(34), 1f); qlp.setMargins(dp(2), 0, dp(2), 0); qb.setLayoutParams(qlp);
            qRow1.addView(qb);
        }
        box.addView(qRow1);

        LinearLayout qRow2 = new LinearLayout(this); qRow2.setPadding(0, dp(6), 0, 0);
        int[] q2 = {6, 8, 12, 24};
        for (int n : q2) {
            Button qb = cleanBtn(String.valueOf(n), "#EFF6FF", "#1D4ED8", v -> { count[0] = n; cntTxt.setText(n + " Photos"); });
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, dp(34), 1f); qlp.setMargins(dp(2), 0, dp(2), 0); qb.setLayoutParams(qlp);
            qRow2.addView(qb);
        }
        box.addView(qRow2);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Custom Passport Photo Sheet").setView(box)
                .setPositiveButton("Generate Sheet", (d, w) -> {
                    boolean is4x6 = (ppPaperSp.getSelectedItemPosition() == 1);
                    boolean isStamp = (ppTypeSp.getSelectedItemPosition() == 1);
                    buildCustomPassportSheet(photo, is4x6, isStamp, count[0]);
                }).setNegativeButton("Cancel", null).show();
    }

    private void buildCustomPassportSheet(Bitmap photo, boolean is4x6, boolean isStamp, int photoCount) {
        // 300 DPI exact physical sizing so 1, 2, 3... photos stay exact real-world passport size on top of A4/4x6!
        int sw = is4x6 ? 1200 : 2480;
        int sh = is4x6 ? 1800 : 3508;
        int pw = isStamp ? 295 : 413; // 2.5cm or 3.5cm @ 300 DPI
        int ph = isStamp ? 354 : 531; // 3.0cm or 4.5cm @ 300 DPI
        int margin = 90, gapPx = 42;

        int maxCols = Math.max(1, (sw - margin * 2 + gapPx) / (pw + gapPx));
        int maxRows = Math.max(1, (sh - margin * 2 + gapPx) / (ph + gapPx));
        int actualCount = Math.min(photoCount, maxCols * maxRows);

        Bitmap sheet = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet); c.drawColor(Color.WHITE);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE); border.setColor(Color.parseColor("#CBD5E1")); border.setStrokeWidth(3);

        for (int i = 0; i < actualCount; i++) {
            int col = i % maxCols;
            int row = i / maxCols;
            int l = margin + col * (pw + gapPx);
            int t = margin + row * (ph + gapPx);
            Rect box = new Rect(l, t, l + pw, t + ph);
            drawCenterCrop(c, photo, box);
            c.drawRect(box, border);
        }

        if (is4x6) sizeSp.setSelection(3); else sizeSp.setSelection(0);
        qualSp.setSelection(2);
        closePdf(); totalPages = 1; rawBmp = sheet; resetEditsOnly(); applyEditsToPreview();
        updateLoadedUiState((is4x6 ? "4x6" : "A4") + " Sheet (" + actualCount + " Passport Photo" + (actualCount > 1 ? "s" : "") + ")");
    }

    private void drawCenterCrop(Canvas c, Bitmap src, Rect dst) {
        float srcRatio = (float) src.getWidth() / src.getHeight();
        float dstRatio = (float) dst.width() / dst.height();
        Rect srcRect;
        if (srcRatio > dstRatio) {
            int cropW = Math.round(src.getHeight() * dstRatio);
            int xOff = (src.getWidth() - cropW) / 2;
            srcRect = new Rect(xOff, 0, xOff + cropW, src.getHeight());
        } else {
            int cropH = Math.round(src.getWidth() / dstRatio);
            int yOff = (src.getHeight() - cropH) / 2;
            srcRect = new Rect(0, yOff, src.getWidth(), yOff + cropH);
        }
        c.drawBitmap(src, srcRect, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
    }

    private void saveCurrentAsJpg() {
        Bitmap bmp = applyAllEdits(rawBmp);
        if (bmp == null) { Toast.makeText(this, "Pehle file chunein ya scan karein!", Toast.LENGTH_SHORT).show(); return; }
        try {
            String name = "AmanPrint_" + System.currentTimeMillis() + ".jpg";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name); cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AmanPrint");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("Storage error");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) { bmp.compress(Bitmap.CompressFormat.JPEG, 95, os); }
            Toast.makeText(this, "Saved in Gallery: Pictures/AmanPrint/" + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private void saveCurrentAsPdf() {
        Bitmap bmp = applyAllEdits(rawBmp);
        if (bmp == null) { Toast.makeText(this, "Pehle file chunein ya scan karein!", Toast.LENGTH_SHORT).show(); return; }
        PdfDocument doc = new PdfDocument();
        try {
            PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
            drawFit(pg.getCanvas(), bmp, new Rect(16, 16, 579, 826), 0);
            doc.finishPage(pg);
            String name = "AmanPrint_" + System.currentTimeMillis() + ".pdf";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name); cv.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("Downloads error");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { doc.writeTo(os); }
            } else {
                File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
                try (FileOutputStream fos = new FileOutputStream(f)) { doc.writeTo(fos); }
            }
            Toast.makeText(this, "Saved PDF in Downloads: " + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "PDF failed: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
        finally { doc.close(); }
    }

    // Printer Scanner (eSCL over HTTPS 443 + HTTP 8080/80 + Wi-Fi Direct binding + WebScan SSL Fix)
    private void openPrinterScannerDialog() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(14), dp(18), dp(10));
        TextView hint = new TextView(this); hint.setText("HP Smart Tank 589 glass par document rakhein aur Scan Now dabayein:");
        hint.setTextColor(Color.parseColor("#0F172A")); hint.setTextSize(13); box.addView(hint);

        EditText ipEd = new EditText(this); ipEd.setText(printerIp); ipEd.setTextColor(Color.parseColor("#0F172A"));
        box.addView(ipEd);

        Spinner scanColorSp = spin(new String[]{"Color Scan (RGB)", "Black & White Scan"});
        box.addView(scanColorSp);

        Spinner scanDpiSp = spin(new String[]{"300 DPI (Clear Document)", "150 DPI (Fast Scan)"});
        box.addView(scanDpiSp);

        TextView st = new TextView(this); st.setText("Ready — Wi-Fi bound to " + printerIp);
        st.setTextColor(Color.parseColor("#0E7490")); st.setPadding(0, dp(8), 0, dp(4));
        box.addView(st);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(100);
        box.addView(pb);

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Scan from HP Smart Tank 589").setView(box)
                .setPositiveButton("Scan Now", null)
                .setNeutralButton("WebScan", (d, w) -> openWebDialog("https://" + ipEd.getText().toString().trim() + "/#hId-pgWebScan"))
                .setNegativeButton("Close", null).create();
        dlg.show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            printerIp = ipEd.getText().toString().trim();
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            boolean isColor = scanColorSp.getSelectedItemPosition() == 0;
            int dpi = scanDpiSp.getSelectedItemPosition() == 0 ? 300 : 150;
            runEsclScan(printerIp, isColor, dpi, st, pb, dlg);
        });
    }

    private void runEsclScan(String ip, boolean isColor, int dpi, TextView st, ProgressBar pb, AlertDialog dlg) {
        new Thread(() -> {
            try {
                uiHandler.post(() -> { st.setText("Connecting via Wi-Fi / HTTPS to HP 589..."); pb.setProgress(20); });
                String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<scan:ScanSettings xmlns:scan=\"http://schemas.hp.com/imaging/escl/2011/05/03\" xmlns:pwg=\"http://www.pwg.org/schemas/2012/12/pwg\">"
                        + "<pwg:Version>2.6</pwg:Version>"
                        + "<pwg:ScanRegions><pwg:ScanRegion>"
                        + "<pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>"
                        + "<pwg:Height>3508</pwg:Height><pwg:Width>2480</pwg:Width>"
                        + "<pwg:XOffset>0</pwg:XOffset><pwg:YOffset>0</pwg:YOffset>"
                        + "</pwg:ScanRegion></pwg:ScanRegions>"
                        + "<scan:InputSource>Platen</scan:InputSource>"
                        + "<scan:ColorMode>" + (isColor ? "RGB24" : "Grayscale8") + "</scan:ColorMode>"
                        + "<scan:XResolution>" + dpi + "</scan:XResolution>"
                        + "<scan:YResolution>" + dpi + "</scan:YResolution>"
                        + "<pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>"
                        + "</scan:ScanSettings>";

                String[] endpoints = {
                        "https://" + ip + "/eSCL/ScanJobs",
                        "http://" + ip + ":8080/eSCL/ScanJobs",
                        "http://" + ip + "/eSCL/ScanJobs"
                };
                String loc = null;
                String usedBase = "https://" + ip;
                StringBuilder errs = new StringBuilder();
                for (String ep : endpoints) {
                    String[] res = postEsclWithReason(ep, xml);
                    if (res[0] != null) {
                        loc = res[0];
                        usedBase = ep.substring(0, ep.indexOf("/eSCL"));
                        break;
                    } else {
                        errs.append(res[1]).append(" | ");
                    }
                }
                if (loc == null) {
                    throw new Exception("Scanner blocked/offline (" + errs.toString().trim() + "). Tap 'WebScan' to enable/scan.");
                }
                if (loc.startsWith("/")) loc = usedBase + loc;
                String docUrl = loc.endsWith("/") ? (loc + "NextDocument") : (loc + "/NextDocument");

                uiHandler.post(() -> { st.setText("Printer scanning glass... Wait 5-10 sec"); pb.setProgress(55); });
                byte[] bytes = null;
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(1500);
                    HttpURLConnection c = openPrinterHttp(docUrl, "GET", 6000, 25000);
                    int code = c.getResponseCode();
                    if (code == 301 || code == 302) {
                        String redir = c.getHeaderField("Location");
                        if (redir != null) { docUrl = redir; continue; }
                    }
                    if (code == 200) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (InputStream is = c.getInputStream()) {
                            byte[] buf = new byte[8192]; int r;
                            while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
                        }
                        bytes = baos.toByteArray();
                        break;
                    }
                }
                if (bytes == null || bytes.length == 0) throw new Exception("Scan timed out! Check printer screen.");
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) throw new Exception("Invalid scan image returned.");

                uiHandler.post(() -> {
                    pb.setProgress(100); closePdf(); totalPages = 1; rawBmp = bmp; resetEditsOnly(); applyEditsToPreview();
                    updateLoadedUiState("Scanned from HP 589 — Save JPG/PDF or Print");
                    dlg.dismiss();
                    Toast.makeText(this, "Scan complete! Saved to preview.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                uiHandler.post(() -> { st.setText(e.getMessage()); dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); });
            }
        }).start();
    }

    private String[] postEsclWithReason(String url, String xml) {
        try {
            HttpURLConnection c = openPrinterHttp(url, "POST", 5000, 12000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml");
            try (OutputStream os = c.getOutputStream()) { os.write(xml.getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            if (code == 201) return new String[]{c.getHeaderField("Location"), "OK"};
            return new String[]{null, "HTTP " + code};
        } catch (Exception e) {
            return new String[]{null, e.getClass().getSimpleName()};
        }
    }

    private List<Integer> parsePageIndices(int total) {
        String txt = pageRangeEdit.getText().toString().trim().toLowerCase();
        if (txt.isEmpty() || txt.equals("all")) {
            if (!selectedPages.isEmpty() && pdfRen != null) return new ArrayList<>(selectedPages);
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < total; i++) all.add(i);
            return all;
        }
        Set<Integer> set = new LinkedHashSet<>();
        for (String part : txt.split(",")) {
            part = part.trim();
            try {
                if (part.contains("-")) {
                    String[] ab = part.split("-");
                    if (ab.length == 2) {
                        int s = Math.max(1, Integer.parseInt(ab[0].trim()));
                        int e = Math.min(total, Integer.parseInt(ab[1].trim()));
                        for (int p = s; p <= e; p++) set.add(p - 1);
                    }
                } else if (!part.isEmpty()) {
                    int p = Integer.parseInt(part);
                    if (p >= 1 && p <= total) set.add(p - 1);
                }
            } catch (NumberFormatException ignored) {}
        }
        return new ArrayList<>(set);
    }

    private void openInAppPrintSheet() {
        if (rawBmp == null && pdfRen == null) { Toast.makeText(this, "Pehle file chunein ya scan karein!", Toast.LENGTH_SHORT).show(); return; }
        int dupMode = duplexSp.getSelectedItemPosition();
        // 0=Single, 1=Front All Odd, 2=Back All Even Reverse, 3=Back All Even Normal, 4=Auto Manual Duplex
        launchDirectPrintDialog(dupMode, false);
    }

    private void launchDirectPrintDialog(int dupMode, boolean isAutoStep2) {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(18), dp(14), dp(18), dp(10));
        String title = isAutoStep2 ? "Manual Duplex — Back Pages (Even Reverse)"
                : dupMode == 1 ? "Front Pages Only (All Odd: 1, 3, 5...)"
                : dupMode == 2 ? "Back Pages Only (All Even Reverse)"
                : dupMode == 3 ? "Back Pages Only (All Even Normal)"
                : dupMode == 4 ? "Auto Manual Duplex — Step 1 (Front Odd)"
                : "Aman Print — Direct Spooler";

        TextView info = new TextView(this);
        info.setText("Printer: " + printerName + " (" + connMode + ") | Copies: " + copyCount);
        info.setTextColor(Color.parseColor("#0F172A")); info.setTextSize(13); info.setTypeface(null, Typeface.BOLD);
        panel.addView(info);

        EditText ipBox = new EditText(this); ipBox.setText(printerIp); ipBox.setTextColor(Color.parseColor("#0F172A"));
        if (!connMode.equals("USB OTG")) panel.addView(ipBox);

        TextView progTxt = new TextView(this); progTxt.setText("Ready to send 300 DPI print job.");
        progTxt.setTextColor(Color.parseColor("#0F5A9C")); progTxt.setPadding(0, dp(8), 0, dp(4));
        panel.addView(progTxt);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(100);
        panel.addView(bar);

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(title).setView(panel)
                .setPositiveButton("Start Print", null)
                .setNegativeButton("Close", null).create();
        dlg.show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!connMode.equals("USB OTG")) printerIp = ipBox.getText().toString().trim();
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            runDirectPrintJob(dupMode, isAutoStep2, progTxt, bar, dlg);
        });
    }

    private int[] getSelectedPaperPoints() {
        int sz = sizeSp.getSelectedItemPosition();
        if (sz == 1) return new int[]{709, 595};
        if (sz == 2) return new int[]{624, 794};
        if (sz == 3) return new int[]{288, 432};
        if (sz == 4) return new int[]{360, 504};
        if (sz == 5) return new int[]{420, 595};
        if (sz == 6) return new int[]{612, 1008};
        return new int[]{595, 842};
    }

    private void runDirectPrintJob(int dupMode, boolean isAutoStep2, TextView progTxt, ProgressBar bar, AlertDialog dlg) {
        final int multi = multiSp.getSelectedItemPosition(), sc = scaleSp.getSelectedItemPosition(), mg = marginSp.getSelectedItemPosition();
        final int q = qualSp.getSelectedItemPosition();
        final boolean isDuplexJob = (dupMode > 0);
        final int copies = isDuplexJob ? 1 : copyCount;
        final boolean rev = revCheck.isChecked(), isColor = (colorMode == 0), isLand = (orientationMode == 1);
        final int[] pts = getSelectedPaperPoints();
        final int ptW = isLand ? pts[1] : pts[0];
        final int ptH = isLand ? pts[0] : pts[1];

        new Thread(() -> {
            try {
                int cnt = (pdfRen != null) ? totalPages : 1;
                List<Integer> rawList = parsePageIndices(cnt);
                List<Integer> list = new ArrayList<>();
                boolean wantOdd = (dupMode == 1) || (dupMode == 4 && !isAutoStep2);
                boolean wantEven = (dupMode == 2) || (dupMode == 3) || (dupMode == 4 && isAutoStep2);
                boolean reverseEven = (dupMode == 2) || (dupMode == 4 && isAutoStep2);

                for (int idx : rawList) {
                    int pn = idx + 1;
                    if (wantOdd) { if (pn % 2 == 1) list.add(idx); }
                    else if (wantEven) { if (pn % 2 == 0) list.add(idx); }
                    else list.add(idx);
                }
                if (rev || reverseEven) Collections.reverse(list);
                if (list.isEmpty()) throw new Exception("Selected range mein koi page nahi hai!");

                uiHandler.post(() -> { progTxt.setText("Rendering 300 DPI pages..."); bar.setProgress(30); });
                List<Bitmap> oneSet = new ArrayList<>();
                int dpi = 300;
                int sw = Math.round(ptW * dpi / 72f);
                int sh = Math.round(ptH * dpi / 72f);
                int lm = (mg == 2) ? 190 : (mg == 1 ? 0 : 48), pd = (mg == 1) ? 0 : 48;

                if (multi == 3) {
                    Bitmap src = pageBmp(list.get(0));
                    int hw = src.getWidth() / 2, hh = src.getHeight() / 2;
                    Rect[] t = {new Rect(0,0,hw,hh), new Rect(hw,0,src.getWidth(),hh), new Rect(0,hh,hw,src.getHeight()), new Rect(hw,hh,src.getWidth(),src.getHeight())};
                    for (int i = 0; i < 4; i++) {
                        Bitmap sb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(sb); c.drawColor(Color.WHITE);
                        c.drawBitmap(src, t[i], new Rect(lm, pd, sw - pd, sh - pd), null); oneSet.add(sb);
                    }
                } else if (multi == 1) {
                    for (int i = 0; i < list.size(); i += 2) {
                        Bitmap sb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(sb); c.drawColor(Color.WHITE);
                        drawFit(c, pageBmp(list.get(i)), new Rect(lm, pd, sw - pd, sh / 2 - 18), sc);
                        if (i + 1 < list.size()) drawFit(c, pageBmp(list.get(i + 1)), new Rect(lm, sh / 2 + 18, sw - pd, sh - pd), sc);
                        oneSet.add(sb);
                    }
                } else if (multi == 2) {
                    for (int i = 0; i < list.size(); i += 4) {
                        Bitmap sb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(sb); c.drawColor(Color.WHITE);
                        int mx = (lm + sw - pd) / 2, my = sh / 2;
                        Rect[] cells = {new Rect(lm, pd, mx - 14, my - 14), new Rect(mx + 14, pd, sw - pd, my - 14), new Rect(lm, my + 14, mx - 14, sh - pd), new Rect(mx + 14, my + 14, sw - pd, sh - pd)};
                        for (int k = 0; k < 4 && (i + k) < list.size(); k++) drawFit(c, pageBmp(list.get(i + k)), cells[k], sc);
                        oneSet.add(sb);
                    }
                } else {
                    for (int idx : list) {
                        Bitmap sb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(sb); c.drawColor(Color.WHITE);
                        drawFit(c, pageBmp(idx), new Rect(lm, pd, sw - pd, sh - pd), sc); oneSet.add(sb);
                    }
                }

                List<Bitmap> finalSheets = new ArrayList<>();
                for (int c = 0; c < copies; c++) finalSheets.addAll(oneSet);

                uiHandler.post(() -> { progTxt.setText("Encoding 300 DPI PWG-Raster..."); bar.setProgress(60); });
                int qualVal = (q == 2 ? 5 : (q == 1 || inkSaverOn ? 3 : 4));
                byte[] pwgData = buildPwgRaster(finalSheets, isColor, dpi, ptW, ptH, qualVal);
                byte[] ippPacket = buildIppPacket(printerIp, pwgData, isColor, qualVal);

                uiHandler.post(() -> { progTxt.setText("Sending to HP Smart Tank 589..."); bar.setProgress(82); });
                if (connMode.equals("USB OTG")) sendUsb(ippPacket);
                else sendIpp(printerIp, ippPacket);

                uiHandler.post(() -> {
                    bar.setProgress(100);
                    dlg.dismiss();
                    if (dupMode == 4 && !isAutoStep2) {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                                .setTitle("कागज़ पलटकर डालें")
                                .setMessage("Front (Odd: 1, 3, 5...) पेज प्रिंट हो गए हैं। अब प्रिंटेड कागज़ पलटकर ट्रे में डालें, फिर Continue दबाएँ।")
                                .setPositiveButton("Continue (Back Even)", (d2, w2) -> launchDirectPrintDialog(4, true))
                                .setNegativeButton("Done", null).show();
                    } else if (dupMode == 1) {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                                .setTitle("Front Pages Printed!")
                                .setMessage("सभी Front (Odd) पेज प्रिंट हो गए हैं। क्या अब कागज़ पलटकर Back (Even Reverse) पेज प्रिंट करने हैं?")
                                .setPositiveButton("Print Back Pages Now", (d2, w2) -> { duplexSp.setSelection(2); launchDirectPrintDialog(2, false); })
                                .setNegativeButton("Done & Clear", (d2, w2) -> clearWorkspaceForNewPrint()).show();
                    } else {
                        // Automatically show clean finished dialog with 1-tap New Print (Clear) so user never has to restart app!
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                                .setTitle("Print Finished!")
                                .setMessage(finalSheets.size() + " sheet(s) sent to HP Smart Tank 589.\n\nनया प्रिंट करने के लिए 'New Print (Clear)' दबाएँ।")
                                .setPositiveButton("New Print (Clear)", (d2, w2) -> {
                                    clearWorkspaceForNewPrint();
                                    Toast.makeText(this, "Screen cleared! Ready for new print.", Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton("Keep File", null).show();
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> { progTxt.setText("Error: " + e.getMessage()); dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); });
            }
        }).start();
    }

    private Bitmap pageBmp(int idx) {
        Bitmap base = rawBmp;
        if (pdfRen != null) {
            PdfRenderer.Page p = pdfRen.openPage(idx);
            base = Bitmap.createBitmap(p.getWidth() * 3, p.getHeight() * 3, Bitmap.Config.ARGB_8888);
            base.eraseColor(Color.WHITE); p.render(base, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); p.close();
        }
        return applyAllEdits(base);
    }

    private void drawFit(Canvas c, Bitmap b, Rect box, int sc) {
        if (b == null) return;
        if (sc == 1) { c.drawBitmap(b, null, box, null); return; }
        float s = (sc == 2) ? 1f : Math.min((float) box.width() / b.getWidth(), (float) box.height() / b.getHeight());
        int dw = Math.min(box.width(), (int)(b.getWidth() * s)), dh = Math.min(box.height(), (int)(b.getHeight() * s));
        int l = box.left + (box.width() - dw) / 2, t = box.top + (box.height() - dh) / 2;
        c.drawBitmap(b, null, new Rect(l, t, l + dw, t + dh), null);
    }

    private void writePwgPixel(OutputStream out, int px, boolean isColor) throws IOException {
        if (isColor) {
            out.write((px >> 16) & 0xFF);
            out.write((px >> 8) & 0xFF);
            out.write(px & 0xFF);
        } else {
            out.write((((px >> 16) & 0xFF) * 77 + ((px >> 8) & 0xFF) * 150 + (px & 0xFF) * 29) >> 8);
        }
    }

    private byte[] buildPwgRaster(List<Bitmap> pages, boolean isColor, int dpi, int ptW, int ptH, int qual) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'R', 'a', 'S', '2'});
        for (Bitmap bmp : pages) {
            int w = bmp.getWidth(), h = bmp.getHeight();
            ByteBuffer hdr = ByteBuffer.allocate(1796).order(ByteOrder.BIG_ENDIAN);
            hdr.put("PwgRaster".getBytes(StandardCharsets.US_ASCII));
            hdr.putInt(276, dpi); hdr.putInt(280, dpi);
            hdr.putInt(292, ptW); hdr.putInt(296, ptH);
            hdr.putInt(340, 1);
            hdr.putInt(352, ptW); hdr.putInt(356, ptH);
            hdr.putInt(372, w); hdr.putInt(376, h);
            hdr.putInt(384, 8);
            hdr.putInt(388, isColor ? 24 : 8);
            hdr.putInt(392, isColor ? (w * 3) : w);
            hdr.putInt(396, 0);
            hdr.putInt(400, isColor ? 19 : 18);
            hdr.putInt(420, isColor ? 3 : 1);
            hdr.putInt(452, pages.size());
            hdr.putInt(456, 1); hdr.putInt(460, 1);
            hdr.putInt(472, w); hdr.putInt(476, h);
            hdr.putInt(480, 0x00FFFFFF); hdr.putInt(484, qual);
            out.write(hdr.array());

            int[] row = new int[w];
            int[] nextRow = new int[w];
            int y = 0;
            while (y < h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1);
                int repLines = 1;
                while (y + repLines < h && repLines < 256) {
                    bmp.getPixels(nextRow, 0, w, 0, y + repLines, w, 1);
                    if (!Arrays.equals(row, nextRow)) break;
                    repLines++;
                }
                out.write((byte) (repLines - 1));
                int x = 0;
                while (x < w) {
                    int p0 = row[x];
                    int run = 1;
                    while (x + run < w && run < 128 && row[x + run] == p0) run++;
                    if (run >= 2) {
                        out.write((byte) (run - 1));
                        writePwgPixel(out, p0, isColor);
                        x += run;
                    } else {
                        int litStart = x, litLen = 1;
                        x++;
                        while (x < w && litLen < 128) {
                            if (x + 1 < w && row[x] == row[x + 1]) break;
                            litLen++; x++;
                        }
                        if (litLen == 1) {
                            out.write(0x00);
                            writePwgPixel(out, row[litStart], isColor);
                        } else {
                            out.write((byte) (257 - litLen));
                            for (int k = 0; k < litLen; k++) writePwgPixel(out, row[litStart + k], isColor);
                        }
                    }
                }
                y += repLines;
            }
        }
        return out.toByteArray();
    }

    private byte[] buildIppPacket(String ip, byte[] pwg, boolean isColor, int qual) throws IOException {
        ByteArrayOutputStream ipp = new ByteArrayOutputStream();
        ipp.write(new byte[]{0x01, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x01, 0x01});
        ippAttr(ipp, 0x47, "attributes-charset", "utf-8".getBytes(StandardCharsets.UTF_8));
        ippAttr(ipp, 0x48, "attributes-natural-language", "en".getBytes(StandardCharsets.US_ASCII));
        ippAttr(ipp, 0x45, "printer-uri", ("ipp://" + ip + ":631/ipp/print").getBytes(StandardCharsets.UTF_8));
        ippAttr(ipp, 0x42, "requesting-user-name", "AmanPrint".getBytes(StandardCharsets.UTF_8));
        ippAttr(ipp, 0x49, "document-format", "image/pwg-raster".getBytes(StandardCharsets.US_ASCII));
        ipp.write(0x02);
        ippAttr(ipp, 0x44, "print-color-mode", (isColor ? "color" : "monochrome").getBytes(StandardCharsets.US_ASCII));
        ippAttr(ipp, 0x23, "print-quality", ByteBuffer.allocate(4).putInt(qual).array());
        ippAttr(ipp, 0x44, "sides", "one-sided".getBytes(StandardCharsets.US_ASCII));
        ipp.write(0x03);
        ipp.write(pwg);
        return ipp.toByteArray();
    }

    private void sendIpp(String ip, byte[] ippPacket) throws Exception {
        HttpURLConnection c = openPrinterHttp("http://" + ip + ":631/ipp/print", "POST", 6000, 30000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/ipp");
        try (OutputStream os = c.getOutputStream()) { os.write(ippPacket); }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("Printer HTTP " + code + " — Check Wi-Fi & IP");
    }

    private void ippAttr(OutputStream os, int tag, String name, byte[] val) throws IOException {
        os.write(tag); byte[] nb = name.getBytes(StandardCharsets.US_ASCII);
        os.write((nb.length >> 8) & 0xFF); os.write(nb.length & 0xFF); os.write(nb);
        os.write((val.length >> 8) & 0xFF); os.write(val.length & 0xFF); os.write(val);
    }

    private void sendUsb(byte[] data) throws Exception {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (um == null || usbPrinter == null) throw new Exception("HP Smart Tank 589 USB not connected!");
        if (!um.hasPermission(usbPrinter)) {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent("com.abhishek.USB_PERM"), PendingIntent.FLAG_IMMUTABLE);
            um.requestPermission(usbPrinter, pi); throw new Exception("Allow USB popup & tap Start Print again!");
        }
        UsbInterface intf = null;
        UsbEndpoint epOut = null;
        for (int idx = 0; idx < usbPrinter.getInterfaceCount(); idx++) {
            UsbInterface candidate = usbPrinter.getInterface(idx);
            if (candidate.getInterfaceClass() == 7) {
                for (int i = 0; i < candidate.getEndpointCount(); i++) {
                    UsbEndpoint ep = candidate.getEndpoint(i);
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        intf = candidate; epOut = ep; break;
                    }
                }
            }
            if (epOut != null) break;
        }
        if (intf == null || epOut == null) throw new Exception("USB Printer interface not found!");
        UsbDeviceConnection conn = um.openDevice(usbPrinter);
        if (conn == null || !conn.claimInterface(intf, true)) throw new Exception("Cannot open USB printer!");
        int off = 0;
        while (off < data.length) {
            int len = Math.min(16384, data.length - off);
            int sent = conn.bulkTransfer(epOut, Arrays.copyOfRange(data, off, off + len), len, 15000);
            if (sent < 0) { conn.close(); throw new Exception("USB transfer failed!"); }
            off += sent;
        }
        conn.releaseInterface(intf); conn.close();
    }

    private void promptPrinterIp() {
        EditText ed = new EditText(this); ed.setText(printerIp); ed.setTextColor(Color.parseColor("#0F172A"));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("HP Smart Tank 589 IP").setView(ed)
                .setPositiveButton("Save", (d, w) -> { printerIp = ed.getText().toString().trim(); statusTxt.setText("Target IP: " + printerIp); })
                .setNegativeButton("Cancel", null).show();
    }

    // Generate & Print Built-in CMYK Nozzle Check + Head Cleaning Test Pattern
    private void printNozzleCheckPage() {
        Bitmap test = Bitmap.createBitmap(2480, 3508, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(test); c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setColor(Color.BLACK); p.setTextSize(72); p.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText("AMAN PRINT — HP SMART TANK 589 NOZZLE CHECK", 150, 220, p);
        p.setTextSize(42); p.setTypeface(Typeface.DEFAULT);
        c.drawText("Check all 4 color bars & fine grids below for missing lines (clogged nozzles):", 150, 300, p);

        String[] names = {"BLACK (K) — Pigment Black", "CYAN (C) — Blue Tank", "MAGENTA (M) — Pink Tank", "YELLOW (Y) — Yellow Tank"};
        int[] cols = {Color.BLACK, Color.CYAN, Color.MAGENTA, Color.YELLOW};
        for (int i = 0; i < 4; i++) {
            int topY = 440 + i * 540;
            p.setColor(Color.BLACK); p.setStyle(Paint.Style.FILL); p.setTextSize(44);
            c.drawText(names[i], 150, topY, p);

            p.setColor(cols[i]);
            c.drawRect(150, topY + 30, 1250, topY + 250, p);

            // Fine nozzle step grid
            p.setStrokeWidth(5);
            for (int l = 0; l < 18; l++) {
                int ly = topY + 40 + l * 12;
                c.drawLine(1320 + (l % 3) * 30, ly, 2320, ly, p);
            }
            p.setStyle(Paint.Style.STROKE); p.setColor(Color.DKGRAY); p.setStrokeWidth(3);
            c.drawRect(150, topY + 30, 1250, topY + 250, p);
            p.setStyle(Paint.Style.FILL);
        }

        // Alignment fine text & border
        p.setColor(Color.BLACK); p.setTextSize(38);
        c.drawText("Fine Text Sharpness: ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789 @#$%", 150, 2720, p);
        c.drawText("If lines are broken, run Printhead Cleaning from HP EWS below.", 150, 2810, p);

        closePdf(); totalPages = 1; rawBmp = test; resetEditsOnly(); setColor(0); sizeSp.setSelection(0);
        applyEditsToPreview();
        updateLoadedUiState("Nozzle Check Test Page Ready — Tap PRINT NOW");
        Toast.makeText(this, "Nozzle Check page loaded! Tap PRINT NOW to print.", Toast.LENGTH_LONG).show();
    }

    private void configurePrinterWebView(WebView wv) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        wv.setWebViewClient(new WebViewClient() {
            private boolean triedHttpFallback = false;
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
            @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (!triedHttpFallback && failingUrl != null && failingUrl.startsWith("https://")) {
                    triedHttpFallback = true;
                    view.loadUrl(failingUrl.replaceFirst("https://", "http://"));
                }
            }
        });
    }

    private void openInkAndDashboard() {
        bindToWifiNetwork(true);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView note = new TextView(this);
        note.setText("Note: HP Smart Tank 589 bottle tanks mein physical % sensor nahi hota (front glass se level dikhta hai). Neeche printer status + Nozzle Check diya hai:");
        note.setTextSize(12); note.setTextColor(Color.parseColor("#475569"));
        box.addView(note);

        TextView inkTitle = new TextView(this); inkTitle.setText("Checking HP 589 Status...");
        inkTitle.setTypeface(null, Typeface.BOLD); inkTitle.setTextColor(Color.parseColor("#0F172A"));
        inkTitle.setPadding(0, dp(8), 0, dp(4));
        box.addView(inkTitle);

        LinearLayout barsBox = new LinearLayout(this); barsBox.setOrientation(LinearLayout.VERTICAL); barsBox.setPadding(0, dp(2), 0, dp(8));
        box.addView(barsBox);

        final AlertDialog[] inkDlgRef = new AlertDialog[1];
        LinearLayout btnRow = new LinearLayout(this); btnRow.setPadding(0, 0, 0, dp(8));
        btnRow.addView(cleanBtn("Print Nozzle Check", "#DCFCE7", "#166534", v -> {
            if (inkDlgRef[0] != null) inkDlgRef[0].dismiss();
            printNozzleCheckPage();
        }));
        gap(btnRow, 8);
        btnRow.addView(cleanBtn("Open EWS in Chrome", "#DBEAFE", "#1E40AF", v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + printerIp))); } catch (Exception ignored) {}
        }));
        box.addView(btnRow);

        new Thread(() -> {
            try {
                String xml = fetchPrinterXml("https://" + printerIp + "/DevMgmt/ConsumableConfigDyn.xml");
                if (xml == null) xml = fetchPrinterXml("http://" + printerIp + "/DevMgmt/ConsumableConfigDyn.xml");
                if (xml == null) throw new Exception("No XML");

                Matcher mLabel = Pattern.compile("<dd:ConsumableLabelCode>([^<]+)</dd:ConsumableLabelCode>").matcher(xml);
                Matcher mPct = Pattern.compile("<dd:ConsumablePercentageLevelRemaining>(\\d+)</dd:ConsumablePercentageLevelRemaining>").matcher(xml);
                Matcher mState = Pattern.compile("<dd:ConsumableState>([^<]+)</dd:ConsumableState>").matcher(xml);
                List<String> labels = new ArrayList<>(); List<String> infos = new ArrayList<>(); List<Integer> pcts = new ArrayList<>();
                while (mLabel.find()) {
                    labels.add(mLabel.group(1));
                    int pct = mPct.find() ? Integer.parseInt(mPct.group(1)) : -1;
                    String st = mState.find() ? mState.group(1) : "OK";
                    pcts.add(pct);
                    infos.add(pct >= 0 ? (pct + "% (" + st + ")") : st);
                }
                uiHandler.post(() -> {
                    inkTitle.setText(labels.isEmpty() ? "HP 589 Connected — Live EWS Dashboard:" : "HP Smart Tank 589 Ink Status:");
                    for (int i = 0; i < labels.size(); i++) {
                        String code = labels.get(i); int pct = pcts.get(i);
                        int col = code.contains("C") ? Color.parseColor("#06B6D4") : code.contains("M") ? Color.parseColor("#EC4899") : code.contains("Y") ? Color.parseColor("#EAB308") : Color.parseColor("#1E293B");
                        TextView t = new TextView(this); t.setText(code + " Tank: " + infos.get(i)); t.setTextSize(12); t.setTextColor(col);
                        barsBox.addView(t);
                        if (pct >= 0) {
                            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                            pb.setMax(100); pb.setProgress(pct); pb.setProgressTintList(ColorStateList.valueOf(col));
                            barsBox.addView(pb);
                        }
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> inkTitle.setText("HP EWS Dashboard (" + printerIp + "):"));
            }
        }).start();

        WebView wv = new WebView(this);
        configurePrinterWebView(wv);
        wv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        wv.loadUrl("https://" + printerIp);
        box.addView(wv);

        inkDlgRef[0] = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Ink, Nozzle Check & EWS").setView(box).setPositiveButton("Close", null).show();
    }

    private String fetchPrinterXml(String urlStr) {
        try {
            HttpURLConnection c = openPrinterHttp(urlStr, "GET", 4000, 6000);
            if (c.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private void openWebDialog(String url) {
        bindToWifiNetwork(true);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(10), dp(10), dp(10), dp(10));
        Button chromeBtn = cleanBtn("Open in Chrome Browser", "#DBEAFE", "#1E40AF", v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        });
        chromeBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        box.addView(chromeBtn);

        WebView wv = new WebView(this);
        configurePrinterWebView(wv);
        wv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        wv.loadUrl(url);
        box.addView(wv);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("HP WebScan").setView(box).setPositiveButton("Close", null).show();
    }

    private void startAutoDiscovery() {
        try {
            nsdMgr = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsdMgr == null) return;
            ippListener = makeDisc(); scanListener = makeDisc();
            nsdMgr.discoverServices("_ipp._tcp.", NsdManager.PROTOCOL_DNS_SD, ippListener);
            nsdMgr.discoverServices("_uscan._tcp.", NsdManager.PROTOCOL_DNS_SD, scanListener);
        } catch (Exception ignored) {}
    }

    private NsdManager.DiscoveryListener makeDisc() {
        return new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String r) {}
            @Override public void onServiceFound(NsdServiceInfo s) {
                if (s.getServiceName().toLowerCase().contains("hp") || s.getServiceName().toLowerCase().contains("smart tank")) {
                    nsdMgr.resolveService(s, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo si, int e) {}
                        @Override public void onServiceResolved(NsdServiceInfo si) {
                            if (si.getHost() != null && si.getHost().getHostAddress() != null) {
                                printerIp = si.getHost().getHostAddress(); printerName = si.getServiceName();
                                uiHandler.post(() -> statusTxt.setText("Connected: " + printerName + " (" + printerIp + ")"));
                            }
                        }
                    });
                }
            }
            @Override public void onServiceLost(NsdServiceInfo s) {}
            @Override public void onDiscoveryStopped(String s) {}
            @Override public void onStartDiscoveryFailed(String s, int e) {}
            @Override public void onStopDiscoveryFailed(String s, int e) {}
        };
    }

    private void stopAutoDiscovery() {
        try { if (nsdMgr != null) { if (ippListener != null) nsdMgr.stopServiceDiscovery(ippListener); if (scanListener != null) nsdMgr.stopServiceDiscovery(scanListener); } } catch (Exception ignored) {}
    }

    private void checkUsb() {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (um != null) {
            for (UsbDevice d : um.getDeviceList().values()) {
                if (d.getVendorId() == 1008 || d.getDeviceClass() == 7) { usbPrinter = d; statusTxt.setText("USB OTG Ready: " + d.getProductName()); return; }
            }
        }
        if (connMode.equals("USB OTG")) statusTxt.setText("USB OTG: Connect USB-B to Type-C cable to HP Smart Tank 589");
    }
}
