"""
DeskBot Desktop Main Application Window.
Equivalent of the Android DeskDroid companion app with full feature parity.
Built with CustomTkinter and styled with Catppuccin Mocha colors.
"""

import os
import sys
import threading
from typing import Optional, List, Tuple

import customtkinter as ctk

from ui.theme import (
    BASE, MANTLE, CRUST, SURFACE0, SURFACE1, SURFACE2,
    OVERLAY0, OVERLAY1, SUBTEXT0, SUBTEXT1, TEXT,
    MAUVE, RED, PEACH, YELLOW, GREEN, TEAL, BLUE, SAPPHIRE,
    FONT_FAMILY, FONT_MONO
)
from ui.widgets import (
    FlatCard, StatusPill, MetricCard, SectionLabel, LogPanel, DevicePickerModal
)
from ble_manager import BleManager
from gemini_manager import GeminiManager
from audio_manager import AudioManager
from tts_manager import TtsManager


class DeskBotApp(ctk.CTk):
    """
    Main Application Window for DeskBot Desktop.
    """
    def __init__(self):
        super().__init__()

        # Window Configuration
        self.title("DeskBot Desktop — Maya AI Companion")
        self.geometry("1060x720")
        self.minsize(860, 560)
        self.configure(fg_color=BASE)

        # State Variables
        self.is_connected = False
        self.is_connecting = False
        self.is_ai_session_active = False
        self.audio_source = "DeskBot Mic (BLE)"  # or "PC Microphone"
        self.last_gemini_response = ""
        self.connected_device_name = "DeskBot ESP32"
        self.mtu_str = "23 B"
        self._is_closing = False
        self._pending_logs: List[Tuple[str, str, str, Optional[str]]] = []

        # Build GUI first so log panel is available
        self.log_panel: Optional[LogPanel] = None
        self._init_layout()

        # Initialize Managers
        self._init_managers()

        # Window Close Protocol
        self.protocol("WM_DELETE_WINDOW", self.on_closing)

        # Schedule flush of any startup logs once main loop starts
        self.after(50, self._flush_pending_logs)

        # Log system boot
        self.safe_log("SYSTEM", "DeskBot Desktop application started", "SUCCESS")
        self.safe_log("SYSTEM", f"Gemini Live Model: {self.gemini_mgr.MODEL_ID if hasattr(self.gemini_mgr, 'MODEL_ID') else 'gemini-3.8-live'}", "INFO")

    # ------------------------------------------------------------------------------------
    # Initialization
    # ------------------------------------------------------------------------------------

    def _init_managers(self):
        """Initialize background managers with thread-safe UI callbacks."""
        # 1. Text-To-Speech Manager
        self.tts_mgr = TtsManager(
            rate=175,
            on_log=self.safe_log
        )

        # 2. Audio Capture Manager (for PC microphone fallback / input)
        self.audio_mgr = AudioManager(
            on_audio_chunk=self._on_local_audio_chunk,
            on_log=self.safe_log
        )

        # 3. Gemini Live Multimodal WebSocket Manager
        self.gemini_mgr = GeminiManager(
            on_response=self._on_gemini_response,
            on_setup_complete=self._on_gemini_setup_complete,
            on_error=self._on_gemini_error,
            on_log=self.safe_log
        )

        # 4. BLE Peripheral Manager
        self.ble_mgr = BleManager(
            on_audio_packet=self._on_ble_audio_packet,
            on_voice_start=self._on_voice_start,
            on_voice_end=self._on_voice_end,
            on_status=self._on_ble_status,
            on_log=self.safe_log,
            on_disconnected=self._on_ble_disconnected,
            on_mtu=self._on_ble_mtu
        )

    def _init_layout(self):
        """Create the responsive two-column layout."""
        self.grid_rowconfigure(0, weight=1)
        self.grid_columnconfigure(0, weight=0)  # Left Sidebar (fixed width)
        self.grid_columnconfigure(1, weight=1)  # Right Panel (expands)

        # --------------------------------------------------------------------------------
        # Left Sidebar (Fixed 340px)
        # --------------------------------------------------------------------------------
        self.sidebar_frame = ctk.CTkScrollableFrame(
            self,
            width=340,
            fg_color=BASE,
            corner_radius=0,
            scrollbar_button_color=SURFACE1,
            scrollbar_button_hover_color=SURFACE2
        )
        self.sidebar_frame.grid(row=0, column=0, sticky="nsew", padx=(16, 8), pady=16)

        # 1. Status Pill
        self.status_pill = StatusPill(
            self.sidebar_frame,
            on_click=self._handle_hero_connect
        )
        self.status_pill.pack(fill="x", pady=(0, 16))

        # 2. Header Row: App Title + Quick Scan Button
        header_row = ctk.CTkFrame(self.sidebar_frame, fg_color="transparent")
        header_row.pack(fill="x", pady=(0, 16))

        title_col = ctk.CTkFrame(header_row, fg_color="transparent")
        title_col.pack(side="left", fill="y", anchor="w")

        title_lbl = ctk.CTkLabel(
            title_col,
            text="DeskBot",
            font=ctk.CTkFont(family=FONT_FAMILY, size=26, weight="bold"),
            text_color=TEXT
        )
        title_lbl.pack(anchor="w")

        subtitle_lbl = ctk.CTkLabel(
            title_col,
            text="Smart Desktop Companion",
            font=ctk.CTkFont(family=FONT_FAMILY, size=12),
            text_color=SUBTEXT0
        )
        subtitle_lbl.pack(anchor="w")

        # Quick Scan Button (Antenna icon)
        self.quick_scan_btn = ctk.CTkButton(
            header_row,
            text="📡",
            width=42,
            height=42,
            corner_radius=12,
            fg_color=SURFACE0,
            border_color=SURFACE1,
            border_width=1,
            hover_color=SURFACE1,
            font=ctk.CTkFont(size=18),
            command=self._open_device_picker
        )
        self.quick_scan_btn.pack(side="right")

        # 3. Hero Device Card
        self.hero_card = FlatCard(self.sidebar_frame, corner_radius=18, fg_color=SURFACE0)
        self.hero_card.pack(fill="x", pady=(0, 20))

        hero_inner = ctk.CTkFrame(self.hero_card, fg_color="transparent")
        hero_inner.pack(padx=20, pady=20, fill="x")

        # Robot Badge Icon
        self.robot_badge = ctk.CTkFrame(
            hero_inner,
            width=64,
            height=64,
            corner_radius=32,
            fg_color=MANTLE
        )
        self.robot_badge.pack(pady=(0, 10))
        self.robot_badge.pack_propagate(False)

        self.robot_icon = ctk.CTkLabel(
            self.robot_badge,
            text="🤖",
            font=ctk.CTkFont(size=30)
        )
        self.robot_icon.place(relx=0.5, rely=0.5, anchor="center")

        self.device_name_lbl = ctk.CTkLabel(
            hero_inner,
            text="DeskBot ESP32",
            font=ctk.CTkFont(family=FONT_FAMILY, size=18, weight="bold"),
            text_color=TEXT
        )
        self.device_name_lbl.pack()

        self.device_status_lbl = ctk.CTkLabel(
            hero_inner,
            text="Ready to pair via BLE",
            font=ctk.CTkFont(family=FONT_FAMILY, size=12),
            text_color=SUBTEXT0
        )
        self.device_status_lbl.pack(pady=(2, 16))

        # Main Connect / Disconnect Action Button
        self.hero_btn = ctk.CTkButton(
            hero_inner,
            text="Connect to DeskBot",
            height=44,
            corner_radius=12,
            fg_color=MAUVE,
            text_color=CRUST,
            hover_color="#b48bf2",
            font=ctk.CTkFont(family=FONT_FAMILY, size=14, weight="bold"),
            command=self._handle_hero_connect
        )
        self.hero_btn.pack(fill="x")

        # 4. System Telemetry Section
        SectionLabel(self.sidebar_frame, text="SYSTEM TELEMETRY").pack(fill="x", pady=(0, 8), padx=2)

        self.telemetry_card = FlatCard(self.sidebar_frame, corner_radius=16, fg_color=SURFACE0)
        self.telemetry_card.pack(fill="x", pady=(0, 20))

        telemetry_row = ctk.CTkFrame(self.telemetry_card, fg_color="transparent")
        telemetry_row.pack(fill="x", padx=10, pady=10)
        telemetry_row.columnconfigure(0, weight=1)
        telemetry_row.columnconfigure(1, weight=1)
        telemetry_row.columnconfigure(2, weight=1)

        self.metric_status = MetricCard(telemetry_row, title="STATUS", value="Offline", value_color=SUBTEXT0)
        self.metric_status.grid(row=0, column=0, padx=4, sticky="nsew")

        self.metric_link = MetricCard(telemetry_row, title="LINK", value="BLE 5.0", value_color=BLUE)
        self.metric_link.grid(row=0, column=1, padx=4, sticky="nsew")

        self.metric_mtu = MetricCard(telemetry_row, title="MTU", value="23 B", value_color=TEAL)
        self.metric_mtu.grid(row=0, column=2, padx=4, sticky="nsew")

        # 5. AI Voice Assistant Section
        SectionLabel(self.sidebar_frame, text="AI VOICE ASSISTANT").pack(fill="x", pady=(0, 8), padx=2)

        self.ai_card = FlatCard(self.sidebar_frame, corner_radius=16, fg_color=SURFACE0)
        self.ai_card.pack(fill="x", pady=(0, 16))

        ai_inner = ctk.CTkFrame(self.ai_card, fg_color="transparent")
        ai_inner.pack(fill="x", padx=14, pady=14)

        ai_top_row = ctk.CTkFrame(ai_inner, fg_color="transparent")
        ai_top_row.pack(fill="x", pady=(0, 10))

        # AI Icon badge
        ai_badge = ctk.CTkFrame(ai_top_row, width=40, height=40, corner_radius=10, fg_color=MANTLE)
        ai_badge.pack(side="left", padx=(0, 10))
        ai_badge.pack_propagate(False)

        ai_icon_lbl = ctk.CTkLabel(ai_badge, text="✨", font=ctk.CTkFont(size=18))
        ai_icon_lbl.place(relx=0.5, rely=0.5, anchor="center")

        ai_info_col = ctk.CTkFrame(ai_top_row, fg_color="transparent")
        ai_info_col.pack(side="left", fill="y", anchor="w")

        ai_title = ctk.CTkLabel(
            ai_info_col,
            text="Gemini Live",
            font=ctk.CTkFont(family=FONT_FAMILY, size=14, weight="bold"),
            text_color=TEXT
        )
        ai_title.pack(anchor="w")

        self.ai_status_lbl = ctk.CTkLabel(
            ai_info_col,
            text="Voice AI · Idle",
            font=ctk.CTkFont(family=FONT_FAMILY, size=11),
            text_color=SUBTEXT0
        )
        self.ai_status_lbl.pack(anchor="w")

        # AI Action Buttons (Mute + Start/Stop Session)
        ai_actions = ctk.CTkFrame(ai_top_row, fg_color="transparent")
        ai_actions.pack(side="right")

        # Mute TTS Button
        self.mute_btn = ctk.CTkButton(
            ai_actions,
            text="🔊",
            width=34,
            height=34,
            corner_radius=17,
            fg_color=TEAL,
            hover_color=GREEN,
            text_color=CRUST,
            font=ctk.CTkFont(size=14),
            command=self._toggle_tts_mute
        )
        self.mute_btn.pack(side="left", padx=(0, 6))

        # Start / Stop Session Button
        self.ai_session_btn = ctk.CTkButton(
            ai_actions,
            text="Start",
            width=68,
            height=34,
            corner_radius=10,
            fg_color=MAUVE,
            text_color=CRUST,
            hover_color="#b48bf2",
            font=ctk.CTkFont(family=FONT_FAMILY, size=12, weight="bold"),
            command=self._toggle_ai_session
        )
        self.ai_session_btn.pack(side="left")

        # Audio Input Source Selector
        audio_src_row = ctk.CTkFrame(ai_inner, fg_color="transparent")
        audio_src_row.pack(fill="x", pady=(4, 0))

        audio_src_lbl = ctk.CTkLabel(
            audio_src_row,
            text="Input Source:",
            font=ctk.CTkFont(family=FONT_FAMILY, size=11),
            text_color=SUBTEXT1
        )
        audio_src_lbl.pack(side="left", padx=(0, 8))

        self.audio_src_segment = ctk.CTkSegmentedButton(
            audio_src_row,
            values=["DeskBot Mic (BLE)", "PC Mic"],
            font=ctk.CTkFont(family=FONT_FAMILY, size=10, weight="bold"),
            selected_color=MAUVE,
            selected_hover_color="#b48bf2",
            unselected_color=MANTLE,
            unselected_hover_color=SURFACE1,
            text_color=CRUST,
            command=self._on_audio_source_changed
        )
        self.audio_src_segment.set("DeskBot Mic (BLE)")
        self.audio_src_segment.pack(side="right", fill="x", expand=True)

        # Last Gemini Response Card
        self.last_response_card = FlatCard(ai_inner, corner_radius=10, fg_color=MANTLE)
        # Initially hidden until first response
        self.last_response_card.pack(fill="x", pady=(12, 0))

        resp_col = ctk.CTkFrame(self.last_response_card, fg_color="transparent")
        resp_col.pack(fill="x", padx=12, pady=10)

        resp_title = ctk.CTkLabel(
            resp_col,
            text="LAST RESPONSE",
            font=ctk.CTkFont(family=FONT_FAMILY, size=9, weight="bold"),
            text_color=OVERLAY1
        )
        resp_title.pack(anchor="w")

        self.last_response_lbl = ctk.CTkLabel(
            resp_col,
            text="Maya is waiting for your voice...",
            font=ctk.CTkFont(family=FONT_FAMILY, size=12),
            text_color=TEXT,
            wraplength=280,
            justify="left"
        )
        self.last_response_lbl.pack(anchor="w", pady=(4, 0))

        # --------------------------------------------------------------------------------
        # Right Panel (Terminal Log Console)
        # --------------------------------------------------------------------------------
        self.right_panel = ctk.CTkFrame(self, fg_color=BASE)
        self.right_panel.grid(row=0, column=1, sticky="nsew", padx=(8, 16), pady=16)
        self.right_panel.grid_rowconfigure(1, weight=1)
        self.right_panel.grid_columnconfigure(0, weight=1)

        # Terminal Header
        term_header = ctk.CTkFrame(self.right_panel, fg_color="transparent")
        term_header.grid(row=0, column=0, sticky="ew", pady=(0, 8))

        term_title = ctk.CTkLabel(
            term_header,
            text="LIVE DIAGNOSTIC TERMINAL",
            font=ctk.CTkFont(family=FONT_FAMILY, size=11, weight="bold"),
            text_color=OVERLAY1
        )
        term_title.pack(side="left")

        # Terminal Panel
        self.log_panel = LogPanel(self.right_panel)
        self.log_panel.grid(row=1, column=0, sticky="nsew")

    # ------------------------------------------------------------------------------------
    # Thread-Safe UI & Logging Bridges
    # ------------------------------------------------------------------------------------

    def safe_log(self, tag: str, message: str, level: str = "INFO", key: Optional[str] = None):
        """Thread-safe log dispatcher that routes to LogPanel via after(0)."""
        if self._is_closing:
            return

        def _post():
            if self.log_panel and not self._is_closing:
                if key:
                    self.log_panel.upsert_log(key, tag, message, level)
                else:
                    self.log_panel.append_log(tag, message, level)

        try:
            self.after(0, _post)
        except (RuntimeError, Exception):
            # Main thread is not in main loop yet; buffer for initial flush
            self._pending_logs.append((tag, message, level, key))

    def _flush_pending_logs(self):
        """Flush logs queued before the Tkinter mainloop was running."""
        while self._pending_logs:
            tag, message, level, key = self._pending_logs.pop(0)
            if self.log_panel:
                if key:
                    self.log_panel.upsert_log(key, tag, message, level)
                else:
                    self.log_panel.append_log(tag, message, level)

    def safe_update_ui(self, func):
        """Post arbitrary state update to Tkinter event loop."""
        if self._is_closing:
            return
        try:
            self.after(0, func)
        except Exception:
            pass

    # ------------------------------------------------------------------------------------
    # UI Actions & Callbacks
    # ------------------------------------------------------------------------------------

    def _handle_hero_connect(self):
        """Handle Hero Button (Connect / Disconnect)."""
        if self.is_connected:
            self._disconnect_deskbot()
        else:
            self._open_device_picker()

    def _open_device_picker(self):
        """Open DevicePickerModal and trigger BLE scan."""
        picker = DevicePickerModal(
            parent=self,
            on_select_device=self._connect_to_device,
            on_rescan=self._rescan_devices
        )
        self.device_picker = picker
        self._rescan_devices()

    def _rescan_devices(self):
        """Initiate background BLE scan."""
        self.status_pill.set_status("scanning", "DeskBot · Scanning...")

        def _on_scan_done(devices, err):
            def _update():
                if hasattr(self, "device_picker") and self.device_picker.winfo_exists():
                    self.device_picker.set_devices(devices, error_msg=str(err) if err else None)
                if not self.is_connected and not self.is_connecting:
                    self.status_pill.set_status("disconnected", "DeskBot · Disconnected")
            self.safe_update_ui(_update)

        self.ble_mgr.scan_async(_on_scan_done, timeout=6.0)

    def _connect_to_device(self, name: str, address: str):
        """Initiate connection to target BLE device."""
        self.is_connecting = True
        self.connected_device_name = name or "DeskBot"
        self.status_pill.set_status("connecting", f"Connecting to {self.connected_device_name}...")
        self.hero_btn.configure(text="Connecting...", fg_color=SURFACE1, state="disabled")
        self.device_name_lbl.configure(text=self.connected_device_name)
        self.device_status_lbl.configure(text="Establishing hardware link...")

        def _on_connect_done(success: bool, err):
            def _update():
                self.is_connecting = False
                if success:
                    self.is_connected = True
                    self.status_pill.set_status("connected", f"DeskBot · Connected")
                    self.hero_btn.configure(
                        text="Disconnect",
                        fg_color=RED,
                        hover_color="#e07a97",
                        state="normal"
                    )
                    self.hero_card.configure(border_color=MAUVE)
                    self.device_status_lbl.configure(text="Active hardware link established")
                    self.metric_status.set_value("Online", GREEN)
                    self.metric_link.set_value("BLE 5.0", BLUE)
                    self.metric_mtu.set_value(self.mtu_str, TEAL)
                else:
                    self.is_connected = False
                    self.status_pill.set_status("error", "Connection Failed")
                    self.hero_btn.configure(
                        text="Connect to DeskBot",
                        fg_color=MAUVE,
                        hover_color="#b48bf2",
                        state="normal"
                    )
                    self.hero_card.configure(border_color=SURFACE1)
                    self.device_status_lbl.configure(text="Pairing failed. Check device.")
                    self.metric_status.set_value("Offline", SUBTEXT0)

            self.safe_update_ui(_update)

        self.ble_mgr.connect_async(address, _on_connect_done)

    def _disconnect_deskbot(self):
        """Disconnect BLE link."""
        self.ble_mgr.disconnect_async()
        self._on_ble_disconnected()

    def _on_ble_disconnected(self):
        """Called when BLE peripheral disconnects."""
        def _update():
            self.is_connected = False
            self.is_connecting = False
            self.status_pill.set_status("disconnected", "DeskBot · Disconnected")
            self.hero_btn.configure(
                text="Connect to DeskBot",
                fg_color=MAUVE,
                hover_color="#b48bf2",
                state="normal"
            )
            self.hero_card.configure(border_color=SURFACE1)
            self.device_status_lbl.configure(text="Ready to pair via BLE")
            self.metric_status.set_value("Offline", SUBTEXT0)

            # If AI session was tied to BLE, leave Gemini running or idle
            if self.is_ai_session_active and self.audio_source == "DeskBot Mic (BLE)":
                self.ai_status_lbl.configure(text="Voice AI · Waiting for BLE", text_color=YELLOW)

        self.safe_update_ui(_update)

    def _on_ble_mtu(self, mtu: int):
        """Called when negotiated MTU size is available."""
        self.mtu_str = f"{mtu} B"
        self.safe_update_ui(lambda: self.metric_mtu.set_value(self.mtu_str, TEAL))

    def _on_ble_status(self, text: str):
        """Handle status characteristic notification text."""
        # Status char messages e.g. OTA status or voice markers
        self.safe_log("STATUS", text, "INFO")

    # ------------------------------------------------------------------------------------
    # AI Voice Session & Gemini Handlers
    # ------------------------------------------------------------------------------------

    def _toggle_ai_session(self):
        """Start or stop Gemini Live WebSocket session."""
        if self.is_ai_session_active:
            self._stop_ai_session()
        else:
            self._start_ai_session()

    def _start_ai_session(self):
        self.is_ai_session_active = True
        self.ai_session_btn.configure(
            text="Stop",
            fg_color=RED,
            hover_color="#e07a97"
        )
        self.ai_status_lbl.configure(text="Connecting to Gemini Live...", text_color=MAUVE)
        self.ai_card.configure(border_color=MAUVE)

        # Start microphone if PC mic selected
        if self.audio_source == "PC Mic":
            self.audio_mgr.start()

        # Connect WebSocket
        self.gemini_mgr.connect()

    def _stop_ai_session(self):
        self.is_ai_session_active = False
        self.ai_session_btn.configure(
            text="Start",
            fg_color=MAUVE,
            hover_color="#b48bf2"
        )
        self.ai_status_lbl.configure(text="Voice AI · Idle", text_color=SUBTEXT0)
        self.ai_card.configure(border_color=SURFACE1)

        # Stop local mic if active
        self.audio_mgr.stop()

        # Disconnect Gemini
        self.gemini_mgr.disconnect()

    def _on_gemini_setup_complete(self):
        """Called when Gemini Live setup completes and model is ready."""
        def _update():
            if self.is_ai_session_active:
                self.ai_status_lbl.configure(text="Session active · Listening", text_color=GREEN)
        self.safe_update_ui(_update)

    def _on_gemini_error(self, err: str):
        """Called on Gemini API error."""
        def _update():
            self.ai_status_lbl.configure(text=f"Error: {err[:28]}...", text_color=RED)
        self.safe_update_ui(_update)

    def _on_gemini_response(self, text: str):
        """
        Called when Gemini generates a completed voice turn response.
        1. Updates 'Last Response' preview in UI
        2. Sends text to DeskBot SSD1306 OLED display via BLE
        3. Speaks text via local TTS if unmuted
        """
        self.last_gemini_response = text

        def _update():
            # Update Last Response label
            self.last_response_lbl.configure(text=text)

            # Send to DeskBot OLED screen over BLE
            if self.is_connected:
                self.ble_mgr.send_display_async(text)

            # Speak response via offline TTS engine
            if not self.tts_mgr.muted:
                self.tts_mgr.speak(text)

        self.safe_update_ui(_update)

    # ------------------------------------------------------------------------------------
    # Audio Routing & Voice Activity Detection (VAD)
    # ------------------------------------------------------------------------------------

    def _on_voice_start(self):
        """ESP32 VAD triggered: VOICE_START received over BLE."""
        self.safe_log("BLE", "VOICE_START marker received", "INFO")
        if self.is_ai_session_active:
            self.gemini_mgr.send_start_of_turn()
            self.safe_update_ui(lambda: self.ai_status_lbl.configure(text="Listening to voice...", text_color=GREEN))

    def _on_voice_end(self):
        """ESP32 VAD silence detected: VOICE_END received over BLE."""
        self.safe_log("BLE", "VOICE_END marker received", "INFO")
        if self.is_ai_session_active:
            self.gemini_mgr.send_end_of_turn()
            self.safe_update_ui(lambda: self.ai_status_lbl.configure(text="Processing response...", text_color=MAUVE))

    def _on_ble_audio_packet(self, pcm_bytes: bytes):
        """Raw 16-bit PCM audio notification received from DeskBot INMP441 microphone."""
        if self.audio_source == "DeskBot Mic (BLE)" and self.is_ai_session_active:
            self.gemini_mgr.send_audio(pcm_bytes)

    def _on_local_audio_chunk(self, pcm_bytes: bytes):
        """Raw 16-bit PCM audio chunk received from PC microphone via sounddevice."""
        if self.audio_source == "PC Mic" and self.is_ai_session_active:
            self.gemini_mgr.send_audio(pcm_bytes)

    def _on_audio_source_changed(self, value: str):
        """User toggled between DeskBot Mic and PC Microphone."""
        self.audio_source = value
        self.safe_log("AUDIO", f"Switched audio input to: {value}", "INFO")

        if self.is_ai_session_active:
            if value == "PC Mic":
                self.audio_mgr.start()
            else:
                self.audio_mgr.stop()

    def _toggle_tts_mute(self):
        """Toggle text-to-speech speaker output mute."""
        new_mute = not self.tts_mgr.muted
        self.tts_mgr.muted = new_mute

        if new_mute:
            self.mute_btn.configure(text="🔇", fg_color=RED, hover_color="#e07a97")
            self.safe_log("TTS", "Text-to-speech muted", "WARN")
        else:
            self.mute_btn.configure(text="🔊", fg_color=TEAL, hover_color=GREEN)
            self.safe_log("TTS", "Text-to-speech unmuted", "INFO")

    # ------------------------------------------------------------------------------------
    # Application Shutdown
    # ------------------------------------------------------------------------------------

    def on_closing(self):
        """Gracefully disconnect all hardware and network sessions before exiting."""
        self._is_closing = True
        try:
            self.safe_log("SYSTEM", "Shutting down DeskBot Desktop...", "INFO")
            # Stop AI session
            if self.gemini_mgr:
                self.gemini_mgr.disconnect()
            # Stop local audio
            if self.audio_mgr:
                self.audio_mgr.stop()
            # Stop TTS
            if self.tts_mgr:
                self.tts_mgr.stop()
            # Disconnect BLE
            if self.ble_mgr:
                self.ble_mgr.disconnect_async()
        except Exception:
            pass

        self.destroy()
        sys.exit(0)
