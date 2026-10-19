#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Arduino.h>
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <HTTPUpdate.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>


// Wi-Fi
const char *ssid = "HONOR X7b";
const char *password = "marjppu36u272u6";

// Version compiled into THIS firmware.
// Change this before building each new release.
#define FIRMWARE_VERSION "v1.0.4"
String device_name = "DeskBot_" FIRMWARE_VERSION;

// ESP32-C3 supports Bluetooth Low Energy (BLE), not Bluetooth Classic SPP.
// This is a Nordic-UART-style service used by the companion Android app.
constexpr char BLE_SERVICE_UUID[] = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_RX_UUID[] = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_TX_UUID[] = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
BLEServer *bleServer = nullptr;
BLECharacteristic *bleTxCharacteristic = nullptr;
bool bleClientConnected = false;

// GitHub repository
const char *RELEASE_API =
    "https://api.github.com/repos/ThenulSahansa/DeskBot/releases/latest";

const char *DIRECT_FIRMWARE_URL =
    "https://github.com/ThenulSahansa/DeskBot/releases/latest/download/firmware.ino.bin";

const char *FIRMWARE_ASSET = "firmware.ino.bin";

String latestVersion = "";
String firmwareURL = "";

bool connectWiFi(const char *wifiSsid = ssid, const char *wifiPassword = password);
void performUpdate();
void startBle();

void blePrint(const String &message) {
  Serial.println(message);
  if (bleClientConnected && bleTxCharacteristic != nullptr) {
    bleTxCharacteristic->setValue(message.c_str());
    bleTxCharacteristic->notify();
  }
}

class DeskBotServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override { bleClientConnected = true; }
  void onDisconnect(BLEServer *server) override {
    bleClientConnected = false;
    server->startAdvertising();
  }
};

class DeskBotCommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    String command = characteristic->getValue().c_str();
    command.trim();
    Serial.printf("[BLE] Received: %s\n", command.c_str());
    if (command.equalsIgnoreCase("Update")) {
      performUpdate();
    }
  }
};

void startBle() {
  BLEDevice::init(device_name.c_str());
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new DeskBotServerCallbacks());

  BLEService *service = bleServer->createService(BLE_SERVICE_UUID);
  bleTxCharacteristic = service->createCharacteristic(
      BLE_TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  bleTxCharacteristic->addDescriptor(new BLE2902());
  BLECharacteristic *rxCharacteristic = service->createCharacteristic(
      BLE_RX_UUID, BLECharacteristic::PROPERTY_WRITE |
                       BLECharacteristic::PROPERTY_WRITE_NR);
  rxCharacteristic->setCallbacks(new DeskBotCommandCallbacks());
  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->start();
  Serial.printf("BLE device \"%s\" is advertising.\n", device_name.c_str());
}

bool getLatestRelease() {
  WiFiClientSecure client;
  client.setInsecure(); // Testing only
  client.setTimeout(30000);

  HTTPClient http;
  http.setTimeout(30000);
  http.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);

  Serial.println("[API] Requesting GitHub release metadata...");

  if (!http.begin(client, RELEASE_API)) {
    Serial.println("[API] Failed to initialize HTTP.");
    return false;
  }

  http.addHeader("Accept", "application/vnd.github+json");
  http.addHeader("User-Agent", "DeskBot-ESP32");

  int status = http.GET();

  Serial.printf("[API] HTTP status: %d\n", status);

  if (status != HTTP_CODE_OK) {
    Serial.printf("[API] Request failed: %s\n",
                  HTTPClient::errorToString(status).c_str());
    http.end();
    return false;
  }

  // Read the complete response before parsing JSON.
  String response = http.getString();

  Serial.printf("[API] Response size: %u bytes\n",
                (unsigned int)response.length());

  http.end();

  if (response.isEmpty()) {
    Serial.println("[API] Empty response from GitHub.");
    return false;
  }

  JsonDocument doc;

  DeserializationError error = deserializeJson(doc, response);

  if (error) {
    Serial.printf("[API] JSON parsing failed: %s\n", error.c_str());
    Serial.printf("[API] Response preview: %.250s\n", response.c_str());
    return false;
  }

  const char *tag = doc["tag_name"] | "";

  if (strlen(tag) == 0) {
    Serial.println("[API] Missing tag_name in release metadata.");
    return false;
  }

  latestVersion = String(tag);
  firmwareURL = "";

  JsonArray assets = doc["assets"].as<JsonArray>();

  for (JsonObject asset : assets) {
    const char *name = asset["name"] | "";

    if (String(name) == FIRMWARE_ASSET) {
      const char *url = asset["browser_download_url"] | "";
      firmwareURL = String(url);
      break;
    }
  }

  Serial.printf("[API] Latest release: %s\n", latestVersion.c_str());

  if (firmwareURL.isEmpty()) {
    Serial.printf("[API] Asset '%s' not found in this release.\n",
                  FIRMWARE_ASSET);
    return false;
  }

  Serial.printf("[API] Firmware URL: %s\n", firmwareURL.c_str());

  return true;
}

bool connectWiFi(const char *wifiSsid, const char *wifiPassword) {
  if (WiFi.status() == WL_CONNECTED) {
    return true;
  }

  WiFi.mode(WIFI_STA);
  WiFi.begin(wifiSsid, wifiPassword);
  Serial.print("[WiFi] Connecting");

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 30000) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[WiFi] Connection failed.");
    return false;
  }

  Serial.printf("[WiFi] Connected! IP: %s, Gateway: %s\n",
                WiFi.localIP().toString().c_str(),
                WiFi.gatewayIP().toString().c_str());

  // Test DNS resolution for GitHub
  IPAddress resolvedIP;
  if (!WiFi.hostByName("api.github.com", resolvedIP)) {
    Serial.println("[WiFi] Warning: Hotspot DNS failed. Setting Google/Cloudflare DNS (8.8.8.8)...");
    WiFi.config(WiFi.localIP(), WiFi.gatewayIP(), WiFi.subnetMask(),
                IPAddress(8, 8, 8, 8), IPAddress(1, 1, 1, 1));
    if (WiFi.hostByName("api.github.com", resolvedIP)) {
      Serial.printf("[WiFi] DNS resolved via 8.8.8.8: %s\n", resolvedIP.toString().c_str());
    } else {
      Serial.println("[WiFi] Error: Could not resolve api.github.com. Verify mobile data on hotspot.");
    }
  } else {
    Serial.printf("[WiFi] DNS OK. api.github.com: %s\n", resolvedIP.toString().c_str());
  }

  return true;
}

void performUpdate() {
  Serial.println("\n[OTA] Update triggered via BLE. Starting update...");
  blePrint("[OTA] Update triggered via BLE. Starting update...");

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[OTA] Wi-Fi not connected. Connecting...");
    blePrint("[OTA] Connecting to Wi-Fi...");
    if (!connectWiFi(ssid, password)) {
      Serial.println("[OTA] Could not connect to Wi-Fi. Update aborted.");
      blePrint("[OTA] Wi-Fi connection failed. Update aborted.");
      return;
    }
  }

  Serial.printf("[OTA] Free heap with BT running: %u bytes (Max contiguous block: %u bytes)\n",
                (unsigned int)ESP.getFreeHeap(), (unsigned int)ESP.getMaxAllocHeap());

  // Notify the app before stopping BLE to free memory.
  blePrint("[OTA] Wi-Fi connected. Freeing BLE memory for TLS download...");
  delay(300);

  // Stop BLE before the TLS download. BLE can be recreated if the update fails.
  BLEDevice::deinit(true);
  bleServer = nullptr;
  bleTxCharacteristic = nullptr;
  bleClientConnected = false;

  Serial.printf("[OTA] Free heap after stopping BLE: %u bytes (Max contiguous block: %u bytes)\n",
                (unsigned int)ESP.getFreeHeap(), (unsigned int)ESP.getMaxAllocHeap());

  // Obtain the latest release asset URL from GitHub
  if (!getLatestRelease() || firmwareURL.isEmpty()) {
    Serial.println("[OTA] Using direct GitHub latest release URL...");
    firmwareURL = DIRECT_FIRMWARE_URL;
  }

  if (!latestVersion.isEmpty()) {
    Serial.printf("[OTA] GitHub latest version: %s\n", latestVersion.c_str());
  }

  Serial.printf("[OTA] Downloading and installing firmware from: %s\n", firmwareURL.c_str());

  WiFiClientSecure client;
  client.setInsecure(); // Testing only; validate TLS in production.
  client.setTimeout(30000);

  httpUpdate.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);
  httpUpdate.rebootOnUpdate(true);

  httpUpdate.onProgress([](int current, int total) {
    if (total > 0) {
      Serial.printf("[OTA] Download: %d%%\n", (current * 100) / total);
    }
  });

  // Directly perform update without any version comparison
  t_httpUpdate_return result = httpUpdate.update(client, firmwareURL);

  if (result == HTTP_UPDATE_FAILED) {
    Serial.printf("[OTA] Error %d: %s\n", httpUpdate.getLastError(),
                  httpUpdate.getLastErrorString().c_str());
    startBle();
    blePrint("[OTA] Update failed (" + String(httpUpdate.getLastError()) +
             "): " + httpUpdate.getLastErrorString());
  } else if (result == HTTP_UPDATE_NO_UPDATES) {
    Serial.println("[OTA] No update installed.");
    startBle();
    blePrint("[OTA] No update installed.");
  } else if (result == HTTP_UPDATE_OK) {
    Serial.println("[OTA] Update successful; rebooting.");
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("\n========== DeskBot ==========");
  Serial.printf("Installed version: %s\n", FIRMWARE_VERSION);
  Serial.printf("Flash size: %u bytes\n", (unsigned int)ESP.getFlashChipSize());
  Serial.printf("OTA free space: %u bytes\n",
                (unsigned int)ESP.getFreeSketchSpace());

  startBle();
}

void loop() {
  delay(20);
}
