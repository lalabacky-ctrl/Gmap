# Google Maps → Đồng hồ

App Android nhỏ, giao diện tiếng Việt, dành cho đầu máy ECARX trên xe Geely (loại mà Sunray chạy được).
App đọc chỉ đường của **Google Maps** rồi hiện lên **màn hình đồng hồ (cockpit / DIM)**.
Chỉ có phần dẫn đường. Không có HUD, điều hòa, chẩn đoán ECU hay các phần khác của Sunray.

## App lấy gì từ Sunray

Phần đẩy lên đồng hồ được viết lại theo đúng cách Sunray 1.4.4 làm (các lớp `a2.p`, `a2.o`, `a2.n`, `y1.a`, `y1.b`, `y1.c`):

| Việc | Sunray làm | App này |
|---|---|---|
| Kết nối đồng hồ | `DimInteraction.create(context)` → `registerConnectWatcher` → `connect()` → `getNaviInteraction()` | Giống hệt |
| Bắt đầu | `notifyNavigationStatus(2)` | Giống hệt |
| Mỗi bước | `updateNavigationInfo(INavigationInfo)` rồi `updateDriveDirection(0)` | Giống hệt |
| Kết thúc | `updateNavigationInfo(trạng thái 0)`, `notifyTurnByTurnStopped()`, `notifyNavigationStatus(0)`, `updateAddresses(3, rỗng)` | Giống hệt |
| Mã hướng rẽ | 102 trái, 103 phải, 104/105 chếch trái/phải, 106/107 rẽ gắt, 108 quay đầu, 109 thẳng, 112 vòng xuyến, 115 đến nơi, 119 quay đầu phải | Giống hệt |
| Tên đường dài | Chạy chữ, 13 ký tự (đầu máy IHU628G/IHU634G) hoặc 6 ký tự (đời khác) | Giống hệt, đổi được trong app |
| Nguồn chỉ đường | Yandex Navigator / 2GIS | **Google Maps** (đọc thông báo dẫn đường) |

## Cách app đọc Google Maps

Google Maps không cho app khác lấy dữ liệu dẫn đường. App đọc **thông báo dẫn đường** của Google Maps (cần quyền "đọc thông báo"):

- **Khoảng cách tới chỗ rẽ**: lấy từ tiêu đề thông báo ("300 m", "1,2 km").
- **Tên đường**: lấy từ nội dung thông báo.
- **Hướng rẽ**: Google Maps chỉ vẽ hình mũi tên, không ghi chữ. App tìm hướng theo thứ tự:
  1. Hướng **bạn đã dạy** cho hình mũi tên đó (chính xác nhất).
  2. Chữ trong thông báo, nếu có ("rẽ phải", "quay đầu", "vòng xuyến"…).
  3. **Tự đoán từ hình**: tìm gốc và đầu mũi tên rồi đo góc. Đã thử đúng 13/13 kiểu mũi tên mẫu, nhưng hình thật của Google Maps có thể khác.
- Hết dẫn đường (thông báo biến mất) thì app tự tắt hiển thị trên đồng hồ.

## Hai bản APK

| Bản | Khi nào dùng | Cài |
|---|---|---|
| **thuong** (`vn.cockpit.gmaps`) | Thử trước | Cài như app bình thường |
| **heThong** (`vn.cockpit.gmaps.hethong`) | Khi bản thường báo lỗi DIM trong Nhật ký | Chạy quyền hệ thống giống Sunray. Bắt buộc ký bằng khóa platform AOSP, đúng khóa đã ký Sunray (SHA-1 `27:19:6E:38:…:3D:FA`). Tự cấp được quyền đọc thông báo. |

Sunray chạy quyền hệ thống. Đầu máy có thể chỉ cho app hệ thống gọi thư viện đồng hồ. Nếu vậy thì chỉ bản heThong chạy được.

## Build ra APK

Cách 1: **GitHub (không cần cài gì)**
1. Tạo kho (repository) mới trên GitHub rồi tải toàn bộ thư mục này lên.
2. Vào tab **Actions**. Lần chạy "Build APK" sẽ tự chạy (hoặc bấm *Run workflow*).
3. Chạy xong, tải mục **GMapsDongHo-apk**. Trong đó có `GMapsDongHo-thuong.apk` và `GMapsDongHo-hethong.apk`.
   Bước build tự tải khóa platform AOSP và kiểm tra khóa đúng là khóa đã ký Sunray.

Cách 2: **Android Studio trên máy tính**
1. Mở thư mục này bằng Android Studio (JDK 17).
2. Muốn build bản hệ thống thì chạy trước `bash tools/tao-khoa-platform.sh tai` (cần `openssl`).
3. Chạy `./gradlew assembleThuongRelease assembleHeThongRelease`. File APK nằm trong `app/build/outputs/apk/`.

## Cài và dùng

1. Cài APK lên đầu máy (USB + trình quản lý file, hoặc `adb install`).
2. Mở app, bấm **Cấp quyền đọc thông báo**:
   - Bản heThong tự cấp.
   - Bản thường mở màn hình cài đặt. Nếu đầu máy giấu màn hình này, dùng nút **Cấp quyền bằng máy tính (lệnh adb)**.
3. Bấm **Gửi thử các hướng rẽ**. Đồng hồ phải lần lượt hiện rẽ phải, rẽ trái, đi thẳng… Nếu không hiện, mở **Nhật ký** xem dòng `DIM:`.
4. Mở Google Maps **ngay trên đầu máy**, chọn điểm đến, bấm Bắt đầu. Đồng hồ sẽ hiện hướng rẽ, khoảng cách và tên đường.
5. Nếu đồng hồ hiện sai hướng ở hình nào, vào **Dạy hướng rẽ**, bấm vào hình đó rồi chọn hướng đúng. Lần sau sẽ đúng.

## Cài đặt trong app

- **Đẩy chỉ đường Google Maps lên đồng hồ**: công tắc chính.
- **Hiện tên đường**.
- **Bỏ dấu tiếng Việt**: bật sẵn, phòng khi đồng hồ không có font tiếng Việt.
- **Tự đoán hướng rẽ từ hình**: tắt đi thì hình chưa dạy sẽ không hiện mũi tên.
- **Độ dài tên đường**: Tự động / 6 / 10 / 13 / 20 / không giới hạn.
- **Gửi mã**: gửi một mã hướng rẽ bất kỳ (ví dụ 141–152, 184–188) để xem đồng hồ vẽ hình gì.

## Giới hạn

- Google Maps phải chạy trên đầu máy. Qua Android Auto / CarPlay thì Google Maps chạy trên điện thoại, app không đọc được.
- Google Maps có thể đổi kiểu thông báo sau mỗi lần cập nhật. Khi đó gửi file **Nhật ký** (có sẵn nội dung thông báo và hình mũi tên dạng chữ) để sửa.
- Chưa có làn đường, camera, HUD.
