#include <HTTPClient.h>
#include <HTTPUpdate.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>

const char *ssid = "HONOR X7b";
const char *password = "marjppu36u272u6";

const String CURRENT_VERSION = "v1.0.0";

// Direct URL to your latest firmware.bin in GitHub Releases
// Format:
// https://github.com/<owner>/<repo>/releases/latest/download/firmware.bin
const String FIRMWARE_URL = "https://github.com/ThenulSahansa/DeskBot/releases/"
                            "latest/download/firmware.ino.bin";

// Check interval (e.g., check every 10 minutes)
unsigned long lastCheck = 0;

void checkForUpdate() {
  Serial.println("[OTA] Checking for firmware updates...");

  WiFiClientSecure client;
  // For testing, setInsecure() bypasses SSL certificate chain validation.
  // For production, supply the root certificate to prevent MITM attacks.
  client.setInsecure();

  // Allow following GitHub's 302 redirect to the CDN storage
  httpUpdate.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);

  // Perform the update
  t_httpUpdate_return ret = httpUpdate.update(client, FIRMWARE_URL);

  switch (ret) {
  case HTTP_UPDATE_FAILED:
    Serial.printf("[OTA] Update failed (%d): %s\n", httpUpdate.getLastError(),
                  httpUpdate.getLastErrorString().c_str());
    break;

  case HTTP_UPDATE_NO_UPDATES:
    Serial.println("[OTA] No update available.");
    break;

  case HTTP_UPDATE_OK:
    Serial.println("[OTA] Update succeeded! Rebooting...");
    // The ESP32 restarts automatically on success
    break;
  }
}

void setup() {
  Serial.begin(115200);
  WiFi.begin(ssid, password);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\n[WiFi] Connected!");
  Serial.printf("Current Version: %s\n", CURRENT_VERSION.c_str());

  // Check on boot
  checkForUpdate();
  pinMode(2, OUTPUT);
}

void loop() {
  digitalWrite(2, HIGH);
  delay(200);
  digitalWrite(2, LOW);
  delay(200);
}
