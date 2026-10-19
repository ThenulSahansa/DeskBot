#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <Update.h>

// ESP32-C3 supports Bluetooth Low Energy (BLE), not Bluetooth Classic SPP.
// The Android app checks GitHub, downloads the binary, then sends it here.
constexpr char DEVICE_NAME[] = "DeskBot";
constexpr char BLE_SERVICE_UUID[] = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_COMMAND_UUID[] = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_STATUS_UUID[] = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr char BLE_FIRMWARE_UUID[] = "6E400004-B5A3-F393-E0A9-E50E24DCCA9E";

BLEServer *bleServer = nullptr;
BLECharacteristic *statusCharacteristic = nullptr;
bool otaInProgress = false;
size_t expectedFirmwareBytes = 0;
size_t writtenFirmwareBytes = 0;

void sendStatus(const char *message) {
  Serial.println(message);
  if (statusCharacteristic != nullptr && bleServer != nullptr &&
      bleServer->getConnectedCount() > 0) {
    statusCharacteristic->setValue(message);
    statusCharacteristic->notify();
  }
}

void resetOta() {
  if (otaInProgress) {
    Update.abort();
  }
  otaInProgress = false;
  expectedFirmwareBytes = 0;
  writtenFirmwareBytes = 0;
}

class DeskBotServerCallbacks : public BLEServerCallbacks {
  void onDisconnect(BLEServer *server) override { server->startAdvertising(); }
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

void startBle() {
  BLEDevice::init(DEVICE_NAME);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new DeskBotServerCallbacks());

  BLEService *service = bleServer->createService(BLE_SERVICE_UUID);
  BLECharacteristic *commandCharacteristic = service->createCharacteristic(
      BLE_COMMAND_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  // The standard Nordic UART TX characteristic. ESP32-C3's NimBLE stack
  // automatically provides its writable CCCD descriptor for notifications.
  statusCharacteristic = service->createCharacteristic(
      BLE_STATUS_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  BLECharacteristic *firmwareCharacteristic = service->createCharacteristic(
      BLE_FIRMWARE_UUID, BLECharacteristic::PROPERTY_WRITE);
  commandCharacteristic->setCallbacks(new CommandCallbacks());
  firmwareCharacteristic->setCallbacks(new FirmwareDataCallbacks());
  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setName(DEVICE_NAME);
  advertising->setScanResponse(true);
  advertising->start();
}

void setup() {
  Serial.begin(115200);
  startBle();
}

void loop() { delay(20); }
