# DeskBot Desktop 🤖✨

A complete Python desktop companion application for **DeskBot**, mirroring the Android companion app (**DeskDroid**) with full feature parity.

DeskBot Desktop connects to an **ESP32-C3 SuperMini** companion robot over Bluetooth Low Energy (BLE), streams 16 kHz audio to Google's **Gemini Live Multimodal WebSocket API** (`models/gemini-3.8-live`), displays Maya's replies on the robot's **SSD1306 OLED screen**, and speaks responses through local text-to-speech.

---

## 📸 Key Features

- **Catppuccin Mocha Dark UI**: Built with `customtkinter`, following the exact color scheme and layout of the Android DeskDroid app.
- **Robust BLE GATT Integration**: Powered by `bleak` on an asynchronous event loop; scans, connects, and handles GATT notifications for status, raw audio, and display updates.
- **Gemini Live Multimodal Protocol**:
  - Connects to `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`
  - Streams 16 kHz mono 16-bit PCM in 50 ms chunks (1600 bytes) as base64.
  - Implements the exact same Automatic Gain Control (AGC) with 28,000 soft-knee limiter as the Android app.
  - Voiced thresholding (`MIN_ACTIVE_SAMPLES = 120ms`) prevents random noise or clicks from triggering hallucinations.
  - Appends 500 ms trailing digital silence and `{"realtimeInput": {"audioStreamEnd": true}}` on turn completion.
  - 1.0-second fallback idle timer.
- **Dual Microphone Routing**: Supports streaming from the DeskBot's onboard **INMP441 I2S microphone** via BLE, or from your **PC Microphone** via `sounddevice`.
- **SSD1306 OLED Display Forwarding**: Automatically formats and sends Gemini's responses to the robot's display characteristic ending with `DISPLAY_END`.
- **Local Text-To-Speech (TTS)**: Offline voice output powered by `pyttsx3` with an instant Mute/Unmute toggle.
- **Live Diagnostic Terminal**:
  - Catppuccin Crust styled terminal with color-coded component tags (`[BLE]`, `[GEMINI]`, `[AUDIO]`, `[HEARD]`, `[DISPLAY]`, `[SYSTEM]`).
  - In-place transcript upserting for live user speech recognition.
  - Auto-scrolling and line counting.
- **Real-Time System Telemetry**: Displays device connection state, RF link version (`BLE 5.0`), and negotiated MTU size.

---

## 🛠️ Project Structure

```
DeskDesktop/
├── main.py              # Application entry point
├── ble_manager.py       # BLE scanning, connection, and GATT notifications (Bleak)
├── gemini_manager.py    # Gemini Live WebSocket client (mirrors GeminiLiveManager.kt)
├── audio_manager.py     # Local microphone capture (sounddevice + numpy)
├── tts_manager.py       # Offline speech synthesis (pyttsx3)
├── ui/
│   ├── __init__.py
│   ├── app.py           # Main window layout, sidebar, terminal & state management
│   ├── widgets.py       # Reusable components (FlatCard, StatusPill, MetricCard, LogPanel, DevicePickerModal)
│   └── theme.py         # Catppuccin Mocha color constants and typography
├── requirements.txt     # Python dependencies
├── .env.example         # Environment template for Gemini API key
├── .env                 # Environment configuration
└── README.md            # Documentation
```

---

## ⚡ Hardware Pinout Reference

| Component | Pin / Signal | ESP32-C3 SuperMini GPIO | Notes |
| :--- | :--- | :--- | :--- |
| **INMP441 Mic** | `SCK` (BCLK) | **GPIO 4** | I2S Bit Clock |
| | `WS` (LRCLK) | **GPIO 5** | Word Select / Frame Sync |
| | `SD` (DIN) | **GPIO 6** | Serial Data |
| | `L/R` | **GND** | **Crucial:** Must be GND for Left Slot (`I2S_STD_SLOT_LEFT`) |
| | `VDD` | **3.3V** | Do not use 5V |
| | `GND` | **GND** | Common ground |
| **SSD1306 OLED**| `SDA` | **GPIO 2** | I2C Data (`0x3C`) |
| | `SCL` | **GPIO 3** | I2C Clock (`0x3C`) |
| | `VCC` | **3.3V** | Power |
| | `GND` | **GND** | Common ground |

---

## 📡 BLE GATT Service & Characteristics

- **Service UUID**: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- **Command Char** (`...0002`): Write OTA commands (`OTA_BEGIN`, `OTA_ABORT`, `OTA_END`)
- **Status Char** (`...0003`): Notify (`VOICE_START`, `VOICE_END`, OTA status)
- **Firmware Char** (`...0004`): Write raw binary firmware chunks
- **Audio Char** (`...0005`): Notify 16-bit PCM (16 kHz mono, 240 bytes / notify)
- **Display Char** (`...0006`): Write UTF-8 text ending with `DISPLAY_END` in chunks $\le$ MTU-3

---

## 🚀 Getting Started

### 1. Prerequisites
- Python 3.10+
- A Google Gemini API key (obtainable from [Google AI Studio](https://aistudio.google.com/))
- Bluetooth adapter enabled on your computer

### 2. Installation
Open a terminal in the `DeskDesktop` directory and install the requirements:

```bash
cd DeskDesktop
pip install -r requirements.txt
```

### 3. Configure Gemini API Key
Edit the `.env` file (or set the `GEMINI_API_KEY` environment variable):

```ini
GEMINI_API_KEY=your_actual_gemini_api_key_here
```

### 4. Run the Application
Launch the desktop app:

```bash
python main.py
```

---

## 🎯 Usage Workflow

1. **Power on DeskBot**: Ensure the ESP32-C3 SuperMini is powered and running `firmware.ino`.
2. **Connect**:
   - Click the **Connect to DeskBot** button or the antenna icon (`📡`) in the header.
   - The Device Picker modal will scan for nearby BLE peripherals.
   - Select your DeskBot device (flagged with the `DESKBOT` badge) and click **Connect**.
3. **Start AI Session**:
   - Click **Start** in the **AI Voice Assistant** section.
   - The status indicator will switch to **Session active · Listening**.
4. **Speak**:
   - Speak towards DeskBot's microphone.
   - The hardware VAD triggers `VOICE_START`, and audio streams in real-time to Gemini Live.
   - The Live Diagnostic Terminal displays the live recognition under `[HEARD]`.
   - When you finish speaking, `VOICE_END` commits the utterance with trailing digital silence.
5. **Response**:
   - Gemini Maya responds in 1–2 friendly sentences.
   - Text is sent to the ESP32's OLED screen via BLE.
   - Text is spoken through your PC speakers via Text-To-Speech (if not muted).
