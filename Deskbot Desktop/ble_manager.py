"""
Bluetooth Low Energy (BLE) Manager for DeskBot Desktop.
Communicates with the ESP32-C3 SuperMini firmware over custom GATT characteristics:
- Service:   6E400001-B5A3-F393-E0A9-E50E24DCCA9E
- Command:   6E400002-B5A3-F393-E0A9-E50E24DCCA9E (OTA commands)
- Status:    6E400003-B5A3-F393-E0A9-E50E24DCCA9E (VOICE_START, VOICE_END, OTA status)
- Firmware:  6E400004-B5A3-F393-E0A9-E50E24DCCA9E (Firmware binary chunks)
- Audio:     6E400005-B5A3-F393-E0A9-E50E24DCCA9E (16kHz mono 16-bit PCM notifications)
- Display:   6E400006-B5A3-F393-E0A9-E50E24DCCA9E (OLED display text)
"""

import time
import asyncio
import threading
from typing import Callable, Optional, List, Tuple

from bleak import BleakScanner, BleakClient
from bleak.backends.characteristic import BleakGATTCharacteristic
from bleak.exc import BleakBluetoothNotAvailableError


class BleManager:
    """
    Async BLE Manager running a dedicated background event loop thread.
    Exposes both coroutines and thread-safe synchronous scheduling helpers.
    """
    SERVICE_UUID  = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    COMMAND_UUID  = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    STATUS_UUID   = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    FIRMWARE_UUID = "6e400004-b5a3-f393-e0a9-e50e24dcca9e"
    AUDIO_UUID    = "6e400005-b5a3-f393-e0a9-e50e24dcca9e"
    DISPLAY_UUID  = "6e400006-b5a3-f393-e0a9-e50e24dcca9e"

    def __init__(
        self,
        on_audio_packet: Callable[[bytes], None],
        on_voice_start: Callable[[], None],
        on_voice_end: Callable[[], None],
        on_status: Callable[[str], None],
        on_log: Callable[..., None],
        on_disconnected: Optional[Callable[[], None]] = None,
        on_mtu: Optional[Callable[[int], None]] = None
    ):
        self.on_audio_packet = on_audio_packet
        self.on_voice_start = on_voice_start
        self.on_voice_end = on_voice_end
        self.on_status = on_status
        self.on_log = on_log
        self.on_disconnected_cb = on_disconnected
        self.on_mtu_cb = on_mtu

        self.client: Optional[BleakClient] = None
        self.connected = False
        self.connected_device_name = "DeskBot"
        self.connected_address = ""
        self.mtu = 23

        self._audio_packet_count = 0
        self._last_voice_marker_time = 0.0  # 300ms debounce
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._thread: Optional[threading.Thread] = None

        # Start background event loop for BLE operations
        self._start_event_loop()

    def _log(self, tag: str, msg: str, level: str = "INFO"):
        if self.on_log:
            self.on_log(tag, msg, level)

    def _start_event_loop(self):
        """Starts a persistent asyncio event loop running on a background daemon thread."""
        self._loop = asyncio.new_event_loop()

        def _run():
            asyncio.set_event_loop(self._loop)
            self._loop.run_forever()

        self._thread = threading.Thread(target=_run, daemon=True, name="BLE-Event-Loop")
        self._thread.start()

    # ------------------------------------------------------------------------------------
    # Native Async Coroutines (run inside self._loop)
    # ------------------------------------------------------------------------------------

    async def scan(self, timeout: float = 6.0) -> List[Tuple[str, str, bool]]:
        """
        Scan for nearby Bluetooth LE devices.
        Returns sorted list of (name, address, is_deskbot).
        """
        self._log("BLE", f"Scanning for BLE devices ({timeout:.0f}s)...", "INFO")
        try:
            discovered = await BleakScanner.discover(timeout=timeout, return_adv=True)
            results = []

            for addr, (dev, adv) in discovered.items():
                name = dev.name or adv.local_name or ""
                uuids = [str(u).lower() for u in (adv.service_uuids or [])]

                is_deskbot = (
                    "deskbot" in name.lower() or
                    any(u.startswith("6e400001") for u in uuids)
                )

                if is_deskbot or name:
                    results.append((name or "Unknown Device", addr, is_deskbot))

            # Sort DeskBot devices to the top, then alphabetically by name
            results.sort(key=lambda item: (not item[2], item[0]))
            self._log("BLE", f"Scan complete. Found {len(results)} devices.", "SUCCESS")
            return results
        except BleakBluetoothNotAvailableError:
            self._log("BLE", "Bluetooth radio is turned OFF. Please enable Bluetooth in Windows Settings.", "ERROR")
            return []
        except Exception as e:
            self._log("BLE", f"Scan failed: {e}", "ERROR")
            return []

    async def connect(self, address: str):
        """
        Connect to the target DeskBot peripheral and subscribe to GATT notifications.
        """
        if self.connected and self.client:
            await self.disconnect()

        self._log("BLE", f"Connecting to DeskBot ({address})...", "INFO")
        self.connected_address = address

        try:
            self.client = BleakClient(address, disconnected_callback=self._on_disconnected)
            await self.client.connect()

            self.connected = True
            self.connected_device_name = "DeskBot"

            # Query MTU
            try:
                self.mtu = getattr(self.client, "mtu_size", 23)
            except Exception:
                self.mtu = 23

            if self.on_mtu_cb:
                self.on_mtu_cb(self.mtu)

            self._log("BLE", f"Connected to DeskBot ({address}) [MTU: {self.mtu}]", "SUCCESS")

            # Subscribe to status characteristic (0003)
            try:
                await self.client.start_notify(self.STATUS_UUID, self._on_status_notify)
                self._log("BLE", "Subscribed to Status characteristic", "INFO")
            except Exception as e:
                self._log("BLE", f"Failed to subscribe to Status characteristic: {e}", "WARN")

            # Subscribe to audio characteristic (0005)
            try:
                await self.client.start_notify(self.AUDIO_UUID, self._on_audio_notify)
                self._log("BLE", "Subscribed to Audio characteristic", "INFO")
            except Exception as e:
                self._log("BLE", f"Failed to subscribe to Audio characteristic: {e}", "WARN")

        except BleakBluetoothNotAvailableError:
            self.connected = False
            self.client = None
            self._log("BLE", "Bluetooth radio is turned OFF. Please enable Bluetooth in Windows Settings.", "ERROR")
            raise
        except Exception as e:
            self.connected = False
            self.client = None
            self._log("BLE", f"Connection failed: {e}", "ERROR")
            raise e

    def _on_disconnected(self, client):
        self.connected = False
        self._log("BLE", "Disconnected from DeskBot", "WARN")
        if self.on_disconnected_cb:
            self.on_disconnected_cb()

    def _on_status_notify(self, char: BleakGATTCharacteristic, data: bytearray):
        """Handle incoming status characteristic notification."""
        try:
            text = bytes(data).decode("utf-8", errors="replace").strip()
            self.on_status(text)

            now = time.monotonic()
            if text == "VOICE_START" and (now - self._last_voice_marker_time) > 0.3:
                self._last_voice_marker_time = now
                self.on_voice_start()
            elif text == "VOICE_END" and (now - self._last_voice_marker_time) > 0.3:
                self._last_voice_marker_time = now
                self.on_voice_end()
        except Exception as e:
            self._log("BLE", f"Status notify error: {e}", "ERROR")

    def _on_audio_notify(self, char: BleakGATTCharacteristic, data: bytearray):
        """Handle incoming audio characteristic notification."""
        # Check for VOICE_START / VOICE_END sent on audio characteristic
        if len(data) <= 16:
            try:
                text = bytes(data).decode("utf-8", errors="replace").strip()
                now = time.monotonic()
                if text == "VOICE_START" and (now - self._last_voice_marker_time) > 0.3:
                    self._last_voice_marker_time = now
                    self.on_voice_start()
                    return
                elif text == "VOICE_END" and (now - self._last_voice_marker_time) > 0.3:
                    self._last_voice_marker_time = now
                    self.on_voice_end()
                    return
            except Exception:
                pass

        # Raw PCM audio packet
        self._audio_packet_count += 1
        self.on_audio_packet(bytes(data))

    async def send_display_text(self, text: str):
        """
        Send text to DeskBot OLED via BLE display characteristic (0006).
        Appends DISPLAY_END marker and chunks within MTU constraints.
        """
        if not self.connected or not self.client:
            self._log("DISPLAY", "Cannot send display text: DeskBot not connected", "WARN")
            return

        try:
            payload = (text + "\nDISPLAY_END").encode("utf-8")
            chunk_size = max(20, min(240, self.mtu - 3))

            for i in range(0, len(payload), chunk_size):
                chunk = payload[i:i + chunk_size]
                await self.client.write_gatt_char(self.DISPLAY_UUID, chunk, response=True)

            self._log("DISPLAY", f"Sent {len(payload)} bytes to OLED display", "SUCCESS")
        except Exception as e:
            self._log("DISPLAY", f"Failed to send display text: {e}", "ERROR")

    async def send_command(self, command: str):
        """Send command string to Command characteristic (0002)."""
        if not self.connected or not self.client:
            return

        try:
            payload = command.encode("utf-8")
            await self.client.write_gatt_char(self.COMMAND_UUID, payload, response=True)
            self._log("BLE", f"Command sent: {command}", "INFO")
        except Exception as e:
            self._log("BLE", f"Failed to send command: {e}", "ERROR")

    async def disconnect(self):
        """Disconnect active BLE client."""
        if self.client and self.connected:
            try:
                await self.client.disconnect()
            except Exception:
                pass
        self.connected = False
        self.client = None

    # ------------------------------------------------------------------------------------
    # Thread-Safe Synchronous Helper Methods for UI Callbacks
    # ------------------------------------------------------------------------------------

    def scan_async(self, callback: Callable[[List[Tuple[str, str, bool]], Optional[Exception]], None], timeout: float = 6.0):
        """Schedule BLE scan on background loop; callback(results, err) invoked when done."""
        future = asyncio.run_coroutine_threadsafe(self.scan(timeout), self._loop)

        def _on_done(f):
            try:
                res = f.result()
                callback(res, None)
            except Exception as e:
                callback([], e)

        future.add_done_callback(_on_done)

    def connect_async(self, address: str, callback: Optional[Callable[[bool, Optional[Exception]], None]] = None):
        """Schedule BLE connect on background loop."""
        future = asyncio.run_coroutine_threadsafe(self.connect(address), self._loop)

        if callback:
            def _on_done(f):
                try:
                    f.result()
                    callback(True, None)
                except Exception as e:
                    callback(False, e)

            future.add_done_callback(_on_done)

    def disconnect_async(self, callback: Optional[Callable[[], None]] = None):
        """Schedule BLE disconnect on background loop."""
        future = asyncio.run_coroutine_threadsafe(self.disconnect(), self._loop)

        if callback:
            def _on_done(f):
                try:
                    f.result()
                except Exception:
                    pass
                callback()

            future.add_done_callback(_on_done)

    def send_display_async(self, text: str, callback: Optional[Callable[[bool], None]] = None):
        """Schedule OLED text transmission on background loop."""
        future = asyncio.run_coroutine_threadsafe(self.send_display_text(text), self._loop)

        if callback:
            def _on_done(f):
                try:
                    f.result()
                    callback(True)
                except Exception:
                    callback(False)

            future.add_done_callback(_on_done)
