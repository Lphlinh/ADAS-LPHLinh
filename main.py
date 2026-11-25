"""
main.py: Ứng dụng ADAS chính - YOLOv8 + GPS Android + Luật GT Việt Nam
"""
import cv2
import numpy as np
import winsound
import time
from ultralytics import YOLO
from distance_estimator import DistanceEstimator
from gps_receiver import AndroidGPSReceiver
import config

def main():
    print("[INFO] Đang khởi động hệ thống...")
    gps = AndroidGPSReceiver(port=config.GPS_UDP_PORT, default_speed=config.DEFAULT_SPEED)
    estimator = DistanceEstimator()

    print("[INFO] Đang tải mô hình YOLOv8n...")
    model = YOLO("yolov8n.pt")

    # Đọc từ file video hoặc đổi thành 0 để dùng Webcam
    video_source = "data/sample_drive.mp4"
    cap = cv2.VideoCapture(video_source)
    if not cap.isOpened():
        print(f"[INFO] Không tìm thấy file {video_source}. Chuyển sang mở Webcam...")
        cap = cv2.VideoCapture(0)
        if not cap.isOpened():
            print("[ERROR] Không thể mở Webcam. Vui lòng kiểm tra lại thiết bị!")
            return

    TARGET_CLASSES = [2, 3, 5, 7] # car, motorcycle, bus, truck
    is_rain = False
    last_beep_time = 0

    print("=" * 60)
    print("HỆ THỐNG ADAS ĐÃ SẴN SÀNG:")
    print(" - Phím [q]: Thoát chương trình")
    print(" - Phím [r]: Bật/Tắt chế độ trời mưa (tăng 35% cự ly an toàn)")
    print(" - Phím [Mũi tên Lên / Xuống]: Tăng / Giảm tốc độ thủ công (khi test)")
    print("=" * 60)

    while cap.isOpened():
        ret, frame = cap.read()
        if not ret:
            break

        h, w, _ = frame.shape
        roi_top = int(h * config.ROI_Y_MIN)
        roi_left = int(w * config.ROI_X_MIN)
        roi_right = int(w * config.ROI_X_MAX)

        ego_speed, is_gps_live = gps.get_speed()

        results = model.track(frame, persist=True, classes=TARGET_CLASSES, verbose=False)
        lead_vehicle = None
        min_lead_distance = float('inf')

        if results[0].boxes.id is not None:
            boxes = results[0].boxes.xyxy.cpu().numpy()
            track_ids = results[0].boxes.id.int().cpu().numpy()
            clss = results[0].boxes.cls.int().cpu().numpy()

            for box, track_id, cls_id in zip(boxes, track_ids, clss):
                x1, y1, x2, y2 = box.astype(int)
                bbox_w = x2 - x1
                class_name = model.names[cls_id]

                raw_dist = estimator.estimate_distance(bbox_w, class_name)
                v_data = estimator.update_vehicle(track_id, raw_dist)

                center_x = (x1 + x2) // 2
                in_roi = (y2 > roi_top) and (roi_left < center_x < roi_right)

                if in_roi and v_data.smooth_distance < min_lead_distance:
                    min_lead_distance = v_data.smooth_distance
                    lead_vehicle = (box, class_name, v_data)

                # Vẽ khung nhẹ cho các xe xung quanh
                cv2.rectangle(frame, (x1, y1), (x2, y2), (180, 180, 180), 1)
                cv2.putText(frame, f"{v_data.smooth_distance}m", (x1, max(y1 - 5, 12)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.45, (180, 180, 180), 1)

        # ------------------ BẢNG HUD HIỂN THỊ CẢNH BÁO ------------------
        hud_h, hud_w = 200, 560
        hud_bg = frame.copy()
        cv2.rectangle(hud_bg, (15, 15), (15 + hud_w, 15 + hud_h), (20, 20, 20), -1)
        frame = cv2.addWeighted(hud_bg, 0.75, frame, 0.25, 0)

        # Trạng thái GPS
        gps_tag = "LIVE ANDROID GPS" if is_gps_live else "MANUAL SIMULATION"
        gps_color = (0, 255, 0) if is_gps_live else (0, 215, 255)
        cv2.putText(frame, f"GPS: [{gps_tag}]", (30, 42),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, gps_color, 1)

        d_law = config.get_vn_law_distance(ego_speed, is_rain)
        rain_str = " | [TRO'I MUA: +35%]" if is_rain else ""
        cv2.putText(frame, f"TOC DO XE: {ego_speed:.0f} km/h (Luat dinh: >= {d_law}m){rain_str}", (30, 72),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)

        if lead_vehicle:
            l_box, l_class, l_data = lead_vehicle
            lx1, ly1, lx2, ly2 = l_box.astype(int)
            status, color, level, _ = estimator.evaluate_safety_level(l_data, ego_speed, is_rain)

            # Đóng khung xe dẫn đầu nổi bật
            cv2.rectangle(frame, (lx1, ly1), (lx2, ly2), color, 3)

            ttc_str = f"{l_data.ttc:.1f}s" if l_data.ttc != float('inf') else "AN TOAN"
            v_rel_kmh = l_data.relative_speed * 3.6

            cv2.putText(frame, f"KHOANG CACH HIEN TAI: {l_data.smooth_distance:.1f} m", (30, 110),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.75, color, 2)
            cv2.putText(frame, f"TOC DO T/DOI: {v_rel_kmh:+.1f} km/h | TTC: {ttc_str}", (30, 145),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.55, (220, 220, 220), 2)
            cv2.putText(frame, f"TRANG THAI: {status}", (30, 185),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)

            # Cảnh báo âm thanh (Buzzer Beep)
            now_t = time.time()
            if level == 3 and (now_t - last_beep_time > 0.4):
                winsound.Beep(2000, 200) # Tiếng bíp dồn dập
                last_beep_time = now_t
            elif level == 2 and (now_t - last_beep_time > 1.2):
                winsound.Beep(1200, 150) # Tiếng bíp ngắt quãng
                last_beep_time = now_t
        else:
            cv2.putText(frame, "KHONG CO XE PHIA TRUOC", (30, 130),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)

        # Làn đường tham chiếu ROI
        cv2.line(frame, (roi_left, h), (int(w * 0.42), roi_top), (255, 255, 0), 1)
        cv2.line(frame, (roi_right, h), (int(w * 0.58), roi_top), (255, 255, 0), 1)

        cv2.imshow("ADAS Safe Distance & Collision Warning (FCW)", frame)

        key = cv2.waitKey(1) & 0xFF
        if key == ord('q'):
            break
        elif key == ord('r'):
            is_rain = not is_rain
        elif key == 82 or key == ord('w'): # Mũi tên Lên
            gps.set_manual_speed(+5)
        elif key == 84 or key == ord('s'): # Mũi tên Xuống
            gps.set_manual_speed(-5)

    gps.stop()
    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
