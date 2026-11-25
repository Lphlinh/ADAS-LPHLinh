"""
gps_receiver.py: Luồng chạy ngầm nhận tốc độ từ điện thoại Android qua UDP
"""
import socket
import threading
import time
import json
import re

class AndroidGPSReceiver:
    def __init__(self, port=5005, default_speed=50.0):
        self.port = port
        self.current_speed = default_speed
        self.is_connected = False
        self.last_received_time = 0
        self.running = True

        self.thread = threading.Thread(target=self._listen_udp, daemon=True)
        self.thread.start()

    def _listen_udp(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.bind(("", self.port))
            sock.settimeout(1.0)
        except Exception as e:
            print(f"[GPS WARNING] Không thể mở cổng UDP {self.port}: {e}")
            return

        while self.running:
            try:
                data, _ = sock.recvfrom(2048)
                msg = data.decode('utf-8', errors='ignore').strip()
                speed = self._parse_speed(msg)
                if speed is not None:
                    self.current_speed = max(0.0, round(float(speed), 1))
                    self.last_received_time = time.time()
                    self.is_connected = True
            except socket.timeout:
                pass
            except Exception:
                pass

            if time.time() - self.last_received_time > 3.0:
                self.is_connected = False

    def _parse_speed(self, msg):
        try:
            if msg.startswith("$"): # Chuỗi NMEA
                parts = msg.split(",")
                if parts[0] in ["$GPRMC", "$GNRMC"] and len(parts) > 7:
                    if parts[7]: # Knots
                        return float(parts[7]) * 1.852
                elif parts[0] in ["$GPVTG", "$GNVTG"] and len(parts) > 7:
                    if parts[7]: # km/h
                        return float(parts[7])
            elif "{" in msg: # Dạng JSON
                data = json.loads(msg)
                for key in ["speed_kmh", "speed", "spd"]:
                    if key in data:
                        val = float(data[key])
                        return val * 3.6 if val < 50 and "kmh" not in key else val
            else: # Chuỗi số hoặc CSV
                matches = re.findall(r"[-+]?\d*\.\d+|\d+", msg)
                if matches:
                    return float(matches[0])
        except Exception:
            return None
        return None

    def get_speed(self):
        return self.current_speed, self.is_connected

    def set_manual_speed(self, delta):
        self.current_speed = max(0.0, round(self.current_speed + delta, 1))

    def stop(self):
        self.running = False
