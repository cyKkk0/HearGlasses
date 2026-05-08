/*
  ESP32-S3 Wi-Fi AP phone connection demo

  Upload this sketch, then connect your phone to:
    Wi-Fi SSID: HearGlasses-ESP32
    Password:  12345678

  Open this address in the phone browser:
    http://192.168.4.1
*/

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>

static constexpr const char *AP_SSID = "HearGlasses-ESP32";
static constexpr const char *AP_PASSWORD = "12345678";

static constexpr int SERIAL_BAUD = 115200;
static constexpr int AP_CHANNEL = 6;
static constexpr int AP_MAX_CONNECTIONS = 4;

WebServer server(80);

static String htmlPage()
{
  const int connectedDevices = WiFi.softAPgetStationNum();
  const IPAddress ip = WiFi.softAPIP();

  String html;
  html.reserve(1800);
  html += "<!doctype html><html lang='zh-CN'><head>";
  html += "<meta charset='utf-8'>";
  html += "<meta name='viewport' content='width=device-width, initial-scale=1'>";
  html += "<title>HearGlasses ESP32-S3</title>";
  html += "<style>";
  html += "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;margin:0;padding:24px;background:#f5f7fa;color:#18202a;}";
  html += "main{max-width:640px;margin:0 auto;}";
  html += "h1{font-size:26px;margin:0 0 12px;}";
  html += ".panel{background:white;border:1px solid #dfe5ec;border-radius:8px;padding:18px;margin:14px 0;}";
  html += ".row{display:flex;justify-content:space-between;gap:12px;padding:8px 0;border-bottom:1px solid #eef2f5;}";
  html += ".row:last-child{border-bottom:0;}";
  html += ".label{color:#647283;}";
  html += "button{font-size:16px;padding:10px 14px;border:0;border-radius:6px;background:#1565c0;color:white;}";
  html += "pre{white-space:pre-wrap;background:#111827;color:#d1fae5;padding:12px;border-radius:6px;}";
  html += "</style></head><body><main>";
  html += "<h1>HearGlasses ESP32-S3</h1>";
  html += "<div class='panel'>";
  html += "<div class='row'><span class='label'>Wi-Fi</span><strong>";
  html += AP_SSID;
  html += "</strong></div>";
  html += "<div class='row'><span class='label'>ESP32 IP</span><strong>";
  html += ip.toString();
  html += "</strong></div>";
  html += "<div class='row'><span class='label'>Connected phones</span><strong>";
  html += connectedDevices;
  html += "</strong></div>";
  html += "<div class='row'><span class='label'>Uptime</span><strong>";
  html += millis() / 1000;
  html += " s</strong></div>";
  html += "</div>";
  html += "<div class='panel'>";
  html += "<button onclick='checkStatus()'>刷新状态</button>";
  html += "<pre id='status'>点击按钮读取 /status</pre>";
  html += "</div>";
  html += "<script>";
  html += "async function checkStatus(){";
  html += "const r=await fetch('/status');";
  html += "document.getElementById('status').textContent=JSON.stringify(await r.json(),null,2);";
  html += "}";
  html += "checkStatus();";
  html += "</script>";
  html += "</main></body></html>";
  return html;
}

static void handleRoot()
{
  server.send(200, "text/html; charset=utf-8", htmlPage());
}

static void handleStatus()
{
  String json;
  json.reserve(256);
  json += "{";
  json += "\"ssid\":\"";
  json += AP_SSID;
  json += "\",";
  json += "\"ip\":\"";
  json += WiFi.softAPIP().toString();
  json += "\",";
  json += "\"connected_devices\":";
  json += WiFi.softAPgetStationNum();
  json += ",";
  json += "\"uptime_ms\":";
  json += millis();
  json += ",";
  json += "\"free_heap\":";
  json += ESP.getFreeHeap();
  json += "}";

  server.send(200, "application/json; charset=utf-8", json);
}

static void handleNotFound()
{
  server.send(404, "text/plain; charset=utf-8", "Not found");
}

static void setupWifiAp()
{
  WiFi.mode(WIFI_AP);
  WiFi.setSleep(false);

  const bool ok = WiFi.softAP(
    AP_SSID,
    AP_PASSWORD,
    AP_CHANNEL,
    false,
    AP_MAX_CONNECTIONS
  );

  if (!ok) {
    Serial.println("Wi-Fi AP start failed");
    while (true) {
      delay(1000);
    }
  }

  Serial.println("Wi-Fi AP started");
  Serial.print("SSID: ");
  Serial.println(AP_SSID);
  Serial.print("Password: ");
  Serial.println(AP_PASSWORD);
  Serial.print("AP IP: ");
  Serial.println(WiFi.softAPIP());
}

static void setupHttpServer()
{
  server.on("/", HTTP_GET, handleRoot);
  server.on("/status", HTTP_GET, handleStatus);
  server.on("/ping", HTTP_GET, []() {
    server.send(200, "text/plain; charset=utf-8", "pong");
  });
  server.onNotFound(handleNotFound);
  server.begin();
  Serial.println("HTTP server started on port 80");
}

void setup()
{
  Serial.begin(SERIAL_BAUD);
  delay(500);

  Serial.println();
  Serial.println("HearGlasses ESP32-S3 Wi-Fi AP demo");

  setupWifiAp();
  setupHttpServer();
}

void loop()
{
  server.handleClient();
}
