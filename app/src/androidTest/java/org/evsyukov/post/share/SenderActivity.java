package org.evsyukov.post.share;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

/**
 * The app that shares, in the test APK's own process and package: it sends a fixture of {@link TestShareProvider} with
 * a text to Post's Share activity with a read grant, and stays behind the sheet until told to finish. Like the
 * provider it is Java: the test APK's process has no Kotlin runtime.
 */
public final class SenderActivity extends Activity {
    private static final String EXTRA_FILE = "file";
    private static final String EXTRA_TEXT = "text";
    private static final String EXTRA_FINISH = "finish";
    private static final String TARGET = "org.evsyukov.post";
    private static final String TEST_PACKAGE = "org.evsyukov.post.test";

    /**
     * Asks the sender, from Post's process, to share {@code file} with {@code text}. The shell starts it: Post's own
     * process may not start another app's activity from the background.
     */
    public static void share(Instrumentation instrumentation, String file, String text) {
        start(instrumentation, "--es " + EXTRA_FILE + " " + file + " --es " + EXTRA_TEXT + " " + Uri.encode(text));
    }

    /** Closes the sender, so the device is left as it was. */
    public static void close(Instrumentation instrumentation) {
        start(instrumentation, "--ez " + EXTRA_FINISH + " true");
    }

    private static void start(Instrumentation instrumentation, String extras) {
        String command = "am start -W -n " + TEST_PACKAGE + "/" + SenderActivity.class.getName() + " " + extras;
        try (ParcelFileDescriptor out = instrumentation.getUiAutomation().executeShellCommand(command);
             InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            // Reading to the end waits for the command.
            while (in.read() >= 0) { }
        } catch (IOException e) {
            throw new IllegalStateException(command, e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setText("Gallery\n\nA photo of the river at dawn");
        view.setTextSize(28f);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(0xE8, 0xF0, 0xE4));
        view.setTextColor(Color.rgb(0x20, 0x30, 0x20));
        setContentView(view);
        if (savedInstanceState == null) handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent request) {
        if (request.getBooleanExtra(EXTRA_FINISH, false)) {
            finish();
            return;
        }
        String name = request.getStringExtra(EXTRA_FILE);
        if (name == null) return;
        Uri uri = TestShareProvider.uri(name);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setComponent(new ComponentName(TARGET, TARGET + ".share.ShareActivity"))
                .setType(getContentResolver().getType(uri))
                .putExtra(Intent.EXTRA_TEXT, Uri.decode(request.getStringExtra(EXTRA_TEXT)))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newRawUri("", uri));
        startActivity(share);
    }
}
