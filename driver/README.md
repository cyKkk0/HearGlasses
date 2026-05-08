# ESP32-S3 INMP441 driver

This folder contains a minimal Arduino sketch for receiving audio from an
INMP441 I2S microphone connected to an ESP32-S3.

It also contains a Wi-Fi AP demo sketch for connecting a phone directly to the
ESP32-S3 without a router.

## Wiring

| INMP441 | ESP32-S3 |
| --- | --- |
| VDD | 3.3V |
| GND | GND |
| SD | GPIO13 |
| SCK | GPIO2 |
| WS | GPIO15 |
| L/R | GND |

`L/R` is tied to `GND`, so the sketch reads the left I2S channel.

## Listen on the computer

1. Open `driver/inmp441_capture/inmp441_capture.ino` in Arduino IDE.
2. Select an ESP32-S3 board target.
3. Install/select the ESP32 Arduino core if needed.
4. Upload the sketch.
5. Close Arduino Serial Monitor if it is open.
6. Play the serial PCM stream from the conda `base` environment:

```bash
conda run -n base python tools/play_serial_pcm.py --port /dev/cu.usbmodem5C371873961 --baud 921600 --gain 4
```

The sketch streams raw `16000 Hz / mono / signed 16-bit little-endian` PCM
bytes over USB serial by default.

## Diagnostic mode

If you want readable microphone level diagnostics instead of audio playback,
set this in `inmp441_capture.ino`:

```cpp
#define RAW_PCM_SERIAL 0
```

Then upload again and open Serial Monitor at `921600` baud.

The default output is readable audio level diagnostics:

```text
rms=123, peak=980
```

Speak near the microphone. `rms` and `peak` should rise when sound is present.

## Wi-Fi AP phone demo

Open and upload:

```text
driver/wifi_ap_phone/wifi_ap_phone.ino
```

After upload, connect your phone to:

```text
SSID: HearGlasses-ESP32
Password: 12345678
```

Then open this URL in the phone browser:

```text
http://192.168.4.1
```

Useful endpoints:

```text
http://192.168.4.1/
http://192.168.4.1/status
http://192.168.4.1/ping
```

Phones may show a "no internet" warning for this Wi-Fi network. Keep the phone
connected anyway; this is normal because the ESP32-S3 AP is only a local link.

## BLE audio peripheral demo

Open and upload:

```text
driver/ble_audio_peripheral/ble_audio_peripheral.ino
```

Or upload with Arduino CLI:

```bash
arduino-cli upload --fqbn esp32:esp32:esp32s3 --port /dev/cu.usbmodem5C371873961 driver/ble_audio_peripheral
```

The ESP32-S3 advertises as:

```text
HearGlasses
```

It uses the same BLE service and characteristic UUIDs as the Android app:

```text
Service:    000018fd-0000-1000-8000-00805f9b34fb
Audio_TX:   00002a3d-0000-1000-8000-00805f9b34fb
Command_TX: 00002a3e-0000-1000-8000-00805f9b34fb
Text_RX:    00002a3f-0000-1000-8000-00805f9b34fb
```

Audio is sent as raw `16000 Hz / mono / signed 16-bit little-endian` PCM over
BLE notifications. The Android `RealBleManager` is configured to treat hardware
BLE audio as PCM for this bring-up sketch, and the app plays incoming hardware
BLE PCM through Android `AudioTrack` while also feeding it into the recognition
pipeline.
