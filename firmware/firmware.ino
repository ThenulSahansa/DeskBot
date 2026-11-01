#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <Update.h>
#include <ESP_I2S.h>
#include <math.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

I2SClass microphone;

// --- Pin Definitions ---
#define I2S_SCK 4
#define I2S_WS  5
#define I2S_SD  6
#define DISPLAY_SCL 3
#define DISPLAY_SDA 2
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64 // Change to 32 if using a 128x32 display
#define OLED_RESET    -1

// --- Audio Configuration ---
#define AUDIO_SAMPLE_RATE   16000
#define I2S_BUFFER_SAMPLES  256
#define BLE_AUDIO_CHUNK     240   // Max bytes per BLE notify (~120 16-bit samples)
#define VAD_RMS_THRESHOLD   500
#define SILENCE_TIMEOUT_MS  500

// --- I2S read buffer (32-bit from INMP441) ---
int32_t i2sReadBuffer[I2S_BUFFER_SAMPLES];
// --- 16-bit converted buffer for BLE transmission ---
int16_t audioSendBuffer[I2S_BUFFER_SAMPLES];

// ESP32-C3 supports Bluetooth Low Energy (BLE), not Bluetooth Classic SPP.
// The Android app checks GitHub, downloads the binary, then sends it here.
constexpr char DEVICE_NAME[] = "DeskBot";
constexpr char BLE_SERVICE_UUID[]  = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_COMMAND_UUID[]  = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_STATUS_UUID[]   = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_FIRMWARE_UUID[] = "6E400004-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_AUDIO_UUID[]    = "6E400005-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_DISPLAY_UUID[]  = "6E400006-B5A3-F393-E0A9-E50E24DCCA9E";

BLEServer *bleServer = nullptr;
BLECharacteristic *statusCharacteristic = nullptr;
BLECharacteristic *audioCharacteristic = nullptr;
BLECharacteristic *displayCharacteristic = nullptr;

// --- OTA state ---
bool otaInProgress = false;
size_t expectedFirmwareBytes = 0;
size_t writtenFirmwareBytes = 0;

// --- Voice activity detection state ---
bool isVoiceActive = false;
unsigned long lastVoiceTimeMs = 0;

// --- BLE connection state ---
bool bleConnected = false;

// --- Display text accumulation buffer ---
String displayAccumBuffer = "";

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// ============================================================
// OLED Display Helper
// ============================================================
void displayText(const char* text) {
  display.clearDisplay();
  display.setTextSize(1);       // 6x8 pixels per character
  display.setTextColor(SSD1306_WHITE);

  const int maxCharsPerLine = 21;  // 128 / 6 = 21 chars per line
  const int maxLines = 8;          // 64 / 8 = 8 lines

  // Word-wrap the text into lines
  String lines[64];  // generous upper bound
  int lineCount = 0;
  String input(text);
  int pos = 0;
  int len = input.length();

  while (pos < len && lineCount < 64) {
    // Check for explicit newline
    int nlPos = input.indexOf('\n', pos);
    int segEnd = (nlPos >= 0 && nlPos < pos + maxCharsPerLine) ? nlPos : -1;

    if (segEnd >= 0) {
      // Newline found within current line width
      lines[lineCount++] = input.substring(pos, segEnd);
      pos = segEnd + 1;
      continue;
    }

    // No newline within range; check if remaining text fits on one line
    if (pos + maxCharsPerLine >= len) {
      lines[lineCount++] = input.substring(pos);
      break;
    }

    // Find last space within maxCharsPerLine for word wrap
    int wrapAt = -1;
    for (int i = pos + maxCharsPerLine; i > pos; i--) {
      if (input.charAt(i) == ' ') {
        wrapAt = i;
        break;
      }
    }

    if (wrapAt > pos) {
      lines[lineCount++] = input.substring(pos, wrapAt);
      pos = wrapAt + 1; // skip the space
    } else {
      // No space found; hard-break at maxCharsPerLine
      lines[lineCount++] = input.substring(pos, pos + maxCharsPerLine);
      pos += maxCharsPerLine;
    }
  }

  // If text is longer than 8 lines, show the last 8 lines (scroll to bottom)
  int startLine = 0;
  if (lineCount > maxLines) {
    startLine = lineCount - maxLines;
  }

  for (int i = startLine; i < lineCount && (i - startLine) < maxLines; i++) {
    display.setCursor(0, (i - startLine) * 8);
    display.print(lines[i]);
  }

  display.display();
}

// ============================================================
// BLE Status Helper
// ============================================================
void sendStatus(const char *message) {
  Serial.println(message);
  if (statusCharacteristic != nullptr && bleServer != nullptr &&
      bleServer->getConnectedCount() > 0) {
    statusCharacteristic->setValue(message);
    statusCharacteristic->notify();
  }
}

// ============================================================
// OTA Helpers
// ============================================================
void resetOta() {
  if (otaInProgress) {
    Update.abort();
  }
  otaInProgress = false;
  expectedFirmwareBytes = 0;
  writtenFirmwareBytes = 0;
}

// ============================================================
// BLE Callbacks
// ============================================================
class DeskBotServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    bleConnected = true;
    displayText("Connected!");
    Serial.println("BLE client connected.");
  }

  void onDisconnect(BLEServer *server) override {
    bleConnected = false;
    isVoiceActive = false;
    displayAccumBuffer = "";
    displayText("Disconnected");
    Serial.println("BLE client disconnected.");
    delay(500);
    displayText("DeskBot Ready\nWaiting for BLE...");
    server->startAdvertising();
  }
};

class FirmwareDataCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    if (!otaInProgress) {
      return;
    }

    // Use the raw buffer: getValue() returns Arduino String in ESP32 core 3.x
    // and cannot safely represent arbitrary firmware bytes.
    uint8_t *data = characteristic->getData();
    const size_t dataLength = characteristic->getLength();
    const size_t remaining = expectedFirmwareBytes - writtenFirmwareBytes;
    if (data == nullptr || dataLength == 0 || dataLength > remaining ||
        Update.write(data, dataLength) != dataLength) {
      sendStatus("[OTA] Firmware write failed.");
      resetOta();
      return;
    }

    writtenFirmwareBytes += dataLength;
  }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    String command = characteristic->getValue().c_str();
    command.trim();

    if (command.startsWith("OTA_BEGIN:")) {
      resetOta();
      const size_t imageSize = command.substring(10).toInt();
      if (imageSize == 0 || !Update.begin(imageSize, U_FLASH)) {
        sendStatus("[OTA] Cannot start update.");
        return;
      }
      expectedFirmwareBytes = imageSize;
      writtenFirmwareBytes = 0;
      otaInProgress = true;
      sendStatus("[OTA] Receiving firmware.");
    } else if (command == "OTA_ABORT") {
      resetOta();
      sendStatus("[OTA] Cancelled.");
    } else if (command == "OTA_END") {
      if (!otaInProgress || writtenFirmwareBytes != expectedFirmwareBytes || !Update.end()) {
        sendStatus("[OTA] Finalization failed.");
        resetOta();
        return;
      }
      sendStatus("[OTA] Complete. Restarting.");
      delay(250);
      ESP.restart();
    }
  }
};

class DisplayTextCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    uint8_t *data = characteristic->getData();
    size_t dataLength = characteristic->getLength();

    if (data == nullptr || dataLength == 0) {
      return;
    }

    String chunk;
    chunk.reserve(dataLength);
    for (size_t i = 0; i < dataLength; i++) {
      chunk += (char)data[i];
    }

    displayAccumBuffer += chunk;

    // Check for end-of-message markers
    bool shouldDisplay = false;
    if (displayAccumBuffer.endsWith("DISPLAY_END")) {
      // Remove the marker before displaying
      displayAccumBuffer = displayAccumBuffer.substring(
          0, displayAccumBuffer.length() - 11);
      shouldDisplay = true;
    } else if (displayAccumBuffer.indexOf('\n') >= 0 &&
               !displayAccumBuffer.endsWith("\n")) {
      // Contains a newline but doesn't end with one — could be partial.
      // Only auto-display on DISPLAY_END or when a single complete write
      // ends with newline.
    } else if (displayAccumBuffer.endsWith("\n")) {
      shouldDisplay = true;
    }

    if (shouldDisplay) {
      displayAccumBuffer.trim();
      if (displayAccumBuffer.length() > 0) {
        displayText(displayAccumBuffer.c_str());
      }
      displayAccumBuffer = "";
    }
  }
};

// ============================================================
// BLE Initialization
// ============================================================
void startBle() {
  BLEDevice::init(DEVICE_NAME);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new DeskBotServerCallbacks());

  // Allocate 30 handles for characteristics + descriptors
  BLEService *service = bleServer->createService(BLEUUID(String(BLE_SERVICE_UUID)), 30);

  // Command characteristic (WRITE) — receives OTA commands, etc.
  BLECharacteristic *commandCharacteristic = service->createCharacteristic(
      BLE_COMMAND_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);

  // The standard Nordic UART TX characteristic. ESP32-C3's NimBLE stack
  // automatically provides its writable CCCD descriptor for notifications.
  statusCharacteristic = service->createCharacteristic(
      BLE_STATUS_UUID, BLECharacteristic::PROPERTY_NOTIFY);

  // Firmware data characteristic (WRITE) — receives OTA binary chunks
  BLECharacteristic *firmwareCharacteristic = service->createCharacteristic(
      BLE_FIRMWARE_UUID, BLECharacteristic::PROPERTY_WRITE);

  // Audio characteristic (NOTIFY) — sends 16-bit PCM audio chunks
  audioCharacteristic = service->createCharacteristic(
      BLE_AUDIO_UUID, BLECharacteristic::PROPERTY_NOTIFY);

  // Display text characteristic (WRITE) — receives text to show on OLED
  displayCharacteristic = service->createCharacteristic(
      BLE_DISPLAY_UUID, BLECharacteristic::PROPERTY_WRITE);

  // Set callbacks
  commandCharacteristic->setCallbacks(new CommandCallbacks());
  firmwareCharacteristic->setCallbacks(new FirmwareDataCallbacks());
  displayCharacteristic->setCallbacks(new DisplayTextCallbacks());

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setName(DEVICE_NAME);
  advertising->setScanResponse(true);
  advertising->start();
}

// ============================================================
// Audio Processing & BLE Transmission
// ============================================================
void processAudio() {
  // Read I2S samples (32-bit from INMP441)
  size_t bytesRead = microphone.readBytes(
      (char *)i2sReadBuffer, sizeof(i2sReadBuffer));
  size_t samplesRead = bytesRead / sizeof(int32_t);

  if (samplesRead == 0) {
    return;
  }

  // Convert 32-bit to 16-bit and calculate RMS
  int64_t sumSquares = 0;
  for (size_t i = 0; i < samplesRead; i++) {
    int16_t sample16 = (int16_t)(i2sReadBuffer[i] >> 16);
    audioSendBuffer[i] = sample16;
    sumSquares += (int64_t)sample16 * (int64_t)sample16;
  }
  double rms = sqrt((double)sumSquares / (double)samplesRead);

  unsigned long now = millis();

  if (rms > VAD_RMS_THRESHOLD) {
    // Voice detected
    if (!isVoiceActive) {
      isVoiceActive = true;
      sendStatus("VOICE_START");
      displayText("Listening...");
      Serial.println("VAD: Voice started");
    }
    lastVoiceTimeMs = now;

    // Send audio chunks via BLE notify
    if (bleConnected && audioCharacteristic != nullptr) {
      size_t totalBytes = samplesRead * sizeof(int16_t);
      size_t offset = 0;
      while (offset < totalBytes) {
        size_t chunkSize = totalBytes - offset;
        if (chunkSize > BLE_AUDIO_CHUNK) {
          chunkSize = BLE_AUDIO_CHUNK;
        }
        audioCharacteristic->setValue(
            (uint8_t *)audioSendBuffer + offset, chunkSize);
        audioCharacteristic->notify();
        offset += chunkSize;
      }
    }
  } else if (isVoiceActive) {
    // Below threshold — still send audio until silence timeout
    // (captures trailing speech)
    if (bleConnected && audioCharacteristic != nullptr) {
      size_t totalBytes = samplesRead * sizeof(int16_t);
      size_t offset = 0;
      while (offset < totalBytes) {
        size_t chunkSize = totalBytes - offset;
        if (chunkSize > BLE_AUDIO_CHUNK) {
          chunkSize = BLE_AUDIO_CHUNK;
        }
        audioCharacteristic->setValue(
            (uint8_t *)audioSendBuffer + offset, chunkSize);
        audioCharacteristic->notify();
        offset += chunkSize;
      }
    }

    // Check silence timeout
    if (now - lastVoiceTimeMs >= SILENCE_TIMEOUT_MS) {
      isVoiceActive = false;
      sendStatus("VOICE_END");
      Serial.println("VAD: Voice ended");
    }
  }
}

// ============================================================
// Setup
// ============================================================
void setup() {
  Serial.begin(115200);
  startBle();
  delay(1500);

  Wire.begin(DISPLAY_SDA, DISPLAY_SCL);

  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println(F("SSD1306 allocation failed"));
    while (true);
  }

  // BCLK, WS, DOUT (unused), DIN
  microphone.setPins(
    I2S_SCK,
    I2S_WS,
    -1,
    I2S_SD
  );

  if (!microphone.begin(
        I2S_MODE_STD,
        AUDIO_SAMPLE_RATE,
        I2S_DATA_BIT_WIDTH_32BIT,
        I2S_SLOT_MODE_MONO,
        I2S_STD_SLOT_LEFT
      )) {
    Serial.println("I2S initialization failed!");
    displayText("I2S FAILED!");
    while (true) {
      delay(1000);
    }
  }

  Serial.println("INMP441 microphone ready!");
  displayText("DeskBot Ready\nWaiting for BLE...");
}

// ============================================================
// Main Loop
// ============================================================
void loop() {
  // Skip audio processing during OTA to avoid contention
  if (otaInProgress) {
    delay(20);
    return;
  }

  processAudio();
}
