"""
Audio Capture Manager for DeskBot Desktop.
Streams 16kHz mono 16-bit PCM microphone audio via sounddevice.
"""

from typing import Callable, Optional
import sounddevice as sd
import numpy as np

SAMPLE_RATE = 16000
BLOCK_SIZE = 480  # 30 ms blocks (480 samples * 2 bytes = 960 bytes)


class AudioManager:
    """
    Manages local PC microphone audio input using sounddevice.
    Streams 16 kHz, 16-bit signed LE mono PCM chunks to the callback.
    """
    def __init__(self, on_audio_chunk: Callable[[bytes], None], on_log: Optional[Callable] = None):
        self.on_audio_chunk = on_audio_chunk
        self.on_log = on_log
        self._stream: Optional[sd.InputStream] = None
        self._running = False

    def _log(self, msg: str, level: str = "INFO"):
        if self.on_log:
            self.on_log("AUDIO", msg, level)

    @property
    def is_running(self) -> bool:
        return self._running

    def start(self):
        """Start capturing microphone audio."""
        if self._running:
            return

        try:
            self._running = True
            self._stream = sd.InputStream(
                samplerate=SAMPLE_RATE,
                channels=1,
                dtype="int16",
                blocksize=BLOCK_SIZE,
                callback=self._audio_callback
            )
            self._stream.start()
            self._log(f"Microphone stream started ({SAMPLE_RATE} Hz, 16-bit mono)", "INFO")
        except Exception as e:
            self._running = False
            self._stream = None
            self._log(f"Failed to start microphone: {e}", "ERROR")

    def _audio_callback(self, indata: np.ndarray, frames: int, time_info, status):
        """sounddevice callback invoked on audio input buffer availability."""
        if status and self.on_log:
            if status.input_overflow:
                self._log("Audio input overflow detected", "WARN")

        if self._running:
            # indata is numpy int16 array of shape (frames, 1)
            raw_bytes = bytes(indata)
            self.on_audio_chunk(raw_bytes)

    def stop(self):
        """Stop capturing microphone audio."""
        self._running = False
        if self._stream:
            try:
                self._stream.stop()
                self._stream.close()
            except Exception as e:
                self._log(f"Error stopping audio stream: {e}", "WARN")
            self._stream = None
            self._log("Microphone stream stopped", "INFO")
