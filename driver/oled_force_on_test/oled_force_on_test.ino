#include <Arduino.h>
#include <Wire.h>

static constexpr int PIN_OLED_SDA = 18;
static constexpr int PIN_OLED_SCL = 10;
static constexpr uint8_t OLED_ADDR = 0x3C;

static void writeCommand(uint8_t command)
{
  Wire.beginTransmission(OLED_ADDR);
  Wire.write(0x00);
  Wire.write(command);
  const uint8_t error = Wire.endTransmission();
  if (error != 0) {
    Serial.printf("command 0x%02X error=%u\n", command, error);
  }
}

static bool probe()
{
  Wire.beginTransmission(OLED_ADDR);
  return Wire.endTransmission() == 0;
}

static void forceOn()
{
  writeCommand(0xAE);  // off while configuring
  writeCommand(0x8D);  // charge pump
  writeCommand(0x14);
  writeCommand(0x81);  // contrast
  writeCommand(0xFF);
  writeCommand(0xA6);  // normal, not inverted
  writeCommand(0xA5);  // entire display ON, ignore RAM
  writeCommand(0xAF);  // display ON
}

void setup()
{
  Serial.begin(115200);
  delay(1000);
  Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
  Wire.setClock(50000);
  delay(100);

  Serial.println("OLED force-on test");
}

void loop()
{
  const bool ok = probe();
  Serial.printf("probe 0x3C: %s\n", ok ? "OK" : "missing");
  if (ok) {
    forceOn();
    Serial.println("sent force-on commands");
  }
  delay(1000);
}
