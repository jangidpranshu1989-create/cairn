package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

/**
 * Renders a WebView's PrintDocumentAdapter straight to a PDF file without showing the system print dialog.
 * Lives in android.print because the result-callback constructors are package-private.
 */
public final class PdfExporter {
    public interface Done { void ok(); void fail(String message); }

    private PdfExporter() {}

    public static void export(final PrintDocumentAdapter adapter, PrintAttributes attrs, final ParcelFileDescriptor out, final Done done) {
        adapter.onStart();
        adapter.onLayout(null, attrs, null, new PrintDocumentAdapter.LayoutResultCallback() {
            @Override public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, out, new CancellationSignal(), new PrintDocumentAdapter.WriteResultCallback() {
                    @Override public void onWriteFinished(PageRange[] pages) { adapter.onFinish(); done.ok(); }
                    @Override public void onWriteFailed(CharSequence error) { adapter.onFinish(); done.fail(String.valueOf(error)); }
                    @Override public void onWriteCancelled() { adapter.onFinish(); done.fail("cancelled"); }
                });
            }
            @Override public void onLayoutFailed(CharSequence error) { adapter.onFinish(); done.fail(String.valueOf(error)); }
            @Override public void onLayoutCancelled() { adapter.onFinish(); done.fail("cancelled"); }
        }, null);
    }
}
