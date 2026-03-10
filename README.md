# Scropy Android

An Android-to-Android scrcpy client. Control and mirror any Android device from another Android device via ADB over WiFi or USB OTG — no PC required.

> **Based on the original [scrcpy](https://github.com/Genymobile/scrcpy) by [Genymobile](https://github.com/Genymobile).**  
> This project reimplements the client side natively for Android using a built-in ADB implementation.

---

## Demo

[![Watch on YouTube](https://img.youtube.com/vi/UrtwvAFH8G4/maxresdefault.jpg)](https://www.youtube.com/watch?v=UrtwvAFH8G4)

---

## Features

- **ADB over WiFi** — connect wirelessly using Android's Wireless Debugging (pairing code or direct IP)
- **ADB over USB** — connect via USB OTG cable (no PC needed)
- **Screen mirroring** — live stream the remote device's screen
- **Shell terminal** — interactive ADB shell directly on the device
- **Device list** — save and manage multiple devices with screenshots and metadata
- **mDNS discovery** — auto-detect nearby devices advertising ADB over WiFi
- **Device info** — shows model name and Android version per saved device
- **Self-connection** — connect the device to itself (great for Samsung DeX and desktop mode use cases)

---

## Upcoming Features

- **App Management** — browse, install, and uninstall apps on the connected device
- **File Manager** — explore the device file system, upload and download files
- **Copy & Paste** — sync clipboard between devices

---

## Requirements

- Android 8.0 (API 26) or higher on both devices
- **Wireless Debugging** enabled on the target device (Settings → Developer Options → Wireless Debugging)  
  _or_ a USB OTG cable for USB mode
- Developer Options enabled on the target device

---

## Getting Started

### 1. Enable Wireless Debugging on target device

1. Go to **Settings → Developer Options → Wireless Debugging**
2. Enable it and note the IP address and port shown

### 2. Connect from Scropy

1. Open **Scropy** on your controller device
2. Tap **+ New** → **ADB over WiFi**
3. Enter the IP and port (or use the pairing code flow for first-time setup)
4. Tap **Connect** — the device is saved for future use

### 3. Stream or Shell

- Tap **Stream** to mirror the remote screen
- Tap **Shell** to open an interactive ADB terminal

---

## USB OTG Connection

1. Plug the target device into the controller device via USB OTG
2. Tap **+ New** → **ADB over USB**
3. Accept the USB debugging prompt on the target device
4. Tap **Connect**

---

## Self-Connection (Samsung DeX & Desktop Mode)

You can connect a device **to itself** — useful when running Scropy in Samsung DeX, desktop mode, or any large-screen / windowed Android environment.

1. Enable **Wireless Debugging** on the device (Settings → Developer Options → Wireless Debugging)
2. Note the IP and port shown (it will be `127.0.0.1` or the loopback address)
3. Open **Scropy** on the same device
4. Tap **+ New** → **ADB over WiFi** and enter `127.0.0.1` with the port shown
5. Tap **Connect** — you can now mirror and control your own screen in a window

> **Tip for Samsung DeX users:** Launch Scropy from the DeX desktop, connect to `127.0.0.1`, then stream your phone screen into a resizable DeX window — no second device needed.

---

## Building from Source

```bash
git clone https://github.com/feggaa/scrcpy-android
cd scrcpy-android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires Android Studio Hedgehog or later, or Android SDK with build tools.

---

## Legal & Disclaimer

This app is intended for **development and testing purposes only**.

- You must own or have **explicit authorization** to connect to any target device.
- Unauthorized access to devices may violate local laws and regulations.
- This project is not affiliated with or endorsed by Genymobile.
- The developer assumes no liability for misuse.

---

## Credits

- Original **scrcpy** desktop tool: [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)
- Android client developed by **[Rabi3](https://github.com/feggaa)**
