#include <Arduino.h>
#include <Wire.h>

static constexpr int PIN_OLED_SDA = 18;
static constexpr int PIN_OLED_SCL = 10;
static constexpr uint8_t OLED_ADDR = 0x3C;
static constexpr int OLED_WIDTH = 128;
static constexpr int OLED_PAGES = 8;

static void writeCommand(uint8_t command)
{
  Wire.beginTransmission(OLED_ADDR);
  Wire.write(0x00);
  Wire.write(command);
  Wire.endTransmission();
}

static void writeDataChunk(const uint8_t *data, size_t length)
{
  Wire.beginTransmission(OLED_ADDR);
  Wire.write(0x40);
  for (size_t i = 0; i < length; ++i) {
    Wire.write(data[i]);
  }
  Wire.endTransmission();
}

static void setPageCursor(uint8_t page, uint8_t column)
{
  writeCommand(0xB0 | page);
  writeCommand(0x00 | (column & 0x0F));
  writeCommand(0x10 | (column >> 4));
}

static void initDisplay()
{
  delay(100);
  writeCommand(0xAE);
  writeCommand(0xD5);
  writeCommand(0x80);
  writeCommand(0xA8);
  writeCommand(0x3F);
  writeCommand(0xD3);
  writeCommand(0x00);
  writeCommand(0x40);
  writeCommand(0x8D);
  writeCommand(0x14);
  writeCommand(0x20);
  writeCommand(0x02);  // page addressing mode: most conservative for SSD1306/SH1106.
  writeCommand(0xA1);
  writeCommand(0xC8);
  writeCommand(0xDA);
  writeCommand(0x12);
  writeCommand(0x81);
  writeCommand(0xFF);
  writeCommand(0xD9);
  writeCommand(0xF1);
  writeCommand(0xDB);
  writeCommand(0x40);
  writeCommand(0xA4);
  writeCommand(0xA6);
  writeCommand(0x2E);
  writeCommand(0xAF);
}

static void fillPageMode(uint8_t pattern, uint8_t columnOffset)
{
  uint8_t chunk[16];
  memset(chunk, pattern, sizeof(chunk));

  for (uint8_t page = 0; page < OLED_PAGES; ++page) {
    setPageCursor(page, columnOffset);
    for (int col = 0; col < OLED_WIDTH; col += sizeof(chunk)) {
      writeDataChunk(chunk, sizeof(chunk));
    }
  }
}

static void stripesPageMode(uint8_t columnOffset)
{
  uint8_t chunk[16];
  for (uint8_t page = 0; page < OLED_PAGES; ++page) {
    setPageCursor(page, columnOffset);
    for (int col = 0; col < OLED_WIDTH; col += sizeof(chunk)) {
      for (int i = 0; i < static_cast<int>(sizeof(chunk)); ++i) {
        const int absoluteCol = col + i;
        const bool vertical = ((absoluteCol / 8) % 2) == 0;
        const bool horizontal = (page % 2) == 0;
        chunk[i] = vertical ^ horizontal ? 0xFF : 0x00;
      }
      writeDataChunk(chunk, sizeof(chunk));
    }
  }
}

static void blockHiPageMode(uint8_t columnOffset)
{
  fillPageMode(0x00, columnOffset);

  uint8_t chunk[8];
  memset(chunk, 0xFF, sizeof(chunk));

  for (uint8_t page = 1; page <= 5; ++page) {
    setPageCursor(page, columnOffset + 8);
    writeDataChunk(chunk, sizeof(chunk));
    setPageCursor(page, columnOffset + 40);
    writeDataChunk(chunk, sizeof(chunk));
    setPageCursor(page, columnOffset + 76);
    writeDataChunk(chunk, sizeof(chunk));
  }

  setPageCursor(3, columnOffset + 8);
  for (int i = 0; i < 5; ++i) {
    writeDataChunk(chunk, sizeof(chunk));
  }

  setPageCursor(1, columnOffset + 68);
  for (int i = 0; i < 3; ++i) {
    writeDataChunk(chunk, sizeof(chunk));
  }

  setPageCursor(5, columnOffset + 68);
  for (int i = 0; i < 3; ++i) {
    writeDataChunk(chunk, sizeof(chunk));
  }
}

void setup()
{
  Serial.begin(115200);
  Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
  Wire.setClock(100000);
  delay(500);

  Serial.println("OLED page address test");
  initDisplay();
}

void loop()
{
  Serial.println("offset 0: full white");
  initDisplay();
  fillPageMode(0xFF, 0);
  delay(2000);

  Serial.println("offset 0: full black");
  fillPageMode(0x00, 0);
  delay(2000);

  Serial.println("offset 0: stripes");
  stripesPageMode(0);
  delay(2000);

  Serial.println("offset 0: block HI");
  blockHiPageMode(0);
  delay(2000);

  Serial.println("offset 2: full white");
  initDisplay();
  fillPageMode(0xFF, 2);
  delay(2000);

  Serial.println("offset 2: full black");
  fillPageMode(0x00, 2);
  delay(2000);

  Serial.println("offset 2: stripes");
  stripesPageMode(2);
  delay(2000);

  Serial.println("offset 2: block HI");
  blockHiPageMode(2);
  delay(2000);
}
