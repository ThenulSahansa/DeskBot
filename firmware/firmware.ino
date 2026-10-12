
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

// GitHub repository
const char *RELEASE_API =
    "https://api.github.com/repos/ThenulSahansa/DeskBot/releases/latest";

const char *FIRMWARE_ASSET = "firmware.ino.bin";

String latestVersion = "";
String firmwareURL = "";

void checkForUpdate();

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

void checkForUpdate() {
  Serial.println("\n[OTA] Checking GitHub for updates...");

  Serial.printf("[OTA] Installed version: %s\n", FIRMWARE_VERSION);

  if (!getLatestRelease()) {
    Serial.println("[OTA] Could not obtain release information.");
    return;
  }

  Serial.printf("[OTA] Latest version:    %s\n", latestVersion.c_str());

  if (latestVersion == FIRMWARE_VERSION) {
    Serial.println("[OTA] Already up to date.");
    return;
  }

  Serial.println("[OTA] New version detected.");

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

  Serial.println("[OTA] Starting firmware installation...");

  t_httpUpdate_return result =
      httpUpdate.update(client, firmwareURL, FIRMWARE_VERSION);

  if (result == HTTP_UPDATE_FAILED) {
    Serial.printf("[OTA] Error %d: %s\n", httpUpdate.getLastError(),
                  httpUpdate.getLastErrorString().c_str());
  } else if (result == HTTP_UPDATE_NO_UPDATES) {
    Serial.println("[OTA] No update installed.");
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

  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);

  Serial.print("[WiFi] Connecting");

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 30000) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[WiFi] Connection failed.");
    return;
  }

  Serial.printf("[WiFi] Connected: %s\n", WiFi.localIP().toString().c_str());

  checkForUpdate();
}

void loop() {
  // Run your normal DeskBot functionality here.
}
