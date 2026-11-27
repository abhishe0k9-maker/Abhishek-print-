package com.abhishek.smarttankpro;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
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
    private TextView statusBanner, pageCounterText;
    private ImageView hdPreviewImage;
    private Spinner paperSizeSpinner, mediaQualitySpinner, scalingSpinner, marginSpinner, duplexSpinner, multiPageSpinner, pageRangeSpinner;
    private CheckBox reverseOrderCheck;

    private ParcelFileDescriptor pdfDescriptor;
    private PdfRenderer pdfRenderer;
    private int currentPageIndex = 0;
    private int totalPdfPages = 0;
    private Bitmap currentPreviewBitmap;
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
                        currentPreviewBitmap = applyScanEnhancement(photo);
                        hdPreviewImage.setImageBitmap(currentPreviewBitmap);
                        pageCounterText.setText("Camera Scan Ready (Enhanced B&W)");
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildProUi();
        checkUsbPrinter();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closePdf();
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

    private void buildProUi() {
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setBackgroundColor(Color.parseColor("#F1F5F9"));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(28, 28, 28, 60);
        rootScroll.addView(main);

        TextView title = new TextView(this);
        title.setText("HP Smart Tank 589 Pro");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#005A9E"));
        main.addView(title);

        statusBanner = new TextView(this);
        statusBanner.setText("Mode: Home Wi-Fi | Ready");
        statusBanner.setPadding(20, 16, 20, 16);
        statusBanner.setBackgroundColor(Color.parseColor("#DBEAFE"));
        statusBanner.setTextColor(Color.parseColor("#1E3A8A"));
        main.addView(statusBanner);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, 16, 0, 16);
        String[] modes = {"Wi-Fi", "Wi-Fi Direct", "USB OTG"};
        for (String m : modes) {
            Button b = new Button(this);
            b.setText(m);
            b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            b.setOnClickListener(v -> switchConnectionMode(m));
            modeRow.addView(b);
        }
        main.addView(modeRow);

        LinearLayout actionRow1 = new LinearLayout(this);
        actionRow1.setOrientation(LinearLayout.HORIZONTAL);
        actionRow1.addView(makeBtn("Open PDF/Photo", v -> {
            pickingPdfForIdFront = false;
            pickingPdfForIdBack = false;
            filePicker.launch(new String[]{"application/pdf", "image/*"});
        }));
        actionRow1.addView(makeBtn("Camera Scan", v -> {
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cameraLauncher.launch(cam);
        }));
        main.addView(actionRow1);

        LinearLayout actionRow2 = new LinearLayout(this);
        actionRow2.setOrientation(LinearLayout.HORIZONTAL);
        actionRow2.addView(makeBtn("PDF/Photo ID Maker", v -> showIdCardDialog()));
        actionRow2.addView(makeBtn("Ink & EWS Tools", v -> openPrinterDashboard()));
        main.addView(actionRow2);

        pageCounterText = new TextView(this);
        pageCounterText.setText("No file loaded - Select PDF or Image above");
        pageCounterText.setPadding(0, 16, 0, 8);
        pageCounterText.setTypeface(null, Typeface.BOLD);
        main.addView(pageCounterText);

        hdPreviewImage = new ImageView(this);
        hdPreviewImage.setMinimumHeight(520);
        hdPreviewImage.setMaxHeight(680);
        hdPreviewImage.setAdjustViewBounds(true);
        hdPreviewImage.setBackgroundColor(Color.WHITE);
        hdPreviewImage.setPadding(12, 12, 12, 12);
        main.addView(hdPreviewImage);

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.addView(makeBtn("Prev Page", v -> renderPdfPage(currentPageIndex - 1)));
        navRow.addView(makeBtn("Next Page", v -> renderPdfPage(currentPageIndex + 1)));
        main.addView(navRow);

        main.addView(makeLabel("1. Paper Size:"));
        paperSizeSpinner = makeSpinner(new String[]{
                "A4 (210 x 297 mm)",
                "Custom Glossy (25 x 21 cm)",
                "Ruled/Punched Sheet (22 x 28 cm)",
                "4 x 6 inch Photo",
                "5 x 7 inch Photo",
                "A5 (148 x 210 mm)",
                "Legal (8.5 x 14 in)"
        });
        main.addView(paperSizeSpinner);

        main.addView(makeLabel("2. Paper Type & Print Quality:"));
        mediaQualitySpinner = makeSpinner(new String[]{
                "Plain Paper - Normal",
                "Plain Paper - Draft (Ink Saver)",
                "Glossy Photo Paper (180-300 GSM - Best)",
                "Matte Brochure Paper",
                "Heavy Cardstock (200 GSM)"
        });
        main.addView(mediaQualitySpinner);

        main.addView(makeLabel("3. Size, Scaling & Margins:"));
        scalingSpinner = makeSpinner(new String[]{"Scale to fit", "Fill page (Borderless)", "Actual size (100%)"});
        main.addView(scalingSpinner);

        marginSpinner = makeSpinner(new String[]{
                "By printable area (3mm)",
                "Borderless (0mm)",
                "Punched Holes Left Margin (18mm)"
        });
        main.addView(marginSpinner);

        main.addView(makeLabel("4. Double-Sided Printing (Manual Duplex):"));
        duplexSpinner = makeSpinner(new String[]{
                "One side only",
                "Manually - Step 1: Print Odd Pages (1,3,5...)",
                "Manually - Step 2: Print Even Pages (Reverse)"
        });
        main.addView(duplexSpinner);

        main.addView(makeLabel("5. Multi-Page Layout:"));
        multiPageSpinner = makeSpinner(new String[]{
                "Singly (1 page per sheet)",
                "Multiple: 2-in-1 (2 pages per sheet)",
                "Multiple: 4-in-1 (4 pages per sheet)",
                "Poster (Split 1 page into 2x2 sheets)"
        });
        main.addView(multiPageSpinner);

        main.addView(makeLabel("6. Pages & Order:"));
        pageRangeSpinner = makeSpinner(new String[]{"All Pages", "Current Page Only", "Odd Pages Only", "Even Pages Only"});
        main.addView(pageRangeSpinner);

        reverseOrderCheck = new CheckBox(this);
        reverseOrderCheck.setText("Print in reverse order (Last page first)");
        main.addView(reverseOrderCheck);

        Button printNowBtn = new Button(this);
        printNowBtn.setText("PRINT NOW");
        printNowBtn.setTextSize(17);
        printNowBtn.setTextColor(Color.WHITE);
        printNowBtn.setBackgroundColor(Color.parseColor("#0077C8"));
        printNowBtn.setPadding(24, 28, 24, 28);
        printNowBtn.setOnClickListener(v -> executeProPrint());
        main.addView(printNowBtn);

        setContentView(rootScroll);
    }

    private void switchConnectionMode(String mode) {
        connectionMode = mode;
        if (mode.equals("Wi-Fi Direct")) {
            printerIp = "192.168.223.1";
            statusBanner.setText("Mode: Wi-Fi Direct (IP: 192.168.223.1) - Connect phone Wi-Fi to HP Direct");
        } else if (mode.equals("USB OTG")) {
            checkUsbPrinter();
        } else {
            statusBanner.setText("Mode: Home Wi-Fi - Ensure HP Print Service Plugin is ON");
        }
    }

    private void checkUsbPrinter() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager != null) {
            HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
            for (UsbDevice d : devices.values()) {
                if (d.getVendorId() == 1008 || d.getDeviceClass() == 7) {
                    statusBanner.setText("USB OTG Connected: " + d.getProductName() + " (Ready)");
                    return;
                }
            }
        }
        if (connectionMode.equals("USB OTG")) {
            statusBanner.setText("USB OTG: Connect USB-B to Type-C cable to HP Smart Tank 589");
        }
    }

    private void handleSelectedUri(Uri uri) {
        try {
            String mime = getContentResolver().getType(uri);
            if (mime != null && mime.contains("pdf")) {
                closePdf();
                pdfDescriptor = getContentResolver().openFileDescriptor(uri, "r");
                if (pdfDescriptor == null) return;
                pdfRenderer = new PdfRenderer(pdfDescriptor);
                totalPdfPages = pdfRenderer.getPageCount();
                currentPageIndex = 0;
                renderPdfPage(0);

                if (pickingPdfForIdFront || pickingPdfForIdBack) {
                    openPdfIdCropper();
                }
            } else {
                Bitmap bmp;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(is);
                }
                if (bmp == null) {
                    Toast.makeText(this, "Could not decode image", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (pickingPdfForIdFront) {
                    idFrontBitmap = bmp;
                    pickingPdfForIdFront = false;
                    combineAndPreviewIdCard();
                } else if (pickingPdfForIdBack) {
                    idBackBitmap = bmp;
                    pickingPdfForIdBack = false;
                    combineAndPreviewIdCard();
                } else {
                    closePdf();
                    totalPdfPages = 1;
                    currentPreviewBitmap = bmp;
                    hdPreviewImage.setImageBitmap(bmp);
                    pageCounterText.setText("Photo Loaded (HD Preview)");
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error loading file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void renderPdfPage(int index) {
        if (pdfRenderer == null || index < 0 || index >= totalPdfPages) return;
        currentPageIndex = index;
        PdfRenderer.Page page = pdfRenderer.openPage(index);
        int w = (int) (page.getWidth() * 2.5f);
        int h = (int) (page.getHeight() * 2.5f);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
        page.close();

        currentPreviewBitmap = bmp;
        hdPreviewImage.setImageBitmap(bmp);
        pageCounterText.setText("PDF HD Preview - Page " + (index + 1) + " of " + totalPdfPages);
    }

    private void showIdCardDialog() {
        String[] options = {
                "1. Extract Front Side from PDF / Photo",
                "2. Extract Back Side from PDF / Photo",
                "3. Generate 2-in-1 ID Sheet Preview"
        };
        new AlertDialog.Builder(this)
                .setTitle("PDF & Photo ID Card Extractor")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        pickingPdfForIdFront = true;
                        pickingPdfForIdBack = false;
                        filePicker.launch(new String[]{"application/pdf", "image/*"});
                    } else if (which == 1) {
                        pickingPdfForIdBack = true;
                        pickingPdfForIdFront = false;
                        filePicker.launch(new String[]{"application/pdf", "image/*"});
                    } else {
                        combineAndPreviewIdCard();
                    }
                }).show();
    }

    private void openPdfIdCropper() {
        if (currentPreviewBitmap == null) return;
        String[] cropPresets = {
                "Top Half of Page (e-Aadhaar / PAN Top)",
                "Bottom Half of Page (e-Aadhaar Cutout)",
                "Center Card Area (Smart Card Crop)",
                "Full Page As-Is"
        };
        new AlertDialog.Builder(this)
                .setTitle("Select ID Card Area from PDF Page " + (currentPageIndex + 1))
                .setItems(cropPresets, (d, which) -> {
                    int w = currentPreviewBitmap.getWidth();
                    int h = currentPreviewBitmap.getHeight();
                    Bitmap cropped;
                    if (which == 0) cropped = Bitmap.createBitmap(currentPreviewBitmap, 0, 0, w, h / 2);
                    else if (which == 1) cropped = Bitmap.createBitmap(currentPreviewBitmap, 0, h / 2, w, h / 2);
                    else if (which == 2) cropped = Bitmap.createBitmap(currentPreviewBitmap, w / 8, h / 4, (w * 3) / 4, h / 2);
                    else cropped = currentPreviewBitmap;

                    if (pickingPdfForIdFront) {
                        idFrontBitmap = cropped;
                        pickingPdfForIdFront = false;
                        Toast.makeText(this, "Front ID Extracted!", Toast.LENGTH_SHORT).show();
                    } else if (pickingPdfForIdBack) {
                        idBackBitmap = cropped;
                        pickingPdfForIdBack = false;
                        Toast.makeText(this, "Back ID Extracted!", Toast.LENGTH_SHORT).show();
                    }
                    combineAndPreviewIdCard();
                }).show();
    }

    private void combineAndPreviewIdCard() {
        Bitmap sheet = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet);
        c.drawColor(Color.WHITE);
        Paint border = new Paint();
        border.setStyle(Paint.Style.STROKE);
        border.setColor(Color.LTGRAY);
        border.setStrokeWidth(3);

        Rect frontRect = new Rect(180, 160, 1060, 720);
        Rect backRect = new Rect(180, 820, 1060, 1380);
        c.drawRect(frontRect, border);
        c.drawRect(backRect, border);

        if (idFrontBitmap != null) drawFittedBitmap(c, idFrontBitmap, frontRect, 0);
        if (idBackBitmap != null) drawFittedBitmap(c, idBackBitmap, backRect, 0);

        closePdf();
        totalPdfPages = 1;
        currentPreviewBitmap = sheet;
        hdPreviewImage.setImageBitmap(sheet);
        pageCounterText.setText("2-in-1 ID Card Sheet Ready (Front + Back)");
    }

    private Bitmap applyScanEnhancement(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        float contrast = 1.6f;
        float translate = (-0.5f * contrast + 0.5f) * 255f;
        cm.postConcat(new ColorMatrix(new float[]{
                contrast, 0, 0, 0, translate,
                0, contrast, 0, 0, translate,
                0, 0, contrast, 0, translate,
                0, 0, 0, 1, 0
        }));
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(src, 0, 0, p);
        return out;
    }

    private void executeProPrint() {
        if (currentPreviewBitmap == null && pdfRenderer == null) {
            Toast.makeText(this, "Please select a PDF or Photo first!", Toast.LENGTH_SHORT).show();
            return;
        }

        int duplexPos = duplexSpinner.getSelectedItemPosition();
        if (duplexPos == 1) {
            new AlertDialog.Builder(this)
                    .setTitle("Manual Duplex - Step 1 (Odd Pages)")
                    .setMessage("Printing Odd pages (1, 3, 5...). After printing finishes, flip the stack face-down with top edge going in first, then select Step 2 (Even Pages).")
                    .setPositiveButton("Start Odd Print", (d, w) -> startAndroidPrintJob(true, false))
                    .show();
        } else if (duplexPos == 2) {
            startAndroidPrintJob(false, true);
        } else {
            startAndroidPrintJob(false, false);
        }
    }

    private Bitmap getPageBitmap(int pageIdx) {
        if (pdfRenderer != null) {
            PdfRenderer.Page p = pdfRenderer.openPage(pageIdx);
            Bitmap bmp = Bitmap.createBitmap(p.getWidth() * 2, p.getHeight() * 2, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            p.close();
            return bmp;
        }
        return currentPreviewBitmap;
    }

    private void drawFittedBitmap(Canvas canvas, Bitmap bmp, Rect box, int scalingMode) {
        if (bmp == null) return;
        if (scalingMode == 1) {
            canvas.drawBitmap(bmp, null, box, null);
            return;
        }
        float bw = bmp.getWidth();
        float bh = bmp.getHeight();
        float boxW = box.width();
        float boxH = box.height();
        float scale = (scalingMode == 2) ? 1.0f : Math.min(boxW / bw, boxH / bh);
        int dw = Math.min(box.width(), (int) (bw * scale));
        int dh = Math.min(box.height(), (int) (bh * scale));
        int left = box.left + (box.width() - dw) / 2;
        int top = box.top + (box.height() - dh) / 2;
        canvas.drawBitmap(bmp, null, new Rect(left, top, left + dw, top + dh), null);
    }

    private void startAndroidPrintJob(boolean onlyOdd, boolean onlyEven) {
        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) return;
        PrintAttributes.Builder attr = new PrintAttributes.Builder();

        int sizePos = paperSizeSpinner.getSelectedItemPosition();
        if (sizePos == 1) attr.setMediaSize(new PrintAttributes.MediaSize("CUSTOM_25X21", "25x21cm Glossy", 9842, 8267));
        else if (sizePos == 2) attr.setMediaSize(new PrintAttributes.MediaSize("CUSTOM_22X28", "22x28cm Ruled", 8661, 11023));
        else if (sizePos == 3) attr.setMediaSize(PrintAttributes.MediaSize.NA_INDEX_4X6);
        else if (sizePos == 4) attr.setMediaSize(PrintAttributes.MediaSize.NA_INDEX_5X7);
        else if (sizePos == 5) attr.setMediaSize(PrintAttributes.MediaSize.ISO_A5);
        else if (sizePos == 6) attr.setMediaSize(PrintAttributes.MediaSize.NA_LEGAL);
        else attr.setMediaSize(PrintAttributes.MediaSize.ISO_A4);

        int qualPos = mediaQualitySpinner.getSelectedItemPosition();
        if (qualPos == 2) attr.setResolution(new PrintAttributes.Resolution("HP_BEST", "Photo Best", 1200, 1200));
        else if (qualPos == 1) attr.setResolution(new PrintAttributes.Resolution("HP_DRAFT", "Draft", 300, 300));
        else attr.setResolution(new PrintAttributes.Resolution("HP_NORM", "Normal", 600, 600));

        final int multiMode = multiPageSpinner.getSelectedItemPosition();
        final int scalingMode = scalingSpinner.getSelectedItemPosition();
        final int marginMode = marginSpinner.getSelectedItemPosition();

        printManager.print("SmartTank589_Job", new PrintDocumentAdapter() {
            final List<Integer> pagesToPrint = new ArrayList<>();

            @Override
            public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cancel, LayoutResultCallback cb, Bundle extras) {
                pagesToPrint.clear();
                int count = (pdfRenderer != null) ? totalPdfPages : 1;
                int rangeMode = pageRangeSpinner.getSelectedItemPosition();

                for (int i = 0; i < count; i++) {
                    int pageNum = i + 1;
                    if (onlyOdd || rangeMode == 2) {
                        if (pageNum % 2 == 1) pagesToPrint.add(i);
                    } else if (onlyEven || rangeMode == 3) {
                        if (pageNum % 2 == 0) pagesToPrint.add(i);
                    } else if (rangeMode == 1) {
                        if (i == currentPageIndex) pagesToPrint.add(i);
                    } else {
                        pagesToPrint.add(i);
                    }
                }
                if (reverseOrderCheck.isChecked() || onlyEven) Collections.reverse(pagesToPrint);

                int sheetCount;
                if (multiMode == 1) sheetCount = (int) Math.ceil(pagesToPrint.size() / 2.0);
                else if (multiMode == 2) sheetCount = (int) Math.ceil(pagesToPrint.size() / 4.0);
                else if (multiMode == 3) sheetCount = 4;
                else sheetCount = pagesToPrint.size();

                PrintDocumentInfo info = new PrintDocumentInfo.Builder("SmartTank589_Print.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(Math.max(1, sheetCount))
                        .build();
                cb.onLayoutFinished(info, true);
            }

            @Override
            public void onWrite(PageRange[] ranges, ParcelFileDescriptor dest, CancellationSignal cancel, WriteResultCallback cb) {
                PdfDocument outDoc = new PdfDocument();
                int leftM = (marginMode == 2) ? 50 : (marginMode == 1 ? 0 : 12);
                int pad = (marginMode == 1) ? 0 : 12;
                int pw = 595, ph = 842;

                if (multiMode == 3 && !pagesToPrint.isEmpty()) {
                    Bitmap src = getPageBitmap(pagesToPrint.get(0));
                    if (src != null) {
                        int hw = src.getWidth() / 2, hh = src.getHeight() / 2;
                        Rect[] tiles = {
                                new Rect(0, 0, hw, hh),
                                new Rect(hw, 0, src.getWidth(), hh),
                                new Rect(0, hh, hw, src.getHeight()),
                                new Rect(hw, hh, src.getWidth(), src.getHeight())
                        };
                        for (int t = 0; t < 4; t++) {
                            PdfDocument.Page outPage = outDoc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, t + 1).create());
                            outPage.getCanvas().drawBitmap(src, tiles[t], new Rect(leftM, pad, pw - pad, ph - pad), null);
                            outDoc.finishPage(outPage);
                        }
                    }
                } else if (multiMode == 1) {
                    int sheetNum = 1;
                    for (int i = 0; i < pagesToPrint.size(); i += 2) {
                        PdfDocument.Page outPage = outDoc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, sheetNum++).create());
                        int midY = ph / 2;
                        drawFittedBitmap(outPage.getCanvas(), getPageBitmap(pagesToPrint.get(i)), new Rect(leftM, pad, pw - pad, midY - 6), scalingMode);
                        if (i + 1 < pagesToPrint.size()) {
                            drawFittedBitmap(outPage.getCanvas(), getPageBitmap(pagesToPrint.get(i + 1)), new Rect(leftM, midY + 6, pw - pad, ph - pad), scalingMode);
                        }
                        outDoc.finishPage(outPage);
                    }
                } else if (multiMode == 2) {
                    int sheetNum = 1;
                    for (int i = 0; i < pagesToPrint.size(); i += 4) {
                        PdfDocument.Page outPage = outDoc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, sheetNum++).create());
                        int midX = (leftM + pw - pad) / 2;
                        int midY = ph / 2;
                        Rect[] cells = {
                                new Rect(leftM, pad, midX - 4, midY - 4),
                                new Rect(midX + 4, pad, pw - pad, midY - 4),
                                new Rect(leftM, midY + 4, midX - 4, ph - pad),
                                new Rect(midX + 4, midY + 4, pw - pad, ph - pad)
                        };
                        for (int k = 0; k < 4 && (i + k) < pagesToPrint.size(); k++) {
                            drawFittedBitmap(outPage.getCanvas(), getPageBitmap(pagesToPrint.get(i + k)), cells[k], scalingMode);
                        }
                        outDoc.finishPage(outPage);
                    }
                } else {
                    for (int idx = 0; idx < pagesToPrint.size(); idx++) {
                        PdfDocument.Page outPage = outDoc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, idx + 1).create());
                        drawFittedBitmap(outPage.getCanvas(), getPageBitmap(pagesToPrint.get(idx)), new Rect(leftM, pad, pw - pad, ph - pad), scalingMode);
                        outDoc.finishPage(outPage);
                    }
                }

                try (FileOutputStream fos = new FileOutputStream(dest.getFileDescriptor())) {
                    outDoc.writeTo(fos);
                    cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (Exception e) {
                    cb.onWriteFailed(e.getMessage());
                } finally {
                    outDoc.close();
                }
            }
        }, attr.build());
    }

    private void openPrinterDashboard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16, 16, 16, 16);

        LinearLayout ipBar = new LinearLayout(this);
        ipBar.setOrientation(LinearLayout.HORIZONTAL);
        EditText ipField = new EditText(this);
        ipField.setText(printerIp);
        ipField.setHint("Printer IP (e.g. 192.168.223.1)");
        ipField.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button goBtn = new Button(this);
        goBtn.setText("Open");
        ipBar.addView(ipField);
        ipBar.addView(goBtn);
        box.addView(ipBar);

        WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.setWebViewClient(new WebViewClient());
        wv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 950));
        wv.loadUrl("http://" + printerIp);
        box.addView(wv);

        goBtn.setOnClickListener(v -> {
            printerIp = ipField.getText().toString().trim();
            wv.loadUrl("http://" + printerIp);
        });

        new AlertDialog.Builder(this)
                .setTitle("HP Smart Tank 589 Live Dashboard")
                .setView(box)
                .setPositiveButton("Close", null)
                .show();
    }

    private Button makeBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        b.setOnClickListener(l);
        return b;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 18, 0, 4);
        return tv;
    }

    private Spinner makeSpinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        s.setAdapter(a);
        return s;
    }
}
