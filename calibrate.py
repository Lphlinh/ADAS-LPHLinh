"""
calibrate.py: Hiệu chuẩn tiêu cự Focal Length cho Camera của Thầy
"""
def calibrate_focal_length(known_distance, real_width, pixel_width):
    focal = (pixel_width * known_distance) / real_width
    print("=" * 60)
    print(f"Khoảng cách thực tế (m): {known_distance}")
    print(f"Chiều rộng xe thực tế (m): {real_width}")
    print(f"Chiều rộng xe trên ảnh (pixel): {pixel_width}")
    print(f"--> TIÊU CỰ FOCAL_LENGTH TÍNH TOÁN ĐƯỢC: {focal:.2f}")
    print("Thầy hãy mở config.py và thay số này vào FOCAL_LENGTH")
    print("=" * 60)
    return focal

if __name__ == "__main__":
    calibrate_focal_length(known_distance=10.0, real_width=1.75, pixel_width=166)
