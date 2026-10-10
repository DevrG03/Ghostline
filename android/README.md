# Ghostline for Android

A small native app that works with the web version: same 6-letter pairing codes, saved rooms,
encryption and safety code. Chat, voice calls and full-screen video calls.

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

- **First run:** enter your server address (e.g. `https://ghostline-xxxx.onrender.com`). Change it
  later by tapping the server line at the bottom of the room list.
- **New room** shows a 6-letter code; the other person taps **Join with code** and types it.
- Whoever taps **New room** owns it; whoever joins is a participant. The owner's ⋯ menu adds
  **Remove participant** and **End room for both**; a participant only has **Leave room**.
- Rooms stay in the list until you leave or end them (you're asked first). Back just returns
  to the list. During a call, the chat button shrinks the call to a bar so you can keep chatting.

## Calls

- **Voice** or **Video** to call; **Voice** or **Video** to answer. Turn your camera on or off and
  flip between front and back cameras during a call. The camera is fully released when it's off.
- Video calls are full screen. Tap the other person's video to switch between filling the screen
  and showing the whole picture.

## Privacy

Same model as the web app: the room secret comes from an ECDH exchange during pairing, signaling
is AES-256-GCM encrypted, and chat and calls go directly between devices. Rooms and history are
stored in the app's private storage, encrypted with a key held in the Android Keystore.
Screenshots, screen recording and the app-switcher preview are blocked, and app backups are off.

## Limits

- Keep the app open during a call. Android silences the microphone of apps in the background.
- Only HTTPS servers work, plus `localhost` for testing over USB.
