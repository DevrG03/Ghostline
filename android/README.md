# Ghostline for Android

A small native app that joins the same rooms as the web version, using the same invite links,
encryption and safety code. Chat and voice calls; you can see the other person's camera, but the app
doesn't send your own video yet.

Why it exists: on some phones (seen on a moto g 5G running LineageOS 15), every browser asks the
audio driver for 32-bit float microphone audio, the driver rejects it, and calls are silent or fail
with `NotReadableError`. This app records through Android's `AudioRecord` in 16-bit, like WhatsApp,
which works on those phones.

## Build and install

Needs JDK 17 and the Android SDK (`brew install --cask android-commandlinetools`, then
`sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`).

    cd android
    echo "sdk.dir=$(brew --prefix)/share/android-commandlinetools" > local.properties
    ./gradlew testDebugUnitTest assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

## Use

- **Join:** paste an invite link and tap Join, or use "Share" on a link in another app and pick Ghostline.
- **Create:** enter your server address (e.g. `https://ghostline-xxxx.onrender.com`) and tap Create.

## Privacy

Same model as the web app: the room key lives only in the invite link, signaling is AES-256-GCM
encrypted, and chat and calls go directly between devices. The app stores only the server address.
Screenshots, screen recording and the app-switcher preview are blocked, and app backups are off.

## Limits

- No camera of your own yet: the other person's video is shown, but the app only sends audio.
- Keep the app open during a call. Android silences the microphone of apps in the background.
- Only HTTPS servers work, plus `localhost` for testing over USB.
