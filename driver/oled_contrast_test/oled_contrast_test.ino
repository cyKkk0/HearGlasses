#include <Arduino.h>
#include <U8g2lib.h>

static constexpr int PIN_OLED_SDA = 18;
static constexpr int PIN_OLED_SCL = 10;

static U8G2_SSD1306_128X64_NONAME_F_SW_I2C display(
  U8G2_R0,
  PIN_OLED_SCL,
  PIN_OLED_SDA,
  U8X8_PIN_NONE
);

void setup()
{
  Serial.begin(115200);
  delay(1000);
  Serial.println("OLED contrast test");

  display.begin();
  display.setI2CAddress(0x3C << 1);
  display.setContrast(255);
}

void loop()
{
  Serial.println("white background, black shapes");
  display.clearBuffer();
  display.setDrawColor(1);
  display.drawBox(0, 0, 128, 64);
  display.setDrawColor(0);
  display.drawBox(0, 0, 128, 12);
  display.drawBox(0, 52, 128, 12);
  display.drawBox(8, 20, 112, 8);
  display.drawFrame(20, 32, 88, 14);
  display.sendBuffer();
  delay(3000);

  Serial.println("black background, ascii text");
  display.clearBuffer();
  display.setDrawColor(1);
  display.setFont(u8g2_font_ncenB14_tr);
  display.drawStr(0, 22, "HELLO");
  display.setFont(u8g2_font_10x20_tr);
  display.drawStr(0, 52, "123 ABC");
  display.sendBuffer();
  delay(3000);

  Serial.println("black background, chinese font");
  display.clearBuffer();
  display.setDrawColor(1);
  display.enableUTF8Print();
  display.setFont(u8g2_font_wqy16_t_gb2312);
  display.drawUTF8(0, 24, "hello");
  display.drawUTF8(0, 50, "你好");
  display.sendBuffer();
  delay(3000);
}
