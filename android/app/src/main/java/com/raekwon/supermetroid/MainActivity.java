package com.raekwon.supermetroid;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

// SDL's own JNI glue lives in libSDL2.so; the game itself (main.c and the
// rest of ../../../../src, see android/app/jni/src/Android.mk) is built as a
// separate libmain.so whose SDL_main() SDLActivity dlsym()s and calls.
public class MainActivity extends SDLActivity {
    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { showSecondScreenIfPresent(); }
        @Override public void onDisplayRemoved(int displayId) { showSecondScreenIfPresent(); }
        @Override public void onDisplayChanged(int displayId) { }
    };
    private SecondScreenPresentation secondScreen;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        goFullscreen();
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(displayListener, null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        showSecondScreenIfPresent();
    }

    @Override
    protected void onStop() {
        dismissSecondScreen();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        displayManager.unregisterDisplayListener(displayListener);
        dismissSecondScreen();
        super.onDestroy();
    }

    // The Thor exposes its bottom panel as a genuine secondary Display (not
    // the default one - confirmed via `adb shell dumpsys display`: displayId
    // 0 is the main "Built-in Screen" the game already renders to, displayId
    // 4 is "Screen-2", an EXTERNAL/FLAG_PRESENTATION display), so a plain
    // Presentation on the first non-default display found is enough here -
    // no need for zelda3-android's "game got launched on the secondary
    // display" swap-handling, which doesn't apply to this hardware.
    private void showSecondScreenIfPresent() {
        if (secondScreen != null) return;
        int mainDisplayId = getWindowManager().getDefaultDisplay().getDisplayId();
        for (Display display : displayManager.getDisplays()) {
            if (display.getDisplayId() == mainDisplayId) continue;
            secondScreen = new SecondScreenPresentation(this, display);
            secondScreen.setOnDismissListener(dialog -> secondScreen = null);
            try {
                secondScreen.show();
            } catch (Exception e) {
                secondScreen = null;
            }
            return;
        }
    }

    private void dismissSecondScreen() {
        if (secondScreen != null) {
            secondScreen.dismiss();
            secondScreen = null;
        }
    }

    // Ammo-cycle shortcut (L2/R2) - handled here, at the raw Android key
    // event level, rather than through SDL's usual SDL_GameController input
    // path (main.c's HandleGamepadInput/RemapSdlButton). The Thor's own
    // controller reports L2/R2 as plain digital AKEYCODE_BUTTON_L2/R2 key
    // events (confirmed via `adb shell getevent -lp`: BTN_TL2/BTN_TR2 in
    // its KEY set, no ABS_Z/ABS_RZ/ABS_GAS/ABS_BRAKE trigger axes at all),
    // but SDL's Android joystick layer (SDL_sysjoystick.c) maps
    // AKEYCODE_BUTTON_L2/R2 to raw joystick button indices 15/16 - outside
    // the SDL_GameControllerButton enum's range, so they never surface as
    // an SDL_CONTROLLERBUTTONDOWN/SDL_CONTROLLERAXISMOTION event at all
    // (main.c's event loop only listens for those two, never raw
    // SDL_JOYBUTTONDOWN). Intercepting the physical KeyEvent directly here
    // and consuming ONLY these two codes - passing every other key through
    // to SDL untouched - is the only way to actually observe them on this
    // hardware.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                GameState.cycleSelectedAmmo(code == KeyEvent.KEYCODE_BUTTON_R2 ? 1 : -1);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
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
