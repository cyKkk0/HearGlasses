/*
  ESP32-S3 + INMP441 I2S microphone capture

  Wiring:
    INMP441 VDD -> ESP32 3.3V
    INMP441 GND -> ESP32 GND
    INMP441 SD  -> ESP32 GPIO13
    INMP441 SCK -> ESP32 GPIO2
    INMP441 WS  -> ESP32 GPIO15
    INMP441 L/R -> ESP32 GND  (left channel)

  Open Serial Monitor at 921600 baud only when RAW_PCM_SERIAL is 0. By default
  this sketch streams raw PCM audio over USB serial so a computer can play it.

  Set RAW_PCM_SERIAL to 0 if you want readable RMS/peak diagnostics instead
  of raw audio bytes.
*/

#include <Arduino.h>
#include <math.h>
#include "driver/i2s.h"

#define RAW_PCM_SERIAL 1

static constexpr i2s_port_t I2S_PORT = I2S_NUM_0;

static constexpr int PIN_I2S_SD = 13;
static constexpr int PIN_I2S_SCK = 2;
static constexpr int PIN_I2S_WS = 15;

static constexpr int SAMPLE_RATE = 16000;
static constexpr int SERIAL_BAUD = 921600;
static constexpr int FRAME_SAMPLES = 320;  // 20 ms at 16 kHz

static int32_t i2sReadBuffer[FRAME_SAMPLES];
static int16_t pcm16Buffer[FRAME_SAMPLES];

static bool setupI2s()
{
  const i2s_config_t i2sConfig = {
    .mode = static_cast<i2s_mode_t>(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 8,
    .dma_buf_len = FRAME_SAMPLES,
    .use_apll = false,
    .tx_desc_auto_clear = false,
    .fixed_mclk = 0,
  };

  const i2s_pin_config_t pinConfig = {
    .bck_io_num = PIN_I2S_SCK,
    .ws_io_num = PIN_I2S_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num = PIN_I2S_SD,
  };

  esp_err_t err = i2s_driver_install(I2S_PORT, &i2sConfig, 0, nullptr);
  if (err != ESP_OK) {
    Serial.printf("i2s_driver_install failed: %d\n", err);
    return false;
  }

  err = i2s_set_pin(I2S_PORT, &pinConfig);
  if (err != ESP_OK) {
    Serial.printf("i2s_set_pin failed: %d\n", err);
    i2s_driver_uninstall(I2S_PORT);
    return false;
  }

  i2s_zero_dma_buffer(I2S_PORT);
  return true;
}

static size_t readPcm16Frame()
{
  size_t bytesRead = 0;
  const esp_err_t err = i2s_read(
    I2S_PORT,
    i2sReadBuffer,
    sizeof(i2sReadBuffer),
    &bytesRead,
    portMAX_DELAY
  );

  if (err != ESP_OK || bytesRead == 0) {
    return 0;
  }

  const size_t sampleCount = bytesRead / sizeof(int32_t);
  for (size_t i = 0; i < sampleCount; ++i) {
    // INMP441 outputs 24-bit samples inside a 32-bit I2S slot.
    pcm16Buffer[i] = static_cast<int16_t>(i2sReadBuffer[i] >> 16);
  }

  return sampleCount;
}

static void printLevel(const int16_t *samples, size_t sampleCount)
{
  uint64_t sumSquares = 0;
  int32_t peak = 0;

  for (size_t i = 0; i < sampleCount; ++i) {
    const int32_t sample = samples[i];
    const int32_t absSample = abs(sample);
    if (absSample > peak) {
      peak = absSample;
    }
    sumSquares += static_cast<int64_t>(sample) * sample;
  }

  const uint32_t rms = sqrt(static_cast<double>(sumSquares) / sampleCount);
  Serial.printf("rms=%lu, peak=%ld\n", static_cast<unsigned long>(rms), static_cast<long>(peak));
}

void setup()
{
  Serial.begin(SERIAL_BAUD);
  delay(500);

#if !RAW_PCM_SERIAL
  Serial.println();
  Serial.println("INMP441 I2S capture starting...");
  Serial.printf(
    "Pins: SD=%d, SCK=%d, WS=%d, sample_rate=%d, baud=%d\n",
    PIN_I2S_SD,
    PIN_I2S_SCK,
    PIN_I2S_WS,
    SAMPLE_RATE,
    SERIAL_BAUD
  );
#endif

  if (!setupI2s()) {
#if !RAW_PCM_SERIAL
    Serial.println("I2S setup failed. Check board target and pin wiring.");
#endif
    while (true) {
      delay(1000);
    }
  }

#if !RAW_PCM_SERIAL
  Serial.println("I2S ready.");
#endif
}

void loop()
{
  const size_t sampleCount = readPcm16Frame();
  if (sampleCount == 0) {
    delay(1);
    return;
  }

#if RAW_PCM_SERIAL
  Serial.write(reinterpret_cast<const uint8_t *>(pcm16Buffer), sampleCount * sizeof(int16_t));
#else
  printLevel(pcm16Buffer, sampleCount);
  delay(20);
#endif
}
