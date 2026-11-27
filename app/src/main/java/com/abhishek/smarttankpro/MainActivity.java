package com.abhishek.smarttankpro;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.*;
import android.print.*;
import android.provider.MediaStore;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.*;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private String connMode = "Wi-Fi", printerIp = "192.168.1.100";
    private int colorMode = 0; // 0=Color, 1=B&W, 2=Sharp B&W
    private LinearLayout rootBox;
    private TextView statusTxt, pageTxt, badgeTxt;
    private ImageView previewImg;
    private final Button[] modeBtns = new Button[3], colorBtns = new Button[3];
    private Spinner sizeSp, qualSp, scaleSp, marginSp, duplexSp, multiSp, rangeSp;
    private CheckBox revCheck;
    private ParcelFileDescriptor pdfFd;
    private PdfRenderer pdfRen;
    private int curPage = 0, totalPages = 0;
    private Bitmap rawBmp, idFront, idBack;
    private boolean pickFront = false, pickBack = false;

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) loadUri(uri); });

    private final ActivityResultLauncher<Intent> camLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == RESULT_OK && res.getData() != null && res.getData().getExtras() != null) {
                    Bitmap b = (Bitmap) res.getData().getExtras().get("data");
                    if (b != null) { closePdf(); totalPages = 1; rawBmp = b; setColor(2); pageTxt.setText("Camera Scan Ready"); }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        checkUsb();
    }

    @Override
    protected void onDestroy() { super.onDestroy(); closePdf(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable bg(int fill, int stroke, int sw, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (sw > 0) g.setStroke(sw, stroke);
        g.setCornerRadius(dp(r));
        return g;
    }

    private GradientDrawable grad(int c1, int c2, float r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(dp(r));
        return g;
    }

    private void bounce(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start();
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            return false;
        });
    }

    private void closePdf() {
        try { if (pdfRen != null) { pdfRen.close(); pdfRen = null; } if (pdfFd != null) { pdfFd.close(); pdfFd = null; } } catch (Exception ignored) {}
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#EEF2F6"));
        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        rootBox.setPadding(dp(14), dp(14), dp(14), dp(36));
        sv.addView(rootBox);

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.VERTICAL);
        hdr.setBackground(grad(Color.parseColor("#0077C8"), Color.parseColor("#004B87"), 20));
        hdr.setPadding(dp(18), dp(18), dp(18), dp(18));
        hdr.setElevation(dp(6));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView ttl = new TextView(this);
        ttl.setText("HP Smart Tank 589 Pro");
        ttl.setTextSize(20);
        ttl.setTypeface(null, Typeface.BOLD);
        ttl.setTextColor(Color.WHITE);
        ttl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(ttl);

        badgeTxt = new TextView(this);
        badgeTxt.setText("● Wi-Fi");
        badgeTxt.setTextSize(12);
        badgeTxt.setTypeface(null, Typeface.BOLD);
        badgeTxt.setTextColor(Color.parseColor("#004B87"));
        badgeTxt.setBackground(bg(Color.parseColor("#E0F2FE"), 0, 0, 20));
        badgeTxt.setPadding(dp(12), dp(5), dp(12), dp(5));
        top.addView(badgeTxt);
        hdr.addView(top);

        statusTxt = new TextView(this);
        statusTxt.setText("Ready — Select a PDF or Photo to begin");
        statusTxt.setTextSize(13);
        statusTxt.setTextColor(Color.parseColor("#E0F2FE"));
        statusTxt.setPadding(0, dp(8), 0, dp(14));
        hdr.addView(statusTxt);

        LinearLayout mBar = new LinearLayout(this);
        mBar.setBackground(bg(Color.parseColor("#003B6B"), 0, 0, 14));
        mBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] modes = {"Wi-Fi", "Wi-Fi Direct", "USB OTG"};
        for (int i = 0; i < 3; i++) {
            final String m = modes[i];
            Button b = new Button(this);
            b.setText(m); b.setAllCaps(false); b.setTextSize(13); b.setTypeface(null, Typeface.BOLD); b.setStateListAnimator(null);
            b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1f));
            bounce(b); b.setOnClickListener(v -> setMode(m));
            modeBtns[i] = b; mBar.addView(b);
        }
        hdr.addView(mBar);
        rootBox.addView(hdr);
        refreshModeUi();

        LinearLayout act = card();
        LinearLayout r1 = new LinearLayout(this);
        r1.addView(tile("📄 Open PDF / Photo", "#EFF6FF", "#1D4ED8", v -> { pickFront = pickBack = false; picker.launch(new String[]{"application/pdf", "image/*"}); }));
        gap(r1, 8);
        r1.addView(tile("📷 Camera Scan", "#F0FDF4", "#15803D", v -> camLauncher.launch(new Intent(MediaStore.ACTION_IMAGE_CAPTURE))));
        act.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setPadding(0, dp(8), 0, 0);
        r2.addView(tile("🪪 ID Card Extractor", "#FEF3C7", "#B45309", v -> idDialog()));
        gap(r2, 8);
        r2.addView(tile("💧 Ink & Dashboard", "#F3E8FF", "#6D28D9", v -> openEws()));
        act.addView(r2);
        rootBox.addView(act);

        LinearLayout cCard = card();
        cCard.addView(secTitle("🎨 Color Mode (Live Preview & Print)"));
        LinearLayout cBar = new LinearLayout(this);
        cBar.setBackground(bg(Color.parseColor("#F1F5F9"), Color.parseColor("#CBD5E1"), dp(1), 14));
        cBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] cNames = {"🌈 Full Color", "⚫ Black & White", "📄 Sharp B&W"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button cb = new Button(this);
            cb.setText(cNames[i]); cb.setAllCaps(false); cb.setTextSize(12); cb.setTypeface(null, Typeface.BOLD); cb.setStateListAnimator(null);
            cb.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
            bounce(cb); cb.setOnClickListener(v -> setColor(idx));
            colorBtns[i] = cb; cBar.addView(cb);
        }
        cCard.addView(cBar);
        rootBox.addView(cCard);
        refreshColorUi();
        LinearLayout pCard = card();
        pageTxt = new TextView(this);
        pageTxt.setText("No file loaded — Tap 'Open PDF / Photo' above");
        pageTxt.setTextSize(13); pageTxt.setTypeface(null, Typeface.BOLD); pageTxt.setTextColor(Color.parseColor("#0F172A"));
        pageTxt.setPadding(0, 0, 0, dp(10));
        pCard.addView(pageTxt);

        previewImg = new ImageView(this);
        previewImg.setMinimumHeight(dp(240)); previewImg.setMaxHeight(dp(340)); previewImg.setAdjustViewBounds(true);
        previewImg.setBackground(bg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), dp(1), 12));
        previewImg.setPadding(dp(8), dp(8), dp(8), dp(8));
        pCard.addView(previewImg);

        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(0, dp(10), 0, 0);
        nav.addView(tile("◀ Prev Page", "#F1F5F9", "#0F172A", v -> showPdfPage(curPage - 1)));
        gap(nav, 8);
        nav.addView(tile("Next Page ▶", "#F1F5F9", "#0F172A", v -> showPdfPage(curPage + 1)));
        pCard.addView(nav);
        rootBox.addView(pCard);

        LinearLayout set = card();
        set.addView(secTitle("⚙️ Paper & Print Settings"));
        set.addView(lbl("Paper Size:"));
        sizeSp = spin(new String[]{"A4 (210 x 297 mm)", "Custom Glossy (25 x 21 cm)", "Ruled/Punched Sheet (22 x 28 cm)", "4 x 6 inch Photo", "5 x 7 inch Photo", "A5 (148 x 210 mm)", "Legal (8.5 x 14 in)"});
        set.addView(sizeSp);

        set.addView(lbl("Paper Type & Quality:"));
        qualSp = spin(new String[]{"Plain Paper - Normal", "Plain Paper - Draft (Ink Saver)", "Glossy Photo Paper (180-300 GSM - Best)", "Matte Brochure Paper", "Heavy Cardstock (200 GSM)"});
        set.addView(qualSp);

        set.addView(lbl("Page Scaling:"));
        scaleSp = spin(new String[]{"Scale to fit", "Fill page (Borderless)", "Actual size (100%)"});
        set.addView(scaleSp);

        set.addView(lbl("Margins:"));
        marginSp = spin(new String[]{"By printable area (3mm)", "Borderless (0mm)", "Punched Left Margin (18mm)"});
        set.addView(marginSp);

        set.addView(lbl("Double-Sided Printing (Manual Duplex):"));
        duplexSp = spin(new String[]{"One side only", "Manually - Step 1: Odd Pages (1,3,5...)", "Manually - Step 2: Even Pages (Reverse)"});
        set.addView(duplexSp);

        set.addView(lbl("Multi-Page Layout:"));
        multiSp = spin(new String[]{"Singly (1 page/sheet)", "Multiple: 2-in-1", "Multiple: 4-in-1", "Poster (2x2 A4 Split)"});
        set.addView(multiSp);

        set.addView(lbl("Pages & Order:"));
        rangeSp = spin(new String[]{"All Pages", "Current Page Only", "Odd Pages Only", "Even Pages Only"});
        set.addView(rangeSp);

        revCheck = new CheckBox(this);
        revCheck.setText("Print in reverse order (Last page first)");
        revCheck.setTextColor(Color.parseColor("#0F172A"));
        revCheck.setTextSize(14);
        revCheck.setPadding(dp(4), dp(12), dp(4), dp(4));
        revCheck.setButtonTintList(ColorStateList.valueOf(Color.parseColor("#0077C8")));
        set.addView(revCheck);
        rootBox.addView(set);

        Button pBtn = new Button(this);
        pBtn.setText("🖨️  PRINT NOW"); pBtn.setAllCaps(false); pBtn.setTextSize(17); pBtn.setTypeface(null, Typeface.BOLD);
        pBtn.setTextColor(Color.WHITE);
        pBtn.setBackground(grad(Color.parseColor("#0077C8"), Color.parseColor("#005A9E"), 16));
        pBtn.setElevation(dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.setMargins(0, dp(12), 0, dp(12));
        pBtn.setLayoutParams(lp);
        bounce(pBtn); pBtn.setOnClickListener(v -> runPrint());
        rootBox.addView(pBtn);
        setContentView(sv);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(bg(Color.WHITE, Color.parseColor("#E2E8F0"), dp(1), 18));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        c.setLayoutParams(lp);
        return c;
    }

    private Button tile(String t, String bgHex, String fgHex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setTextSize(13); b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.parseColor(fgHex)); b.setBackground(bg(Color.parseColor(bgHex), 0, 0, 12));
        b.setStateListAnimator(null); b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        bounce(b); b.setOnClickListener(l);
        return b;
    }

    private void gap(LinearLayout r, int w) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1)); r.addView(v); }

    private TextView secTitle(String t) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(15); tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(Color.parseColor("#0F172A"));
        tv.setPadding(0, 0, 0, dp(10));
        return tv;
    }

    private TextView lbl(String t) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(13); tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(Color.parseColor("#334155"));
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private Spinner spin(String[] items) {
        Spinner s = new Spinner(this);
        s.setBackground(bg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), dp(1), 12));
        s.setPadding(dp(10), dp(6), dp(10), dp(6));
        s.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int p, View cv, ViewGroup pr) {
                TextView tv = (TextView) super.getView(p, cv, pr);
                tv.setTextColor(Color.parseColor("#0F172A")); tv.setTextSize(14); tv.setTypeface(null, Typeface.BOLD);
                return tv;
            }
            @Override public View getDropDownView(int p, View cv, ViewGroup pr) {
                TextView tv = (TextView) super.getDropDownView(p, cv, pr);
                tv.setTextColor(Color.parseColor("#0F172A")); tv.setBackgroundColor(Color.WHITE);
                tv.setPadding(dp(16), dp(14), dp(16), dp(14)); tv.setTextSize(14);
                return tv;
            }
        });
        return s;
    }

    private void setMode(String m) {
        TransitionManager.beginDelayedTransition(rootBox, new AutoTransition().setDuration(180));
        connMode = m; badgeTxt.setText("● " + m); refreshModeUi();
        if (m.equals("Wi-Fi Direct")) { printerIp = "192.168.223.1"; statusTxt.setText("Wi-Fi Direct: Connect Wi-Fi to HP Smart Tank 580 (192.168.223.1)"); }
        else if (m.equals("USB OTG")) checkUsb();
        else statusTxt.setText("Home Wi-Fi: Keep phone & printer on same Wi-Fi network");
    }

    private void refreshModeUi() {
        String[] m = {"Wi-Fi", "Wi-Fi Direct", "USB OTG"};
        for (int i = 0; i < 3; i++) {
            boolean act = m[i].equals(connMode);
            modeBtns[i].setBackground(bg(act ? Color.WHITE : Color.TRANSPARENT, 0, 0, 10));
            modeBtns[i].setTextColor(act ? Color.parseColor("#004B87") : Color.parseColor("#BAE6FD"));
        }
    }

    private void setColor(int idx) {
        TransitionManager.beginDelayedTransition(rootBox, new AutoTransition().setDuration(180));
        colorMode = idx; refreshColorUi();
        if (rawBmp != null) { previewImg.setAlpha(0.35f); previewImg.setImageBitmap(filterBmp(rawBmp)); previewImg.animate().alpha(1f).setDuration(200).start(); }
    }

    private void refreshColorUi() {
        for (int i = 0; i < 3; i++) {
            boolean act = (i == colorMode);
            colorBtns[i].setBackground(bg(act ? (i == 0 ? Color.parseColor("#0077C8") : Color.parseColor("#1E293B")) : Color.TRANSPARENT, 0, 0, 10));
            colorBtns[i].setTextColor(act ? Color.WHITE : Color.parseColor("#334155"));
        }
    }

    private Bitmap filterBmp(Bitmap src) {
        if (src == null || colorMode == 0) return src;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out); Paint p = new Paint(); ColorMatrix cm = new ColorMatrix(); cm.setSaturation(0);
        if (colorMode == 2) {
            float k = 1.65f, t = (-0.5f * k + 0.5f) * 255f;
            cm.postConcat(new ColorMatrix(new float[]{k,0,0,0,t, 0,k,0,0,t, 0,0,k,0,t, 0,0,0,1,0}));
        }
        p.setColorFilter(new ColorMatrixColorFilter(cm)); c.drawBitmap(src, 0, 0, p);
        return out;
    }

    private void checkUsb() {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (um != null) {
            for (UsbDevice d : um.getDeviceList().values()) {
                if (d.getVendorId() == 1008 || d.getDeviceClass() == 7) { statusTxt.setText("USB OTG Connected: " + d.getProductName() + " (Ready)"); return; }
            }
        }
        if (connMode.equals("USB OTG")) statusTxt.setText("USB OTG: Connect USB-B to Type-C cable to HP Smart Tank 589");
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
                if (pickFront || pickBack) cropIdDialog();
            } else {
                Bitmap b; try (InputStream is = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(is); }
                if (b == null) return;
                if (pickFront) { idFront = b; pickFront = false; buildIdSheet(); }
                else if (pickBack) { idBack = b; pickBack = false; buildIdSheet(); }
                else { closePdf(); totalPages = 1; rawBmp = b; previewImg.setImageBitmap(filterBmp(b)); pageTxt.setText("Photo Loaded (HD Preview)"); }
            }
        } catch (Exception e) { Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void showPdfPage(int idx) {
        if (pdfRen == null || idx < 0 || idx >= totalPages) return;
        curPage = idx;
        PdfRenderer.Page p = pdfRen.openPage(idx);
        Bitmap b = Bitmap.createBitmap((int)(p.getWidth() * 2.5f), (int)(p.getHeight() * 2.5f), Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); p.close();
        rawBmp = b; previewImg.setImageBitmap(filterBmp(b));
        pageTxt.setText("PDF HD Preview — Page " + (idx + 1) + " of " + totalPages);
    }

    private void idDialog() {
        String[] opts = {"1. Extract Front Side from PDF / Photo", "2. Extract Back Side from PDF / Photo", "3. Generate 2-in-1 ID Sheet"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("PDF & Photo ID Extractor")
                .setItems(opts, (d, w) -> {
                    if (w == 0) { pickFront = true; pickBack = false; picker.launch(new String[]{"application/pdf", "image/*"}); }
                    else if (w == 1) { pickBack = true; pickFront = false; picker.launch(new String[]{"application/pdf", "image/*"}); }
                    else buildIdSheet();
                }).show();
    }

    private void cropIdDialog() {
        if (rawBmp == null) return;
        String[] pr = {"Top Half (e-Aadhaar / PAN Top)", "Bottom Half (e-Aadhaar Cutout)", "Center Card Area", "Full Page As-Is"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Crop ID Area from Page " + (curPage + 1))
                .setItems(pr, (d, w) -> {
                    int bw = rawBmp.getWidth(), bh = rawBmp.getHeight();
                    Bitmap cr = (w == 0) ? Bitmap.createBitmap(rawBmp, 0, 0, bw, bh / 2) : (w == 1) ? Bitmap.createBitmap(rawBmp, 0, bh / 2, bw, bh / 2) : (w == 2) ? Bitmap.createBitmap(rawBmp, bw / 8, bh / 4, (bw * 3) / 4, bh / 2) : rawBmp;
                    if (pickFront) { idFront = cr; pickFront = false; } else if (pickBack) { idBack = cr; pickBack = false; }
                    buildIdSheet();
                }).show();
    }

    private void buildIdSheet() {
        Bitmap sh = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sh); c.drawColor(Color.WHITE);
        Paint b = new Paint(); b.setStyle(Paint.Style.STROKE); b.setColor(Color.LTGRAY); b.setStrokeWidth(3);
        Rect r1 = new Rect(180, 160, 1060, 720), r2 = new Rect(180, 820, 1060, 1380);
        c.drawRect(r1, b); c.drawRect(r2, b);
        if (idFront != null) drawFit(c, idFront, r1, 0);
        if (idBack != null) drawFit(c, idBack, r2, 0);
        closePdf(); totalPages = 1; rawBmp = sh; previewImg.setImageBitmap(filterBmp(sh));
        pageTxt.setText("2-in-1 ID Card Sheet Ready");
    }

    private void runPrint() {
        if (rawBmp == null && pdfRen == null) { Toast.makeText(this, "Select a PDF or Photo first!", Toast.LENGTH_SHORT).show(); return; }
        int dup = duplexSp.getSelectedItemPosition();
        if (dup == 1) new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Manual Duplex — Step 1 (Odd)")
                .setMessage("Prints Odd pages (1,3,5...). Flip stack face-down with top edge in first, then run Step 2 (Even).").setPositiveButton("Start Odd", (d, w) -> startPrint(true, false)).show();
        else startPrint(false, dup == 2);
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

    private void startPrint(boolean odd, boolean even) {
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (pm == null) return;
        PrintAttributes.Builder at = new PrintAttributes.Builder();
        at.setColorMode(colorMode == 0 ? PrintAttributes.COLOR_MODE_COLOR : PrintAttributes.COLOR_MODE_MONOCHROME);
        int sz = sizeSp.getSelectedItemPosition();
        if (sz == 1) at.setMediaSize(new PrintAttributes.MediaSize("C25X21", "25x21cm Glossy", 9842, 8267));
        else if (sz == 2) at.setMediaSize(new PrintAttributes.MediaSize("C22X28", "22x28cm Ruled", 8661, 11023));
        else if (sz == 3) at.setMediaSize(PrintAttributes.MediaSize.NA_INDEX_4X6);
        else if (sz == 4) at.setMediaSize(new PrintAttributes.MediaSize("P5X7", "5x7 in", 5000, 7000));
        else if (sz == 5) at.setMediaSize(PrintAttributes.MediaSize.ISO_A5);
        else if (sz == 6) at.setMediaSize(PrintAttributes.MediaSize.NA_LEGAL);
        else at.setMediaSize(PrintAttributes.MediaSize.ISO_A4);

        int q = qualSp.getSelectedItemPosition();
        at.setResolution(q == 2 ? new PrintAttributes.Resolution("BEST", "Best", 1200, 1200) : q == 1 ? new PrintAttributes.Resolution("DRAFT", "Draft", 300, 300) : new PrintAttributes.Resolution("NORM", "Normal", 600, 600));
        final int multi = multiSp.getSelectedItemPosition(), sc = scaleSp.getSelectedItemPosition(), mg = marginSp.getSelectedItemPosition();

        pm.print("SmartTank589_Job", new PrintDocumentAdapter() {
            final List<Integer> list = new ArrayList<>();
            @Override public void onLayout(PrintAttributes oA, PrintAttributes nA, CancellationSignal cs, LayoutResultCallback cb, Bundle ex) {
                list.clear(); int cnt = (pdfRen != null) ? totalPages : 1, rng = rangeSp.getSelectedItemPosition();
                for (int i = 0; i < cnt; i++) {
                    int pn = i + 1;
                    if (odd || rng == 2) { if (pn % 2 == 1) list.add(i); }
                    else if (even || rng == 3) { if (pn % 2 == 0) list.add(i); }
                    else if (rng == 1) { if (i == curPage) list.add(i); }
                    else list.add(i);
                }
                if (revCheck.isChecked() || even) Collections.reverse(list);
                int sh = (multi == 1) ? (int) Math.ceil(list.size() / 2.0) : (multi == 2) ? (int) Math.ceil(list.size() / 4.0) : (multi == 3) ? 4 : list.size();
                cb.onLayoutFinished(new PrintDocumentInfo.Builder("SmartTank589.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(Math.max(1, sh)).build(), true);
            }
            @Override public void onWrite(PageRange[] r, ParcelFileDescriptor dest, CancellationSignal cs, WriteResultCallback cb) {
                PdfDocument doc = new PdfDocument();
                int lm = (mg == 2) ? 50 : (mg == 1 ? 0 : 12), pd = (mg == 1) ? 0 : 12, pw = 595, ph = 842;
                if (multi == 3 && !list.isEmpty()) {
                    Bitmap src = pageBmp(list.get(0));
                    if (src != null) {
                        int hw = src.getWidth() / 2, hh = src.getHeight() / 2;
                        Rect[] t = {new Rect(0,0,hw,hh), new Rect(hw,0,src.getWidth(),hh), new Rect(0,hh,hw,src.getHeight()), new Rect(hw,hh,src.getWidth(),src.getHeight())};
                        for (int i = 0; i < 4; i++) { PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, i + 1).create()); pg.getCanvas().drawBitmap(src, t[i], new Rect(lm, pd, pw - pd, ph - pd), null); doc.finishPage(pg); }
                    }
                } else if (multi == 1) {
                    for (int i = 0, s = 1; i < list.size(); i += 2) {
                        PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, s++).create());
                        drawFit(pg.getCanvas(), pageBmp(list.get(i)), new Rect(lm, pd, pw - pd, ph / 2 - 6), sc);
                        if (i + 1 < list.size()) drawFit(pg.getCanvas(), pageBmp(list.get(i + 1)), new Rect(lm, ph / 2 + 6, pw - pd, ph - pd), sc);
                        doc.finishPage(pg);
                    }
                } else if (multi == 2) {
                    for (int i = 0, s = 1; i < list.size(); i += 4) {
                        PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, s++).create());
                        int mx = (lm + pw - pd) / 2, my = ph / 2;
                        Rect[] c = {new Rect(lm, pd, mx - 4, my - 4), new Rect(mx + 4, pd, pw - pd, my - 4), new Rect(lm, my + 4, mx - 4, ph - pd), new Rect(mx + 4, my + 4, pw - pd, ph - pd)};
                        for (int k = 0; k < 4 && (i + k) < list.size(); k++) drawFit(pg.getCanvas(), pageBmp(list.get(i + k)), c[k], sc);
                        doc.finishPage(pg);
                    }
                } else {
                    for (int i = 0; i < list.size(); i++) {
                        PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, i + 1).create());
                        drawFit(pg.getCanvas(), pageBmp(list.get(i)), new Rect(lm, pd, pw - pd, ph - pd), sc);
                        doc.finishPage(pg);
                    }
                }
                try (FileOutputStream fos = new FileOutputStream(dest.getFileDescriptor())) { doc.writeTo(fos); cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES}); }
                catch (Exception e) { cb.onWriteFailed(e.getMessage()); } finally { doc.close(); }
            }
        }, at.build());
    }

    private void openEws() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout bar = new LinearLayout(this);
        EditText ip = new EditText(this); ip.setText(printerIp); ip.setTextColor(Color.parseColor("#0F172A")); ip.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button go = new Button(this); go.setText("Open"); bar.addView(ip); bar.addView(go); box.addView(bar);
        WebView wv = new WebView(this); wv.getSettings().setJavaScriptEnabled(true); wv.setWebViewClient(new WebViewClient());
        wv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))); wv.loadUrl("http://" + printerIp); box.addView(wv);
        go.setOnClickListener(v -> { printerIp = ip.getText().toString().trim(); wv.loadUrl("http://" + printerIp); });
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("HP Smart Tank 589 Dashboard").setView(box).setPositiveButton("Close", null).show();
    }
}
// END OF FILE
 
