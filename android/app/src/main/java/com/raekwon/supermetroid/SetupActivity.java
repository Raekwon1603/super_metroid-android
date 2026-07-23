package com.raekwon.supermetroid;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

// Launcher activity. The game (native SnesInit(), src/main.c) expects a raw
// ROM file on disk - no BPS-patched asset blob step like zelda3-android's,
// since this decomp loads a ROM directly on every existing platform. This
// screen just makes sure getExternalFilesDir(null)/sm.smc exists, prompting
// a SAF file picker to copy the user's own ROM there if it doesn't yet, then
// hands off to MainActivity (the SDLActivity subclass that runs the game).
public class SetupActivity extends Activity {
    private static final int REQUEST_PICK_ROM = 1;
    private static final int MIN_ROM_SIZE = 512 * 1024; // smallest real SM dumps are ~2MB; refuse obvious junk

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (romFile().length() > 0) {
            launchGame();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setText("Select your Super Metroid ROM (sm.smc/.sfc) to continue.");
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        Button pick = new Button(this);
        pick.setText("Select ROM");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, REQUEST_PICK_ROM);
            }
        });
        root.addView(pick);

        setContentView(root);
    }

    private File romFile() {
        return new File(getExternalFilesDir(null), "sm.smc");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ROM || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            copyRom(uri);
            launchGame();
        } catch (Exception e) {
            status.setText("Couldn't read that file: " + e.getMessage());
        }
    }

    private void copyRom(Uri uri) throws Exception {
        File dest = romFile();
        File tmp = new File(dest.getParentFile(), "sm.smc.tmp");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IllegalStateException("could not open picked file");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        if (tmp.length() < MIN_ROM_SIZE) {
            tmp.delete();
            throw new IllegalStateException("file is too small to be a Super Metroid ROM");
        }
        if (!tmp.renameTo(dest)) {
            throw new IllegalStateException("failed to finalize ROM file");
        }
    }

    private void launchGame() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
