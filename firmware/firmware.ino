#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
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
#define OLED_RESET   -1

// --- Audio & VAD Configuration ---
#define AUDIO_SAMPLE_RATE     16000
#define I2S_BUFFER_SAMPLES    256
#define BLE_AUDIO_CHUNK       240   // Max bytes per BLE notify (120 16-bit samples)
#define VAD_RMS_THRESHOLD     350   // AC RMS threshold after DC-offset removal
#define SILENCE_TIMEOUT_MS    650   // Silence duration to mark end of speech
#define MAX_VOICE_DURATION_MS 6000  // Hard cap (6s) so mic never streams forever
#define POST_TURN_COOLDOWN_MS 1500  // Pause mic for 1.5s after VOICE_END while waiting for reply

// --- I2S read buffer (32-bit from INMP441) ---
int32_t i2sReadBuffer[I2S_BUFFER_SAMPLES];
// --- 16-bit converted buffer for BLE transmission ---
int16_t audioSendBuffer[I2S_BUFFER_SAMPLES];

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
unsigned long voiceStartTimeMs = 0;
unsigned long lastVoiceTimeMs = 0;
unsigned long cooldownUntilMs = 0;
double noiseFloorRms = 50.0;

// --- BLE connection state ---
bool bleConnected = false;

// --- Display text accumulation buffer (rendered on main loop) ---
String displayAccumBuffer = "";
String pendingDisplayMessage = "";
volatile bool hasPendingDisplayUpdate = false;

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// =========================================================
// OLED Display Helper
// =========================================================
void displayText(const char* text) {
  display.clearDisplay();
  display.setTextSize(1);       // 6x8 pixels per character
  display.setTextColor(SSD1306_WHITE);

  const int maxCharsPerLine = 21; // 128 / 6 = 21 chars per line
  const int maxLines = SCREEN_HEIGHT / 8;

  String lines[64];
  int lineCount = 0;
  String input(text);
  int pos = 0;
  int len = input.length();

  while (pos < len && lineCount < 64) {
    int nlPos = input.indexOf('\n', pos);
    int segEnd = (nlPos >= 0 && nlPos < pos + maxCharsPerLine) ? nlPos : -1;

    if (segEnd >= 0) {
      lines[lineCount++] = input.substring(pos, segEnd);
      pos = segEnd + 1;
      continue;
    }

    if (pos + maxCharsPerLine >= len) {
      lines[lineCount++] = input.substring(pos);
      break;
    }

    int wrapAt = -1;
    for (int i = pos + maxCharsPerLine; i > pos; i--) {
      if (input.charAt(i) == ' ') {
        wrapAt = i;
        break;
      }
    }

    if (wrapAt > pos) {
      lines[lineCount++] = input.substring(pos, wrapAt);
      pos = wrapAt + 1;
    } else {
      lines[lineCount++] = input.substring(pos, pos + maxCharsPerLine);
      pos += maxCharsPerLine;
    }
  }

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

void queueDisplayText(const String &text) {
  pendingDisplayMessage = text;
  hasPendingDisplayUpdate = true;
}

// =========================================================
// BLE Status Helper
// =========================================================
void sendStatus(const char *message) {
  Serial.println(message);
  if (bleServer != nullptr && bleServer->getConnectedCount() > 0) {
    if (statusCharacteristic != nullptr) {
      statusCharacteristic->setValue(message);
      statusCharacteristic->notify();
    }
    if (audioCharacteristic != nullptr &&
        (strncmp(message, "VOICE_", 6) == 0)) {
      audioCharacteristic->setValue((uint8_t *)message, strlen(message));
      audioCharacteristic->notify();
    }
  }
}

// =========================================================
// OTA Helpers
// =========================================================
void resetOta() {
  if (otaInProgress) {
    Update.abort();
  }
  otaInProgress = false;
  expectedFirmwareBytes = 0;
  writtenFirmwareBytes = 0;
}

// =========================================================
// BLE Callbacks
// =========================================================
class DeskBotServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    bleConnected = true;
    isVoiceActive = false;
    cooldownUntilMs = millis() + 2000; // Wait 2s after BLE connect before enabling VAD
    queueDisplayText("Connected!");
    Serial.println("BLE client connected.");
  }

  void onDisconnect(BLEServer *server) override {
    bleConnected = false;
    isVoiceActive = false;
    displayAccumBuffer = "";
    queueDisplayText("DeskBot Ready\nWaiting for BLE...");
    Serial.println("BLE client disconnected.");
    server->startAdvertising();
  }
};

class FirmwareDataCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    if (!otaInProgress) {
      return;
    }

    uint8_t *data = characteristic->getData();
    const size_t dataLength = characteristic->getLength();
    const size_t remaining = expectedFirmwareBytes - writtenFirmwareBytes;
    if (data == nullptr || dataLength == 0 || dataLength > remaining ||
        Update.write(data, dataLength) != dataLength) {
      sendStatus("OTA Firmware write failed.");
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
        sendStatus("OTA] Cannot start update.");
        return;
      }
      expectedFirmwareBytes = imageSize;
      writtenFirmwareBytes = 0;
      otaInProgress = true;
      sendStatus("OTA] Receiving firmware.");
    } else if (command == "OTA_ABORT") {
      resetOta();
      sendStatus("OTA] Cancelled.");
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

    bool shouldDisplay = false;
    int endMarkerIdx = displayAccumBuffer.indexOf("DISPLAY_END");
    if (endMarkerIdx >= 0) {
      displayAccumBuffer = displayAccumBuffer.substring(0, endMarkerIdx);
      shouldDisplay = true;
    } else if (displayAccumBuffer.endsWith("\n")) {
      shouldDisplay = true;
    }

    if (shouldDisplay) {
      displayAccumBuffer.trim();
      if (displayAccumBuffer.length() > 0) {
        queueDisplayText(displayAccumBuffer);
      }
      displayAccumBuffer = "";
    }
  }
};

// =========================================================
// BLE Initialization
// =========================================================
void startBle() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(517);

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new DeskBotServerCallbacks());

  BLEService *service = bleServer->createService(BLEUUID(String(BLE_SERVICE_UUID)), 30);

  BLECharacteristic *commandCharacteristic = service->createCharacteristic(
      BLE_COMMAND_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);

  statusCharacteristic = service->createCharacteristic(
      BLE_STATUS_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
#if !defined(CONFIG_NIMBLE_ENABLED)
  BLE2902 *status2902 = new BLE2902();
  status2902->setNotifications(true);
  statusCharacteristic->addDescriptor(status2902);
#endif

  BLECharacteristic *firmwareCharacteristic = service->createCharacteristic(
      BLE_FIRMWARE_UUID, BLECharacteristic::PROPERTY_WRITE);

  audioCharacteristic = service->createCharacteristic(
      BLE_AUDIO_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
#if !defined(CONFIG_NIMBLE_ENABLED)
  BLE2902 *audio2902 = new BLE2902();
  audio2902->setNotifications(true);
  audioCharacteristic->addDescriptor(audio2902);
#endif

  displayCharacteristic = service->createCharacteristic(
      BLE_DISPLAY_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);

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

// =========================================================
// Audio Processing & BLE Transmission (with DC Offset Removal)
// =========================================================
void sendAudioBufferOverBle(size_t samplesRead) {
  if (!bleConnected || audioCharacteristic == nullptr) {
    return;
  }
  size_t totalBytes = samplesRead * sizeof(int16_t);
  size_t offset = 0;
  while (offset < totalBytes) {
    size_t chunkSize = totalBytes - offset;
    if (chunkSize > BLE_AUDIO_CHUNK) {
      chunkSize = BLE_AUDIO_CHUNK;
    }
    audioCharacteristic->setValue((uint8_t *)audioSendBuffer + offset, chunkSize);
    audioCharacteristic->notify();
    offset += chunkSize;
    if (offset < totalBytes) {
      delay(4);
    }
  }
}

void processAudio() {
  size_t bytesRead = microphone.readBytes(
      (char *)i2sReadBuffer, sizeof(i2sReadBuffer));
  size_t samplesRead = bytesRead / sizeof(int32_t);

  if (samplesRead == 0) {
    return;
  }

  // Do not process or stream audio until BLE is connected
  if (!bleConnected) {
    return;
  }

  unsigned long now = millis();
  if (now < cooldownUntilMs) {
    return;
  }

  // PASS 1: Compute DC offset (mean of block) so INMP441 static DC bias
  // does not falsely keep RMS above VAD_RMS_THRESHOLD in silence!
  int64_t sum = 0;
  for (size_t i = 0; i < samplesRead; i++) {
    int16_t raw16 = (int16_t)(i2sReadBuffer[i] >> 16);
    audioSendBuffer[i] = raw16;
    sum += raw16;
  }
  int16_t dcOffset = (int16_t)(sum / (int64_t)samplesRead);

  // PASS 2: Subtract DC offset and compute true AC voice RMS
  int64_t sumSquares = 0;
  for (size_t i = 0; i < samplesRead; i++) {
    int32_t centered = (int32_t)audioSendBuffer[i] - (int32_t)dcOffset;
    // Apply 2x gain (+6 dB) to AC speech signal with clamping
    centered *= 2;
    if (centered > 32767) centered = 32767;
    if (centered < -32768) centered = -32768;
    audioSendBuffer[i] = (int16_t)centered;
    sumSquares += (int64_t)centered * (int64_t)centered;
  }
  double rms = sqrt((double)sumSquares / (double)samplesRead);

  // Update adaptive ambient noise floor when not speaking
  if (!isVoiceActive && rms < VAD_RMS_THRESHOLD) {
    noiseFloorRms = 0.95 * noiseFloorRms + 0.05 * rms;
  }

  double dynamicThreshold = fmax((double)VAD_RMS_THRESHOLD, noiseFloorRms * 2.5 + 150.0);

  if (rms > dynamicThreshold) {
    if (!isVoiceActive) {
      isVoiceActive = true;
      voiceStartTimeMs = now;
      sendStatus("VOICE_START");
      displayText("Listening...");
      Serial.printf("VAD: Voice started (RMS=%.0f, thresh=%.0f)\n", rms, dynamicThreshold);
    }
    lastVoiceTimeMs = now;
    sendAudioBufferOverBle(samplesRead);

    // Safety cap: if voice remains active for > 6 seconds, force end-of-turn
    if (now - voiceStartTimeMs >= MAX_VOICE_DURATION_MS) {
      isVoiceActive = false;
      cooldownUntilMs = now + POST_TURN_COOLDOWN_MS;
      sendStatus("VOICE_END");
      displayText("Thinking...");
      Serial.println("VAD: Max voice duration reached -> VOICE_END");
    }
  } else if (isVoiceActive) {
    // Below threshold - send trailing audio until silence timeout
    sendAudioBufferOverBle(samplesRead);

    if (now - lastVoiceTimeMs >= SILENCE_TIMEOUT_MS ||
        now - voiceStartTimeMs >= MAX_VOICE_DURATION_MS) {
      isVoiceActive = false;
      cooldownUntilMs = now + POST_TURN_COOLDOWN_MS;
      sendStatus("VOICE_END");
      displayText("Thinking...");
      Serial.println("VAD: Voice ended");
    }
  }
}

// =========================================================
// Setup
// =========================================================
void setup() {
  Serial.begin(115200);
  startBle();
  delay(1500);

  Wire.begin(DISPLAY_SDA, DISPLAY_SCL);

  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println(F("SSD1306 allocation failed"));
    while (true);
  }

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

// =========================================================
// Main Loop
// =========================================================
void loop() {
  if (hasPendingDisplayUpdate) {
    hasPendingDisplayUpdate = false;
    String msgToRender = pendingDisplayMessage;
    displayText(msgToRender.c_str());
  }

  if (otaInProgress) {
    delay(20);
    return;
  }

  processAudio();
}