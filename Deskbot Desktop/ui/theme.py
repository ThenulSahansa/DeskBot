"""
Catppuccin Mocha Color Palette & Typography Theme for DeskBot Desktop.
Matches the Android DeskDroid companion theme exactly.
"""

# Base background colors
BASE = "#1e1e2e"       # Main background
MANTLE = "#181825"     # Sidebar / darker panels
CRUST = "#11111b"      # Terminal background / lowest layer

# Surface & Container colors
SURFACE0 = "#313244"   # Card containers
SURFACE1 = "#45475a"   # Card borders & separators
SURFACE2 = "#585b70"   # Hover highlights & inactive controls

# Overlays & Subtexts
OVERLAY0 = "#6c7086"   # Disabled / very dim text
OVERLAY1 = "#7f849c"   # Category labels & section headers
SUBTEXT0 = "#a6adc8"   # Secondary text / subtitles
SUBTEXT1 = "#bac2de"   # Medium-contrast labels
TEXT = "#cdd6f4"       # Primary text color

# Accent & Semantic colors
MAUVE = "#cba6f7"      # Primary brand accent / active buttons / badges
RED = "#f38ba8"        # Disconnect / errors / recording
PEACH = "#fab387"      # Display messages / warnings / OTA
YELLOW = "#f9e2af"     # Heard transcription / alerts
GREEN = "#a6e3a1"      # Success / online link / connected state
TEAL = "#94e2d5"       # Audio streaming / MTU
BLUE = "#89b4fa"       # BLE link telemetry / status
SAPPHIRE = "#74c7ec"   # Informational highlights

# Terminal Log Tag Colors
TAG_COLORS = {
    "BLE": BLUE,
    "AUDIO": TEAL,
    "GEMINI": MAUVE,
    "HEARD": YELLOW,
    "DISPLAY": PEACH,
    "SYSTEM": OVERLAY1,
}

# Terminal Log Level Colors
LEVEL_COLORS = {
    "INFO": TEXT,
    "WARN": YELLOW,
    "ERROR": RED,
    "SUCCESS": GREEN,
}

# Standard Typography
FONT_FAMILY = "Segoe UI"
FONT_MONO = "Consolas"
