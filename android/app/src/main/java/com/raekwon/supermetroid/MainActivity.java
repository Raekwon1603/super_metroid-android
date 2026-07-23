package com.raekwon.supermetroid;

import android.os.Bundle;
import android.view.View;

import org.libsdl.app.SDLActivity;

// SDL's own JNI glue lives in libSDL2.so; the game itself (main.c and the
// rest of ../../../../src, see android/app/jni/src/Android.mk) is built as a
// separate libmain.so whose SDL_main() SDLActivity dlsym()s and calls.
public class MainActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        goFullscreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Regaining focus (e.g. after the notification shade or the
        // navigation bar was swiped up) resets the system bars - reapply.
        if (hasFocus) goFullscreen();
    }

    private void goFullscreen() {
        // android:theme (see AndroidManifest.xml) already strips the title
        // bar and status bar at the theme level; this additionally hides the
        // navigation bar and keeps it hidden (immersive sticky), matching
        // AndroidManifest's declared SDL window title ("SuperMet") no longer
        // being visible anywhere.
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
