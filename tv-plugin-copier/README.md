# CloudStream TV Plugin Copier

Android app kết nối Android TV / Google TV qua ADB Wi‑Fi, sao lưu plugin CloudStream local `.cs3` vào điện thoại và cài các bản sao đó sang TV khác.

- Kết nối ADB TCP và Wireless Debugging pairing.
- Quét `/sdcard/Cloudstream3/plugins`.
- Sao lưu/cài lại plugin local không cần root.
- Bắt URL plugin repository từ log CloudStream sau khi cập nhật/cài lại extension.
- Tải `.cs3` từ URL và lưu làm bản sao trên điện thoại.
- Cài bản sao sang TV đích và khởi động lại CloudStream.

Lưu ý: plugin repository được CloudStream lưu trong vùng private `filesDir/Extensions`, nên ADB thường không đọc trực tiếp nếu không root. App không vượt sandbox Android; thay vào đó khôi phục plugin repository từ URL nguồn.
