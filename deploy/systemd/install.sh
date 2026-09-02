#!/usr/bin/env bash
# =====================================================================
#  نصب اولیه روی سرور — یک بار اجرا می‌شود
# =====================================================================
#  کاری که می‌کند: کاربر سرویس، مسیرها، مجوزها، و کپی فایل‌ها.
#  چیزی که *نمی‌کند*: پر کردن رمزها. آن را خودتان انجام می‌دهید.
# =====================================================================
set -euo pipefail

APP_DIR=/opt/log-viewer
CONF_DIR=/etc/log-viewer
DATA_DIR=/var/lib/log-viewer
USER=logviewer

[[ $EUID -eq 0 ]] || { echo "با sudo اجرا کنید" >&2; exit 1; }

echo "→ ساخت کاربر سرویس (بدون شل، بدون خانه)"
id -u "$USER" &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin "$USER"

echo "→ ساخت مسیرها"
install -d -o root    -g "$USER" -m 750 "$CONF_DIR"
install -d -o "$USER" -g "$USER" -m 750 "$CONF_DIR/backups"
install -d -o "$USER" -g "$USER" -m 750 "$DATA_DIR"
install -d -o root    -g root    -m 755 "$APP_DIR"

echo "→ کپی فایل اجرایی و پیکربندی"
install -o root -g root -m 644 backend/target/*.jar "$APP_DIR/app.jar"
for f in config/config.yaml config/config.json; do
  if [[ -f "$CONF_DIR/$(basename "$f")" ]]; then
    echo "   $(basename "$f") از قبل هست — دست نخورد"
  else
    install -o root -g "$USER" -m 640 "$f" "$CONF_DIR/"
  fi
done
cp -r docs "$APP_DIR/" 2>/dev/null || true

echo "→ فایل متغیرهای محیطی"
if [[ ! -f "$CONF_DIR/env" ]]; then
  install -o root -g "$USER" -m 640 deploy/systemd/env.example "$CONF_DIR/env"
  echo "   ⚠️  $CONF_DIR/env را ویرایش کنید: MONGO_URI و ADMIN_TOKEN"
fi

echo "→ نصب unit"
install -m 644 deploy/systemd/saga-log-viewer.service /etc/systemd/system/
systemctl daemon-reload

cat <<'DONE'

نصب انجام شد. گام‌های باقی‌مانده:

  ۱) sudo nano /etc/log-viewer/env        # MONGO_URI و ADMIN_TOKEN
  ۲) sudo systemctl enable --now saga-log-viewer
  ۳) curl -s localhost:8080/api/v1/meta/health | head

DONE
