"""
config.py: Cấu hình thông số vật lý, camera, GPS và Luật Giao thông Việt Nam
"""
REAL_WIDTHS = {
    "car": 1.75,         # Xe con (Sedan, SUV, Crossover) ~ 1.7m - 1.8m
    "truck": 2.40,       # Xe tải ~ 2.2m - 2.5m
    "bus": 2.55,         # Xe khách, xe buýt ~ 2.5m - 2.6m
    "motorcycle": 0.75   # Xe máy ~ 0.7m - 0.8m
}

FOCAL_LENGTH = 950.0     # Tiêu cự camera mặc định (dùng calibrate.py để chỉnh chuẩn)
GPS_UDP_PORT = 5005      # Cổng UDP nhận dữ liệu từ điện thoại Android
DEFAULT_SPEED = 50.0     # Tốc độ mặc định (km/h) khi chưa kết nối điện thoại

# Vùng quan tâm (ROI làn đường phía trước xe)
ROI_Y_MIN = 0.40
ROI_X_MIN = 0.20
ROI_X_MAX = 0.80

def get_vn_law_distance(v_kmh, is_rain=False):
    """
    Khoảng cách an toàn tối thiểu theo Điều 11 Thông tư 31/2019/TT-BGTVT
    """
    if v_kmh <= 10:
        d_min = 3.5  # Dừng đỗ kẹt xe
    elif v_kmh <= 60:
        d_min = max(5.0, v_kmh * (2.0 / 3.6))  # Quy tắc 2 giây
    elif 60 < v_kmh <= 80:
        d_min = 35.0 # Mốc 60 - 80 km/h: 35 mét
    elif 80 < v_kmh <= 100:
        d_min = 55.0 # Mốc 80 - 100 km/h: 55 mét
    else:
        d_min = 70.0 # Trên 100 km/h: 70 mét

    if is_rain:
        d_min *= 1.35  # Tăng 35% khi trời mưa/trơn trượt
    return round(d_min, 1)
