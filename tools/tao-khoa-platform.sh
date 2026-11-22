#!/usr/bin/env bash
# Tạo keys/platform.p12 từ khóa platform (platform.pk8 + platform.x509.pem) để ký bản "hệ thống".
#
# Đầu máy ECARX/Geely mà Sunray chạy được dùng khóa THỬ của AOSP (công khai trong mã nguồn Android,
# thư mục build/target/product/security). Cách lấy:
#   - Chạy script với tham số "tai" để tự tải từ android.googlesource.com, hoặc
#   - Tự chép platform.pk8 và platform.x509.pem vào thư mục keys/ rồi chạy script không tham số.
# Script kiểm tra dấu vân tay chứng chỉ phải TRÙNG với chứng chỉ ký Sunray, sai thì dừng.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p keys

EXPECTED_SHA1="27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA"
BASE="https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security"

if [ "${1:-}" = "tai" ]; then
  echo "Đang tải khóa platform AOSP..."
  curl -fsSL "$BASE/platform.pk8?format=TEXT" | base64 -d > keys/platform.pk8
  curl -fsSL "$BASE/platform.x509.pem?format=TEXT" | base64 -d > keys/platform.x509.pem
fi

[ -f keys/platform.pk8 ] && [ -f keys/platform.x509.pem ] || {
  echo "Thiếu keys/platform.pk8 hoặc keys/platform.x509.pem"; exit 1; }

GOT=$(openssl x509 -in keys/platform.x509.pem -noout -fingerprint -sha1 | sed 's/.*=//' | tr 'a-f' 'A-F')
echo "SHA-1 chứng chỉ: $GOT"
if [ "$GOT" != "$EXPECTED_SHA1" ]; then
  echo "KHÔNG trùng chứng chỉ của Sunray ($EXPECTED_SHA1). Dừng lại."; exit 1
fi

openssl pkcs8 -inform DER -nocrypt -in keys/platform.pk8 -out keys/platform.key.pem
openssl pkcs12 -export -in keys/platform.x509.pem -inkey keys/platform.key.pem \
  -name platform -out keys/platform.p12 -passout pass:android
rm -f keys/platform.key.pem
echo "Đã tạo keys/platform.p12 (mật khẩu: android, alias: platform)"
