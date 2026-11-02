"""
Text-to-Speech (TTS) Manager for DeskBot Desktop.
Uses pyttsx3 with thread safety and mute control.
"""

import threading
import pyttsx3


class TtsManager:
    """
    Offline Text-To-Speech engine using pyttsx3.
    Operates asynchronously on a background worker thread.
    """
    def __init__(self, rate: int = 175, on_log=None):
        self._engine = None
        self._lock = threading.Lock()
        self._ready = False
        self._muted = False
        self._rate = rate
        self._on_log = on_log

        # Initialize pyttsx3 on a background thread to prevent UI freezing
        self._init_thread = threading.Thread(target=self._init_engine, daemon=True, name="TTS-Init")
        self._init_thread.start()

    def _log(self, msg: str, level: str = "INFO"):
        if self._on_log:
            self._on_log("TTS", msg, level)

    def _init_engine(self):
        try:
            # On Windows, initialize COM for this thread
            try:
                import pythoncom
                pythoncom.CoInitialize()
            except ImportError:
                pass

            self._engine = pyttsx3.init()
            self._engine.setProperty("rate", self._rate)
            self._ready = True
            self._log("TTS engine initialized successfully", "SUCCESS")
        except Exception as e:
            self._log(f"TTS init failed: {e}", "WARN")

    @property
    def muted(self) -> bool:
        """Return True if TTS output is currently muted."""
        return self._muted

    @muted.setter
    def muted(self, val: bool):
        """Set TTS mute status."""
        self._muted = bool(val)
        if self._muted:
            self.stop()

    def speak(self, text: str):
        """
        Speak text asynchronously. If muted or engine is not ready, does nothing.
        """
        if self._muted or not text:
            return

        def _speak_worker():
            if not self._ready or not self._engine:
                return

            with self._lock:
                try:
                    # Initialize COM for secondary threads on Windows
                    try:
                        import pythoncom
                        pythoncom.CoInitialize()
                    except ImportError:
                        pass

                    self._engine.stop()
                    self._engine.say(text)
                    self._engine.runAndWait()
                except Exception as e:
                    self._log(f"Speech error: {e}", "WARN")

        threading.Thread(target=_speak_worker, daemon=True, name="TTS-Speak").start()

    def stop(self):
        """Stop any active speech output."""
        if self._ready and self._engine:
            try:
                self._engine.stop()
            except Exception:
                pass
