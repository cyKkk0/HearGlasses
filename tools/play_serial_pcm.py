#!/usr/bin/env python3
"""Play raw PCM audio streamed from the ESP32-S3 over a serial port."""

from __future__ import annotations

import argparse
import queue
import signal
import sys
import threading
import time

import numpy as np
import serial
import sounddevice as sd


SAMPLE_RATE = 16_000
CHANNELS = 1
FRAME_SAMPLES = 320
SAMPLE_WIDTH_BYTES = 2
FRAME_BYTES = FRAME_SAMPLES * SAMPLE_WIDTH_BYTES


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Play ESP32 serial PCM: 16 kHz, mono, signed 16-bit little-endian.",
    )
    parser.add_argument("--port", required=True, help="Serial port, e.g. /dev/cu.usbmodem... or COM5")
    parser.add_argument("--baud", type=int, default=921600, help="Serial baud rate")
    parser.add_argument("--gain", type=float, default=4.0, help="Playback gain multiplier")
    parser.add_argument("--queue-frames", type=int, default=30, help="Audio queue depth")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    audio_queue: queue.Queue[bytes] = queue.Queue(maxsize=args.queue_frames)
    stop_event = threading.Event()
    stats = {"frames": 0, "dropped": 0, "last_print": time.monotonic()}

    def request_stop(signum: int, frame: object) -> None:
        stop_event.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    def serial_reader() -> None:
        try:
            with serial.Serial(args.port, args.baud, timeout=1) as ser:
                ser.reset_input_buffer()
                while not stop_event.is_set():
                    chunk = ser.read(FRAME_BYTES)
                    if len(chunk) != FRAME_BYTES:
                        continue

                    try:
                        audio_queue.put_nowait(chunk)
                    except queue.Full:
                        try:
                            audio_queue.get_nowait()
                        except queue.Empty:
                            pass
                        audio_queue.put_nowait(chunk)
                        stats["dropped"] += 1

                    stats["frames"] += 1
                    now = time.monotonic()
                    if now - stats["last_print"] >= 2:
                        print(
                            f"frames={stats['frames']} queue={audio_queue.qsize()} dropped={stats['dropped']}",
                            file=sys.stderr,
                        )
                        stats["last_print"] = now
        except serial.SerialException as exc:
            print(f"Serial error: {exc}", file=sys.stderr)
            stop_event.set()

    def audio_callback(outdata: np.ndarray, frames: int, time_info: object, status: sd.CallbackFlags) -> None:
        if status:
            print(status, file=sys.stderr)

        try:
            chunk = audio_queue.get_nowait()
        except queue.Empty:
            outdata.fill(0)
            return

        samples = np.frombuffer(chunk, dtype="<i2").astype(np.float32) / 32768.0
        samples = np.clip(samples * args.gain, -1.0, 1.0)

        if len(samples) < frames:
            padded = np.zeros(frames, dtype=np.float32)
            padded[: len(samples)] = samples
            samples = padded
        else:
            samples = samples[:frames]

        outdata[:, 0] = samples

    print(f"Opening {args.port} at {args.baud} baud")
    print("Close Arduino Serial Monitor before running this. Press Ctrl+C to stop.")

    reader = threading.Thread(target=serial_reader, name="SerialPcmReader", daemon=True)
    reader.start()

    try:
        with sd.OutputStream(
            samplerate=SAMPLE_RATE,
            channels=CHANNELS,
            dtype="float32",
            blocksize=FRAME_SAMPLES,
            callback=audio_callback,
        ):
            while not stop_event.is_set():
                time.sleep(0.1)
    finally:
        stop_event.set()
        reader.join(timeout=2)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
