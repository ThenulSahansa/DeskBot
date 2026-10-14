#include "BluetoothSerial.h"
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

// Check if Bluetooth is available
#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled! Please run `make menuconfig` to and enable it
#endif

// Check Serial Port Profile
#if !defined(CONFIG_BT_SPP_ENABLED)
#error Serial Port Profile for Bluetooth is not available or not enabled. It is only available for the ESP32 chip.
#endif

BluetoothSerial SerialBT;

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
  Serial.println("\n[OTA] Update triggered via Bluetooth Serial. Starting update...");
  SerialBT.println("[OTA] Update triggered via Bluetooth Serial. Starting update...");

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[OTA] Wi-Fi not connected. Connecting...");
    SerialBT.println("[OTA] Connecting to Wi-Fi...");
    if (!connectWiFi(ssid, password)) {
      Serial.println("[OTA] Could not connect to Wi-Fi. Update aborted.");
      SerialBT.println("[OTA] Wi-Fi connection failed. Update aborted.");
      return;
    }
  }

  Serial.printf("[OTA] Free heap with BT running: %u bytes (Max contiguous block: %u bytes)\n",
                (unsigned int)ESP.getFreeHeap(), (unsigned int)ESP.getMaxAllocHeap());

  // Notify user over Bluetooth before stopping BT to free memory
  SerialBT.println("[OTA] Wi-Fi connected. Freeing Bluetooth memory for TLS download...");
  SerialBT.flush();
  delay(300);

  // Stop Bluetooth stack to free ~100KB heap for TLS / mbedTLS buffers
  SerialBT.end();

  Serial.printf("[OTA] Free heap after stopping BT: %u bytes (Max contiguous block: %u bytes)\n",
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
    // Restore Bluetooth so device can still communicate after a failure
    SerialBT.begin(device_name);
    SerialBT.printf("[OTA] Update failed (%d): %s\n", httpUpdate.getLastError(),
                    httpUpdate.getLastErrorString().c_str());
  } else if (result == HTTP_UPDATE_NO_UPDATES) {
    Serial.println("[OTA] No update installed.");
    SerialBT.begin(device_name);
    SerialBT.println("[OTA] No update installed.");
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

  SerialBT.begin(device_name);
  Serial.printf("The device with name \"%s\" is started.\nNow you can pair it "
                "with Bluetooth!\n",
                device_name.c_str());
}

void loop() {
  // Forward characters from USB Serial to Bluetooth
  while (Serial.available()) {
    SerialBT.write(Serial.read());
  }

  // Forward characters from Bluetooth to USB Serial and listen for Update command
  static String btBuffer = "";
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    Serial.write(c);

    if (c == '\n' || c == '\r') {
      btBuffer.trim();
      if (btBuffer.equalsIgnoreCase("Update")) {
        performUpdate();
      }
      btBuffer = "";
    } else {
      btBuffer += c;
      if (btBuffer.equalsIgnoreCase("Update")) {
        performUpdate();
        btBuffer = "";
      }
    }

    if (btBuffer.length() > 64) {
      btBuffer = "";
    }
  }

  delay(20);
}
