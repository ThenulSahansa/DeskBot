"""
Reusable UI Widgets for DeskBot Desktop Application.
Crafted with CustomTkinter and styled with Catppuccin Mocha colors.
"""

import time
import tkinter as tk
import customtkinter as ctk
from typing import Callable, Optional, Dict, Tuple

from ui.theme import (
    BASE, MANTLE, CRUST, SURFACE0, SURFACE1, SURFACE2,
    OVERLAY0, OVERLAY1, SUBTEXT0, SUBTEXT1, TEXT,
    MAUVE, RED, PEACH, YELLOW, GREEN, TEAL, BLUE, SAPPHIRE,
    TAG_COLORS, LEVEL_COLORS, FONT_FAMILY, FONT_MONO
)


class FlatCard(ctk.CTkFrame):
    """
    Flat container card with rounded corners and border.
    Mirrors the Jetpack Compose FlatCard component from DeskDroid.
    """
    def __init__(
        self,
        master,
        corner_radius: int = 14,
        fg_color: str = SURFACE0,
        border_color: str = SURFACE1,
        border_width: int = 1,
        **kwargs
    ):
        super().__init__(
            master=master,
            corner_radius=corner_radius,
            fg_color=fg_color,
            border_color=border_color,
            border_width=border_width,
            **kwargs
        )


class SectionLabel(ctk.CTkLabel):
    """
    Uppercase, tracked section header label.
    """
    def __init__(self, master, text: str, **kwargs):
        super().__init__(
            master=master,
            text=text.upper(),
            font=ctk.CTkFont(family=FONT_FAMILY, size=11, weight="bold"),
            text_color=OVERLAY1,
            anchor="w",
            **kwargs
        )


class StatusPill(FlatCard):
    """
    Status pill shown at the top of the app.
    Displays an active status dot and connection text.
    """
    def __init__(self, master, on_click: Optional[Callable] = None, **kwargs):
        super().__init__(
            master=master,
            corner_radius=20,
            fg_color=SURFACE0,
            border_color=SURFACE1,
            border_width=1,
            **kwargs
        )
        self.on_click = on_click

        # Content container
        self.container = ctk.CTkFrame(self, fg_color="transparent")
        self.container.pack(padx=14, pady=6)

        # Dot indicator
        self.dot_label = ctk.CTkLabel(
            self.container,
            text="●",
            font=ctk.CTkFont(family=FONT_FAMILY, size=12, weight="bold"),
            text_color=BLUE,
            width=14
        )
        self.dot_label.pack(side="left", padx=(0, 6))

        # Status text
        self.text_label = ctk.CTkLabel(
            self.container,
            text="DeskBot · Disconnected",
            font=ctk.CTkFont(family=FONT_FAMILY, size=12, weight="bold"),
            text_color=TEXT
        )
        self.text_label.pack(side="left")

        # Optional click handler
        if on_click:
            self.bind("<Button-1>", lambda e: on_click())
            self.container.bind("<Button-1>", lambda e: on_click())
            self.dot_label.bind("<Button-1>", lambda e: on_click())
            self.text_label.bind("<Button-1>", lambda e: on_click())

    def set_status(self, state: str, text: Optional[str] = None):
        """
        Update the status pill display.
        States: 'disconnected', 'connecting', 'connected', 'scanning', 'error'
        """
        state = state.lower()
        if state == "connected":
            color = GREEN
            default_text = "DeskBot · Connected"
            border_c = GREEN
        elif state in ("connecting", "scanning"):
            color = MAUVE
            default_text = "DeskBot · Connecting..." if state == "connecting" else "DeskBot · Scanning..."
            border_c = MAUVE
        elif state == "error":
            color = RED
            default_text = "DeskBot · Connection Error"
            border_c = RED
        else:
            color = OVERLAY0
            default_text = "DeskBot · Disconnected"
            border_c = SURFACE1

        display_text = text if text is not None else default_text
        self.dot_label.configure(text_color=color)
        self.text_label.configure(text=display_text)
        self.configure(border_color=border_c)


class MetricCard(FlatCard):
    """
    Telemetry tile showing a small title and prominent metric value.
    Matches DeskDroid's STATUS, LINK, and MTU telemetry cards.
    """
    def __init__(
        self,
        master,
        title: str,
        value: str,
        value_color: str = TEXT,
        **kwargs
    ):
        super().__init__(
            master=master,
            corner_radius=10,
            fg_color=MANTLE,
            border_color=SURFACE1,
            border_width=1,
            **kwargs
        )
        self.columnconfigure(0, weight=1)

        self.title_label = ctk.CTkLabel(
            self,
            text=title.upper(),
            font=ctk.CTkFont(family=FONT_FAMILY, size=9, weight="bold"),
            text_color=OVERLAY1
        )
        self.title_label.pack(pady=(8, 2), padx=6)

        self.value_label = ctk.CTkLabel(
            self,
            text=value,
            font=ctk.CTkFont(family=FONT_FAMILY, size=13, weight="bold"),
            text_color=value_color
        )
        self.value_label.pack(pady=(0, 8), padx=6)

    def set_value(self, value: str, value_color: Optional[str] = None):
        self.value_label.configure(text=value)
        if value_color:
            self.value_label.configure(text_color=value_color)


class LogPanel(ctk.CTkFrame):
    """
    Catppuccin Crust styled terminal console with colored tags,
    timestamps, auto-scroll, clear action, and live upserting.
    """
    def __init__(self, master, on_clear: Optional[Callable] = None, **kwargs):
        super().__init__(
            master=master,
            corner_radius=14,
            fg_color=CRUST,
            border_color=SURFACE1,
            border_width=1,
            **kwargs
        )
        self.on_clear_cb = on_clear
        self._line_count = 0
        self._upsert_marks: Dict[str, Tuple[str, str]] = {}  # key -> (start_mark, end_mark)

        # Header bar
        self.header = ctk.CTkFrame(self, fg_color=SURFACE0, corner_radius=12, height=36)
        self.header.pack(fill="x", padx=1, pady=1)

        header_left = ctk.CTkFrame(self.header, fg_color="transparent")
        header_left.pack(side="left", padx=12, pady=6)

        # Terminal prompt symbol
        self.term_icon = ctk.CTkLabel(
            header_left,
            text=">",
            font=ctk.CTkFont(family=FONT_MONO, size=13, weight="bold"),
            text_color=GREEN,
            width=16
        )
        self.term_icon.pack(side="left", padx=(0, 6))

        self.term_title = ctk.CTkLabel(
            header_left,
            text="TERMINAL",
            font=ctk.CTkFont(family=FONT_MONO, size=11, weight="bold"),
            text_color=OVERLAY1
        )
        self.term_title.pack(side="left", padx=(0, 8))

        self.count_label = ctk.CTkLabel(
            header_left,
            text="0 lines",
            font=ctk.CTkFont(family=FONT_MONO, size=10),
            text_color=OVERLAY0
        )
        self.count_label.pack(side="left")

        # Clear button
        self.clear_btn = ctk.CTkButton(
            self.header,
            text="Clear",
            width=54,
            height=24,
            corner_radius=6,
            fg_color=SURFACE1,
            hover_color=SURFACE2,
            text_color=TEXT,
            font=ctk.CTkFont(family=FONT_MONO, size=10, weight="bold"),
            command=self.clear
        )
        self.clear_btn.pack(side="right", padx=10, pady=6)

        # Scrollable Textbox
        self.textbox = ctk.CTkTextbox(
            self,
            fg_color=CRUST,
            text_color=TEXT,
            font=ctk.CTkFont(family=FONT_MONO, size=11),
            corner_radius=10,
            border_width=0,
            wrap="word",
            activate_scrollbars=True
        )
        self.textbox.pack(fill="both", expand=True, padx=8, pady=(4, 8))

        # Access underlying Tkinter Text widget to configure tags
        self._tk_text: tk.Text = self.textbox._textbox
        self._configure_tags()

    def _configure_tags(self):
        """Configure color tags for timestamps, components, and severity levels."""
        # Timestamp
        self._tk_text.tag_config("timestamp", foreground=OVERLAY0)

        # Component Tags (bold with specific colors)
        for tag_name, color in TAG_COLORS.items():
            self._tk_text.tag_config(
                f"tag_{tag_name}",
                foreground=color,
                font=(FONT_MONO, 11, "bold")
            )
        self._tk_text.tag_config("tag_DEFAULT", foreground=OVERLAY1, font=(FONT_MONO, 11, "bold"))

        # Severity Levels
        for level_name, color in LEVEL_COLORS.items():
            self._tk_text.tag_config(f"level_{level_name}", foreground=color)

    def append_log(self, tag: str, message: str, level: str = "INFO", timestamp: Optional[str] = None):
        """
        Append a single formatted log line to the terminal.
        """
        if timestamp is None:
            timestamp = time.strftime("%H:%M:%S")

        tag_clean = tag.upper().strip()
        level_clean = level.upper().strip()
        tag_style = f"tag_{tag_clean}" if tag_clean in TAG_COLORS else "tag_DEFAULT"
        level_style = f"level_{level_clean}" if level_clean in LEVEL_COLORS else "level_INFO"

        # Make sure textbox is writable
        self.textbox.configure(state="normal")

        # Insert timestamp
        self._tk_text.insert("end", f"[{timestamp}] ", "timestamp")

        # Insert tag in bold color
        self._tk_text.insert("end", f"[{tag_clean}] ", tag_style)

        # Insert message in level color
        self._tk_text.insert("end", f"{message}\n", level_style)

        # Auto scroll to bottom
        self._tk_text.see("end")
        self.textbox.configure(state="disabled")

        self._line_count += 1
        self.count_label.configure(text=f"{self._line_count} lines")

    def upsert_log(self, key: str, tag: str, message: str, level: str = "INFO"):
        """
        Update an existing line in place (for live transcripts like [HEARD])
        or append if the key doesn't exist yet.
        """
        timestamp = time.strftime("%H:%M:%S")
        tag_clean = tag.upper().strip()
        level_clean = level.upper().strip()
        tag_style = f"tag_{tag_clean}" if tag_clean in TAG_COLORS else "tag_DEFAULT"
        level_style = f"level_{level_clean}" if level_clean in LEVEL_COLORS else "level_INFO"

        self.textbox.configure(state="normal")

        start_mark = f"mark_start_{key}"
        end_mark = f"mark_end_{key}"

        if key in self._upsert_marks:
            # Replace existing line range
            try:
                # Delete old content between marks
                self._tk_text.delete(start_mark, end_mark)
                # Insert updated line at start_mark
                self._tk_text.insert(start_mark, f"[{timestamp}] ", "timestamp")
                self._tk_text.insert(start_mark, f"[{tag_clean}] ", tag_style)
                self._tk_text.insert(start_mark, f"{message}\n", level_style)
                # Re-mark end
                self._tk_text.mark_set(end_mark, f"{start_mark} lineend + 1 char")
            except Exception:
                # Fallback to append if marks corrupted
                self.append_log(tag, message, level)
        else:
            # First time: place mark at current end
            self._tk_text.mark_set(start_mark, "end-1c")
            self._tk_text.mark_gravity(start_mark, "left")

            self._tk_text.insert("end", f"[{timestamp}] ", "timestamp")
            self._tk_text.insert("end", f"[{tag_clean}] ", tag_style)
            self._tk_text.insert("end", f"{message}\n", level_style)

            self._tk_text.mark_set(end_mark, "end-1c")
            self._tk_text.mark_gravity(end_mark, "right")

            self._upsert_marks[key] = (start_mark, end_mark)
            self._line_count += 1
            self.count_label.configure(text=f"{self._line_count} lines")

        self._tk_text.see("end")
        self.textbox.configure(state="disabled")

    def clear(self):
        """Clear all lines from the terminal."""
        self.textbox.configure(state="normal")
        self._tk_text.delete("1.0", "end")
        self.textbox.configure(state="disabled")
        self._line_count = 0
        self._upsert_marks.clear()
        self.count_label.configure(text="0 lines")
        if self.on_clear_cb:
            self.on_clear_cb()


class DevicePickerModal(ctk.CTkToplevel):
    """
    Modal dialog for discovering and selecting a DeskBot BLE peripheral.
    """
    def __init__(
        self,
        parent,
        on_select_device: Callable[[str, str], None],
        on_rescan: Callable[[], None]
    ):
        super().__init__(parent)
        self.parent = parent
        self.on_select_device = on_select_device
        self.on_rescan = on_rescan

        self.title("Select DeskBot Device")
        self.geometry("480x420")
        self.resizable(False, False)
        self.configure(fg_color=BASE)

        # Modal behavior
        self.transient(parent)
        self.grab_set()

        # Center on parent window
        self.update_idletasks()
        try:
            x = parent.winfo_x() + (parent.winfo_width() // 2) - 240
            y = parent.winfo_y() + (parent.winfo_height() // 2) - 210
            self.geometry(f"+{max(0, x)}+{max(0, y)}")
        except Exception:
            pass

        # Build UI
        self._init_ui()

    def _init_ui(self):
        # Header
        header = ctk.CTkFrame(self, fg_color="transparent")
        header.pack(fill="x", padx=20, pady=(16, 8))

        title = ctk.CTkLabel(
            header,
            text="Available Bluetooth Devices",
            font=ctk.CTkFont(family=FONT_FAMILY, size=16, weight="bold"),
            text_color=TEXT
        )
        title.pack(anchor="w")

        subtitle = ctk.CTkLabel(
            header,
            text="Select your DeskBot ESP32 device to connect",
            font=ctk.CTkFont(family=FONT_FAMILY, size=12),
            text_color=SUBTEXT0
        )
        subtitle.pack(anchor="w")

        # Scrollable device list
        self.device_list_frame = ctk.CTkScrollableFrame(
            self,
            fg_color=MANTLE,
            corner_radius=12,
            border_width=1,
            border_color=SURFACE1
        )
        self.device_list_frame.pack(fill="both", expand=True, padx=20, pady=8)

        # Status text in list
        self.loading_label = ctk.CTkLabel(
            self.device_list_frame,
            text="Scanning for Bluetooth LE devices...",
            font=ctk.CTkFont(family=FONT_FAMILY, size=12),
            text_color=OVERLAY1
        )
        self.loading_label.pack(pady=40)

        # Footer actions
        footer = ctk.CTkFrame(self, fg_color="transparent")
        footer.pack(fill="x", padx=20, pady=(8, 16))

        self.rescan_btn = ctk.CTkButton(
            footer,
            text="Rescan",
            width=90,
            height=34,
            corner_radius=10,
            fg_color=SURFACE0,
            border_color=SURFACE1,
            border_width=1,
            text_color=TEXT,
            hover_color=SURFACE1,
            font=ctk.CTkFont(family=FONT_FAMILY, size=12, weight="bold"),
            command=self._handle_rescan
        )
        self.rescan_btn.pack(side="left")

        self.cancel_btn = ctk.CTkButton(
            footer,
            text="Cancel",
            width=90,
            height=34,
            corner_radius=10,
            fg_color="transparent",
            border_color=SURFACE1,
            border_width=1,
            text_color=SUBTEXT0,
            hover_color=SURFACE0,
            font=ctk.CTkFont(family=FONT_FAMILY, size=12),
            command=self.destroy
        )
        self.cancel_btn.pack(side="right")

    def _handle_rescan(self):
        self.rescan_btn.configure(state="disabled", text="Scanning...")
        # Clear list
        for child in self.device_list_frame.winfo_children():
            child.destroy()
        self.loading_label = ctk.CTkLabel(
            self.device_list_frame,
            text="Scanning for Bluetooth LE devices...",
            font=ctk.CTkFont(family=FONT_FAMILY, size=12),
            text_color=OVERLAY1
        )
        self.loading_label.pack(pady=40)
        self.on_rescan()

    def set_devices(self, devices, error_msg: Optional[str] = None):
        """
        Populate device cards.
        devices: list of (name, address, is_deskbot)
        """
        self.rescan_btn.configure(state="normal", text="Rescan")

        for child in self.device_list_frame.winfo_children():
            child.destroy()

        if error_msg:
            empty_lbl = ctk.CTkLabel(
                self.device_list_frame,
                text=f"Bluetooth Warning:\n{error_msg}",
                font=ctk.CTkFont(family=FONT_FAMILY, size=12),
                text_color=RED
            )
            empty_lbl.pack(pady=40)
            return

        if not devices:
            empty_lbl = ctk.CTkLabel(
                self.device_list_frame,
                text="No Bluetooth LE devices found.\nMake sure DeskBot is powered on and within range.",
                font=ctk.CTkFont(family=FONT_FAMILY, size=12),
                text_color=OVERLAY1
            )
            empty_lbl.pack(pady=40)
            return

        for name, address, is_deskbot in devices:
            self._create_device_card(name, address, is_deskbot)

    def _create_device_card(self, name: str, address: str, is_deskbot: bool):
        card = FlatCard(
            self.device_list_frame,
            corner_radius=10,
            fg_color=SURFACE0 if not is_deskbot else "#26233a",
            border_color=MAUVE if is_deskbot else SURFACE1,
            border_width=1
        )
        card.pack(fill="x", pady=4, padx=4)

        info_col = ctk.CTkFrame(card, fg_color="transparent")
        info_col.pack(side="left", padx=12, pady=10, fill="x", expand=True)

        name_row = ctk.CTkFrame(info_col, fg_color="transparent")
        name_row.pack(fill="x", anchor="w")

        name_lbl = ctk.CTkLabel(
            name_row,
            text=name or "Unknown Device",
            font=ctk.CTkFont(family=FONT_FAMILY, size=13, weight="bold"),
            text_color=TEXT
        )
        name_lbl.pack(side="left")

        if is_deskbot:
            badge = ctk.CTkLabel(
                name_row,
                text="DESKBOT",
                font=ctk.CTkFont(family=FONT_FAMILY, size=9, weight="bold"),
                text_color=CRUST,
                fg_color=MAUVE,
                corner_radius=4,
                padx=6,
                pady=1
            )
            badge.pack(side="left", padx=8)

        addr_lbl = ctk.CTkLabel(
            info_col,
            text=address,
            font=ctk.CTkFont(family=FONT_MONO, size=11),
            text_color=SUBTEXT0
        )
        addr_lbl.pack(anchor="w", pady=(2, 0))

        # Connect button
        btn = ctk.CTkButton(
            card,
            text="Connect",
            width=76,
            height=30,
            corner_radius=8,
            fg_color=MAUVE if is_deskbot else SURFACE1,
            text_color=CRUST if is_deskbot else TEXT,
            hover_color=TEAL if is_deskbot else SURFACE2,
            font=ctk.CTkFont(family=FONT_FAMILY, size=11, weight="bold"),
            command=lambda n=name, a=address: self._on_connect_click(n, a)
        )
        btn.pack(side="right", padx=12, pady=10)

    def _on_connect_click(self, name: str, address: str):
        self.on_select_device(name, address)
        self.destroy()
