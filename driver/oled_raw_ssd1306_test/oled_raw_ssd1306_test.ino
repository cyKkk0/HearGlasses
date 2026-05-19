#include <Arduino.h>
#include <Wire.h>

static constexpr int PIN_OLED_SDA = 18;
static constexpr int PIN_OLED_SCL = 10;
static constexpr uint8_t OLED_ADDR = 0x3C;
static constexpr int OLED_WIDTH = 128;
static constexpr int OLED_HEIGHT = 64;
static constexpr int OLED_PAGES = OLED_HEIGHT / 8;

static void writeCommand(uint8_t command)
{
  Wire.beginTransmission(OLED_ADDR);
  Wire.write(0x00);
  Wire.write(command);
  Wire.endTransmission();
}

static void writeDataByte(uint8_t data)
{
  Wire.beginTransmission(OLED_ADDR);
  Wire.write(0x40);
  Wire.write(data);
  Wire.endTransmission();
}

static void setCursor(uint8_t page, uint8_t column)
{
  writeCommand(0xB0 | page);
  writeCommand(0x00 | (column & 0x0F));
  writeCommand(0x10 | (column >> 4));
}

static void initDisplay()
{
  delay(100);
  writeCommand(0xAE);  // display off
  writeCommand(0xD5);  // clock
  writeCommand(0x80);
  writeCommand(0xA8);  // multiplex
  writeCommand(0x3F);
  writeCommand(0xD3);  // display offset
  writeCommand(0x00);
  writeCommand(0x40);  // start line
  writeCommand(0x8D);  // charge pump
  writeCommand(0x14);
  writeCommand(0x20);  // memory mode
  writeCommand(0x00);  // horizontal addressing
  writeCommand(0xA1);  // segment remap
  writeCommand(0xC8);  // COM scan direction
  writeCommand(0xDA);  // COM pins
  writeCommand(0x12);
  writeCommand(0x81);  // contrast
  writeCommand(0xFF);
  writeCommand(0xD9);  // pre-charge
  writeCommand(0xF1);
  writeCommand(0xDB);  // VCOM detect
  writeCommand(0x40);
  writeCommand(0xA4);  // resume RAM content display
  writeCommand(0xA6);  // normal display
  writeCommand(0x2E);  // deactivate scroll
  writeCommand(0xAF);  // display on
}

static void fillDisplay(uint8_t pattern)
{
  writeCommand(0x21);  // column address
  writeCommand(0);
  writeCommand(127);
  writeCommand(0x22);  // page address
  writeCommand(0);
  writeCommand(7);

  for (int page = 0; page < OLED_PAGES; ++page) {
    for (int col = 0; col < OLED_WIDTH; ++col) {
      writeDataByte(pattern);
    }
  }
}

static void drawStripePattern()
{
  writeCommand(0x21);
  writeCommand(0);
  writeCommand(127);
  writeCommand(0x22);
  writeCommand(0);
  writeCommand(7);

  for (int page = 0; page < OLED_PAGES; ++page) {
    for (int col = 0; col < OLED_WIDTH; ++col) {
      const bool vertical = ((col / 8) % 2) == 0;
      const bool horizontal = (page % 2) == 0;
      writeDataByte(vertical ^ horizontal ? 0xFF : 0x00);
    }
  }
}

static void drawBlockLetters()
{
  fillDisplay(0x00);

  // Big block "HI" using rectangles, no font library involved.
  for (int page = 1; page <= 5; ++page) {
    setCursor(page, 8);
    for (int col = 0; col < 8; ++col) writeDataByte(0xFF);
    setCursor(page, 40);
    for (int col = 0; col < 8; ++col) writeDataByte(0xFF);
    setCursor(page, 76);
    for (int col = 0; col < 8; ++col) writeDataByte(0xFF);
  }

  setCursor(3, 8);
  for (int col = 0; col < 40; ++col) writeDataByte(0xFF);
  setCursor(1, 68);
  for (int col = 0; col < 24; ++col) writeDataByte(0xFF);
  setCursor(5, 68);
  for (int col = 0; col < 24; ++col) writeDataByte(0xFF);
}

void setup()
{
  Serial.begin(115200);
  Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
  Wire.setClock(100000);
  delay(500);

  Serial.println("Raw SSD1306 OLED test");
  initDisplay();
  fillDisplay(0xFF);
}

void loop()
{
  Serial.println("white");
  initDisplay();
  fillDisplay(0xFF);
  delay(2000);

  Serial.println("black");
  fillDisplay(0x00);
  delay(2000);

  Serial.println("stripes");
  drawStripePattern();
  delay(2000);

  Serial.println("HI blocks");
  drawBlockLetters();
  delay(2000);
}
