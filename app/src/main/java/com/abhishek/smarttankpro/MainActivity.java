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
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.*;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.*;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private String connMode = "Wi-Fi", printerIp = "192.168.1.100", printerName = "HP Smart Tank 589";
    private int colorMode = 0, presetMode = 0, copyCount = 1, orientationMode = 0;
    private boolean inkSaverOn = false, advancedOpen = false;

    private LinearLayout rootBox, advContentBox, recentRowBox;
    private TextView statusTxt, pageTxt, badgeTxt, copyValTxt, advToggleTxt;
    private ImageView previewImg;
    private EditText pageRangeEdit;
    private final Button[] modeBtns = new Button[3], colorBtns = new Button[3], presetBtns = new Button[3], orientBtns = new Button[2];
    private CheckBox inkSaverCheck, revCheck;
    private Spinner sizeSp, qualSp, scaleSp, marginSp, duplexSp, multiSp;

    private ParcelFileDescriptor pdfFd;
    private PdfRenderer pdfRen;
    private int curPage = 0, totalPages = 0;
    private Bitmap rawBmp, idFront, idBack;
    private int pickTarget = 0; // 0=normal, 1=idFront, 2=idBack, 3=passport
    private NsdManager nsdMgr;
    private NsdManager.DiscoveryListener ippListener, scanListener;
    private UsbDevice usbPrinter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                    saveRecentUri(uri);
                    loadUri(uri);
                }
            });

    private final ActivityResultLauncher<Intent> camLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == RESULT_OK && res.getData() != null && res.getData().getExtras() != null) {
                    Bitmap b = (Bitmap) res.getData().getExtras().get("data");
                    if (b != null) {
                        closePdf(); totalPages = 1; rawBmp = b; setColor(2);
                        pageTxt.setText("Camera Scan Ready — Save JPG/PDF or Print");
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
        startAutoDiscovery();
        checkUsb();
    }

    @Override protected void onDestroy() { super.onDestroy(); stopAutoDiscovery(); closePdf(); }

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
    }

    private Drawable makeVectorIcon(int type, int color) {
        int s = dp(18);
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.7f));
        float m = dp(2.5f);
        if (type == 1) { // Doc
            c.drawRoundRect(m, m * 0.6f, s - m, s - m * 0.6f, dp(2), dp(2), p);
            c.drawLine(m * 1.8f, s * 0.4f, s - m * 1.8f, s * 0.4f, p);
            c.drawLine(m * 1.8f, s * 0.65f, s * 0.65f, s * 0.65f, p);
        } else if (type == 2) { // Scanner
            c.drawRoundRect(m, s * 0.45f, s - m, s - m, dp(2), dp(2), p);
            c.drawLine(m, s * 0.45f, s - m * 1.4f, m, p);
        } else if (type == 3) { // Camera
            c.drawRoundRect(m, s * 0.3f, s - m, s - m, dp(2.5f), dp(2.5f), p);
            c.drawCircle(s / 2f, s * 0.58f, dp(2.6f), p);
        } else if (type == 4) { // ID Card
            c.drawRoundRect(m * 0.7f, s * 0.22f, s - m * 0.7f, s * 0.78f, dp(2), dp(2), p);
            c.drawCircle(s * 0.33f, s * 0.48f, dp(1.8f), p);
            c.drawLine(s * 0.55f, s * 0.42f, s * 0.8f, s * 0.42f, p);
            c.drawLine(s * 0.55f, s * 0.58f, s * 0.75f, s * 0.58f, p);
        } else if (type == 5) { // Passport Grid
            c.drawRect(m, m, s / 2f - dp(1), s / 2f - dp(1), p);
            c.drawRect(s / 2f + dp(1), m, s - m, s / 2f - dp(1), p);
            c.drawRect(m, s / 2f + dp(1), s / 2f - dp(1), s - m, p);
            c.drawRect(s / 2f + dp(1), s / 2f + dp(1), s - m, s - m, p);
        } else if (type == 6) { // Ink Drop
            Path path = new Path();
            path.moveTo(s / 2f, m);
            path.quadTo(s - m, s * 0.6f, s / 2f, s - m);
            path.quadTo(m, s * 0.6f, s / 2f, m);
            c.drawPath(path, p);
        } else if (type == 7) { // Printer
            c.drawRect(m * 1.4f, m, s - m * 1.4f, s * 0.38f, p);
            c.drawRoundRect(m * 0.7f, s * 0.38f, s - m * 0.7f, s * 0.72f, dp(2), dp(2), p);
            c.drawRect(m * 1.4f, s * 0.62f, s - m * 1.4f, s - m, p);
        } else { // Save arrow
            c.drawLine(s / 2f, m, s / 2f, s * 0.68f, p);
            c.drawLine(s * 0.3f, s * 0.5f, s / 2f, s * 0.68f, p);
            c.drawLine(s * 0.7f, s * 0.5f, s / 2f, s * 0.68f, p);
            c.drawLine(m, s - m, s - m, s - m, p);
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
    private void buildUi() {
        LinearLayout outerRoot = new LinearLayout(this);
        outerRoot.setOrientation(LinearLayout.VERTICAL);
        outerRoot.setBackgroundColor(Color.parseColor("#EEF2F6"));

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        rootBox.setPadding(dp(14), dp(12), dp(14), dp(18));
        sv.addView(rootBox);
        outerRoot.addView(sv);

        // Sticky Bottom PRINT NOW Bar
        LinearLayout stickyBar = new LinearLayout(this);
        stickyBar.setOrientation(LinearLayout.HORIZONTAL);
        stickyBar.setBackgroundColor(Color.WHITE);
        stickyBar.setPadding(dp(14), dp(10), dp(14), dp(12));
        stickyBar.setElevation(dp(12));

        Button printBtn = new Button(this);
        printBtn.setText("PRINT NOW");
        printBtn.setAllCaps(false); printBtn.setTextSize(16); printBtn.setTypeface(null, Typeface.BOLD);
        printBtn.setTextColor(Color.WHITE);
        printBtn.setBackground(grad(Color.parseColor("#0F5A9C"), Color.parseColor("#0077C8"), 14));
        printBtn.setCompoundDrawablesWithIntrinsicBounds(makeVectorIcon(7, Color.WHITE), null, null, null);
        printBtn.setCompoundDrawablePadding(dp(8));
        printBtn.setPadding(dp(20), 0, dp(20), 0);
        printBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        bounce(printBtn); printBtn.setOnClickListener(v -> openInAppPrintSheet());
        stickyBar.addView(printBtn);
        outerRoot.addView(stickyBar);

        // 1. Header Card with Aman Print Logo
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
        logoLp.setMargins(0, 0, dp(10), 0);
        logoImg.setLayoutParams(logoLp);
        top.addView(logoImg);

        TextView ttl = new TextView(this);
        ttl.setText("Aman Print");
        ttl.setTextSize(20); ttl.setTypeface(null, Typeface.BOLD); ttl.setTextColor(Color.WHITE);
        ttl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(ttl);

        badgeTxt = new TextView(this);
        badgeTxt.setText("Wi-Fi");
        badgeTxt.setTextSize(11); badgeTxt.setTypeface(null, Typeface.BOLD);
        badgeTxt.setTextColor(Color.parseColor("#0F5A9C"));
        badgeTxt.setBackground(bg(Color.parseColor("#E0F2FE"), 0, 0, 16));
        badgeTxt.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(badgeTxt);
        hdr.addView(top);

        statusTxt = new TextView(this);
        statusTxt.setText("HP Smart Tank 589 — Tap here to set IP");
        statusTxt.setTextSize(12); statusTxt.setTextColor(Color.parseColor("#E0F2FE"));
        statusTxt.setPadding(0, dp(6), 0, dp(10));
        statusTxt.setOnClickListener(v -> promptPrinterIp());
        hdr.addView(statusTxt);

        // Fixed Single-Line Mode Tabs (Wi-Fi | Wi-Fi Direct | USB OTG)
        LinearLayout mBar = new LinearLayout(this);
        mBar.setBackground(bg(Color.parseColor("#083B66"), 0, 0, 12));
        mBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        String[] modes = {"Wi-Fi", "Wi-Fi Direct", "USB OTG"};
        for (int i = 0; i < 3; i++) {
            final String m = modes[i];
            Button b = new Button(this);
            b.setText(m); b.setAllCaps(false); b.setSingleLine(true); b.setEllipsize(TextUtils.TruncateAt.END);
            b.setTextSize(12); b.setTypeface(null, Typeface.BOLD); b.setStateListAnimator(null);
            b.setPadding(dp(2), 0, dp(2), 0); b.setMinHeight(0); b.setMinimumHeight(0);
            b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(36), 1f));
            bounce(b); b.setOnClickListener(v -> setMode(m));
            modeBtns[i] = b; mBar.addView(b);
        }
        hdr.addView(mBar);
        rootBox.addView(hdr);
        refreshModeUi();

        // 2. Quick Actions Card (Vector Icons + No Clipped Text)
        LinearLayout act = card();
        LinearLayout r1 = new LinearLayout(this);
        r1.addView(iconTile("Open File", 1, "#EFF6FF", "#1D4ED8", v -> { pickTarget = 0; picker.launch(new String[]{"application/pdf", "image/*"}); }));
        gap(r1, 8);
        r1.addView(iconTile("Printer Scan", 2, "#ECFEFF", "#0E7490", v -> openPrinterScannerDialog()));
        act.addView(r1);

        LinearLayout r2 = new LinearLayout(this); r2.setPadding(0, dp(8), 0, 0);
        r2.addView(iconTile("Camera Scan", 3, "#F0FDF4", "#15803D", v -> launchCamera()));
        gap(r2, 8);
        r2.addView(iconTile("ID Card", 4, "#FEF3C7", "#B45309", v -> idDialog()));
        act.addView(r2);

        LinearLayout r3 = new LinearLayout(this); r3.setPadding(0, dp(8), 0, 0);
        r3.addView(iconTile("Passport Sheet", 5, "#FCE7F3", "#BE185D", v -> startPassportSheet()));
        gap(r3, 8);
        r3.addView(iconTile("Ink & Status", 6, "#F3E8FF", "#6D28D9", v -> openInkAndDashboard()));
        act.addView(r3);

        recentRowBox = new LinearLayout(this);
        recentRowBox.setOrientation(LinearLayout.HORIZONTAL);
        recentRowBox.setPadding(0, dp(8), 0, 0);
        act.addView(recentRowBox);
        rootBox.addView(act);

        // 3. Preview & Save Card
        LinearLayout pCard = card();
        pageTxt = new TextView(this);
        pageTxt.setText("फाइल चुनें या प्रिंटर से स्कैन करें");
        pageTxt.setTextSize(13); pageTxt.setTypeface(null, Typeface.BOLD); pageTxt.setTextColor(Color.parseColor("#0F172A"));
        pageTxt.setPadding(0, 0, 0, dp(8));
        pCard.addView(pageTxt);

        previewImg = new ImageView(this);
        previewImg.setMinimumHeight(dp(210)); previewImg.setMaxHeight(dp(300)); previewImg.setAdjustViewBounds(true);
        previewImg.setBackground(bg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), dp(1), 12));
        previewImg.setPadding(dp(8), dp(8), dp(8), dp(8));
        previewImg.setOnClickListener(v -> { if (rawBmp == null && pdfRen == null) { pickTarget = 0; picker.launch(new String[]{"application/pdf", "image/*"}); } });
        pCard.addView(previewImg);

        LinearLayout nav = new LinearLayout(this); nav.setPadding(0, dp(8), 0, 0);
        nav.addView(cleanBtn("Prev Page", "#F1F5F9", "#0F172A", v -> showPdfPage(curPage - 1)));
        gap(nav, 8);
        nav.addView(cleanBtn("Next Page", "#F1F5F9", "#0F172A", v -> showPdfPage(curPage + 1)));
        pCard.addView(nav);

        LinearLayout saveRow = new LinearLayout(this); saveRow.setPadding(0, dp(8), 0, 0);
        saveRow.addView(iconTile("Save JPG", 8, "#DCFCE7", "#166534", v -> saveCurrentAsJpg()));
        gap(saveRow, 8);
        saveRow.addView(iconTile("Save PDF", 8, "#DBEAFE", "#1E40AF", v -> saveCurrentAsPdf()));
        pCard.addView(saveRow);
        rootBox.addView(pCard);

        // 4. Smart Presets & Essential Controls Card
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

        // Color Mode Selector (Full Color | B&W | Sharp B&W)
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

        // Copies + Page Range Row
        LinearLayout cpRow = new LinearLayout(this); cpRow.setPadding(0, dp(10), 0, 0); cpRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView cpLbl = new TextView(this); cpLbl.setText("Copies:"); cpLbl.setTextSize(13); cpLbl.setTypeface(null, Typeface.BOLD); cpLbl.setTextColor(Color.parseColor("#334155"));
        cpRow.addView(cpLbl); gap(cpRow, 8);

        Button minusBtn = new Button(this); minusBtn.setText("-"); minusBtn.setTextSize(16); minusBtn.setTypeface(null, Typeface.BOLD);
        minusBtn.setBackground(bg(Color.parseColor("#E2E8F0"), 0, 0, 8)); minusBtn.setPadding(0, 0, 0, 0);
        minusBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        minusBtn.setOnClickListener(v -> { if (copyCount > 1) { copyCount--; copyValTxt.setText(String.valueOf(copyCount)); } });
        cpRow.addView(minusBtn);

        copyValTxt = new TextView(this); copyValTxt.setText("1"); copyValTxt.setGravity(Gravity.CENTER);
        copyValTxt.setTextSize(15); copyValTxt.setTypeface(null, Typeface.BOLD); copyValTxt.setTextColor(Color.parseColor("#0F172A"));
        copyValTxt.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        cpRow.addView(copyValTxt);

        Button plusBtn = new Button(this); plusBtn.setText("+"); plusBtn.setTextSize(16); plusBtn.setTypeface(null, Typeface.BOLD);
        plusBtn.setBackground(bg(Color.parseColor("#E2E8F0"), 0, 0, 8)); plusBtn.setPadding(0, 0, 0, 0);
        plusBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        plusBtn.setOnClickListener(v -> { if (copyCount < 50) { copyCount++; copyValTxt.setText(String.valueOf(copyCount)); } });
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

        // Portrait / Landscape + Ink Saver Row
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
            if (rawBmp != null) previewImg.setImageBitmap(filterBmp(rawBmp));
        });
        oriRow.addView(inkSaverCheck);
        quickCard.addView(oriRow);
        rootBox.addView(quickCard);

        // 5. Collapsible Advanced Settings Card
        LinearLayout advCard = card();
        advToggleTxt = new TextView(this);
        advToggleTxt.setText("Advanced Settings (Paper, Duplex, 2-in-1)  +");
        advToggleTxt.setTextSize(14); advToggleTxt.setTypeface(null, Typeface.BOLD); advToggleTxt.setTextColor(Color.parseColor("#0F5A9C"));
        advToggleTxt.setPadding(0, dp(2), 0, dp(2));
        advCard.addView(advToggleTxt);

        advContentBox = new LinearLayout(this);
        advContentBox.setOrientation(LinearLayout.VERTICAL);
        advContentBox.setVisibility(View.GONE);
        advContentBox.setPadding(0, dp(8), 0, 0);

        advToggleTxt.setOnClickListener(v -> {
            TransitionManager.beginDelayedTransition(rootBox, new AutoTransition().setDuration(180));
            advancedOpen = !advancedOpen;
            advContentBox.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
            advToggleTxt.setText(advancedOpen ? "Advanced Settings (Hide)  -" : "Advanced Settings (Paper, Duplex, 2-in-1)  +");
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

        advContentBox.addView(lbl("Manual Duplex (Both Sides):"));
        duplexSp = spin(new String[]{"One side only", "Manual Duplex (Odd -> Flip -> Even)"});
        advContentBox.addView(duplexSp);

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
        b.setText(t); b.setAllCaps(false); b.setSingleLine(true); b.setTextSize(13); b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.parseColor(fgHex)); b.setBackground(bg(Color.parseColor(bgHex), 0, 0, 10));
        b.setPadding(dp(8), 0, dp(8), 0); b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        bounce(b); b.setOnClickListener(l); return b;
    }

    private void gap(LinearLayout r, int w) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1)); r.addView(v); }
    private TextView secTitle(String t) { TextView tv = new TextView(this); tv.setText(t); tv.setTextSize(14); tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(Color.parseColor("#0F172A")); tv.setPadding(0, 0, 0, dp(8)); return tv; }
    private TextView lbl(String t) { TextView tv = new TextView(this); tv.setText(t); tv.setTextSize(12); tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(Color.parseColor("#475569")); tv.setPadding(0, dp(10), 0, dp(4)); return tv; }

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
        Bitmap ph = Bitmap.createBitmap(720, 440, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(ph); c.drawColor(Color.parseColor("#F8FAFC"));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE); p.setColor(Color.parseColor("#94A3B8")); p.setStrokeWidth(3);
        p.setPathEffect(new DashPathEffect(new float[]{14, 10}, 0));
        c.drawRoundRect(30, 30, 690, 410, 24, 24, p);
        p.setPathEffect(null); p.setStyle(Paint.Style.FILL); p.setColor(Color.parseColor("#475569"));
        p.setTextAlign(Paint.Align.CENTER); p.setTextSize(28);
        c.drawText("फ़ाइल चुनें या प्रिंटर से स्कैन करें", 360, 210, p);
        p.setTextSize(21); p.setColor(Color.parseColor("#64748B"));
        c.drawText("Tap 'Open File', 'Printer Scan', or 'Camera Scan'", 360, 255, p);
        previewImg.setImageBitmap(ph);
    }

    private void applyPreset(int idx) {
        presetMode = idx; refreshPresetUi();
        if (idx == 0) { // Document
            sizeSp.setSelection(0); qualSp.setSelection(0); scaleSp.setSelection(0); setColor(0);
            Toast.makeText(this, "Document Preset (A4 Normal)", Toast.LENGTH_SHORT).show();
        } else if (idx == 1) { // Photo
            sizeSp.setSelection(3); qualSp.setSelection(2); scaleSp.setSelection(1); setColor(0);
            Toast.makeText(this, "Photo Preset (4x6 Glossy Best)", Toast.LENGTH_SHORT).show();
        } else { // ID Card
            sizeSp.setSelection(0); qualSp.setSelection(2); setColor(0);
            buildIdSheet();
        }
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
        if (m.equals("Wi-Fi Direct")) { printerIp = "192.168.223.1"; statusTxt.setText("Wi-Fi Direct IP: 192.168.223.1 (Tap to edit)"); }
        else if (m.equals("USB OTG")) checkUsb();
        else statusTxt.setText("Home Wi-Fi: " + printerName + " (" + printerIp + ")");
    }

    private void refreshModeUi() {
        String[] m = {"Wi-Fi", "Wi-Fi Direct", "USB OTG"};
        for (int i = 0; i < 3; i++) {
            boolean act = m[i].equals(connMode);
            modeBtns[i].setBackground(bg(act ? Color.WHITE : Color.TRANSPARENT, 0, 0, 9));
            modeBtns[i].setTextColor(act ? Color.parseColor("#0F5A9C") : Color.parseColor("#BAE6FD"));
        }
    }

    private void setColor(int idx) {
        colorMode = idx; refreshColorUi();
        if (rawBmp != null) previewImg.setImageBitmap(filterBmp(rawBmp));
    }

    private void refreshColorUi() {
        for (int i = 0; i < 3; i++) {
            boolean act = (i == colorMode);
            colorBtns[i].setBackground(bg(act ? (i == 0 ? Color.parseColor("#0077C8") : Color.parseColor("#1E293B")) : Color.TRANSPARENT, 0, 0, 9));
            colorBtns[i].setTextColor(act ? Color.WHITE : Color.parseColor("#334155"));
        }
    }

    private Bitmap filterBmp(Bitmap src) {
        if (src == null) return null;
        if (colorMode == 0 && !inkSaverOn) return src;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out); Paint p = new Paint(); ColorMatrix cm = new ColorMatrix();
        if (colorMode != 0) cm.setSaturation(0);
        if (colorMode == 2) {
            float k = 1.65f, t = (-0.5f * k + 0.5f) * 255f;
            cm.postConcat(new ColorMatrix(new float[]{k,0,0,0,t, 0,k,0,0,t, 0,0,k,0,t, 0,0,0,1,0}));
        }
        if (inkSaverOn) {
            cm.postConcat(new ColorMatrix(new float[]{0.82f,0,0,0,42, 0,0.82f,0,0,42, 0,0,0.82f,0,42, 0,0,0,1,0}));
        }
        p.setColorFilter(new ColorMatrixColorFilter(cm)); c.drawBitmap(src, 0, 0, p);
        return out;
    }

    private void saveRecentUri(Uri uri) {
        SharedPreferences sp = getSharedPreferences("aman_print_prefs", MODE_PRIVATE);
        String old = sp.getString("recents", "");
        List<String> list = new ArrayList<>();
        list.add(uri.toString());
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
            String mime = getContentResolver().getType(uri);
            if (mime != null && mime.contains("pdf")) {
                closePdf();
                pdfFd = getContentResolver().openFileDescriptor(uri, "r");
                if (pdfFd == null) return;
                pdfRen = new PdfRenderer(pdfFd);
                totalPages = pdfRen.getPageCount();
                showPdfPage(0);
                if (pickTarget == 1 || pickTarget == 2) cropIdDialog();
            } else {
                Bitmap b; try (InputStream is = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(is); }
                if (b == null) return;
                if (pickTarget == 1) { idFront = b; pickTarget = 0; buildIdSheet(); }
                else if (pickTarget == 2) { idBack = b; pickTarget = 0; buildIdSheet(); }
                else if (pickTarget == 3) { pickTarget = 0; buildPassportSheet(b); }
                else { closePdf(); totalPages = 1; rawBmp = b; previewImg.setImageBitmap(filterBmp(b)); pageTxt.setText("Photo Loaded — Ready to Print or Save"); }
            }
        } catch (Exception e) { Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private void showPdfPage(int idx) {
        if (pdfRen == null || idx < 0 || idx >= totalPages) return;
        curPage = idx;
        PdfRenderer.Page p = pdfRen.openPage(idx);
        Bitmap b = Bitmap.createBitmap((int)(p.getWidth() * 2.5f), (int)(p.getHeight() * 2.5f), Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); p.close();
        rawBmp = b; previewImg.setImageBitmap(filterBmp(b));
        pageTxt.setText("PDF Preview — Page " + (idx + 1) + " of " + totalPages);
    }

    private void idDialog() {
        String[] opts = {"1. Select Front Side (PDF / Photo)", "2. Select Back Side (PDF / Photo)", "3. Show 2-in-1 ID Sheet"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("ID Card Maker")
                .setItems(opts, (d, w) -> {
                    if (w == 0) { pickTarget = 1; picker.launch(new String[]{"application/pdf", "image/*"}); }
                    else if (w == 1) { pickTarget = 2; picker.launch(new String[]{"application/pdf", "image/*"}); }
                    else buildIdSheet();
                }).show();
    }

    private void cropIdDialog() {
        if (rawBmp == null) return;
        String[] pr = {"Top Half (e-Aadhaar / PAN)", "Bottom Half (e-Aadhaar Cutout)", "Center Card Area", "Full Page"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Crop ID Area")
                .setItems(pr, (d, w) -> {
                    int bw = rawBmp.getWidth(), bh = rawBmp.getHeight();
                    Bitmap cr = (w == 0) ? Bitmap.createBitmap(rawBmp, 0, 0, bw, bh / 2) : (w == 1) ? Bitmap.createBitmap(rawBmp, 0, bh / 2, bw, bh / 2) : (w == 2) ? Bitmap.createBitmap(rawBmp, bw / 8, bh / 4, (bw * 3) / 4, bh / 2) : rawBmp;
                    if (pickTarget == 1) idFront = cr; else idBack = cr;
                    pickTarget = 0; buildIdSheet();
                }).show();
    }

    private void buildIdSheet() {
        Bitmap sh = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sh); c.drawColor(Color.WHITE);
        Paint b = new Paint(Paint.ANTI_ALIAS_FLAG); b.setStyle(Paint.Style.STROKE); b.setColor(Color.parseColor("#94A3B8")); b.setStrokeWidth(3);
        Paint txtP = new Paint(Paint.ANTI_ALIAS_FLAG); txtP.setColor(Color.parseColor("#64748B")); txtP.setTextSize(34); txtP.setTextAlign(Paint.Align.CENTER);
        Rect r1 = new Rect(180, 160, 1060, 720), r2 = new Rect(180, 820, 1060, 1380);
        c.drawRect(r1, b); c.drawRect(r2, b);
        if (idFront != null) drawFit(c, idFront, r1, 0); else c.drawText("Front ID (Tap 'ID Card' above)", 620, 450, txtP);
        if (idBack != null) drawFit(c, idBack, r2, 0); else c.drawText("Back ID (Tap 'ID Card' above)", 620, 1110, txtP);
        closePdf(); totalPages = 1; rawBmp = sh; previewImg.setImageBitmap(filterBmp(sh));
        pageTxt.setText("2-in-1 ID Card Sheet (Front + Back)");
    }

    private void startPassportSheet() {
        pickTarget = 3;
        picker.launch(new String[]{"image/*"});
    }

    private void buildPassportSheet(Bitmap photo) {
        String[] layouts = {"4 x 6 inch Sheet (6 Passport Photos)", "A4 Sheet (12 Passport Photos)"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Passport Photo Sheet")
                .setItems(layouts, (d, w) -> {
                    boolean is4x6 = (w == 0);
                    int sw = is4x6 ? 1200 : 1240, sh = is4x6 ? 1800 : 1754;
                    int cols = is4x6 ? 2 : 3, rows = is4x6 ? 3 : 4;
                    Bitmap sheet = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(sheet); c.drawColor(Color.WHITE);
                    Paint border = new Paint(); border.setStyle(Paint.Style.STROKE); border.setColor(Color.LTGRAY); border.setStrokeWidth(2);
                    int pad = 50, gap = 30;
                    int cw = (sw - pad * 2 - gap * (cols - 1)) / cols;
                    int ch = (sh - pad * 2 - gap * (rows - 1)) / rows;
                    for (int r = 0; r < rows; r++) {
                        for (int col = 0; col < cols; col++) {
                            int l = pad + col * (cw + gap), t = pad + r * (ch + gap);
                            Rect box = new Rect(l, t, l + cw, t + ch);
                            c.drawBitmap(photo, null, box, null);
                            c.drawRect(box, border);
                        }
                    }
                    if (is4x6) sizeSp.setSelection(3); else sizeSp.setSelection(0);
                    qualSp.setSelection(2);
                    closePdf(); totalPages = 1; rawBmp = sheet; previewImg.setImageBitmap(filterBmp(sheet));
                    pageTxt.setText(is4x6 ? "4x6 Passport Sheet (6 Photos)" : "A4 Passport Sheet (12 Photos)");
                }).show();
    }

    private void saveCurrentAsJpg() {
        Bitmap bmp = filterBmp(rawBmp);
        if (bmp == null) { Toast.makeText(this, "Pehle file chunein ya scan karein!", Toast.LENGTH_SHORT).show(); return; }
        try {
            String name = "AmanPrint_" + System.currentTimeMillis() + ".jpg";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AmanPrint");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("Storage error");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) { bmp.compress(Bitmap.CompressFormat.JPEG, 95, os); }
            Toast.makeText(this, "Saved in Gallery: Pictures/AmanPrint/" + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private void saveCurrentAsPdf() {
        Bitmap bmp = filterBmp(rawBmp);
        if (bmp == null) { Toast.makeText(this, "Pehle file chunein ya scan karein!", Toast.LENGTH_SHORT).show(); return; }
        PdfDocument doc = new PdfDocument();
        try {
            PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
            drawFit(pg.getCanvas(), bmp, new Rect(16, 16, 579, 826), 0);
            doc.finishPage(pg);
            String name = "AmanPrint_" + System.currentTimeMillis() + ".pdf";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
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
    private void openPrinterScannerDialog() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(14), dp(18), dp(10));
        TextView hint = new TextView(this); hint.setText("HP Smart Tank 589 glass par document rakhein aur Scan Now dabayein:");
        hint.setTextColor(Color.parseColor("#0F172A")); hint.setTextSize(13); box.addView(hint);

        EditText ipEd = new EditText(this); ipEd.setText(printerIp); ipEd.setTextColor(Color.parseColor("#0F172A"));
        box.addView(ipEd);

        Spinner scanColorSp = spin(new String[]{"Color Scan (RGB)", "Black & White Scan"});
        box.addView(scanColorSp);

        TextView st = new TextView(this); st.setText("Ready to scan"); st.setTextColor(Color.parseColor("#0E7490")); st.setPadding(0, dp(8), 0, dp(4));
        box.addView(st);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(100);
        box.addView(pb);

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Scan from HP Smart Tank 589").setView(box)
                .setPositiveButton("Scan Now", null)
                .setNeutralButton("WebScan", (d, w) -> openWebDialog("http://" + ipEd.getText().toString().trim() + "/#hId-pgWebScan"))
                .setNegativeButton("Close", null).create();
        dlg.show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            printerIp = ipEd.getText().toString().trim();
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            runEsclScan(printerIp, scanColorSp.getSelectedItemPosition() == 0, st, pb, dlg);
        });
    }

    private void runEsclScan(String ip, boolean isColor, TextView st, ProgressBar pb, AlertDialog dlg) {
        new Thread(() -> {
            try {
                uiHandler.post(() -> { st.setText("Connecting to scanner..."); pb.setProgress(25); });
                String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><scan:ScanSettings xmlns:scan=\"http://schemas.hp.com/imaging/escl/2011/05/03\" xmlns:pwg=\"http://www.pwg.org/schemas/2012/12/pwg\"><pwg:Version>2.6</pwg:Version><pwg:ScanRegions><pwg:ScanRegion><pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits><pwg:Height>3508</pwg:Height><pwg:Width>2480</pwg:Width><pwg:XOffset>0</pwg:XOffset><pwg:YOffset>0</pwg:YOffset></pwg:ScanRegion></pwg:ScanRegions><scan:InputSource>Platen</scan:InputSource><scan:ColorMode>" + (isColor ? "RGB24" : "Grayscale8") + "</scan:ColorMode><scan:XResolution>300</scan:XResolution><scan:YResolution>300</scan:YResolution><pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat></scan:ScanSettings>";
                String loc = postEscl("http://" + ip + "/eSCL/ScanJobs", xml);
                if (loc == null) loc = postEscl("http://" + ip + ":8080/eSCL/ScanJobs", xml);
                if (loc == null) throw new Exception("Scanner not reachable! Tap 'WebScan' or check Wi-Fi.");
                if (loc.startsWith("/")) loc = "http://" + ip + loc;
                String docUrl = loc.endsWith("/") ? (loc + "NextDocument") : (loc + "/NextDocument");

                uiHandler.post(() -> { st.setText("Scanning page..."); pb.setProgress(60); });
                byte[] bytes = null;
                for (int i = 0; i < 18; i++) {
                    Thread.sleep(1500);
                    HttpURLConnection c = (HttpURLConnection) new URL(docUrl).openConnection();
                    c.setConnectTimeout(5000); c.setReadTimeout(20000);
                    if (c.getResponseCode() == 200) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (InputStream is = c.getInputStream()) { byte[] buf = new byte[8192]; int r; while ((r = is.read(buf)) != -1) baos.write(buf, 0, r); }
                        bytes = baos.toByteArray(); break;
                    }
                }
                if (bytes == null || bytes.length == 0) throw new Exception("Scan timeout!");
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) throw new Exception("Invalid scan image");

                uiHandler.post(() -> {
                    pb.setProgress(100); closePdf(); totalPages = 1; rawBmp = bmp;
                    previewImg.setImageBitmap(filterBmp(bmp));
                    pageTxt.setText("Scanned from HP 589 — Tap Save JPG or Save PDF");
                    dlg.dismiss();
                });
            } catch (Exception e) {
                uiHandler.post(() -> { st.setText(e.getMessage()); dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); });
            }
        }).start();
    }

    private String postEscl(String url, String xml) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml");
            try (OutputStream os = c.getOutputStream()) { os.write(xml.getBytes(StandardCharsets.UTF_8)); }
            if (c.getResponseCode() == 201) return c.getHeaderField("Location");
        } catch (Exception ignored) {}
        return null;
    }

    private List<Integer> parsePageIndices(int total) {
        String txt = pageRangeEdit.getText().toString().trim().toLowerCase();
        if (txt.isEmpty() || txt.equals("all")) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < total; i++) all.add(i);
            return all;
        }
        Set<Integer> set = new LinkedHashSet<>();
        for (String part : txt.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] ab = part.split("-");
                if (ab.length == 2) {
                    int s = Math.max(1, Integer.parseInt(ab[0].trim()));
                    int e = Math.min(total, Integer.parseInt(ab[1].trim()));
                    for (int p = s; p <= e; p++) set.add(p - 1);
                }
            } else {
                int p = Integer.parseInt(part);
                if (p >= 1 && p <= total) set.add(p - 1);
            }
        }
        return new ArrayList<>(set);
    }

    private void openInAppPrintSheet() {
        if (rawBmp == null && pdfRen == null) { Toast.makeText(this, "Pehle file chunein ya scan karein!", Toast.LENGTH_SHORT).show(); return; }
        boolean isDuplex = duplexSp.getSelectedItemPosition() == 1;
        launchDirectPrintDialog(isDuplex ? 1 : 0);
    }

    private void launchDirectPrintDialog(int duplexStep) {
        // duplexStep: 0=normal, 1=Odd pages, 2=Even pages reverse
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(18), dp(14), dp(18), dp(10));
        String title = duplexStep == 1 ? "Manual Duplex — Step 1 (Odd Pages)" : duplexStep == 2 ? "Manual Duplex — Step 2 (Even Pages)" : "Aman Print — Direct Spooler";
        TextView info = new TextView(this);
        info.setText("Printer: " + printerName + " (" + connMode + ") | Copies: " + copyCount);
        info.setTextColor(Color.parseColor("#0F172A")); info.setTextSize(13); info.setTypeface(null, Typeface.BOLD);
        panel.addView(info);

        EditText ipBox = new EditText(this); ipBox.setText(printerIp); ipBox.setTextColor(Color.parseColor("#0F172A"));
        if (!connMode.equals("USB OTG")) panel.addView(ipBox);

        TextView progTxt = new TextView(this); progTxt.setText("Ready to send job directly to printer.");
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
            runDirectPrintJob(duplexStep, progTxt, bar, dlg);
        });
    }

    private void runDirectPrintJob(int duplexStep, TextView progTxt, ProgressBar bar, AlertDialog dlg) {
        final int multi = multiSp.getSelectedItemPosition(), sc = scaleSp.getSelectedItemPosition(), mg = marginSp.getSelectedItemPosition();
        final int q = qualSp.getSelectedItemPosition(), copies = (duplexStep > 0) ? 1 : copyCount;
        final boolean rev = revCheck.isChecked(), isColor = (colorMode == 0), isLand = (orientationMode == 1);

        new Thread(() -> {
            try {
                int cnt = (pdfRen != null) ? totalPages : 1;
                List<Integer> rawList = parsePageIndices(cnt);
                List<Integer> list = new ArrayList<>();
                for (int idx : rawList) {
                    int pn = idx + 1;
                    if (duplexStep == 1) { if (pn % 2 == 1) list.add(idx); }
                    else if (duplexStep == 2) { if (pn % 2 == 0) list.add(idx); }
                    else list.add(idx);
                }
                if (rev || duplexStep == 2) Collections.reverse(list);
                if (list.isEmpty()) throw new Exception("Selected range mein koi page nahi hai!");

                uiHandler.post(() -> { progTxt.setText("Rendering pages..."); bar.setProgress(30); });
                List<Bitmap> oneSet = new ArrayList<>();
                int sw = isLand ? 1754 : 1240, sh = isLand ? 1240 : 1754;
                int lm = (mg == 2) ? 95 : (mg == 1 ? 0 : 24), pd = (mg == 1) ? 0 : 24;

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
                        drawFit(c, pageBmp(list.get(i)), new Rect(lm, pd, sw - pd, sh / 2 - 10), sc);
                        if (i + 1 < list.size()) drawFit(c, pageBmp(list.get(i + 1)), new Rect(lm, sh / 2 + 10, sw - pd, sh - pd), sc);
                        oneSet.add(sb);
                    }
                } else if (multi == 2) {
                    for (int i = 0; i < list.size(); i += 4) {
                        Bitmap sb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(sb); c.drawColor(Color.WHITE);
                        int mx = (lm + sw - pd) / 2, my = sh / 2;
                        Rect[] cells = {new Rect(lm, pd, mx - 8, my - 8), new Rect(mx + 8, pd, sw - pd, my - 8), new Rect(lm, my + 8, mx - 8, sh - pd), new Rect(mx + 8, my + 8, sw - pd, sh - pd)};
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

                uiHandler.post(() -> { progTxt.setText("Sending to HP Smart Tank 589..."); bar.setProgress(70); });
                byte[] pwgData = buildPwgRaster(finalSheets, isColor, 150, isLand);
                if (connMode.equals("USB OTG")) sendUsb(pwgData);
                else sendIpp(printerIp, pwgData, isColor, (q == 2 ? 5 : (q == 1 || inkSaverOn ? 3 : 4)));

                uiHandler.post(() -> {
                    bar.setProgress(100);
                    progTxt.setText("Printed Successfully! (" + finalSheets.size() + " sheets)");
                    if (duplexStep == 1) {
                        dlg.dismiss();
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                                .setTitle("कागज़ पलटकर डालें")
                                .setMessage("Odd पेज (1, 3, 5...) प्रिंट हो गए हैं। अब प्रिंटेड कागज़ पलटकर ट्रे में डालें, फिर Continue दबाएँ ताकि Even पेज अपने-आप उल्टे क्रम में प्रिंट हों।")
                                .setPositiveButton("Continue (Print Even)", (d2, w2) -> launchDirectPrintDialog(2))
                                .setNegativeButton("Cancel", null).show();
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> { progTxt.setText(e.getMessage()); dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); });
            }
        }).start();
    }

    private Bitmap pageBmp(int idx) {
        Bitmap base = rawBmp;
        if (pdfRen != null) {
            PdfRenderer.Page p = pdfRen.openPage(idx);
            base = Bitmap.createBitmap(p.getWidth() * 2, p.getHeight() * 2, Bitmap.Config.ARGB_8888);
            base.eraseColor(Color.WHITE); p.render(base, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); p.close();
        }
        return filterBmp(base);
    }

    private void drawFit(Canvas c, Bitmap b, Rect box, int sc) {
        if (b == null) return;
        if (sc == 1) { c.drawBitmap(b, null, box, null); return; }
        float s = (sc == 2) ? 1f : Math.min((float) box.width() / b.getWidth(), (float) box.height() / b.getHeight());
        int dw = Math.min(box.width(), (int)(b.getWidth() * s)), dh = Math.min(box.height(), (int)(b.getHeight() * s));
        int l = box.left + (box.width() - dw) / 2, t = box.top + (box.height() - dh) / 2;
        c.drawBitmap(b, null, new Rect(l, t, l + dw, t + dh), null);
    }

    private byte[] buildPwgRaster(List<Bitmap> pages, boolean isColor, int dpi, boolean isLand) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'R', 'a', 'S', '2'});
        for (Bitmap bmp : pages) {
            int w = bmp.getWidth(), h = bmp.getHeight();
            ByteBuffer hdr = ByteBuffer.allocate(1796).order(ByteOrder.BIG_ENDIAN);
            hdr.put("PwgRaster".getBytes(StandardCharsets.US_ASCII));
            hdr.putInt(260, dpi); hdr.putInt(264, dpi); hdr.putInt(288, 1);
            hdr.putInt(320, isLand ? 842 : 595); hdr.putInt(324, isLand ? 595 : 842);
            hdr.putInt(352, w); hdr.putInt(356, h); hdr.putInt(364, 8);
            hdr.putInt(368, isColor ? 24 : 8); hdr.putInt(372, isColor ? (w * 3) : w);
            hdr.putInt(380, isColor ? 19 : 18); hdr.putInt(384, pages.size()); hdr.putInt(388, 1); hdr.putInt(392, 1);
            out.write(hdr.array());

            int[] row = new int[w];
            byte[] line = new byte[isColor ? (w * 3) : w];
            for (int y = 0; y < h; y++) {
                bmp.getPixels(row, 0, w, 0, y, w, 1);
                if (isColor) {
                    for (int x = 0; x < w; x++) {
                        int px = row[x]; line[x * 3] = (byte)((px >> 16) & 0xFF); line[x * 3 + 1] = (byte)((px >> 8) & 0xFF); line[x * 3 + 2] = (byte)(px & 0xFF);
                    }
                } else {
                    for (int x = 0; x < w; x++) {
                        int px = row[x]; line[x] = (byte)((((px >> 16) & 0xFF) * 77 + ((px >> 8) & 0xFF) * 150 + (px & 0xFF) * 29) >> 8);
                    }
                }
                out.write(0x00);
                int pos = 0;
                while (pos < line.length) {
                    int chunk = Math.min(126, line.length - pos);
                    out.write((byte)(chunk - 1)); out.write(line, pos, chunk); pos += chunk;
                }
            }
        }
        return out.toByteArray();
    }

    private void sendIpp(String ip, byte[] pwg, boolean isColor, int qual) throws Exception {
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
        ipp.write(0x03); ipp.write(pwg);

        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":631/ipp/print").openConnection();
        c.setConnectTimeout(6000); c.setReadTimeout(25000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/ipp");
        try (OutputStream os = c.getOutputStream()) { os.write(ipp.toByteArray()); }
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
        UsbInterface intf = usbPrinter.getInterface(0); UsbEndpoint epOut = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) epOut = ep;
        }
        if (epOut == null) throw new Exception("USB endpoint missing!");
        UsbDeviceConnection conn = um.openDevice(usbPrinter);
        if (conn == null || !conn.claimInterface(intf, true)) throw new Exception("Cannot open USB printer!");
        int off = 0;
        while (off < data.length) {
            int len = Math.min(16384, data.length - off);
            int sent = conn.bulkTransfer(epOut, Arrays.copyOfRange(data, off, off + len), len, 10000);
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

    private void openInkAndDashboard() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView inkTitle = new TextView(this); inkTitle.setText("HP Smart Tank 589 Live Ink Levels (Checking...)");
        inkTitle.setTypeface(null, Typeface.BOLD); inkTitle.setTextColor(Color.parseColor("#0F172A")); box.addView(inkTitle);

        LinearLayout barsBox = new LinearLayout(this); barsBox.setOrientation(LinearLayout.VERTICAL); barsBox.setPadding(0, dp(6), 0, dp(10));
        box.addView(barsBox);

        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + printerIp + "/DevMgmt/ConsumableConfigDyn.xml").openConnection();
                c.setConnectTimeout(4000); c.setReadTimeout(5000);
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                Matcher mLabel = Pattern.compile("<dd:ConsumableLabelCode>([^<]+)</dd:ConsumableLabelCode>").matcher(sb.toString());
                Matcher mPct = Pattern.compile("<dd:ConsumablePercentageLevelRemaining>(\\d+)</dd:ConsumablePercentageLevelRemaining>").matcher(sb.toString());
                List<String> labels = new ArrayList<>(); List<Integer> pcts = new ArrayList<>();
                while (mLabel.find() && mPct.find()) { labels.add(mLabel.group(1)); pcts.add(Integer.parseInt(mPct.group(1))); }
                uiHandler.post(() -> {
                    inkTitle.setText(labels.isEmpty() ? "Live Ink % unavailable — Check EWS below" : "HP Smart Tank 589 Live Ink Tanks:");
                    for (int i = 0; i < labels.size(); i++) {
                        String code = labels.get(i); int pct = pcts.get(i);
                        int col = code.contains("C") ? Color.parseColor("#06B6D4") : code.contains("M") ? Color.parseColor("#EC4899") : code.contains("Y") ? Color.parseColor("#EAB308") : Color.parseColor("#1E293B");
                        TextView t = new TextView(this); t.setText(code + " Tank: " + pct + "%"); t.setTextSize(12); t.setTextColor(col);
                        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                        pb.setMax(100); pb.setProgress(pct); pb.setProgressTintList(ColorStateList.valueOf(col));
                        barsBox.addView(t); barsBox.addView(pb);
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> inkTitle.setText("Connect printer Wi-Fi to read live Ink %"));
            }
        }).start();

        WebView wv = new WebView(this); wv.getSettings().setJavaScriptEnabled(true); wv.setWebViewClient(new WebViewClient());
        wv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280))); wv.loadUrl("http://" + printerIp);
        box.addView(wv);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Ink Levels & EWS").setView(box).setPositiveButton("Close", null).show();
    }

    private void openWebDialog(String url) {
        WebView wv = new WebView(this); wv.getSettings().setJavaScriptEnabled(true); wv.setWebViewClient(new WebViewClient()); wv.loadUrl(url);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("HP WebScan").setView(wv).setPositiveButton("Close", null).show();
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
// END OF FILE
