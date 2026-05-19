#include <Arduino.h>
#include <U8g2lib.h>

static constexpr int PIN_OLED_SDA = 18;
static constexpr int PIN_OLED_SCL = 10;

static U8G2_SSD1306_128X64_NONAME_F_SW_I2C ssd1306_128x64(
  U8G2_R0,
  PIN_OLED_SCL,
  PIN_OLED_SDA,
  U8X8_PIN_NONE
);

static U8G2_SH1106_128X64_NONAME_F_SW_I2C sh1106_128x64(
  U8G2_R0,
  PIN_OLED_SCL,
  PIN_OLED_SDA,
  U8X8_PIN_NONE
);

static U8G2_SSD1306_128X32_UNIVISION_F_SW_I2C ssd1306_128x32(
  U8G2_R0,
  PIN_OLED_SCL,
  PIN_OLED_SDA,
  U8X8_PIN_NONE
);

static void fillWhite(U8G2 &display)
{
  display.begin();
  display.setContrast(255);
  display.clearBuffer();
  display.setDrawColor(1);
  display.drawBox(0, 0, 128, 64);
  display.sendBuffer();
}

static void drawLabel(U8G2 &display, const char *line1, const char *line2)
{
  display.clearBuffer();
  display.setDrawColor(1);
  display.setFont(u8g2_font_wqy16_t_gb2312);
  display.drawUTF8(0, 24, line1);
  display.drawUTF8(0, 50, line2);
  display.sendBuffer();
}

void setup()
{
  Serial.begin(115200);
  delay(1000);
  Serial.println("OLED fullscreen test on SDA=18 SCL=10");

  fillWhite(ssd1306_128x64);
  Serial.println("Trying SSD1306 128x64 full white");
}

void loop()
{
  delay(3000);
  fillWhite(ssd1306_128x64);
  Serial.println("SSD1306 128x64 full white");

  delay(3000);
  drawLabel(ssd1306_128x64, "SSD1306", "hello 你好");
  Serial.println("SSD1306 128x64 text");

  delay(3000);
  fillWhite(sh1106_128x64);
  Serial.println("SH1106 128x64 full white");

  delay(3000);
  drawLabel(sh1106_128x64, "SH1106", "hello 你好");
  Serial.println("SH1106 128x64 text");

  delay(3000);
  fillWhite(ssd1306_128x32);
  Serial.println("SSD1306 128x32 full white");

  delay(3000);
  drawLabel(ssd1306_128x32, "128x32", "hello");
  Serial.println("SSD1306 128x32 text");
}
