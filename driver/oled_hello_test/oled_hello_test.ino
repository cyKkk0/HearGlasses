#include <Arduino.h>
#include <U8g2lib.h>
#include <Wire.h>

static constexpr int PIN_OLED_SDA = 18;
static constexpr int PIN_OLED_SCL = 10;

// Common 0.96" 128x64 SSD1306 I2C OLED.
// Use software I2C so the non-default ESP32-S3 pins are explicit.
static U8G2_SSD1306_128X64_NONAME_F_SW_I2C display(
  U8G2_R0,
  PIN_OLED_SCL,
  PIN_OLED_SDA,
  U8X8_PIN_NONE
);

void setup()
{
  Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
  display.begin();
  display.enableUTF8Print();
  display.clearBuffer();
  display.setFont(u8g2_font_wqy16_t_gb2312);
  display.drawUTF8(0, 24, "hello");
  display.drawUTF8(0, 50, "你好");
  display.sendBuffer();
}

void loop()
{
  delay(1000);
}
