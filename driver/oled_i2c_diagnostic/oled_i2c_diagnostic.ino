#include <Arduino.h>
#include <U8g2lib.h>
#include <Wire.h>

static constexpr int PIN_OLED_SDA = 18;
static constexpr int PIN_OLED_SCL = 10;
static constexpr int SERIAL_BAUD = 115200;

static U8G2_SSD1306_128X64_NONAME_F_SW_I2C ssd1306(
  U8G2_R0,
  PIN_OLED_SCL,
  PIN_OLED_SDA,
  U8X8_PIN_NONE
);
static U8G2_SH1106_128X64_NONAME_F_SW_I2C sh1106(
  U8G2_R0,
  PIN_OLED_SCL,
  PIN_OLED_SDA,
  U8X8_PIN_NONE
);

static int scanI2c(int sda, int scl)
{
  Wire.end();
  delay(100);
  Wire.begin(sda, scl);
  Wire.setClock(100000);
  delay(100);

  Serial.printf("Scanning I2C: SDA=GPIO%d, SCL=GPIO%d\n", sda, scl);
  int found = 0;
  for (uint8_t address = 1; address < 127; ++address) {
    Wire.beginTransmission(address);
    const uint8_t error = Wire.endTransmission();
    if (error == 0) {
      Serial.printf("  found device at 0x%02X\n", address);
      found += 1;
    }
  }
  if (found == 0) {
    Serial.println("  no I2C devices found");
  }
  return found;
}

static void drawSsd1306(uint8_t address)
{
  ssd1306.setI2CAddress(address << 1);
  ssd1306.begin();
  ssd1306.enableUTF8Print();
  ssd1306.clearBuffer();
  ssd1306.setFont(u8g2_font_wqy16_t_gb2312);
  ssd1306.drawUTF8(0, 24, "hello");
  ssd1306.drawUTF8(0, 50, "你好");
  ssd1306.sendBuffer();
}

static void drawSh1106(uint8_t address)
{
  sh1106.setI2CAddress(address << 1);
  sh1106.begin();
  sh1106.enableUTF8Print();
  sh1106.clearBuffer();
  sh1106.setFont(u8g2_font_wqy16_t_gb2312);
  sh1106.drawUTF8(0, 24, "SH1106");
  sh1106.drawUTF8(0, 50, "你好");
  sh1106.sendBuffer();
}

void setup()
{
  Serial.begin(SERIAL_BAUD);
  delay(1000);
  Serial.println();
  Serial.println("HearGlasses OLED I2C diagnostic");

  const int normalFound = scanI2c(PIN_OLED_SDA, PIN_OLED_SCL);
  const int swappedFound = scanI2c(PIN_OLED_SCL, PIN_OLED_SDA);

  Wire.end();
  delay(100);
  Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
  Wire.setClock(100000);

  if (normalFound > 0) {
    Serial.println("Trying SSD1306 at 0x3C");
    drawSsd1306(0x3C);
  } else if (swappedFound > 0) {
    Serial.println("Device only responded with swapped pins. Swap SDA/SCL wiring.");
  } else {
    Serial.println("No display detected. Check VCC/GND/SDA/SCL wiring and display power.");
  }
}

void loop()
{
  static bool useSh1106 = false;
  delay(3000);

  if (useSh1106) {
    Serial.println("Trying SSD1306 at 0x3C");
    drawSsd1306(0x3C);
  } else {
    Serial.println("Trying SH1106 at 0x3C");
    drawSh1106(0x3C);
  }
  useSh1106 = !useSh1106;
}
