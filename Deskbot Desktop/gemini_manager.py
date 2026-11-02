"""
Gemini Live Multimodal WebSocket Client for DeskBot Desktop.
Direct Python port of Android's GeminiLiveManager.kt with full parity:
- BidiGenerateContent WebSocket streaming
- AGC (Automatic Gain Control) + 28000 soft limiter
- Voiced thresholding & speech verification (rejects clicks/blips)
- Realtime base64 PCM streaming
- Trailing digital silence & audioStreamEnd turn completion
- Live transcripts for input (HEARD) and output (GEMINI)
"""

import os
import sys
import time
import math
import json
import base64
import struct
import asyncio
import threading
from typing import Callable, Optional, List

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

import websockets

# Configuration constants matching GeminiLiveManager.kt
DEFAULT_API_KEY = "YOUR_GEMINI_API_KEY"
MODEL_ID = "models/gemini-3.8-live"
WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

SAMPLE_RATE = 16000
BYTES_PER_SECOND = SAMPLE_RATE * 2
SEND_CHUNK_BYTES = 1600         # 50 ms of 16 kHz mono 16-bit PCM
TRAILING_SILENCE_MS = 500       # Digital silence appended so server-side VAD sees end
ACTIVE_CHUNK_RMS = 45.0         # Threshold for voiced chunk
MIN_ACTIVE_SAMPLES = SAMPLE_RATE * 120 // 1000  # 120 ms minimum voiced audio required
AUDIO_IDLE_END_OF_TURN_MS = 1.0 # 1.0 second silence fallback

# Automatic Gain Control (AGC) constants
AGC_TARGET_RMS = 3000.0
AGC_MIN_GAIN = 1.0
AGC_MAX_GAIN = 20.0
AGC_START_GAIN = 8.0

SYSTEM_INSTRUCTION_TEXT = (
    "You are Maya. An AI agent, a concise and friendly desktop companion. "
    "Keep your answers short (1 to 2 sentences). "
    "Answer only what the user has just said in the audio. Never continue or repeat "
    "an earlier topic unless the user brings it up. If the audio is unclear, "
    "silent or just noise, briefly ask the user to repeat instead of guessing."
)


class GeminiManager:
    """
    Manages real-time bidirectional audio streaming session with Gemini Live API.
    Runs on an isolated asyncio background thread.
    """
    def __init__(
        self,
        on_response: Callable[[str], None],
        on_setup_complete: Callable[[], None],
        on_error: Callable[[str], None],
        on_log: Callable[..., None],
        api_key: Optional[str] = None
    ):
        self.on_response = on_response
        self.on_setup_complete = on_setup_complete
        self.on_error = on_error
        self.on_log = on_log

        # Resolve API Key: parameter -> env var -> default placeholder
        self.api_key = api_key or os.environ.get("GEMINI_API_KEY", DEFAULT_API_KEY)

        self.ws = None
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self._thread: Optional[threading.Thread] = None

        self._connected = False
        self._setup_complete = False
        self._user_disconnected = False

        # Per-turn audio state (guarded by _lock)
        self._lock = threading.Lock()
        self._has_unfinished_turn = False
        self._turn_streaming = False
        self._pending = bytearray()
        self._turn_samples = 0
        self._turn_active_samples = 0
        self._turn_bytes_in = 0
        self._turn_peak = 0
        self._turn_peak_rms = 0.0
        self._turn_first_packet_t = 0.0
        self._turn_last_packet_t = 0.0
        self._agc_gain = AGC_START_GAIN

        # Response & transcription state
        self._response_buffer: List[str] = []
        self._heard_buffer: List[str] = []
        self._heard_seq = 0
        self._response_started = False
        self._heard_shown = False

        # Fallback idle timer
        self._idle_timer: Optional[threading.Timer] = None

    def _log(self, tag: str, msg: str, level: str = "INFO", key: Optional[str] = None):
        if self.on_log:
            self.on_log(tag, msg, level, key=key)

    @property
    def is_connected(self) -> bool:
        return self._connected and self._setup_complete

    def set_api_key(self, key: str):
        self.api_key = key.strip()

    def connect(self):
        """Start the WebSocket client in a background daemon thread."""
        if self._connected or (self._thread and self._thread.is_alive()):
            return

        self._user_disconnected = False
        self._thread = threading.Thread(target=self._run_loop, daemon=True, name="GeminiLive-Loop")
        self._thread.start()

    def _run_loop(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self._ws_lifecycle())
        except Exception as e:
            self._log("GEMINI", f"Event loop error: {e}", "ERROR")
        finally:
            try:
                self.loop.close()
            except Exception:
                pass
            self.loop = None

    async def _ws_lifecycle(self):
        """Reconnection lifecycle loop."""
        while not self._user_disconnected:
            try:
                await self._ws_connect()
            except Exception as e:
                self._connected = False
                self._setup_complete = False
                self._reset_session()
                if self._user_disconnected:
                    break
                self._log("GEMINI", f"WebSocket connection error: {e} — retrying in 2s", "WARN")
                await asyncio.sleep(2.0)

    async def _ws_connect(self):
        if not self.api_key or self.api_key == DEFAULT_API_KEY:
            self._log("GEMINI", "Please set your GEMINI_API_KEY in .env or settings", "ERROR")
            self.on_error("GEMINI_API_KEY not configured")
            return

        url = f"{WS_URL}?key={self.api_key}"
        self._log("GEMINI", "Connecting to Gemini Live API...", "INFO")

        # Support both 'additional_headers' (websockets >= 13) and 'extra_headers' (websockets < 13)
        headers = {"x-goog-api-key": self.api_key}
        connect_kwargs = {"ping_interval": 25, "open_timeout": 15}

        try:
            connect_ctx = websockets.connect(url, additional_headers=headers, **connect_kwargs)
        except TypeError:
            connect_ctx = websockets.connect(url, extra_headers=headers, **connect_kwargs)

        async with connect_ctx as ws:
            self.ws = ws
            self._connected = True
            self._log("GEMINI", "WebSocket connected, sending setup...", "SUCCESS")

            await self._send_setup()

            async for message in ws:
                if self._user_disconnected:
                    break
                await self._handle_message(message)

    async def _send_setup(self):
        """
        Send initial Gemini Live BidiGenerateContent setup payload.
        Crucial: Must not contain realtimeInputConfig or disabled server VAD.
        """
        setup_payload = {
            "setup": {
                "model": MODEL_ID,
                "generationConfig": {
                    "responseModalities": ["AUDIO"]
                },
                "outputAudioTranscription": {},
                "inputAudioTranscription": {},
                "systemInstruction": {
                    "parts": [{
                        "text": SYSTEM_INSTRUCTION_TEXT
                    }]
                }
            }
        }
        await self.ws.send(json.dumps(setup_payload))
        self._log("GEMINI", f"Setup message sent (model={MODEL_ID}, modality=AUDIO)", "INFO")

    async def _handle_message(self, raw_message: str):
        """Process incoming Gemini Live JSON messages."""
        try:
            msg = json.loads(raw_message)

            # Error handling
            if "error" in msg:
                err = msg["error"]
                code = err.get("code", 0)
                message = err.get("message", raw_message)
                self._log("GEMINI", f"API Error ({code}): {message}", "ERROR")
                self.on_error(f"Gemini ({code}): {message}")
                return

            # Setup complete acknowledgment
            if "setupComplete" in msg:
                self._setup_complete = True
                self._log("GEMINI", "Setup complete — ready for voice input!", "SUCCESS")
                self.on_setup_complete()
                return

            if "serverContent" in msg:
                sc = msg["serverContent"]

                # 1. Live transcription of user speech (HEARD)
                in_frag = ""
                if "inputTranscription" in sc:
                    in_frag = sc["inputTranscription"].get("text", "")
                elif "inputAudioTranscription" in sc:
                    in_frag = sc["inputAudioTranscription"].get("text", "")

                if in_frag:
                    self._heard_buffer.append(in_frag)
                    heard_text = "".join(self._heard_buffer).strip()
                    if heard_text:
                        self._heard_shown = True
                        key = f"heard-{self._heard_seq}"
                        self._log("HEARD", f'"{heard_text}"', "INFO", key=key)

                # 2. Transcription of Gemini's response (SAID)
                out_frag = ""
                if "outputTranscription" in sc:
                    out_frag = sc["outputTranscription"].get("text", "")
                elif "outputAudioTranscription" in sc:
                    out_frag = sc["outputAudioTranscription"].get("text", "")

                if out_frag:
                    self._mark_response_started()
                    self._response_buffer.append(out_frag)

                # Model turn parts
                if "modelTurn" in sc:
                    model_turn = sc["modelTurn"]
                    for part in model_turn.get("parts", []):
                        if "text" in part and not part.get("thought", False):
                            self._mark_response_started()
                            self._response_buffer.append(part["text"])
                        if "inlineData" in part:
                            self._mark_response_started()

                # User barged in / interrupted
                if sc.get("interrupted", False):
                    self._response_buffer.clear()
                    self._response_started = False
                    self._log("GEMINI", "Response interrupted by user speech", "WARN")

                gen_complete = sc.get("generationComplete", False)
                turn_complete = sc.get("turnComplete", False)

                if gen_complete or turn_complete:
                    full_response = "".join(self._response_buffer).strip()
                    self._response_buffer.clear()

                    if full_response:
                        self._log("GEMINI", f"Response: {full_response}", "SUCCESS")
                        self.on_response(full_response)

                    if turn_complete:
                        self._finalize_heard()
                        if self._response_started and not self._heard_shown:
                            self._log(
                                "HEARD",
                                "(no transcript) Gemini answered without recognising speech - "
                                "the audio was likely too quiet or noisy",
                                "WARN"
                            )
                        self._response_started = False
                        self._heard_shown = False
                        self._log("GEMINI", "Turn complete", "INFO")

        except Exception as e:
            self._log("GEMINI", f"Message parse error: {e}", "ERROR")

    def _mark_response_started(self):
        if self._response_started:
            return
        self._response_started = True
        self._finalize_heard()

    def _finalize_heard(self):
        heard = "".join(self._heard_buffer).strip()
        if heard:
            key = f"heard-{self._heard_seq}"
            self._log("HEARD", f'"{heard}"', "SUCCESS", key=key)
            self._heard_buffer.clear()
            self._heard_seq += 1

    # ------------------------------------------------------------------------------------
    # Audio Ingestion & Turn Management
    # ------------------------------------------------------------------------------------

    def send_start_of_turn(self):
        """
        Called when VOICE_START marker is received from DeskBot BLE or manual trigger.
        """
        if not self._connected or not self._setup_complete:
            return

        with self._lock:
            if not self._has_unfinished_turn:
                self._begin_turn_locked()

        self._reset_idle_timer()

    def _begin_turn_locked(self):
        self._has_unfinished_turn = True
        self._turn_streaming = False
        self._pending = bytearray()
        self._turn_samples = 0
        self._turn_active_samples = 0
        self._turn_bytes_in = 0
        self._turn_peak = 0
        self._turn_peak_rms = 0.0
        self._turn_first_packet_t = 0.0
        self._turn_last_packet_t = 0.0
        self._agc_gain = max(4.0, min(16.0, self._agc_gain))
        self._log("GEMINI", "Voice turn started", "INFO")

    def send_audio(self, pcm_data: bytes):
        """
        Ingest raw 16-bit little-endian PCM audio.
        Applies AGC, soft-limiter, and buffers until speech is confirmed.
        """
        if not self._connected or not self._setup_complete or len(pcm_data) < 2:
            return

        n = len(pcm_data) // 2

        with self._lock:
            if not self._has_unfinished_turn:
                self._begin_turn_locked()

            now = time.monotonic()
            if self._turn_first_packet_t == 0.0:
                self._turn_first_packet_t = now
            self._turn_last_packet_t = now
            self._turn_bytes_in += len(pcm_data)

            # Unpack signed 16-bit little-endian samples
            samples = struct.unpack_from(f"<{n}h", pcm_data)

            # Measure level (peak & RMS)
            sum_sq = sum(s * s for s in samples)
            rms = math.sqrt(sum_sq / n) if n > 0 else 0.0
            peak = max(abs(s) for s in samples) if samples else 0

            if peak > self._turn_peak:
                self._turn_peak = peak
            if rms > self._turn_peak_rms:
                self._turn_peak_rms = rms

            self._turn_samples += n
            voiced = rms >= ACTIVE_CHUNK_RMS
            if voiced:
                self._turn_active_samples += n

            # AGC: quickly reduce gain when loud, slowly raise it when quiet
            start_gain = self._agc_gain
            if voiced:
                desired = max(AGC_MIN_GAIN, min(AGC_MAX_GAIN, AGC_TARGET_RMS / rms if rms > 0 else AGC_MAX_GAIN))
                speed = 0.6 if desired < self._agc_gain else 0.03
                self._agc_gain += (desired - self._agc_gain) * speed
            end_gain = self._agc_gain

            # Apply smooth AGC ramping & soft limiting
            out = bytearray(n * 2)
            for i, s in enumerate(samples):
                g = start_gain + (end_gain - start_gain) * (i + 1) / n
                v = s * g
                av = abs(v)
                if av > 28000.0:
                    # Soft knee compression at 28000 instead of harsh clipping
                    limited = 28000.0 + (av - 28000.0) * 0.15
                    v = -limited if v < 0 else limited

                iv = max(-32768, min(32767, int(round(v))))
                struct.pack_into("<h", out, i * 2, iv)

            self._pending.extend(out)

            # Confirm real speech: hold audio until >= 120ms voiced audio accumulated
            if not self._turn_streaming and self._turn_active_samples >= MIN_ACTIVE_SAMPLES:
                self._turn_streaming = True
                self._log("AUDIO", "Speech confirmed — streaming to Gemini", "INFO")

            if self._turn_streaming:
                self._flush_pending_locked(force=False)

        self._reset_idle_timer()

    def _flush_pending_locked(self, force: bool = False):
        if not self._turn_streaming or not self._pending:
            return

        data = bytes(self._pending)
        self._pending = bytearray()
        offset = 0

        while offset < len(data):
            end = min(offset + SEND_CHUNK_BYTES, len(data))
            if (end - offset) < SEND_CHUNK_BYTES and not force:
                self._pending = bytearray(data[offset:])
                break
            self._send_audio_chunk(data, offset, end)
            offset = end

    def _send_audio_chunk(self, data: bytes, frm: int, to: int):
        if not self.ws or not self.loop or self.loop.is_closed():
            return

        chunk = data[frm:to]
        b64_audio = base64.b64encode(chunk).decode("ascii")

        payload = json.dumps({
            "realtimeInput": {
                "audio": {
                    "mimeType": f"audio/pcm;rate={SAMPLE_RATE}",
                    "data": b64_audio
                }
            }
        })

        asyncio.run_coroutine_threadsafe(self.ws.send(payload), self.loop)

    def send_end_of_turn(self):
        """
        Called when VOICE_END marker is received from DeskBot BLE or idle silence expires.
        """
        self._cancel_idle_timer()

        with self._lock:
            if not self._has_unfinished_turn:
                return
            self._has_unfinished_turn = False

            seconds = self._turn_samples / SAMPLE_RATE if SAMPLE_RATE else 0
            voiced_ms = self._turn_active_samples * 1000 // SAMPLE_RATE if SAMPLE_RATE else 0
            peak = self._turn_peak
            peak_rms = int(self._turn_peak_rms)
            gain = self._agc_gain
            bytes_in = self._turn_bytes_in
            streaming = self._turn_streaming
            self._turn_streaming = False

            if not self._connected or not self._setup_complete:
                self._pending = bytearray()
                return

            if not streaming:
                # Discard click or brief noise blip
                self._pending = bytearray()
                self._log(
                    "AUDIO",
                    f"Ignored non-speech turn ({seconds:.1f}s, voiced={voiced_ms}ms, peakRms={peak_rms}, peak={peak}). "
                    "If speech is ignored, verify INMP441 L/R pin is tied to GND.",
                    "WARN"
                )
                return

            # Flush any remaining buffer
            self._turn_streaming = True
            self._flush_pending_locked(force=True)
            self._turn_streaming = False

            # Append 500ms trailing digital silence so server VAD detects pause
            tail = bytes(BYTES_PER_SECOND * TRAILING_SILENCE_MS // 1000)
            off = 0
            while off < len(tail):
                end = min(off + SEND_CHUNK_BYTES, len(tail))
                self._send_audio_chunk(tail, off, end)
                off = end

            # Signal end of microphone audio stream
            end_msg = json.dumps({"realtimeInput": {"audioStreamEnd": True}})
            if self.ws and self.loop and not self.loop.is_closed():
                asyncio.run_coroutine_threadsafe(self.ws.send(end_msg), self.loop)

            self._log(
                "GEMINI",
                f"Speech turn sent ({seconds:.1f}s, voiced={voiced_ms}ms, peakRms={peak_rms}, gain={gain:.1f}x)",
                "INFO"
            )

    def _reset_idle_timer(self):
        self._cancel_idle_timer()
        self._idle_timer = threading.Timer(AUDIO_IDLE_END_OF_TURN_MS, self._idle_end_of_turn)
        self._idle_timer.daemon = True
        self._idle_timer.start()

    def _cancel_idle_timer(self):
        if self._idle_timer:
            self._idle_timer.cancel()
            self._idle_timer = None

    def _idle_end_of_turn(self):
        with self._lock:
            if not self._has_unfinished_turn:
                return
        self._log("GEMINI", "Audio stream idle — finalizing turn", "INFO")
        self.send_end_of_turn()

    def _reset_session(self):
        self._cancel_idle_timer()
        with self._lock:
            self._has_unfinished_turn = False
            self._turn_streaming = False
            self._pending = bytearray()
        self._response_buffer.clear()
        self._heard_buffer.clear()
        self._response_started = False
        self._heard_shown = False

    def disconnect(self):
        """Disconnect and clean up WebSocket session."""
        self._user_disconnected = True
        self._reset_session()

        if self.ws and self.loop and not self.loop.is_closed():
            try:
                asyncio.run_coroutine_threadsafe(self.ws.close(1000, "User disconnected"), self.loop)
            except Exception:
                pass

        self._connected = False
        self._setup_complete = False
        self.ws = None
        self._log("GEMINI", "Disconnected from Gemini Live API", "INFO")
