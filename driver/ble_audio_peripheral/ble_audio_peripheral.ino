/*
  ESP32-S3 + INMP441 BLE audio peripheral

  Wiring:
    INMP441 VDD -> ESP32 3.3V
    INMP441 GND -> ESP32 GND
    INMP441 SD  -> ESP32 GPIO13
    INMP441 SCK -> ESP32 GPIO2
    INMP441 WS  -> ESP32 GPIO15
    INMP441 L/R -> ESP32 GND  (left channel)

  BLE:
    Device name: HearGlasses
    Service:     000018fd-0000-1000-8000-00805f9b34fb
    Audio_TX:    00002a3d-0000-1000-8000-00805f9b34fb  notify, PCM bytes
    Command_TX:  00002a3e-0000-1000-8000-00805f9b34fb  notify
    Text_RX:     00002a3f-0000-1000-8000-00805f9b34fb  write

  Audio payload:
    16000 Hz / mono / signed 16-bit little-endian PCM.
*/

#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <math.h>
#include "driver/i2s.h"

static constexpr const char *DEVICE_NAME = "HearGlasses";
static constexpr const char *SERVICE_UUID = "000018fd-0000-1000-8000-00805f9b34fb";
static constexpr const char *AUDIO_TX_UUID = "00002a3d-0000-1000-8000-00805f9b34fb";
static constexpr const char *COMMAND_TX_UUID = "00002a3e-0000-1000-8000-00805f9b34fb";
static constexpr const char *TEXT_RX_UUID = "00002a3f-0000-1000-8000-00805f9b34fb";

static constexpr uint8_t COMMAND_START_SPEECH = 0x01;
static constexpr uint8_t COMMAND_END_SPEECH = 0x00;

static constexpr int SERIAL_BAUD = 115200;
static constexpr int SAMPLE_RATE = 16000;
static constexpr int FRAME_SAMPLES = 320;  // 20 ms at 16 kHz
static constexpr int INPUT_SHIFT = 15;
static constexpr float AUDIO_GAIN = 2.0f;
static constexpr float HIGH_PASS_HZ = 120.0f;
static constexpr float LOW_PASS_HZ = 3800.0f;
static constexpr float NOISE_GATE = 120.0f;
static constexpr float LIMITER_THRESHOLD = 14000.0f;
static constexpr float LIMITER_RATIO = 4.0f;

static constexpr int PIN_I2S_SD = 13;
static constexpr int PIN_I2S_SCK = 2;
static constexpr int PIN_I2S_WS = 15;

// Keep this conservative. Android requests a larger MTU, but 180-byte chunks
// are reliable across more phones during first bring-up.
static constexpr size_t BLE_AUDIO_CHUNK_BYTES = 180;

static constexpr i2s_port_t I2S_PORT = I2S_NUM_0;

static BLECharacteristic *audioTx = nullptr;
static BLECharacteristic *commandTx = nullptr;
static BLECharacteristic *textRx = nullptr;

static volatile bool bleConnected = false;
static volatile bool wasConnected = false;

static int32_t i2sReadBuffer[FRAME_SAMPLES];
static int16_t pcm16Buffer[FRAME_SAMPLES];
static float highPassLastInput = 0.0f;
static float highPassLastOutput = 0.0f;
static float lowPassLastOutput = 0.0f;
static float highPassAlpha = 0.0f;
static float lowPassAlpha = 0.0f;

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override
  {
    bleConnected = true;
    Serial.println("BLE connected");
  }

  void onDisconnect(BLEServer *server) override
  {
    bleConnected = false;
    Serial.println("BLE disconnected");
  }
};

class TextRxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override
  {
    String value = characteristic->getValue();
    Serial.print("Text_RX: ");
    Serial.println(value);
  }
};

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

static void setupAudioFilters()
{
  const float dt = 1.0f / SAMPLE_RATE;

  const float highPassRc = 1.0f / (2.0f * PI * HIGH_PASS_HZ);
  highPassAlpha = highPassRc / (highPassRc + dt);

  const float lowPassRc = 1.0f / (2.0f * PI * LOW_PASS_HZ);
  lowPassAlpha = dt / (lowPassRc + dt);

  highPassLastInput = 0.0f;
  highPassLastOutput = 0.0f;
  lowPassLastOutput = 0.0f;
}

static float applyHighPass(float input)
{
  const float output = highPassAlpha * (highPassLastOutput + input - highPassLastInput);
  highPassLastInput = input;
  highPassLastOutput = output;
  return output;
}

static float applyLowPass(float input)
{
  lowPassLastOutput += lowPassAlpha * (input - lowPassLastOutput);
  return lowPassLastOutput;
}

static float applySoftLimiter(float input)
{
  const float sign = input < 0 ? -1.0f : 1.0f;
  const float magnitude = fabs(input);

  if (magnitude <= LIMITER_THRESHOLD) {
    return input;
  }

  const float compressed = LIMITER_THRESHOLD + (magnitude - LIMITER_THRESHOLD) / LIMITER_RATIO;
  return sign * min(compressed, 32767.0f);
}

static int16_t processAudioSample(int32_t rawSample)
{
  float sample = static_cast<float>(rawSample);
  sample = applyHighPass(sample);
  sample = applyLowPass(sample);

  if (fabs(sample) < NOISE_GATE) {
    sample = 0.0f;
  }

  sample *= AUDIO_GAIN;
  sample = applySoftLimiter(sample);
  sample = constrain(sample, -32768.0f, 32767.0f);
  return static_cast<int16_t>(sample);
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
    pcm16Buffer[i] = processAudioSample(i2sReadBuffer[i] >> INPUT_SHIFT);
  }

  return sampleCount;
}

static void notifyCommand(uint8_t command)
{
  if (!commandTx || !bleConnected) {
    return;
  }

  commandTx->setValue(&command, 1);
  commandTx->notify();
}

static void notifyAudioFrame(const uint8_t *bytes, size_t byteCount)
{
  if (!audioTx || !bleConnected) {
    return;
  }

  for (size_t offset = 0; offset < byteCount && bleConnected; offset += BLE_AUDIO_CHUNK_BYTES) {
    const size_t chunkSize = min(BLE_AUDIO_CHUNK_BYTES, byteCount - offset);
    audioTx->setValue(const_cast<uint8_t *>(bytes + offset), chunkSize);
    audioTx->notify();
    delay(2);
  }
}

static void setupBle()
{
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setPower(ESP_PWR_LVL_P9);
  BLEDevice::setMTU(517);

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);

  audioTx = service->createCharacteristic(
    AUDIO_TX_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  audioTx->addDescriptor(new BLE2902());

  commandTx = service->createCharacteristic(
    COMMAND_TX_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  commandTx->addDescriptor(new BLE2902());

  textRx = service->createCharacteristic(
    TEXT_RX_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  textRx->setCallbacks(new TextRxCallbacks());

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println("BLE advertising started");
  Serial.print("Device name: ");
  Serial.println(DEVICE_NAME);
}

void setup()
{
  Serial.begin(SERIAL_BAUD);
  delay(500);

  Serial.println();
  Serial.println("HearGlasses BLE audio peripheral starting...");

  if (!setupI2s()) {
    Serial.println("I2S setup failed. Check INMP441 wiring.");
    while (true) {
      delay(1000);
    }
  }

  setupAudioFilters();
  setupBle();
}

void loop()
{
  if (!bleConnected) {
    if (wasConnected) {
      wasConnected = false;
      delay(500);
      BLEDevice::startAdvertising();
      Serial.println("BLE advertising restarted");
    }
    delay(20);
    return;
  }

  if (!wasConnected) {
    wasConnected = true;
    notifyCommand(COMMAND_START_SPEECH);
  }

  const size_t sampleCount = readPcm16Frame();
  if (sampleCount == 0) {
    return;
  }

  notifyAudioFrame(
    reinterpret_cast<const uint8_t *>(pcm16Buffer),
    sampleCount * sizeof(int16_t)
  );
}
