# Super Metroid Android (with dual-screen support)

An Android port of [snesrev/sm](https://github.com/snesrev/sm) — a C
decompilation of Super Metroid — with a second-screen mod for dual-display
handhelds like the **AYN Thor**. While you play on the main screen, the
second panel shows a live Zebes minimap (decoded from your own ROM, not a
schematic placeholder) plus your energy, ammo, and item/beam status, so you
don't have to pause and dig through menus to check where you are or what
you're carrying.

This follows the same idea as [samyost1/zelda3-android](https://github.com/samyost1/zelda3-android)'s
dual-screen mod for `snesrev/zelda3`, adapted for Super Metroid's own map and
item system.

The underlying decompile is still an early-stage project — see the
[original repo](https://github.com/snesrev/sm) and its
[Discord](https://discord.gg/AJJbJAzNNJ) for the state of that effort. This
fork does not modify core game logic beyond a couple of crash fixes found
while getting it running well on Android (see commit history) — everything
else is additive (the Android platform target and the second-screen mod).

## Legal

No game assets are included anywhere in this repository or shipped in the
built app. You need your own legally-dumped copy of the ROM
(`sm.smc`/`.sfc`) to build or run this on any platform — the app reads
graphics, audio, and level data from your ROM at runtime, the same way the
original hardware did.

## Android

### Requirements
- Android Studio with the NDK installed (tested with NDK 27)
- Your own Super Metroid ROM

### Building
```sh
cd android
./gradlew assembleDebug
```
Install the resulting APK (`android/app/build/outputs/apk/debug/app-debug.apk`)
on your device. On first launch, it'll prompt you to pick your ROM file - it
gets copied into the app's private storage, never bundled or shared.

### Dual-screen mod
On a device with a second physical display (e.g. the AYN Thor), the second
screen shows automatically once you're in-game:
- **Map** — a live minimap of the explored areas of Zebes, decoded from the
  same ROM tile/palette data the in-game pause-menu map uses, with your
  current room outlined and your position marked.
- **Status** — energy and ammo bars (with missile/super/power-bomb counts
  shown as individual pips when the max is small), and item/beam indicators
  that light up as you collect and equip them.

On a single-screen device it just runs as a normal Android SNES-decomp port.

### Controls
Gamepad input works out of the box; on the Thor specifically, A/B are
remapped to match its Nintendo-style physical layout (see `main.c`'s
`__ANDROID__` guard in `RemapSdlButton`, since SDL's controller database
doesn't have an entry for this device yet).

Quicksave/quickload (useful for testing without replaying long stretches):
hold **L1+R1** and press **Start** to save, **Back/Select** to load — bound
via `android/app/src/main/assets/sm.ini`, since the desktop build's F1-F10
quicksave keys aren't reachable from a gamepad-only device.

## Desktop / Switch

The original platforms this decompile supports (Windows, Linux/macOS,
Nintendo Switch) still build the same way as upstream — see
[BUILDING.md](BUILDING.md).
