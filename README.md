# NVGT Bridge

NVGT Bridge is an Android accessibility service for audio games written in Kotlen.

Audio games need direct touch. A screen reader such as TalkBack captures your taps and swipes for Explore by Touch, so the game never receives them. NVGT Bridge turns Explore by Touch off while you are in a game, and turns it back on the moment a menu, a dialog or the keyboard appears. You do not have to suspend TalkBack or turn it off.

## Requirements

* Android 11 or later
* TalkBack, or another screen reader that uses Explore by Touch

## Install

NVGT Bridge is not on Google Play yet, so you install it from an APK.

Android applies extra restrictions to sideloaded apps that request accessibility permissions. To avoid them, install with one of these:

* **ADB**: `adb install nvgt-bridge.apk`
* **A session based installer**, such as App Manager from F-Droid. These use the `PackageInstaller` session API, which most file managers do not.

If you install another way and the accessibility switch is greyed out with **"Restricted setting"**, do this:

1. Open **Settings > Apps**.
2. Select **NVGT Bridge**.
3. Open the menu in the top right corner.
4. Select **Allow restricted settings** and confirm.

## Set up

1. Open **Settings > Accessibility**.
2. Select **NVGT Bridge** and turn the switch on.
3. Open the app from your app drawer, or select **Settings** on the same Accessibility page.
4. Find your games in the list and turn each one on.

Games that declare native support (see below) are turned on for you the first time NVGT Bridge sees them. After that your choice is remembered, so you can turn one off and it stays off.

## How it works

While you are in a game you enabled, NVGT Bridge passes your touches straight to the game. It gives control back to your screen reader when:

* a dialog or a text field appears
* the keyboard opens
* you open the notification shade or the quick settings panel
* another app opens on top of the game
* the screen turns off

## Settings

* **Per app switch**: choose which games get direct touch.
* **Direct typing**: by default the keyboard area keeps working with your screen reader. Turn this on for a game that draws its own keyboard. Use the **Configure** action on any app in the list.
* **Haptic feedback**: a short vibration when direct touch turns on and off. Off by default.
* **Quick settings tile**: add the **Bridge Toggle** tile to pause and resume NVGT Bridge without leaving your game.
* **Backup and restore**: save your settings to a JSON file and load them on another device. Use the menu in the top right corner.

## For game developers

Add native support so your players do not have to find your game in the list and turn it on.

Add this `<meta-data>` tag to your `AndroidManifest.xml`, inside either `<application>` or your main `<activity>`:

```xml
<meta-data
	android:name="dev.nvgt.capability.DIRECT_TOUCH"
	android:value="true" />
```

NVGT Bridge turns your game on the first time it sees it. The player can still turn it off if they want to.

## Build

```sh
git clone https://github.com/trypsynth/nvgt-bridge.git
cd nvgt-bridge
./gradlew assembleDebug
```

To build a signed release, put a keystore at `keys/release.jks` with the key alias `nvgt`, and put its password in `local.properties`:

```properties
store.password=your-password
```

Without those, `./gradlew assembleRelease` still works and produces an unsigned APK.

Run the tests:

```sh
./gradlew testDebugUnitTest          # unit tests
./gradlew connectedDebugAndroidTest  # instrumented tests, needs a device
```

## Known issues

* The app is not on Google Play yet, so you have to sideload it.
* NVGT Bridge looks for dialogs and text fields up to five levels deep in the view tree. A game that nests a text field deeper than that may not give control back to your screen reader on its own. Use the quick settings tile to pause NVGT Bridge if this happens.

Please open an issue if you find something else, or if you want a feature added.

## Credits

NVGT Bridge was written by [Aryan Choudhary](https://github.com/aryanchoudharypro), who transferred the project to its current maintainer.

## License

MIT. See [LICENSE](LICENSE).
