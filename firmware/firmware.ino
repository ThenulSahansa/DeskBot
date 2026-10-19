#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <Arduino.h>
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
// This is a BLE write service used by the companion Android app.
constexpr char BLE_SERVICE_UUID[] = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_RX_UUID[] = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
BLEServer *bleServer = nullptr;

// The app decides whether an update exists. The C3 only downloads this URL.
const char *DIRECT_FIRMWARE_URL =
    "https://github.com/ThenulSahansa/DeskBot/releases/latest/download/firmware.ino.bin";

bool connectWiFi(const char *wifiSsid = ssid, const char *wifiPassword = password);
void performUpdate();
void startBle();

class DeskBotServerCallbacks : public BLEServerCallbacks {
  void onDisconnect(BLEServer *server) override {
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

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[OTA] Wi-Fi not connected. Connecting...");
    if (!connectWiFi(ssid, password)) {
      Serial.println("[OTA] Could not connect to Wi-Fi. Update aborted.");
      return;
    }
  }

  Serial.printf("[OTA] Free heap with BT running: %u bytes (Max contiguous block: %u bytes)\n",
                (unsigned int)ESP.getFreeHeap(), (unsigned int)ESP.getMaxAllocHeap());

  delay(300);

  // Stop BLE before the TLS download. BLE can be recreated if the update fails.
  BLEDevice::deinit(true);
  bleServer = nullptr;

  Serial.printf("[OTA] Free heap after stopping BLE: %u bytes (Max contiguous block: %u bytes)\n",
                (unsigned int)ESP.getFreeHeap(), (unsigned int)ESP.getMaxAllocHeap());

  Serial.printf("[OTA] Downloading and installing firmware from: %s\n",
                DIRECT_FIRMWARE_URL);

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

  t_httpUpdate_return result = httpUpdate.update(client, DIRECT_FIRMWARE_URL);

  if (result == HTTP_UPDATE_FAILED) {
    Serial.printf("[OTA] Error %d: %s\n", httpUpdate.getLastError(),
                  httpUpdate.getLastErrorString().c_str());
    startBle();
  } else if (result == HTTP_UPDATE_NO_UPDATES) {
    Serial.println("[OTA] No update installed.");
    startBle();
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
