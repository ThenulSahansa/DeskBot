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
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>

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
#define I2S_BUFFER_SAMPLES    256   // 16 ms per block
#define BLE_AUDIO_CHUNK       240   // Max bytes per BLE notify (120 16-bit samples)

// Digital gain applied to the DC-free 24-bit mic signal when converting to 16-bit PCM.
// Kept modest on purpose: the Android app runs an AGC that brings speech to a good level
// without clipping. (Old firmware: >>16 plus 2x, i.e. effectively the same as here.)
#define DIGITAL_GAIN          2.0f

// Voice activity detection. RMS is measured on the DC-free, gained 16-bit signal.
// Start threshold = max(VAD_START_RMS_MIN, noiseFloor * VAD_NOISE_MULT + VAD_NOISE_MARGIN).
// The old fixed threshold of 350 was ~6x too high for an INMP441: it only triggered on the loudest
// syllables, cut off the start of every sentence and let quiet speech through as silence.
#define VAD_START_RMS_MIN     70.0f
#define VAD_NOISE_MULT        3.0f
#define VAD_NOISE_MARGIN      30.0f
#define VAD_KEEPALIVE_RATIO   0.5f  // while talking, only 50% of the start threshold keeps the turn open
#define VAD_START_BLOCKS      2     // consecutive loud blocks (32 ms) needed to start; rejects clicks
#define SILENCE_TIMEOUT_MS    700   // Silence duration to mark end of speech
#define MAX_VOICE_DURATION_MS 20000 // Hard cap so the mic never streams forever
#define POST_TURN_COOLDOWN_MS 300   // Short pause after VOICE_END (the device has no speaker to echo)
#define PREROLL_BLOCKS        20    // 320 ms of audio kept from BEFORE the VAD triggered

// BLE audio is sent from its own task through this queue, so a slow/congested BLE link or an
// OLED redraw can never stall the I2S read loop (the I2S DMA only buffers 6 x 240 samples = 90 ms).
#define AUDIO_QUEUE_DEPTH     128   // ~1 s of audio
#define BLE_NOTIFY_GAP_MS     3     // pacing between notifications

#define VAD_DEBUG             0     // 1 = print RMS / noise floor / threshold to Serial every 2 s

// --- I2S read buffer (32-bit from INMP441) ---
int32_t i2sReadBuffer[I2S_BUFFER_SAMPLES];
// --- 16-bit converted buffer for BLE transmission ---
int16_t audioSendBuffer[I2S_BUFFER_SAMPLES];

// --- Pre-roll ring: the last PREROLL_BLOCKS blocks, sent first when speech is detected ---
int16_t prerollBuffer[PREROLL_BLOCKS][I2S_BUFFER_SAMPLES];
size_t prerollSamples[PREROLL_BLOCKS];
int prerollHead = 0;   // next slot to write
int prerollCount = 0;  // valid slots

// --- BLE audio queue (filled by processAudio(), drained by bleSenderTask()) ---
struct AudioPacket {
  uint8_t len;
  uint8_t isMarker;                // 1 = VOICE_START / VOICE_END text marker
  uint8_t data[BLE_AUDIO_CHUNK];
};
QueueHandle_t audioQueue = nullptr;
volatile uint32_t droppedAudioPackets = 0;

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
unsigned long cooldownUntilMs = 1000; // ignore the DC-filter start-up transient after boot
double noiseFloorRms = 20.0;
int loudBlockCount = 0;

// DC-blocking filter state (kept across blocks so block boundaries stay seamless)
float dcPrevIn = 0.0f;
float dcPrevOut = 0.0f;

// --- BLE connection state ---
bool bleConnected = false;

// --- Display text accumulation buffer (for BLE chunked reception) ---
String displayAccumBuffer = "";

// --- Display animation state machine ---
enum class DisplayState {
  IDLE,
  LISTENING,
  SPEAKING,
  THINKING
};

volatile DisplayState currentDisplayState = DisplayState::IDLE;
volatile DisplayState targetDisplayState  = DisplayState::IDLE;
unsigned long lastAnimFrameMs = 0;
int animFrame = 0;
String speakingText = ""; // stores the Gemini reply text shown while SPEAKING

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// =========================================================
// OLED Display Helpers — Static text (boot/error messages only)
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
  // Routes display intent to the animation state machine.
  // Called from BLE callbacks (BLE thread); targetDisplayState is volatile.
  if (text == "Listening...") {
    targetDisplayState = DisplayState::LISTENING;
  } else if (text == "Thinking...") {
    targetDisplayState = DisplayState::THINKING;
  } else if (text.startsWith("DeskBot") || text == "Connected!") {
    targetDisplayState = DisplayState::IDLE;
  } else {
    // Gemini reply text sent from Android → show SPEAKING animation
    speakingText = text;
    targetDisplayState = DisplayState::SPEAKING;
  }
}

// =========================================================
// OLED Animated State Drawing Functions
// =========================================================

// IDLE — Robot eyes with periodic blink every 3 s (150 ms blink duration)
void drawIdleState(unsigned long now) {
  bool isBlinking = (now % 3000) < 150;
  display.clearDisplay();
  int eyeH = isBlinking ? 4 : 20;
  // Left eye
  display.fillRoundRect(26, 28 - eyeH / 2, 28, eyeH, eyeH / 2, SSD1306_WHITE);
  // Right eye
  display.fillRoundRect(74, 28 - eyeH / 2, 28, eyeH, eyeH / 2, SSD1306_WHITE);
  if (!isBlinking) {
    // Eye shine / highlight dots
    display.fillRect(32, 22, 3, 3, SSD1306_BLACK);
    display.fillRect(80, 22, 3, 3, SSD1306_BLACK);
  }
  display.display();
}

// LISTENING — Animated soundwave bars
void drawListeningState(unsigned long now) {
  display.clearDisplay();
  const int numBars = 7;
  const int barW    = 8;
  const int gap     = 6;
  const int totalW  = numBars * barW + (numBars - 1) * gap;
  const int startX  = (128 - totalW) / 2;
  const int centerY = 32;
  float phase = (now % 800) / 800.0f * 2 * PI;
  for (int i = 0; i < numBars; i++) {
    float wave = sin(phase + i * 0.7f);
    int h = (int)(10 + 18 * ((wave + 1) / 2.0f));
    int x = startX + i * (barW + gap);
    display.fillRoundRect(x, centerY - h / 2, barW, h, 3, SSD1306_WHITE);
  }
  display.display();
}

// SPEAKING — Eyes (no blink) + small soundwave at mouth area
void drawSpeakingState(unsigned long now) {
  display.clearDisplay();
  // Eyes
  display.fillRoundRect(26, 18, 28, 20, 10, SSD1306_WHITE);
  display.fillRoundRect(74, 18, 28, 20, 10, SSD1306_WHITE);
  display.fillRect(32, 20, 3, 3, SSD1306_BLACK);
  display.fillRect(80, 20, 3, 3, SSD1306_BLACK);
  // Small soundwave at mouth area (y≈52)
  const int numBars = 5;
  const int barW    = 5;
  const int gap     = 4;
  const int totalW  = numBars * barW + (numBars - 1) * gap;
  const int startX  = (128 - totalW) / 2;
  float phase = (now % 600) / 600.0f * 2 * PI;
  for (int i = 0; i < numBars; i++) {
    float wave = sin(phase + i * 0.9f);
    int h = (int)(4 + 8 * ((wave + 1) / 2.0f));
    int x = startX + i * (barW + gap);
    display.fillRoundRect(x, 52 - h / 2, barW, h, 2, SSD1306_WHITE);
  }
  display.display();
}

// THINKING — Eyes looking upward + animated thinking dots
void drawThinkingState(unsigned long now) {
  display.clearDisplay();
  // Eye whites
  display.fillRoundRect(26, 18, 28, 22, 10, SSD1306_WHITE);
  display.fillRoundRect(74, 18, 28, 22, 10, SSD1306_WHITE);
  // Pupils shifted up (looking up)
  display.fillCircle(40, 20, 7, SSD1306_BLACK);
  display.fillCircle(88, 20, 7, SSD1306_BLACK);
  // Shine dots
  display.fillRect(37, 17, 3, 3, SSD1306_WHITE);
  display.fillRect(85, 17, 3, 3, SSD1306_WHITE);
  // Animated thinking dots (...)
  float dotPhase = (now % 1200) / 1200.0f;
  for (int i = 0; i < 3; i++) {
    float brightness = sin((dotPhase + i * 0.33f) * PI);
    if (brightness > 0.5f) {
      display.fillCircle(55 + i * 10, 52, 3, SSD1306_WHITE);
    }
  }
  display.display();
}

// =========================================================
// OLED Animation Update — call from loop() at ~30 fps
// =========================================================
void updateDisplayAnimation() {
  unsigned long now = millis();
  if (now - lastAnimFrameMs < 33) return; // ~30 fps
  lastAnimFrameMs = now;

  // Handle state transition: brief black flash, then switch state
  if (targetDisplayState != currentDisplayState) {
    display.clearDisplay();
    display.display();
    delay(60);
    currentDisplayState = targetDisplayState;
    animFrame = 0;
  }

  switch (currentDisplayState) {
    case DisplayState::IDLE:      drawIdleState(now);      break;
    case DisplayState::LISTENING: drawListeningState(now); break;
    case DisplayState::SPEAKING:  drawSpeakingState(now);  break;
    case DisplayState::THINKING:  drawThinkingState(now);  break;
  }
  animFrame++;
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
  }
}

// VOICE_START / VOICE_END go through the same queue as the audio so they can never overtake it.
void enqueueMarker(const char *marker) {
  Serial.println(marker);
  if (audioQueue == nullptr) {
    return;
  }
  AudioPacket pkt;
  pkt.isMarker = 1;
  pkt.len = (uint8_t)strlen(marker);
  memcpy(pkt.data, marker, pkt.len);
  if (xQueueSend(audioQueue, &pkt, pdMS_TO_TICKS(20)) != pdTRUE) {
    droppedAudioPackets++;
  }
}

void enqueueAudioBlock(const int16_t *samples, size_t sampleCount) {
  if (audioQueue == nullptr) {
    return;
  }
  const uint8_t *bytes = (const uint8_t *)samples;
  const size_t total = sampleCount * sizeof(int16_t);
  size_t offset = 0;
  while (offset < total) {
    size_t n = total - offset;
    if (n > BLE_AUDIO_CHUNK) {
      n = BLE_AUDIO_CHUNK;
    }
    AudioPacket pkt;
    pkt.isMarker = 0;
    pkt.len = (uint8_t)n;
    memcpy(pkt.data, bytes + offset, n);
    if (xQueueSend(audioQueue, &pkt, 0) != pdTRUE) {
      droppedAudioPackets++;
    }
    offset += n;
  }
}

// Runs in its own FreeRTOS task: paces BLE notifications so the main loop never blocks on them.
void bleSenderTask(void *param) {
  AudioPacket pkt;
  for (;;) {
    if (xQueueReceive(audioQueue, &pkt, portMAX_DELAY) != pdTRUE) {
      continue;
    }
    if (!bleConnected || audioCharacteristic == nullptr) {
      continue;  // nobody listening: drop
    }
    if (pkt.isMarker) {
      if (statusCharacteristic != nullptr) {
        statusCharacteristic->setValue(pkt.data, pkt.len);
        statusCharacteristic->notify();
        vTaskDelay(pdMS_TO_TICKS(BLE_NOTIFY_GAP_MS));
      }
    }
    audioCharacteristic->setValue(pkt.data, pkt.len);
    audioCharacteristic->notify();
    vTaskDelay(pdMS_TO_TICKS(BLE_NOTIFY_GAP_MS));
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
    loudBlockCount = 0;
    prerollCount = 0;
    if (audioQueue != nullptr) xQueueReset(audioQueue);
    cooldownUntilMs = millis() + 2000; // Wait 2s after BLE connect (MTU / CCCD setup) before enabling VAD
    queueDisplayText("Connected!");
    Serial.println("BLE client connected.");
  }

  void onDisconnect(BLEServer *server) override {
    bleConnected = false;
    isVoiceActive = false;
    loudBlockCount = 0;
    if (audioQueue != nullptr) xQueueReset(audioQueue);
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
// Audio Processing, VAD and pre-roll
// =========================================================
void endVoiceTurn(unsigned long now, const char *reason) {
  isVoiceActive = false;
  loudBlockCount = 0;
  cooldownUntilMs = now + POST_TURN_COOLDOWN_MS;
  enqueueMarker("VOICE_END");
  queueDisplayText("Thinking...");
  Serial.printf("VAD: Voice ended (%s)\n", reason);
  if (droppedAudioPackets > 0) {
    Serial.printf("WARNING: %u audio packets dropped (BLE too slow?)\n", (unsigned)droppedAudioPackets);
    droppedAudioPackets = 0;
  }
}

void processAudio() {
  size_t bytesRead = microphone.readBytes(
      (char *)i2sReadBuffer, sizeof(i2sReadBuffer));
  size_t samplesRead = bytesRead / sizeof(int32_t);

  if (samplesRead == 0) {
    return;
  }

  unsigned long now = millis();

  // 1) Convert INMP441 24-bit-in-32 samples to gained 16-bit PCM. A one-pole DC blocker with state
  //    kept across blocks removes the mic's DC bias (the old per-block mean subtraction put a step
  //    at every 16 ms block boundary = audible 62 Hz buzz that hurt speech recognition).
  //    This always runs - even while idle or disconnected - so the filter and noise floor are
  //    settled before the first utterance.
  double sumSquares = 0.0;
  for (size_t i = 0; i < samplesRead; i++) {
    float x = (float)(i2sReadBuffer[i] >> 8);            // signed 24-bit sample
    float y = x - dcPrevIn + 0.995f * dcPrevOut;         // DC blocker (~13 Hz corner)
    dcPrevIn = x;
    dcPrevOut = y;
    float v = y * (DIGITAL_GAIN / 256.0f);               // 24-bit -> 16-bit scale, with gain
    if (v > 32767.0f) v = 32767.0f;
    if (v < -32768.0f) v = -32768.0f;
    audioSendBuffer[i] = (int16_t)v;
    sumSquares += (double)v * (double)v;
  }
  double rms = sqrt(sumSquares / (double)samplesRead);

  // 2) Adaptive noise floor + thresholds (hysteresis: lower threshold keeps an open turn alive).
  double startThreshold = fmax((double)VAD_START_RMS_MIN,
                               noiseFloorRms * VAD_NOISE_MULT + VAD_NOISE_MARGIN);
  double keepThreshold = startThreshold * VAD_KEEPALIVE_RATIO;

  if (!isVoiceActive) {
    // Follow ambient noise quickly when it is quiet, very slowly when it is loud, so a permanently
    // noisier room eventually stops being treated as speech.
    double rate = (rms < startThreshold) ? 0.02 : 0.002;
    noiseFloorRms = (1.0 - rate) * noiseFloorRms + rate * rms;
  }

#if VAD_DEBUG
  static unsigned long lastDebugMs = 0;
  if (now - lastDebugMs >= 2000) {
    lastDebugMs = now;
    Serial.printf("[VAD] rms=%.0f noise=%.0f start=%.0f keep=%.0f\n", rms, noiseFloorRms, startThreshold, keepThreshold);
  }
#endif

  // Before the turn starts, every block goes into the pre-roll ring.
  if (!isVoiceActive) {
    memcpy(prerollBuffer[prerollHead], audioSendBuffer, samplesRead * sizeof(int16_t));
    prerollSamples[prerollHead] = samplesRead;
    prerollHead = (prerollHead + 1) % PREROLL_BLOCKS;
    if (prerollCount < PREROLL_BLOCKS) prerollCount++;

    if (!bleConnected || now < cooldownUntilMs) {
      loudBlockCount = 0;
      return;
    }

    if (rms > startThreshold) {
      loudBlockCount++;
    } else {
      loudBlockCount = 0;
    }

    if (loudBlockCount >= VAD_START_BLOCKS) {
      isVoiceActive = true;
      voiceStartTimeMs = now;
      lastVoiceTimeMs = now;
      loudBlockCount = 0;
      enqueueMarker("VOICE_START");

      // Send the audio from just BEFORE the trigger (oldest first, includes the current block),
      // so the first syllables of the sentence are not cut off.
      int first = (prerollHead - prerollCount + PREROLL_BLOCKS) % PREROLL_BLOCKS;
      for (int k = 0; k < prerollCount; k++) {
        int idx = (first + k) % PREROLL_BLOCKS;
        enqueueAudioBlock(prerollBuffer[idx], prerollSamples[idx]);
      }
      prerollCount = 0;

      queueDisplayText("Listening...");
      Serial.printf("VAD: Voice started (RMS=%.0f, thresh=%.0f, noise=%.0f)\n", rms, startThreshold, noiseFloorRms);
    }
    return;
  }

  // Turn is open: stream everything (including natural pauses) until real silence.
  enqueueAudioBlock(audioSendBuffer, samplesRead);
  if (rms > keepThreshold) {
    lastVoiceTimeMs = now;
  }

  if (now - voiceStartTimeMs >= MAX_VOICE_DURATION_MS) {
    endVoiceTurn(now, "max duration");
  } else if (now - lastVoiceTimeMs >= SILENCE_TIMEOUT_MS) {
    endVoiceTurn(now, "silence");
  }
}

// =========================================================
// Setup
// =========================================================
void setup() {
  Serial.begin(115200);

  audioQueue = xQueueCreate(AUDIO_QUEUE_DEPTH, sizeof(AudioPacket));
  if (audioQueue == nullptr) {
    Serial.println("Failed to allocate audio queue!");
  }
  xTaskCreate(bleSenderTask, "bleTx", 4096, nullptr, 2, nullptr);

  startBle();
  delay(1500);

  Wire.begin(DISPLAY_SDA, DISPLAY_SCL);
  Wire.setClock(400000);

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
  if (otaInProgress) {
    delay(20);
    return;
  }
  updateDisplayAnimation();
  processAudio();
}