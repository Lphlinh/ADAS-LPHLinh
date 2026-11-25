"""
distance_estimator.py: Đo khoảng cách Pinhole, tính TTC và phân cấp cảnh báo Luật VN
"""
import time
from collections import deque
import numpy as np
from config import REAL_WIDTHS, FOCAL_LENGTH, get_vn_law_distance

class VehicleTrackerData:
    def __init__(self, track_id):
        self.track_id = track_id
        self.history = deque(maxlen=10)
        self.smooth_distance = 0.0
        self.relative_speed = 0.0
        self.ttc = float('inf')

class DistanceEstimator:
    def __init__(self):
        self.vehicles = {}

    def estimate_distance(self, bbox_width, class_name):
        real_width = REAL_WIDTHS.get(class_name, 1.75)
        if bbox_width <= 0:
            return 999.0
        return round((real_width * FOCAL_LENGTH) / bbox_width, 2)

    def update_vehicle(self, track_id, raw_distance):
        now = time.time()
        if track_id not in self.vehicles:
            self.vehicles[track_id] = VehicleTrackerData(track_id)

        v_data = self.vehicles[track_id]
        v_data.history.append((now, raw_distance))

        distances = [d for _, d in v_data.history]
        v_data.smooth_distance = round(float(np.mean(distances)), 2)

        if len(v_data.history) >= 3:
            t_old, d_old = v_data.history[0]
            t_new, d_new = v_data.history[-1]
            dt = t_new - t_old
            if dt > 0.05:
                v_rel = (d_old - d_new) / dt
                v_data.relative_speed = round(v_rel, 2)

                if v_rel > 0.5:
                    v_data.ttc = round(v_data.smooth_distance / v_rel, 2)
                else:
                    v_data.ttc = float('inf')

        return v_data

    def evaluate_safety_level(self, v_data, ego_speed_kmh, is_rain=False):
        d = v_data.smooth_distance
        ttc = v_data.ttc
        d_law = get_vn_law_distance(ego_speed_kmh, is_rain)

        if d < (0.7 * d_law) or ttc <= 1.8:
            status = "NGUY HIEM - PHANH GAP"
            color = (0, 0, 255)       # Đỏ
            level = 3
        elif d < d_law or ttc <= 2.8:
            status = "CANH BAO - DUOI CU LY LUAT"
            color = (0, 215, 255)     # Vàng cam
            level = 2
        else:
            status = "AN TOAN"
            color = (0, 255, 0)       # Xanh lá
            level = 1

        return status, color, level, d_law
