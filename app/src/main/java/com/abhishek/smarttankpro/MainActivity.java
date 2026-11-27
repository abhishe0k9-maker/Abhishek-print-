package com.abhishek.smarttankpro;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private String connectionMode = "Wi-Fi";
    private String printerIp = "192.168.1.100";
    private int colorModeSelection = 0; // 0 = Full Color, 1 = Black & White, 2 = Sharp B&W Photocopy

    private LinearLayout rootContainer;
    private TextView statusBanner, pageCounterText, modeBadgeText;
    private ImageView hdPreviewImage;
    private Button[] modeButtons = new Button[3];
    private Button[] colorButtons = new Button[3];

    private Spinner paperSizeSpinner, mediaQualitySpinner, scalingSpinner, marginSpinner, duplexSpinner, multiPageSpinner, pageRangeSpinner;
    private CheckBox reverseOrderCheck;

    private ParcelFileDescriptor pdfDescriptor;
    private PdfRenderer pdfRenderer;
    private int currentPageIndex = 0;
    private int totalPdfPages = 0;
    private Bitmap rawPreviewBitmap;
    private Bitmap idFrontBitmap, idBackBitmap;
    private boolean pickingPdfForIdFront = false;
    private boolean pickingPdfForIdBack = false;

    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) handleSelectedUri(uri);
            });

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getExtras() != null) {
                    Bitmap photo = (Bitmap) result.getData().getExtras().get("data");
                    if (photo != null) {
                        closePdf();
                        totalPdfPages = 1;
                        rawPreviewBitmap = photo;
                        setColorMode(2); // Auto-switch to Sharp B&W for camera scan
                        pageCounterText.setText("Camera Scan Ready (Tap Color/B&W above to switch)");
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildModernAnimatedUi();
        checkUsbPrinter();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closePdf();
    }

    private int dp(float val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable makeRoundBg(int fillColor, int strokeColor, int strokeWidthPx, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        if (strokeWidthPx > 0) gd.setStroke(strokeWidthPx, strokeColor);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private GradientDrawable makeGradientBg(int startColor, int endColor, float radiusDp) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{startColor, endColor});
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private void attachBounce(View view) {
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
            return false;
        });
    }

    private void closePdf() {
        try {
            if (pdfRenderer != null) {
                pdfRenderer.close();
                pdfRenderer = null;
            }
            if (pdfDescriptor != null) {
                pdfDescriptor.close();
                pdfDescriptor = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void buildModernAnimatedUi() {
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setBackgroundColor(Color.parseColor("#EEF2F6"));

        rootContainer = new LinearLayout(this);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        rootContainer.setPadding(dp(14), dp(14), dp(14), dp(36));
        rootScroll.addView(rootContainer);

        // 1. HERO HEADER CARD (HP Blue Gradient)
        LinearLayout headerCard = new LinearLayout(this);
        headerCard.setOrientation(LinearLayout.VERTICAL);
        headerCard.setBackground(makeGradientBg(Color.parseColor("#0077C8"), Color.parseColor("#004B87"), 20));
        headerCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        headerCard.setElevation(dp(6));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("HP Smart Tank 589");
        title.setTextSize(21);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);

        modeBadgeText = new TextView(this);
        modeBadgeText.setText("● Wi-Fi");
        modeBadgeText.setTextSize(12);
        modeBadgeText.setTypeface(null, Typeface.BOLD);
        modeBadgeText.setTextColor(Color.parseColor("#004B87"));
        modeBadgeText.setBackground(makeRoundBg(Color.parseColor("#E0F2FE"), 0, 0, 20));
        modeBadgeText.setPadding(dp(12), dp(5), dp(12), dp(5));
        titleRow.addView(modeBadgeText);
        headerCard.addView(titleRow);

        statusBanner = new TextView(this);
        statusBanner.setText("Ready — Select a PDF or Photo to begin");
        statusBanner.setTextSize(13);
        statusBanner.setTextColor(Color.parseColor("#E0F2FE"));
        statusBanner.setPadding(0, dp(8), 0, dp(14));
        headerCard.addView(statusBanner);

        // Animated Tri-Mode Pill Bar
        LinearLayout modePillBar = new LinearLayout(this);
        modePillBar.setOrientation(LinearLayout.HORIZONTAL);
        modePillBar.setBackground(makeRoundBg(Color.parseColor("#003B6B"), 0, 0, 14));
        modePillBar.setPadding(dp(4), dp(4), dp(4), dp(4));

        String[] modes = {"Wi-Fi", "Wi-Fi Direct", "USB OTG"};
        for (int i = 0; i < modes.length; i++) {
            final String m = modes[i];
            Button b = new Button(this);
            b.setText(m);
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setTypeface(null, Typeface.BOLD);
            b.setStateListAnimator(null);
            b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1f));
            attachBounce(b);
            b.setOnClickListener(v -> switchConnectionMode(m));
            modeButtons[i] = b;
            modePillBar.addView(b);
        }
        headerCard.addView(modePillBar);
        rootContainer.addView(headerCard);
        updateModeButtonsUi();

        // 2. QUICK ACTION GRID CARD
        LinearLayout actionCard = makeWhiteCard();
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(makeTileBtn("📄 Open PDF / Photo", "#EFF6FF", "#1D4ED8", v -> {
            pickingPdfForIdFront = false;
            pickingPdfForIdBack = false;
            filePicker.launch(new String[]{"application/pdf", "image/*"});
        }));
        addSpace(row1, 8);
        row1.addView(makeTileBtn("📷 Camera Scan", "#F0FDF4", "#15803D", v -> {
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cameraLauncher.launch(cam);
        }));
        actionCard.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        row2.addView(makeTileBtn("🪪 ID Card Extractor", "#FEF3C7", "#B45309", v -> showIdCardDialog()));
        addSpace(row2, 8);
        row2.addView(makeTileBtn("💧 Ink & Dashboard", "#F3E8FF", "#6D28D9", v -> openPrinterDashboard()));
        actionCard.addView(row2);
        rootContainer.addView(actionCard);

        // 3. COLOR vs BLACK & WHITE LIVE SWITCHER CARD
        LinearLayout colorCard = makeWhiteCard();
        colorCard.addView(makeSectionTitle("🎨 Color Mode (Live Preview & Print)"));

        LinearLayout colorPillBar = new LinearLayout(this);
        colorPillBar.setOrientation(LinearLayout.HORIZONTAL);
        colorPillBar.setBackground(makeRoundBg(Color.parseColor("#F1F5F9"), Color.parseColor("#CBD5E1"), dp(1), 14));
        colorPillBar.setPadding(dp(4), dp(4), dp(4), dp(4));

        String[] colorLabels = {"🌈 Full Color", "⚫ Black & White", "📄 Sharp B&W"};
        for (int i = 0; i < colorLabels.length; i++) {
            final int idx = i;
            Button cb = new Button(this);
            cb.setText(colorLabels[i]);
            cb.setAllCaps(false);
            cb.setTextSize(12);
            cb.setTypeface(null, Typeface.BOLD);
            cb.setStateListAnimator(null);
            cb.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
            attachBounce(cb);
            cb.setOnClickListener(v -> setColorMode(idx));
            colorButtons[i] = cb;
            colorPillBar.addView(cb);
        }
        colorCard.addView(colorPillBar);
        rootContainer.addView(colorCard);
        updateColorButtonsUi();

        // 4. HD LIVE PREVIEW CARD
        LinearLayout previewCard = makeWhiteCard();
        pageCounterText = new TextView(this);
        pageCounterText.setText("No file loaded — Tap 'Open PDF / Photo' above");
        pageCounterText.setTextSize(13);
        pageCounterText.setTypeface(null, Typeface.BOLD);
        pageCounterText.setTextColor(Color.parseColor("#0F172A"));
        pageCounterText.setPadding(0, 0, 0, dp(10));
        previewCard.addView(pageCounterText);

        hdPreviewImage = new ImageView(this);
        hdPreviewImage.setMinimumHeight(dp(240));
        hdPreviewImage.setMaxHeight(dp(340));
        hdPreviewImage.setAdjustViewBounds(true);
        hdPreviewImage.setBackground(makeRoundBg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), dp(1), 12));
        hdPreviewImage.setPadding(dp(8), dp(8), dp(8), dp(8));
        previewCard.addView(hdPreviewImage);

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setPadding(0, dp(10), 0, 0);
        navRow.addView(makeTileBtn("◀ Prev Page", "#F1F5F9", "#0F172A", v -> renderPdfPage(currentPageIndex - 1)));
        addSpace(navRow, 8);
        navRow.addView(makeTileBtn("Next Page ▶", "#F1F5F9", "#0F172A", v -> renderPdfPage(currentPageIndex + 1)));
        previewCard.addView(navRow);
        rootContainer.addView(previewCard);

        // 5. PAPER SIZE, QUALITY & LAYOUT SETTINGS CARD
        LinearLayout settingsCard = makeWhiteCard();
        settingsCard.addView(makeSectionTitle("⚙️ Paper & Print Settings"));

        settingsCard.addView(makeFieldLabel("Paper Size:"));
        paperSizeSpinner = makeStyledSpinner(new String[]{
                "A4 (210 x 297 mm)",
                "Custom Glossy (25 x 21 cm)",
                "Ruled/Punched Sheet (22 x 28 cm)",
                "4 x 6 inch Photo",
                "5 x 7 inch Photo",
                "A5 (148 x 210 mm)",
                "Legal (8.5 x 14 in)"
        });
        settingsCard.addView(paperSizeSpinner);

        settingsCard.addView(makeFieldLabel("Paper Type & Quality:"));
        mediaQualitySpinner = makeStyledSpinner(new String[]{
                "Plain Paper - Normal",
                "Plain Paper - Draft (Ink Saver)",
                "Glossy Photo Paper (180-300 GSM - Best)",
                "Matte Brochure Paper",
                "Heavy Cardstock (200 GSM)"
        });
        settingsCard.addView(mediaQualitySpinner);

        settingsCard.addView(makeFieldLabel("Page Scaling:"));
        scalingSpinner = makeStyledSpinner(new String[]{"Scale to fit", "Fill page (Borderless)", "Actual size (100%)"});
        settingsCard.addView(scalingSpinner);

        settingsCard.addView(makeFieldLabel("Margins:"));
        marginSpinner = makeStyledSpinner(new String[]{
                "By printable area (3mm)",
                "Borderless (0mm)",
                "Punched Holes Left Margin (18mm)"
        });
        settingsCard.addView(marginSpinner);

        settingsCard.addView(makeFieldLabel("Double-Sided Printing (Manual Duplex):"));
        duplexSpinner = makeStyledSpinner(new String[]{
                "One side only",
                "Manually - Step 1: Print Odd Pages (1,3,5...)",
                "Manually - Step 2: Print Even Pages (Reverse)"
        });
        settingsCard.addView(duplexSpinner);

        settingsCard.addView(makeFieldLabel("Multi-Page Layout:"));
        multiPageSpinner = makeStyledSpinner(new String[]{
                "Singly (1 page per sheet)",
                "Multiple: 2-in-1 (2 pages per sheet)",
                "Multiple: 4-in-1 (4 pages per sheet)",
                "Poster (Split 1 page into 2x2 sheets)"
        });
        settingsCard.addView(multiPageSpinner);

        settingsCard.addView(makeFieldLabel("Pages & Order:"));
        pageRangeSpinner = makeStyledSpinner(new String[]{"All Pages", "Current Page Only", "Odd Pages Only", "Even Pages Only"});
        settingsCard.addView(pageRangeSpinner);

        reverseOrderCheck = new CheckBox(this);
        reverseOrderCheck.setText("Print in reverse order (Last page first)");
        reverseOrderCheck.setTextColor(Color.parseColor("#0F172A"));
        reverseOrderCheck.setTextSize(14);
        reverseOrderCheck.setPadding(dp(4), dp(12), dp(4), dp(4));
        reverseOrderCheck.setButtonTintList(ColorStateList.valueOf(Color.parseColor("#0077C8")));
        settingsCard.addView(reverseOrderCheck);

        rootContainer.addView(settingsCard);

        // 6. PRIMARY GLOWING PRINT CTA BUTTON
        Button printNowBtn = new Button(this);
        printNowBtn.setText("🖨️  PRINT NOW");
        printNowBtn.setAllCaps(false);
        printNowBtn.setTextSize(17);
        printNowBtn.setTypeface(null, Typeface.BOLD);
        printNowBtn.setTextColor(Color.WHITE);
        printNowBtn.setBackground(makeGradientBg(Color.parseColor("#0077C8"), Color.parseColor("#005A9E"), 16));
        printNowBtn.setElevation(dp(6));
        LinearLayout.LayoutParams ctaParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        ctaParams.setMargins(0, dp(12), 0, dp(12));
        printNowBtn.setLayoutParams(ctaParams);
        attachBounce(printNowBtn);
        printNowBtn.setOnClickListener(v -> executeProPrint());
        rootContainer.addView(printNowBtn);

        setContentView(rootScroll);
    }

    private LinearLayout makeWhiteCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeRoundBg(Color.WHITE, Color.parseColor("#E2E8F0"), dp(1), 18));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(lp);
        return card;
    }

    private Button makeTileBtn(String text, String bgHex, String textHex, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.parseColor(textHex));
        b.setBackground(makeRoundBg(Color.parseColor(bgHex), 0, 0, 12));
        b.setStateListAnimator(null);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        attachBounce(b);
        b.setOnClickListener(l);
        return b;
    }

    private void addSpace(LinearLayout row, int widthDp) {
        View space = new View(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1));
        row.addView(space);
    }

    private TextView makeSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.parseColor("#0F172A"));
        tv.setPadding(0, 0, 0, dp(10));
        return tv;
    }

    private TextView makeFieldLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.parseColor("#334155"));
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private Spinner makeStyledSpinner(String[] items) {
        Spinner s = new Spinner(this);
        s.setBackground(makeRoundBg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), dp(1), 12));
        s.setPadding(dp(10), dp(6), dp(10), dp(6));
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(Color.parseColor("#0F172A"));
                tv.setTextSize(14);
                tv.setTypeface(null, Typeface.BOLD);
                return tv;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setTextColor(Color.parseColor("#0F172A"));
                tv.setBackgroundColor(Color.WHITE);
                tv.setPadding(dp(16), d
