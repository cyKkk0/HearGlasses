/*
  ESP32-S3 + INMP441 BLE audio peripheral

  Wiring:
    INMP441 VDD -> ESP32 3.3V
    INMP441 GND -> ESP32 GND
    INMP441 SD  -> ESP32 GPIO13
    INMP441 SCK -> ESP32 GPIO2
    INMP441 WS  -> ESP32 GPIO15
    INMP441 L/R -> ESP32 GND  (left channel)

    I2C OLED VCC -> ESP32 3.3V
    I2C OLED GND -> ESP32 GND
    I2C OLED SCL -> ESP32 GPIO10
    I2C OLED SDA -> ESP32 GPIO18

  BLE:
    Device name: HearGlasses
    Service:     000018fd-0000-1000-8000-00805f9b34fb
    Audio_TX:    00002a3d-0000-1000-8000-00805f9b34fb  notify, Opus frames
    Command_TX:  00002a3e-0000-1000-8000-00805f9b34fb  notify
    Text_RX:     00002a3f-0000-1000-8000-00805f9b34fb  write

  Audio payload:
    16000 Hz / mono / Opus, 40 ms frames.
*/

#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <math.h>
#include <opus.h>
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
static constexpr int FRAME_SAMPLES = 640;  // 40 ms at 16 kHz
static constexpr int OPUS_BITRATE = 16000;
static constexpr int OPUS_COMPLEXITY = 0;
static constexpr size_t OPUS_MAX_PACKET_BYTES = 256;
static constexpr uint8_t AUDIO_FRAME_QUEUE_LENGTH = 6;
static constexpr uint8_t OPUS_PACKET_QUEUE_LENGTH = 8;
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
static constexpr int PIN_OLED_SDA = 18;
static constexpr int PIN_OLED_SCL = 10;

static constexpr int DISPLAY_WIDTH = 128;
static constexpr int DISPLAY_HEIGHT = 64;
static constexpr int DISPLAY_YELLOW_HEIGHT = 16;
static constexpr int DISPLAY_BLUE_Y = DISPLAY_YELLOW_HEIGHT;
static constexpr int DISPLAY_BLUE_HEIGHT = DISPLAY_HEIGHT - DISPLAY_BLUE_Y;
static constexpr int DISPLAY_LINE_HEIGHT = 7;
static constexpr int DISPLAY_BASELINE = 7;
static constexpr int DISPLAY_BLUE_MAX_LINES = DISPLAY_BLUE_HEIGHT / DISPLAY_LINE_HEIGHT;
static constexpr int MAX_BODY_TEXT_BYTES = 1200;

// Keep notifications below the peer MTU and add a small pacing gap. BLE
// notifications are fire-and-forget, so sending faster than the connection can
// drain will silently drop middle chunks on some phones.
static constexpr size_t BLE_AUDIO_MIN_CHUNK_BYTES = 20;
static constexpr size_t BLE_AUDIO_MAX_CHUNK_BYTES = 509;
static constexpr uint32_t BLE_AUDIO_NOTIFY_GAP_MS = 1;
static constexpr size_t BLE_AUDIO_HEADER_BYTES = 4;
static constexpr uint8_t BLE_AUDIO_MAGIC_0 = 'H';
static constexpr uint8_t BLE_AUDIO_MAGIC_1 = 'G';
static constexpr uint32_t DISPLAY_RENDER_INTERVAL_MS = 1000;

static constexpr i2s_port_t I2S_PORT = I2S_NUM_0;

static BLECharacteristic *audioTx = nullptr;
static BLECharacteristic *commandTx = nullptr;
static BLECharacteristic *textRx = nullptr;
static BLEServer *bleServer = nullptr;

static volatile bool bleConnected = false;
static volatile bool wasConnected = false;
static volatile bool startCommandSent = false;
static volatile uint16_t blePeerMtu = 23;
static uint16_t audioPacketSequence = 0;
static volatile uint32_t capturedFrameCount = 0;
static volatile uint32_t encodedPacketCount = 0;
static volatile uint32_t sentPacketCount = 0;
static uint32_t audioFrameDrops = 0;
static uint32_t opusPacketDrops = 0;
static uint32_t lastStatsMillis = 0;
static uint32_t lastForcedDisplayMillis = 0;
static SemaphoreHandle_t displayRenderMutex = nullptr;
static QueueHandle_t audioFrameQueue = nullptr;
static QueueHandle_t opusPacketQueue = nullptr;

static int32_t i2sReadBuffer[FRAME_SAMPLES];
static uint8_t bleAudioPacketBuffer[BLE_AUDIO_MAX_CHUNK_BYTES];
static float highPassLastInput = 0.0f;
static float highPassLastOutput = 0.0f;
static float lowPassLastOutput = 0.0f;
static float highPassAlpha = 0.0f;
static float lowPassAlpha = 0.0f;
static String statusText = "电量 --%  --:--";
static String bodyText;
static String activeBodyText;
static volatile bool displayDirty = false;
static uint32_t lastDisplayRenderMillis = 0;

struct AudioFrame {
  uint16_t sampleCount;
  int16_t samples[FRAME_SAMPLES];
};

struct OpusPacket {
  uint16_t byteCount;
  uint8_t bytes[OPUS_MAX_PACKET_BYTES];
};

// Common 0.96" 128x64 SSD1306 I2C OLED on address 0x3C. Use ESP32 hardware
// I2C here: software bit-banged I2C is more vulnerable to timing jitter once
// BLE/audio FreeRTOS tasks are running.
static U8G2_SSD1306_128X64_NONAME_F_HW_I2C display(
  U8G2_R0,
  U8X8_PIN_NONE,
  PIN_OLED_SCL,
  PIN_OLED_SDA
);

static uint8_t utf8CharLength(char firstByte)
{
  const uint8_t byte = static_cast<uint8_t>(firstByte);
  if ((byte & 0x80) == 0x00) {
    return 1;
  }
  if ((byte & 0xE0) == 0xC0) {
    return 2;
  }
  if ((byte & 0xF0) == 0xE0) {
    return 3;
  }
  if ((byte & 0xF8) == 0xF0) {
    return 4;
  }
  return 1;
}

static int boundedUtf8EndIndex(const String &text, int startIndex, uint8_t charLength)
{
  const int requestedEnd = startIndex + static_cast<int>(charLength);
  const int textLength = static_cast<int>(text.length());
  return requestedEnd < textLength ? requestedEnd : textLength;
}

static String ellipsizeToWidth(const String &line, int maxWidth)
{
  if (display.getUTF8Width(line.c_str()) <= maxWidth) {
    return line;
  }

  String output;
  for (int index = 0; index < line.length();) {
    const uint8_t charLength = utf8CharLength(line[index]);
    const int endIndex = boundedUtf8EndIndex(line, index, charLength);
    String candidate = output + line.substring(index, endIndex) + "...";
    if (display.getUTF8Width(candidate.c_str()) > maxWidth) {
      return output + "...";
    }
    output = output + line.substring(index, endIndex);
    index += charLength;
  }
  return output;
}

static void drawWrappedTextInRegion(const String &text, int top, int height)
{
  const int maxLines = height / DISPLAY_LINE_HEIGHT;
  if (maxLines <= 0) {
    return;
  }

  if (text.length() == 0) {
    display.drawUTF8(0, top + DISPLAY_BASELINE, "等待转录...");
    return;
  }

  static constexpr int WRAPPED_LINE_CAPACITY = 32;
  String lines[WRAPPED_LINE_CAPACITY];
  int lineCount = 0;
  String currentLine;

  for (int index = 0; index < text.length();) {
    if (text[index] == '\n') {
      if (lineCount >= WRAPPED_LINE_CAPACITY) {
        for (int i = 1; i < WRAPPED_LINE_CAPACITY; ++i) {
          lines[i - 1] = lines[i];
        }
        lineCount = WRAPPED_LINE_CAPACITY - 1;
      }
      lines[lineCount++] = currentLine;
      currentLine = "";
      index += 1;
      continue;
    }

    const uint8_t charLength = utf8CharLength(text[index]);
    const int endIndex = boundedUtf8EndIndex(text, index, charLength);
    const String nextChar = text.substring(index, endIndex);
    const String candidate = currentLine + nextChar;

    if (currentLine.length() > 0 && display.getUTF8Width(candidate.c_str()) > DISPLAY_WIDTH) {
      if (lineCount >= WRAPPED_LINE_CAPACITY) {
        for (int i = 1; i < WRAPPED_LINE_CAPACITY; ++i) {
          lines[i - 1] = lines[i];
        }
        lineCount = WRAPPED_LINE_CAPACITY - 1;
      }
      lines[lineCount++] = currentLine;
      currentLine = "";
    } else {
      currentLine = candidate;
      index += charLength;
    }
  }

  if (currentLine.length() > 0) {
    if (lineCount >= WRAPPED_LINE_CAPACITY) {
      for (int i = 1; i < WRAPPED_LINE_CAPACITY; ++i) {
        lines[i - 1] = lines[i];
      }
      lineCount = WRAPPED_LINE_CAPACITY - 1;
    }
    lines[lineCount++] = currentLine;
  }

  const int visibleLines = min(lineCount, maxLines);
  const int firstVisibleLine = max(0, lineCount - visibleLines);
  for (int line = 0; line < visibleLines; ++line) {
    const String visibleText = ellipsizeToWidth(lines[firstVisibleLine + line], DISPLAY_WIDTH);
    display.drawUTF8(0, top + DISPLAY_BASELINE + line * DISPLAY_LINE_HEIGHT, visibleText.c_str());
  }
}

static void requestDisplayRender()
{
  displayDirty = true;
}

static void renderDisplayNow()
{
  display.clearBuffer();
  display.setDrawColor(1);
  display.setFont(u8g2_font_boutique_bitmap_7x7_t_gb2312);

  drawWrappedTextInRegion(statusText, 0, DISPLAY_YELLOW_HEIGHT);
  if (bodyText.length() == 0) {
    drawWrappedTextInRegion("等待转录...", DISPLAY_BLUE_Y, DISPLAY_BLUE_HEIGHT);
  } else {
    drawWrappedTextInRegion(bodyText, DISPLAY_BLUE_Y, DISPLAY_BLUE_HEIGHT);
  }
  display.sendBuffer();
  displayDirty = false;
  lastDisplayRenderMillis = millis();
}

static void serviceDisplayRender(bool force)
{
  const uint32_t now = millis();
  if (!force && now - lastForcedDisplayMillis >= 2000) {
    force = true;
    lastForcedDisplayMillis = now;
  }

  if (!force && !displayDirty) {
    return;
  }

  if (!force && bleConnected && now - lastDisplayRenderMillis < DISPLAY_RENDER_INTERVAL_MS) {
    return;
  }
  if (displayRenderMutex && xSemaphoreTake(displayRenderMutex, pdMS_TO_TICKS(100)) != pdTRUE) {
    return;
  }
  renderDisplayNow();
  if (displayRenderMutex) {
    xSemaphoreGive(displayRenderMutex);
  }
}

static void resetAudioQueues()
{
  if (audioFrameQueue) {
    xQueueReset(audioFrameQueue);
  }
  if (opusPacketQueue) {
    xQueueReset(opusPacketQueue);
  }
}

static void updateStatusText(const String &text)
{
  statusText = text.length() == 0 ? "电量 --%  --:--" : text;
  requestDisplayRender();
}

static void trimBodyText()
{
  while (bodyText.length() > MAX_BODY_TEXT_BYTES) {
    const uint8_t charLength = utf8CharLength(bodyText[0]);
    const int endIndex = boundedUtf8EndIndex(bodyText, 0, charLength);
    bodyText.remove(0, endIndex);
  }
}

static void appendBodyText(const String &text)
{
  if (text.length() == 0) {
    return;
  }

  bodyText += text;
  trimBodyText();
  requestDisplayRender();
}

static void upsertActiveBodyText(const String &text)
{
  if (text.length() == 0) {
    return;
  }

  if (activeBodyText.length() > 0) {
    const int activeStart = bodyText.lastIndexOf(activeBodyText);
    if (activeStart >= 0) {
      bodyText.remove(activeStart, activeBodyText.length());
      bodyText = bodyText.substring(0, activeStart);
    }
  }

  if (bodyText.length() > 0 && !bodyText.endsWith("\n")) {
    bodyText += "\n";
  }
  activeBodyText = text;
  bodyText += activeBodyText;
  trimBodyText();
  requestDisplayRender();
}

static void commitActiveBodyText()
{
  activeBodyText = "";
}

static void appendFinalBodyText(const String &text)
{
  if (text.length() == 0) {
    return;
  }

  upsertActiveBodyText(text);
  commitActiveBodyText();
}

static void resetBodyText(const String &text)
{
  bodyText = text;
  activeBodyText = "";
  trimBodyText();
  requestDisplayRender();
}

static void updateBodyText(const String &text)
{
  appendBodyText(text);
}

static void setupDisplay()
{
  displayRenderMutex = xSemaphoreCreateMutex();
  Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
  display.begin();
  display.setI2CAddress(0x3C << 1);
  display.setPowerSave(0);
  display.setContrast(255);
  display.enableUTF8Print();
  resetBodyText("HearGlasses\n等待连接...");
  serviceDisplayRender(true);
  Serial.println("OLED initialized on I2C 0x3C");
}

static void handleBleConnected()
{
  if (bleConnected) {
    return;
  }
  bleConnected = true;
  blePeerMtu = 23;
  startCommandSent = false;
  audioPacketSequence = 0;
  resetAudioQueues();
  Serial.println("BLE connected");
  resetBodyText("已连接\n等待转录...");
}

static void handleBleDisconnected()
{
  if (!bleConnected) {
    return;
  }
  bleConnected = false;
  blePeerMtu = 23;
  startCommandSent = false;
  audioPacketSequence = 0;
  resetAudioQueues();
  Serial.println("BLE disconnected");
  resetBodyText("连接已断开\n等待手机连接...");
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override
  {
    handleBleConnected();
  }

  void onDisconnect(BLEServer *server) override
  {
    handleBleDisconnected();
  }

#if defined(CONFIG_BLUEDROID_ENABLED)
  void onConnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override
  {
    handleBleConnected();
    server->requestConnParams(param->connect.remote_bda, 6, 12, 0, 400);
  }

  void onDisconnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override
  {
    handleBleDisconnected();
  }

  void onMtuChanged(BLEServer *server, esp_ble_gatts_cb_param_t *param) override
  {
    blePeerMtu = param->mtu.mtu;
    Serial.printf("BLE MTU changed: %u\n", blePeerMtu);
  }
#endif

#if defined(CONFIG_NIMBLE_ENABLED)
  void onConnect(BLEServer *server, ble_gap_conn_desc *desc) override
  {
    handleBleConnected();
    server->requestConnParams(desc->conn_handle, 6, 12, 0, 400);
  }

  void onDisconnect(BLEServer *server, ble_gap_conn_desc *desc) override
  {
    handleBleDisconnected();
  }

  void onMtuChanged(BLEServer *server, ble_gap_conn_desc *desc, uint16_t mtu) override
  {
    blePeerMtu = mtu;
    Serial.printf("BLE MTU changed: %u\n", blePeerMtu);
  }
#endif
};

class TextRxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override
  {
    String value = characteristic->getValue();
    Serial.printf("Text_RX bytes=%u\n", static_cast<unsigned>(value.length()));
    if (value.startsWith("@STATUS ")) {
      updateStatusText(value.substring(8));
    } else if (value.startsWith("@TEXTF ")) {
      appendFinalBodyText(value.substring(7));
    } else if (value.startsWith("@TEXTA ")) {
      appendBodyText(value.substring(7));
    } else if (value.startsWith("@TEXT ")) {
      upsertActiveBodyText(value.substring(6));
    } else if (value == "@COMMIT") {
      commitActiveBodyText();
    } else if (value == "@CLEAR") {
      resetBodyText("");
    } else {
      updateBodyText(value);
    }
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

static size_t readPcm16Frame(int16_t *output, size_t outputCapacity)
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

  const size_t sampleCount = min(bytesRead / sizeof(int32_t), outputCapacity);
  for (size_t i = 0; i < sampleCount; ++i) {
    output[i] = processAudioSample(i2sReadBuffer[i] >> INPUT_SHIFT);
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

static bool notificationsEnabled(BLECharacteristic *characteristic)
{
  if (!characteristic) {
    return false;
  }

  BLE2902 *descriptor = static_cast<BLE2902 *>(characteristic->getDescriptorByUUID(BLEUUID((uint16_t)0x2902)));
  return descriptor && descriptor->getNotifications();
}

static size_t currentAudioChunkBytes()
{
  uint16_t mtu = blePeerMtu;
  if (bleServer && bleServer->getConnId() != 0xffff) {
    const uint16_t peerMtu = bleServer->getPeerMTU(bleServer->getConnId());
    if (peerMtu > 0) {
      mtu = peerMtu;
    }
  }

  const size_t payloadBytes = mtu > 3 ? static_cast<size_t>(mtu - 3) : BLE_AUDIO_MIN_CHUNK_BYTES;
  return min(BLE_AUDIO_MAX_CHUNK_BYTES, max(BLE_AUDIO_MIN_CHUNK_BYTES, payloadBytes));
}

static bool notifyOpusPacket(const uint8_t *bytes, size_t byteCount)
{
  if (!audioTx || !bleConnected) {
    return false;
  }

  const size_t chunkBytes = currentAudioChunkBytes();
  if (byteCount == 0 || byteCount + BLE_AUDIO_HEADER_BYTES > chunkBytes || byteCount > OPUS_MAX_PACKET_BYTES) {
    return false;
  }

  bleAudioPacketBuffer[0] = BLE_AUDIO_MAGIC_0;
  bleAudioPacketBuffer[1] = BLE_AUDIO_MAGIC_1;
  bleAudioPacketBuffer[2] = static_cast<uint8_t>(audioPacketSequence & 0xFF);
  bleAudioPacketBuffer[3] = static_cast<uint8_t>((audioPacketSequence >> 8) & 0xFF);
  memcpy(bleAudioPacketBuffer + BLE_AUDIO_HEADER_BYTES, bytes, byteCount);
  audioPacketSequence++;

  audioTx->setValue(bleAudioPacketBuffer, byteCount + BLE_AUDIO_HEADER_BYTES);
  audioTx->notify();
  return true;
}

static bool canSendOpusPacket(size_t byteCount)
{
  return audioTx &&
    bleConnected &&
    byteCount > 0 &&
    byteCount <= OPUS_MAX_PACKET_BYTES &&
    byteCount + BLE_AUDIO_HEADER_BYTES <= currentAudioChunkBytes();
}

static void setupBle()
{
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setPower(ESP_PWR_LVL_P9);
  BLEDevice::setMTU(517);

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService *service = bleServer->createService(SERVICE_UUID);

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

static void sendOrDropAudioFrame(const AudioFrame &frame)
{
  if (audioFrameQueue == nullptr) {
    return;
  }

  if (xQueueSend(audioFrameQueue, &frame, 0) == pdPASS) {
    return;
  }

  AudioFrame dropped;
  xQueueReceive(audioFrameQueue, &dropped, 0);
  audioFrameDrops++;
  xQueueSend(audioFrameQueue, &frame, 0);
}

static void sendOrDropOpusPacket(const OpusPacket &packet)
{
  if (opusPacketQueue == nullptr) {
    return;
  }

  if (xQueueSend(opusPacketQueue, &packet, 0) == pdPASS) {
    return;
  }

  OpusPacket dropped;
  xQueueReceive(opusPacketQueue, &dropped, 0);
  opusPacketDrops++;
  xQueueSend(opusPacketQueue, &packet, 0);
}

static void audioCaptureTask(void *param)
{
  Serial.println("audio_capture task started");
  AudioFrame frame;
  while (true) {
    const size_t sampleCount = readPcm16Frame(frame.samples, FRAME_SAMPLES);
    if (sampleCount == 0) {
      continue;
    }
    frame.sampleCount = static_cast<uint16_t>(sampleCount);
    capturedFrameCount++;
    sendOrDropAudioFrame(frame);
  }
}

static void opusEncodeTask(void *param)
{
  Serial.println("opus_encode task starting");
  int encoderError = OPUS_OK;
  OpusEncoder *encoder = opus_encoder_create(SAMPLE_RATE, 1, OPUS_APPLICATION_VOIP, &encoderError);
  if (encoderError != OPUS_OK || encoder == nullptr) {
    Serial.printf("Opus encoder create failed: %d\n", encoderError);
    vTaskDelete(nullptr);
    return;
  }

  opus_encoder_ctl(encoder, OPUS_SET_BITRATE(OPUS_BITRATE));
  opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(OPUS_COMPLEXITY));
  opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
  opus_encoder_ctl(encoder, OPUS_SET_VBR(0));
  Serial.println("opus_encode task started");

  AudioFrame frame;
  OpusPacket packet;
  while (true) {
    if (xQueueReceive(audioFrameQueue, &frame, portMAX_DELAY) != pdPASS) {
      continue;
    }
    const int encodedBytes = opus_encode(
      encoder,
      frame.samples,
      frame.sampleCount,
      packet.bytes,
      OPUS_MAX_PACKET_BYTES
    );
    if (encodedBytes <= 0) {
      Serial.printf("Opus encode failed: %d\n", encodedBytes);
      continue;
    }
    packet.byteCount = static_cast<uint16_t>(encodedBytes);
    encodedPacketCount++;
    sendOrDropOpusPacket(packet);
  }
}

static void bleAudioTxTask(void *param)
{
  Serial.println("ble_audio_tx task started");
  OpusPacket packet;
  while (true) {
    if (!bleConnected) {
      vTaskDelay(pdMS_TO_TICKS(10));
      continue;
    }

    if (!startCommandSent && commandTx) {
      startCommandSent = true;
      notifyCommand(COMMAND_START_SPEECH);
    }

    if (xQueueReceive(opusPacketQueue, &packet, pdMS_TO_TICKS(20)) != pdPASS) {
      continue;
    }

    if (!canSendOpusPacket(packet.byteCount)) {
      Serial.printf(
        "Opus wait/drop: bytes=%u mtuPayload=%u connected=%u notified=%u\n",
        packet.byteCount,
        static_cast<unsigned>(currentAudioChunkBytes()),
        bleConnected ? 1 : 0,
        notificationsEnabled(audioTx) ? 1 : 0
      );
      sendOrDropOpusPacket(packet);
      vTaskDelay(pdMS_TO_TICKS(10));
      continue;
    }

    if (!notifyOpusPacket(packet.bytes, packet.byteCount)) {
      opusPacketDrops++;
      vTaskDelay(pdMS_TO_TICKS(2));
    } else {
      sentPacketCount++;
    }
  }
}

static void displayTask(void *param)
{
  Serial.println("display task started");
  while (true) {
    serviceDisplayRender(false);
    vTaskDelay(pdMS_TO_TICKS(100));
  }
}

static void setupAudioTasks()
{
  audioFrameQueue = xQueueCreate(AUDIO_FRAME_QUEUE_LENGTH, sizeof(AudioFrame));
  opusPacketQueue = xQueueCreate(OPUS_PACKET_QUEUE_LENGTH, sizeof(OpusPacket));
  if (!audioFrameQueue || !opusPacketQueue) {
    Serial.println("Audio queues failed");
    resetBodyText("音频队列初始化失败");
    serviceDisplayRender(true);
    while (true) {
      delay(1000);
    }
  }

  xTaskCreatePinnedToCore(audioCaptureTask, "audio_capture", 8192, nullptr, 4, nullptr, 1);
  xTaskCreatePinnedToCore(opusEncodeTask, "opus_encode", 32768, nullptr, 3, nullptr, 1);
  xTaskCreatePinnedToCore(bleAudioTxTask, "ble_audio_tx", 8192, nullptr, 2, nullptr, 0);
  xTaskCreatePinnedToCore(displayTask, "display", 8192, nullptr, 1, nullptr, 0);
}

static void printAudioStats()
{
  const uint32_t now = millis();
  if (now - lastStatsMillis < 3000) {
    return;
  }
  lastStatsMillis = now;
  Serial.printf(
    "audio stats: cap=%lu enc=%lu sent=%lu frameDrop=%lu opusDrop=%lu aq=%u oq=%u mtu=%u audioSub=%u\n",
    capturedFrameCount,
    encodedPacketCount,
    sentPacketCount,
    audioFrameDrops,
    opusPacketDrops,
    audioFrameQueue ? static_cast<unsigned>(uxQueueMessagesWaiting(audioFrameQueue)) : 0,
    opusPacketQueue ? static_cast<unsigned>(uxQueueMessagesWaiting(opusPacketQueue)) : 0,
    static_cast<unsigned>(currentAudioChunkBytes()),
    notificationsEnabled(audioTx) ? 1 : 0
  );
}

void setup()
{
  Serial.begin(SERIAL_BAUD);
  delay(500);

  Serial.println();
  Serial.println("HearGlasses BLE audio peripheral starting...");

  setupDisplay();

  if (!setupI2s()) {
    Serial.println("I2S setup failed. Check INMP441 wiring.");
    resetBodyText("I2S 初始化失败\n请检查麦克风接线");
    serviceDisplayRender(true);
    while (true) {
      delay(1000);
    }
  }

  setupAudioFilters();
  setupBle();
  setupAudioTasks();
}

void loop()
{
  printAudioStats();

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
  }

  delay(20);
}
