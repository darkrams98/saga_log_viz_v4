# ۷ — استقرار روی سرور عملیاتی

راهنمای کامل، از صفر تا سرویسِ در حال کار. دو مسیر پوشش داده شده:
**Docker Compose** و **systemd + nginx**. هر دو به یک نتیجه می‌رسند؛
یکی را انتخاب کنید.

---

## فهرست

- [پیش از شروع](#پیش-از-شروع)
- [گام ۱ — کاربر فقط-خواندنی MongoDB](#گام-۱--کاربر-فقط-خواندنی-mongodb)
- [گام ۲ — ایندکس‌ها](#گام-۲--ایندکسها)
- [گام ۳ — توکن مدیریتی](#گام-۳--توکن-مدیریتی)
- [مسیر الف — Docker Compose](#مسیر-الف--docker-compose)
- [مسیر ب — systemd + nginx](#مسیر-ب--systemd--nginx)
- [گام ۴ — TLS و انتشار](#گام-۴--tls-و-انتشار)
- [پذیرش پس از استقرار](#پذیرش-پس-از-استقرار)
- [چک‌لیست امنیتی](#چکلیست-امنیتی)
- [به‌روزرسانی و بازگشت](#بهروزرسانی-و-بازگشت)
- [پشتیبان‌گیری](#پشتیبانگیری)
- [پایش](#پایش)
- [عیب‌یابی](#عیبیابی)

---

## پیش از شروع

### نیازمندی‌ها

| مورد | مقدار |
|---|---|
| CPU / RAM | ۲ هسته · ۲ گیگابایت (سرویس با ۱ گیگ heap راحت کار می‌کند) |
| دیسک | ۲ گیگابایت — برنامه کوچک است؛ فضا برای لاگ و پشتیبان پیکربندی |
| Java | ۲۱ (فقط در مسیر systemd) |
| Docker | ۲۴+ با plugin compose (فقط در مسیر Docker) |
| شبکه | دسترسی به MongoDB؛ **نیازی به اینترنت نیست** |

> هیچ CDN یا منبع خارجی‌ای بار نمی‌شود — فونت وزیرمتن هم داخل بسته است.
> برنامه در شبکهٔ کاملاً بسته کار می‌کند.

### آنچه لازم دارید

- آدرس اتصال MongoDB و امکان ساخت کاربر (یا هماهنگی با DBA)
- یک نام دامنهٔ داخلی، مثلاً `log-viewer.bank.internal`
- گواهی TLS برای همان نام

---

## گام ۱ — کاربر فقط-خواندنی MongoDB

**این مهم‌ترین گام امنیتی کل استقرار است.** سه لایهٔ محافظت داخل برنامه
هست، ولی این تنها لایه‌ای است که به کد ما اعتماد نمی‌کند.

```javascript
// با کاربر مدیر به MongoDB وصل شوید
db.getSiblingDB("admin").createUser({
  user: "log_viewer_ro",
  pwd: passwordPrompt(),
  roles: [ { role: "read", db: "saga" } ]     // فقط read، نه readWrite
})
```

بررسی کنید که واقعاً فقط-خواندنی است:

```bash
mongosh "mongodb://log_viewer_ro:***@db-1.internal:27017/saga?authSource=admin" \
  --eval 'db.sagaSequence.insertOne({x:1})'
# انتظار: not authorized on saga to execute command insert
```

اگر این دستور **موفق شد**، ادامه ندهید — نقش کاربر اشتباه است.

### آدرس اتصال پیشنهادی

```
mongodb://log_viewer_ro:PASSWORD@db-1.internal:27017,db-2.internal:27017/saga
        ?replicaSet=rs0
        &authSource=admin
        &readPreference=secondaryPreferred
```

`readPreference=secondaryPreferred` یعنی بار خواندن روی نود ثانویه می‌افتد
و به نود اصلی — که تراکنش‌های واقعی را می‌نویسد — دست نمی‌خورد.

---

## گام ۲ — ایندکس‌ها

خبر خوب: **معمولاً کاری لازم نیست.**

```bash
mongosh "$MONGO_URI" ops/indexes.js
```

انتظار: `✔ ایندکس _id موجود است — کار دیگری لازم نیست.`

جستجوی عادی فقط `find({_id: …})` است و MongoDB خودش روی `_id` ایندکس یکتا
دارد. ایندکس‌های اختیاری فقط وقتی لازم‌اند که بخواهید فیلد دیگری را هم به
جستجوی عادی اضافه کنید — راهنمایش در همان اسکریپت است.

---

## گام ۳ — توکن مدیریتی

```bash
openssl rand -hex 24
```

**تصمیم بگیرید:**

| حالت | نتیجه |
|---|---|
| `ADMIN_TOKEN` خالی | صفحهٔ مدیریتی **کاملاً غیرفعال** است. امن‌ترین حالت. |
| `ADMIN_TOKEN` تنظیم‌شده | صفحهٔ مدیریتی فعال؛ دارندهٔ توکن می‌تواند پوشاندن دادهٔ حساس را خاموش کند. |

> **دسترسی به `/admin` هم‌ارز دسترسی به دادهٔ خام است.** توکن را مثل رمز
> پایگاه داده نگه دارید و مسیر مدیریتی را در nginx به شبکهٔ داخلی محدود کنید.

اگر توکن کوتاه‌تر از ۱۶ نویسه بدهید، پذیرفته نمی‌شود و سرویس با پیام
هشدار بالا می‌آید — یعنی صفحهٔ مدیریتی غیرفعال می‌ماند.

---

## مسیر الف — Docker Compose

### ۱) آماده‌سازی

```bash
cd /opt
sudo unzip saga-log-viewer.zip && cd saga-log-explorer

cp deploy/docker/.env.example .env
chmod 600 .env
nano .env                       # MONGO_URI و ADMIN_TOKEN
```

### ۲) ساخت و اجرا

```bash
docker compose -f deploy/docker/docker-compose.yml --env-file .env up -d --build
```

دو کانتینر بالا می‌آید:

| کانتینر | کار | پورت |
|---|---|---|
| `backend` | Spring Boot | داخلی ۸۰۸۰ |
| `frontend` | nginx + فایل‌های ساخته‌شده | `127.0.0.1:8081` |

پورت عمداً فقط روی لوپ‌بک باز است. TLS و انتشار بیرونی کار پروکسی لبه است.

### ۳) بررسی

```bash
docker compose -f deploy/docker/docker-compose.yml ps
curl -s localhost:8081/api/v1/meta/health | python3 -m json.tool
```

### نکته‌های استقرار Docker

- **`config/` از بیرون mount می‌شود.** ویرایش‌های صفحهٔ مدیریتی روی همین
  حجم می‌نشیند و با هر `up --build` از بین نمی‌رود.
- **`data/` یک volume جدا است** و تاریخچهٔ تغییرات داخلش می‌ماند.
- **فایل‌سیستم کانتینر backend فقط-خواندنی است** (`read_only: true`)؛
  فقط همان دو حجم قابل نوشتن‌اند.
- **MongoDB عمداً در compose نیست.** این سرویس به پایگاه دادهٔ *موجود* شما
  وصل می‌شود؛ تعریفش اینجا یعنی خطر وصل‌شدن به نمونهٔ اشتباه.

---

## مسیر ب — systemd + nginx

### ۱) ساخت بسته

روی ماشین build (نه لزوماً سرور عملیاتی):

```bash
cd backend && mvn -B clean package -DskipTests
cd ../frontend && npm ci && npm run build
```

خروجی‌ها: `backend/target/*.jar` و `frontend/dist/`

### ۲) نصب

```bash
sudo ./deploy/systemd/install.sh
```

اسکریپت این‌ها را می‌سازد:

```
/opt/log-viewer/app.jar             فایل اجرایی
/etc/log-viewer/config.yaml         پیکربندی زیرساخت (۶۴۰، root:logviewer)
/etc/log-viewer/config.json         برچسب‌ها و نمایش
/etc/log-viewer/backups/            پشتیبان‌های خودکار پیکربندی
/etc/log-viewer/env                 رمزها (۶۴۰)
/var/lib/log-viewer/                تاریخچهٔ تغییرات
کاربر سرویس: logviewer (بدون شل، بدون خانه)
```

### ۳) پیکربندی و اجرا

```bash
sudo nano /etc/log-viewer/env       # MONGO_URI و ADMIN_TOKEN
sudo systemctl enable --now saga-log-viewer
sudo systemctl status saga-log-viewer
journalctl -u saga-log-viewer -f
```

### ۴) فایل‌های فرانت‌اند

```bash
sudo mkdir -p /var/www/log-viewer
sudo cp -r frontend/dist/* /var/www/log-viewer/
sudo chown -R www-data:www-data /var/www/log-viewer
```

### ۵) nginx

```bash
sudo cp deploy/nginx/limits.conf                 /etc/nginx/conf.d/00-limits.conf
sudo cp deploy/nginx/proxy_params_logviewer.conf /etc/nginx/
sudo cp deploy/nginx/nginx.conf                  /etc/nginx/sites-available/log-viewer
sudo ln -sf /etc/nginx/sites-available/log-viewer /etc/nginx/sites-enabled/
```

سه ویرایش لازم است:

1. `root` را به `/var/www/log-viewer` تغییر دهید
2. `proxy_pass http://backend:8080` را به `http://127.0.0.1:8080` تغییر دهید
3. در بلوک `/api/v1/admin/` فهرست `allow` را با شبکهٔ خودتان جایگزین کنید

```bash
sudo nginx -t && sudo systemctl reload nginx
```

### سخت‌سازی که unit فایل انجام می‌دهد

سرویس با `ProtectSystem=strict` اجرا می‌شود: کل فایل‌سیستم برایش
فقط-خواندنی است جز دو مسیر (`/etc/log-viewer` و `/var/lib/log-viewer`).
به‌علاوه `NoNewPrivileges`، `PrivateTmp`، `RestrictAddressFamilies` و
`SystemCallFilter=@system-service`.

اگر ویرایش پیکربندی از صفحهٔ مدیریتی لازم **ندارید**، این خط را هم بردارید
تا سرویس هیچ‌جا اجازهٔ نوشتن نداشته باشد:

```ini
ReadWritePaths=/etc/log-viewer
```

---

## گام ۴ — TLS و انتشار

**بدون TLS مستقر نکنید.** توکن مدیریتی در هدر می‌رود و روی HTTP ساده
در شبکه قابل شنود است.

در `deploy/nginx/nginx.conf` بلوک TLS انتهای فایل را از کامنت دربیاورید،
گواهی را بگذارید و HTTP را به HTTPS ریدایرکت کنید:

```nginx
ssl_certificate     /etc/ssl/certs/log-viewer.crt;
ssl_certificate_key /etc/ssl/private/log-viewer.key;
ssl_protocols       TLSv1.2 TLSv1.3;
add_header Strict-Transport-Security "max-age=31536000" always;
```

### احراز هویت سازمانی (اختیاری ولی توصیه‌شده)

اگر LDAP/SSO دارید، آن را در nginx بگذارید و نام کاربر را به برنامه بدهید
تا در تاریخچهٔ تغییرات به‌جای `admin-token` نام واقعی ثبت شود:

```nginx
auth_request /auth;
auth_request_set $user $upstream_http_x_forwarded_user;
proxy_set_header X-Forwarded-User $user;
```

برنامه هدرهای `X-Forwarded-User`، `X-Remote-User` و `X-Auth-User` را
می‌شناسد.

---

## پذیرش پس از استقرار

این هفت بررسی را انجام دهید. هر کدام یک ادعای مشخص را می‌سنجد.

```bash
BASE=https://log-viewer.bank.internal
TOKEN='…'
```

**۱) سلامت پایه**

```bash
curl -s $BASE/api/v1/meta/health | python3 -m json.tool
```

| کلید | مقدار درست |
|---|---|
| `mongo.reachable` | `true` |
| `readOnly.blockedWriteAttempts` | `0` |
| `searchFields.problems` | `[]` |
| `configWarnings` / `labelWarnings` | `[]` |

**۲) نمایش یک لاگ واقعی** — یک شناسه از ELK بردارید:

```bash
curl -s "$BASE/api/v1/log/<ID>" | python3 -c \
  'import json,sys;d=json.load(sys.stdin);print(d["mongoOperations"],d["operations"])'
# انتظار: 1 ['findOne']
```

اگر عددی جز `1` دیدید، قید طراحی شکسته — گزارش دهید.

**۳) فقط-خواندنی بودن پایگاه داده**

```bash
mongosh "$MONGO_URI" --eval 'db.sagaSequence.insertOne({x:1})'
# انتظار: not authorized
```

**۴) صفحهٔ مدیریتی بدون توکن بسته است**

```bash
curl -s -o /dev/null -w '%{http_code}\n' $BASE/api/v1/admin/status
# انتظار: 401 (یا 403 اگر nginx از بیرون بسته باشد)
```

**۵) صفحهٔ مدیریتی با توکن باز است**

```bash
curl -s -H "Authorization: Bearer $TOKEN" $BASE/api/v1/admin/status \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["maskingProfile"])'
# انتظار: secretsOnly
```

**۶) مسیر مدیریتی از بیرون شبکه بسته است** — از یک ماشین خارج از رنج
مجاز، همان درخواست ۵ را بزنید. انتظار: `403` از nginx.

**۷) رابط کاربری** — `$BASE/log` را باز کنید، یک شناسه بچسبانید و
گراف را ببینید.

---

## چک‌لیست امنیتی

پیش از تحویل به تیم پشتیبانی:

- [ ] کاربر MongoDB نقش `read` دارد، نه `readWrite` — با insert تست شد
- [ ] `readPreference=secondaryPreferred` در آدرس اتصال هست
- [ ] TLS فعال است و HTTP ریدایرکت می‌شود
- [ ] `ADMIN_TOKEN` تصادفی و حداقل ۲۴ نویسه است
- [ ] مسیر `/api/v1/admin/` در nginx به شبکهٔ داخلی محدود شده
- [ ] `/actuator/` از بیرون در دسترس نیست
- [ ] فایل رمزها مجوز ۶۴۰ دارد و مالکش `root` است
- [ ] سرویس با کاربر غیر-root اجرا می‌شود
- [ ] `privacy.maskingProfile` روی `secretsOnly` است، نه `off`
- [ ] تاریخچهٔ تغییرات (`admin-audit.log`) در پشتیبان‌گیری هست
- [ ] rate limit در nginx فعال است
- [ ] هشدار برای `readOnly.blockedWriteAttempts > 0` تنظیم شده

---

## به‌روزرسانی و بازگشت

### Docker

```bash
docker compose -f deploy/docker/docker-compose.yml --env-file .env up -d --build
```

بازگشت: تصویر نسخهٔ قبلی را با تگش برگردانید
(`image: saga-log-viewer-backend:2.0.0`) و دوباره `up -d` بزنید.

### systemd

```bash
sudo systemctl stop saga-log-viewer
sudo cp /opt/log-viewer/app.jar /opt/log-viewer/app.jar.previous   # ← قبل از هر چیز
sudo cp backend/target/*.jar /opt/log-viewer/app.jar
sudo systemctl start saga-log-viewer
```

بازگشت:

```bash
sudo systemctl stop saga-log-viewer
sudo mv /opt/log-viewer/app.jar.previous /opt/log-viewer/app.jar
sudo systemctl start saga-log-viewer
```

### تغییر پیکربندی بدون قطعی

سه راه، به ترتیب ترجیح:

```bash
# ۱) از صفحهٔ مدیریتی — با اعتبارسنجی، پشتیبان و ثبت در تاریخچه
#    https://…/admin/config

# ۲) ویرایش فایل + بازخوانی
sudo nano /etc/log-viewer/config.json
sudo systemctl reload saga-log-viewer

# ۳) مستقیم با API
curl -X POST -H "Authorization: Bearer $TOKEN" $BASE/api/v1/admin/reload
```

> اگر فایل جدید خراب باشد، **نسخهٔ سالم قبلی حفظ می‌شود** و دلیل در
> `labelWarnings` می‌آید. سرویس از کار نمی‌افتد.

---

## پشتیبان‌گیری

| مسیر | محتوا | اهمیت |
|---|---|---|
| `/etc/log-viewer/config.json` | برچسب‌ها و تنظیمات نمایش | 🔴 بالا — کار دستی تیم |
| `/etc/log-viewer/config.yaml` | پیکربندی زیرساخت | 🟡 متوسط |
| `/etc/log-viewer/backups/` | ۲۰ نسخهٔ آخر پیکربندی | 🟢 راحتی |
| `/var/lib/log-viewer/admin-audit.log` | تاریخچهٔ تغییرات | 🔴 بالا — نیاز ممیزی |

**دادهٔ لاگ پشتیبان نمی‌خواهد** — این سرویس هیچ داده‌ای ذخیره نمی‌کند و
مالک لاگ‌ها MongoDB است.

```bash
# پشتیبان روزانه
tar czf /backup/log-viewer-$(date +%F).tar.gz \
    /etc/log-viewer /var/lib/log-viewer
```

---

## پایش

### هشدارهای پیشنهادی

| نشانه | شدت | معنا |
|---|---|---|
| `readOnly.blockedWriteAttempts > 0` | 🔴 بحرانی | تلاش برای نوشتن — نباید هرگز رخ دهد |
| `mongo.reachable == false` | 🔴 بحرانی | ارتباط با پایگاه داده قطع است |
| `maskingProfile == "off"` | 🟠 مهم | پوشاندن دادهٔ حساس خاموش شده |
| `searchFields.problems` غیرخالی | 🟡 هشدار | فیلدی بدون ایندکس فعال شده |
| نرخ خطای ۵xx > ۱٪ | 🟡 هشدار | — |
| `p95` مسیر `/log/{id}` > ۲ ثانیه | 🟡 هشدار | ایندکس یا شبکه |

### نمونهٔ اسکریپت پایش

```bash
#!/usr/bin/env bash
H=$(curl -fsS http://127.0.0.1:8080/api/v1/meta/health) || { echo "CRIT: سرویس پاسخ نمی‌دهد"; exit 2; }
python3 - "$H" <<'PY'
import json, sys
d = json.loads(sys.argv[1])
if not d["mongo"]["reachable"]:                    print("CRIT: MongoDB قطع"); sys.exit(2)
if d["readOnly"]["blockedWriteAttempts"] > 0:      print("CRIT: تلاش نوشتن"); sys.exit(2)
if d.get("maskingProfile") == "off":               print("WARN: پوشاندن خاموش"); sys.exit(1)
if d["searchFields"]["problems"]:                  print("WARN: ایندکس کم"); sys.exit(1)
print("OK")
PY
```

### Prometheus

آمار صفحهٔ مدیریتی با ری‌استارت صفر می‌شود و برای «همین حالا» است.
برای روند بلندمدت، actuator را بدهید:

```
GET /actuator/metrics/http.server.requests
GET /actuator/metrics/jvm.memory.used
```

### لاگ‌ها

```bash
# systemd
journalctl -u saga-log-viewer -f
journalctl -u saga-log-viewer --since "1 hour ago" | grep -i "مسدود شد"

# Docker
docker compose -f deploy/docker/docker-compose.yml logs -f backend

# تاریخچهٔ تغییرات مدیریتی (JSONL)
jq -c 'select(.action|startswith("config."))' /var/lib/log-viewer/admin-audit.log | tail
```

---

## عیب‌یابی

### سرویس بالا نمی‌آید

```bash
journalctl -u saga-log-viewer -n 80 --no-pager
```

| پیام | علت | راه‌حل |
|---|---|---|
| `فایل پیکربندی پیدا نشد` | مسیر اشتباه | `-Dlogexplorer.config` را بررسی کنید |
| `Authentication failed` | رمز اشتباه | `/etc/log-viewer/env` |
| `Address already in use` | پورت اشغال | `ss -ltnp \| grep 8080` |
| `Permission denied` روی audit | مالکیت مسیر | `chown -R logviewer /var/lib/log-viewer` |

### «لاگی با این شناسه پیدا نشد» با اینکه شناسه درست است

به ترتیب احتمال:

1. **فاصله یا نویسهٔ اضافه** هنگام کپی — شایع‌ترین علت. کادر جستجو
   گیومه و `ObjectId(...)` را خودش پاک می‌کند، ولی فاصلهٔ داخل رشته نه.
2. **نوع `_id` فرق دارد.** اگر مجموعهٔ شما `ObjectId` ذخیره می‌کند و
   ورودی رشته است، نتیجه صفر است بدون هیچ خطایی. در `config.json`:
   `{ "field": "_id", "type": "objectId" }`
3. لاگ هنوز به MongoDB نرسیده، یا بر اساس سیاست نگهداشت حذف شده.

### صفحهٔ مدیریتی ۵۰۳ می‌دهد

`ADMIN_TOKEN` تنظیم نشده یا کوتاه‌تر از ۱۶ نویسه است. این رفتار عمدی است
(fail-closed). پس از تنظیم، سرویس را ری‌استارت کنید — این متغیر با
`reload` خوانده نمی‌شود.

### صفحهٔ مدیریتی ۴۲۹ می‌دهد

پنج تلاش ناموفق پشت سر هم. پنج دقیقه صبر کنید یا سرویس را ری‌استارت کنید.

### همه چیز کند است

1. آیا کسی «جستجوی پیشرفته» را باز گذاشته؟ گران‌ترین حالت است.
2. `POST /api/v1/meta/indexes/inspect` — فیلدی بدون ایندکس فعال شده؟
3. `readPreference` را بررسی کنید؛ بار نباید روی نود اصلی باشد.
4. در صفحهٔ مدیریتی → «آمار»، ستون `p95` هر مسیر را ببینید.

### گراف خالی است

پیام زیر گراف را بخوانید:

| پیام | یعنی |
|---|---|
| «فیلد commandList وجود ندارد» | این لاگ مرحله ندارد — طبیعی است |
| «commandList آرایه نیست» | ساختار عوض شده؛ `graph.source` را بررسی کنید |
| «مرحلهٔ N شیء نبود» | یک عنصر خراب رد شد؛ بقیه رسم شده‌اند |

در هر سه حالت **نمای جدولی و JSON خام کامل‌اند**.

### نام میکروسرویس به انگلیسی است

یعنی `routingKey` هنوز ترجمه ندارد. به صفحهٔ مدیریتی → «آمار و برچسب‌های
گم‌شده» بروید؛ همان مقدار آنجا فهرست شده و دکمهٔ «افزودن» شما را با کلید
آماده به ویرایشگر می‌برد.

### پس از ویرایش پیکربندی، چیزی عوض نشد

- کش مرورگر: `Ctrl+Shift+R`
- اگر فایل را دستی ویرایش کردید، `reload` لازم است
- در صفحهٔ مدیریتی → «تاریخچه»، ببینید تغییر ثبت شده یا نه

### بازگشت اضطراری پیکربندی

```bash
# از صفحهٔ مدیریتی: تاریخچه و نسخه‌ها → بازگشت
# یا دستی:
sudo cp /etc/log-viewer/backups/config.json.<STAMP>.bak /etc/log-viewer/config.json
sudo systemctl reload saga-log-viewer
```

---

← قبلی: [۶ — مستند Backend](06-backend.md)
