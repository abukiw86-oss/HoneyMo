# HoneyMo Ecosystem - Real-Time Screen Capture & Preview System

A complete, compliant Android screen-sharing and multi-device preview system built with **Kotlin (Android)** and **Node.js (Backend Relay)**.

---

## 🏗 System Architecture

```
                 [Android Device A - HoneyMo (Streamer)]
                                    │
                       1. MediaProjection Screen Capture
                       2. Hardware H.264 Encoder (MediaCodec)
                       3. Binary WebSocket Stream (ws://.../ws/device)
                                    │
                                    ▼
                      [Node.js Multi-Device Relay Server]
                                    │
        ┌───────────────────────────┴───────────────────────────┐
        ▼                                                       ▼
[Android Device B - HoneyMo Preview]               [Web Admin Dashboard]
  - Device discovery (GET /api/devices)              - Browser MSE player (JMuxer)
  - Multi-device list with names & FPS               - Real-time telemetry & controls
  - Hardware H.264 decoding on SurfaceView           - http://localhost:3000
```

---

## 📱 Applications in this Project

### 1. **HoneyMo (Streamer App)**
* **Location:** `/home/abuki/Development/Kotlin_projects/HoneyMo`
* **Purpose:** Captures the screen via Android's `MediaProjection`, hardware-encodes frames using `MediaCodec` (`video/avc` H.264), and streams raw NAL units over WebSocket.
* **Features:**
  * Android 14+ compliant ordered start (`startForeground` before `getMediaProjection`).
  * `BootReceiver` for boot-up alerts.
  * WakeLock and battery optimization exemption handling.
  * Custom launcher icon configured from `assets/launcher_icon.jpeg`.

### 2. **HoneyMo Preview (Viewer App)**
* **Location:** `/home/abuki/Development/Kotlin_projects/HoneyMo_Preview`
* **Purpose:** Android client application to monitor and display streaming devices.
* **Features:**
  * **Device List Screen:** Displays real-time cards of all active streaming devices with device name, resolution, FPS, and live status.
  * **Stream Player Screen:** Tapping a device opens an ultra-low latency hardware player using Android's native `MediaCodec` decoder rendering directly onto a `SurfaceView`.
  * **Controls:** Instant keyframe request (`REQUEST_KEYFRAME`), real-time FPS counter, and data received telemetry.
  * Custom launcher icon configured from `assets/launcher_icon.jpeg`.

### 3. **Node.js Relay Server & Web Dashboard**
* **Location:** `server/`
* **Purpose:** Multi-device relay server routing binary H.264 video streams from capture devices to viewer clients.
* **Features:**
  * Tracks multi-device connections (`/ws/device?id=...&name=...`).
  * REST API: `GET /api/devices` and `GET /api/status`.
  * Selectively forwards video frames to subscribed viewer clients (`/ws/viewer?deviceId=...`).
  * Caches SPS/PPS codec configurations for instantaneous video playback.
  * Embedded HTML5 Web Admin Dashboard at `http://localhost:3000`.

---

## 🚀 Quick Start Guide

### 1. Start the Relay Server
```bash
cd server
npm start
```
The server will print its available network IP addresses:
```
====================================================
 HoneyMo Multi-Device Server running on port 3000
 Web Admin Dashboard: http://localhost:3000
 Local IP Addresses:
   -> Device stream endpoint: ws://192.168.1.X:3000/ws/device
   -> Viewer endpoint:        ws://192.168.1.X:3000/ws/viewer
====================================================
```

---

### 2. Build & Install **HoneyMo** (Streamer)
```bash
cd /home/abuki/Development/Kotlin_projects/HoneyMo
./gradlew assembleDebug

# Install on capturing device
adb -s <DEVICE_A> install -r app/build/outputs/apk/debug/app-debug.apk
```
* Open **HoneyMo**, enter your server WebSocket address, tap **START SCREEN CAPTURE**, and accept the prompt.

---

### 3. Build & Install **HoneyMo Preview** (Viewer)
```bash
cd /home/abuki/Development/Kotlin_projects/HoneyMo_Preview
./gradlew assembleDebug

# Install on viewing device
adb -s <DEVICE_B> install -r app/build/outputs/apk/debug/app-debug.apk
```
* Open **HoneyMo Preview**, enter the server host (`<YOUR_PC_IP>:3000`), and tap **Refresh**.
* All active streaming devices will appear in the list with their device model and resolution.
* Tap any device in the list to watch its screen live in real-time!
