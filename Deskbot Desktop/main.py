"""
DeskBot Desktop Application Entry Point.
AI Desktop Companion mirroring the DeskDroid Android companion application.
"""

import sys
import os

# Ensure current directory is in sys.path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import customtkinter
from ui.app import DeskBotApp


def main():
    # Set dark appearance mode and modern color theme
    customtkinter.set_appearance_mode("dark")
    customtkinter.set_default_color_theme("dark-blue")

    # Launch DeskBot Desktop Application
    app = DeskBotApp()
    app.mainloop()


if __name__ == "__main__":
    main()
