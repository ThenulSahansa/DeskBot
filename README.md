# DeskBot

## ESP32-C3 SuperMini firmware

The firmware now targets an ESP32-C3 SuperMini. Select **ESP32C3 Dev Module**
in Arduino IDE (or `esp32-c3-devkitm-1` in PlatformIO), set the flash size to
match your board (normally 4 MB), and upload `firmware/firmware.ino`.

ESP32-C3 has BLE only, so DeskBot uses a BLE UART-style service rather than
Bluetooth Classic serial. Install the updated Android app from this project;
the previous app version, which uses Classic SPP, cannot connect to a C3.

For first upload, hold the board's **BOOT** button while beginning the upload
if it does not enter download mode automatically.
