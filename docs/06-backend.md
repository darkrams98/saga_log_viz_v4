# ۶ — مستند Backend

مرجع کامل ساختار، منطق و همهٔ endpointها.
برای «چرا این‌طور» به [۲ — معماری](02-architecture.md) نگاه کنید؛ این سند «چه چیزی و کجا» است.

---

## فهرست

- [یک نگاه](#یک-نگاه)
- [ساختار بسته‌ها](#ساختار-بستهها)
- [چرخهٔ عمر یک درخواست](#چرخهٔ-عمر-یک-درخواست)
- [پیکربندی](#پیکربندی)
- [قراردادهای همیشگی](#قراردادهای-همیشگی)
- [Endpointها](#endpointها) — [عمومی](#۱-نمایش-لاگ--apiv1log) · [فراداده](#۲-فراداده--apiv1meta) · [مدیریتی](#۳-مدیریتی--apiv1admin)
- [قالب خطا](#قالب-خطا)
- [مدل‌های داده](#مدلهای-داده)

---

## یک نگاه

| مورد | مقدار |
|---|---|
| زبان و فریم‌ورک | Java 21 · Spring Boot 3.3.5 (فقط `web` و `actuator`) |
| دسترسی پایگاه داده | `mongodb-driver-sync` مستقیم — **بدون** Spring Data MongoDB |
| حالت | **فقط-خواندنی** روی MongoDB؛ فقط `config.json` روی دیسک نوشته می‌شود |
| کلاس‌ها | ۳۹ کلاس در ۸ بسته |
| Endpointها | ۲۲ مسیر: ۴ عمومی، ۴ فراداده، ۱۲ مدیریتی، ۲ actuator |
| حالت بدون‌حالت | همه‌چیز stateless است جز آمار درون‌حافظه‌ای که با ری‌استارت صفر می‌شود |

---

## ساختار بسته‌ها

```
com.citydi.logexplorer
├── LogExplorerApplication      نقطهٔ شروع + پیکربندی CORS
│
├── config/                     «چطور بخوان» — config.yaml
│   ├── AppConfig               مدل تایپ‌دار، همه با پیش‌فرض
│   ├── ConfigLoader            SnakeYAML + ${ENV:default}، تحمل‌کننده
│   ├── ConfigNode              دسترسی بخشنده به درخت YAML
│   └── ConfigProvider          نگه‌داری + بازخوانی زنده
│
├── labels/                     «چطور نشان بده» — config.json
│   ├── LabelConfig             مدل تایپ‌دار برچسب‌ها، گراف، جستجو
│   ├── LabelConfigLoader       Jackson، تحمل‌کننده، کلید «_» = توضیح
│   ├── LabelConfigProvider     نگه‌داری + بازخوانی زنده
│   └── LabelResolver           زنجیرهٔ ترجمه (قلب فارسی‌سازی)
│
├── mongo/                      تنها راه ارتباط با پایگاه داده
│   ├── MongoClientConfig       ساخت کلاینت، retryWrites(false)
│   ├── LogCollection           دروازه — ۵ متد، بدون نوشتن، بدون پویش
│   ├── ReadOnlyGuard           نگهبان درایور با فهرست مجاز
│   ├── ReadOnlyViolationException
│   ├── IndexInspector          راستی‌آزمایی ادعای indexed
│   └── OperationCounter        شمارش پرس‌وجو در هر درخواست
│
├── parse/                      موتور عمومی (مستقل از schema)
│   ├── PathExpression          نحو مسیر: a.b، a[0].b، a[*].b، a#json.b
│   ├── PathResolver            پیمایش گراف ناشناخته، با بودجه
│   ├── TypeCoercion            تبدیل نوع، به‌ویژه زمان
│   ├── TextTransforms          تبدیل‌های regex از config
│   ├── JsonStrings             تشخیص و parse رشتهٔ JSON
│   ├── DocumentFlattener       سند → درخت فیلد با بودجه
│   ├── FieldNode / LogRecord   مدل‌های خروجی
│   └── LogRecordMapper         نگاشت عمومی سند
│
├── flow/                       گراف جریان اجرا
│   ├── FlowGraph               رکوردهای گره، یال، خلاصه
│   └── FlowGraphBuilder        ساخت از commandList (هرگز پرتاب نمی‌کند)
│
├── mask/
│   └── MaskingService          پوشاندن با پروفایل قابل تنظیم
│
├── service/
│   ├── LogLookupService        یافتن + آماده‌سازی سه نما
│   └── MongoErrors             ترجمهٔ خطای Mongo به فارسی
│
├── api/
│   ├── LogController           /api/v1/log
│   ├── MetaController          /api/v1/meta
│   └── ApiExceptionHandler     قالب یکسان خطا
│
└── admin/                      بخش مدیریتی (تنها بخشی که می‌نویسد)
    ├── AdminSecurity           فیلتر توکن، fail-closed
    ├── AdminController         /api/v1/admin
    ├── ConfigEditorService     خواندن/اعتبارسنجی/ذخیره/نسخه‌ها
    ├── UsageRegistry           آمار و برچسب‌های ترجمه‌نشده (در حافظه)
    ├── UsageFilter             زمان‌سنجی درخواست‌ها
    └── AuditLog                تاریخچهٔ تغییرات، JSONL فقط-افزودنی
```

---

## چرخهٔ عمر یک درخواست

نمونه: `GET /api/v1/log/68a1b2c3…`

```
۱. UsageFilter          کرنومتر شروع می‌شود
۲. AdminSecurity        رد می‌شود (این مسیر مدیریتی نیست)
۳. LogController        OperationCounter.start() — شمارش از صفر
۴. LogLookupService     فیلد جستجو را با config.json بررسی می‌کند
                        ↳ اگر enabled یا indexed نباشد → خطای ۴۰۰
۵. LogCollection        findOne(...) ← تنها پرس‌وجوی این مسیر
۶. FlowGraphBuilder     گراف از همان سند در حافظه
                        ↳ برچسب ترجمه‌نشده؟ در UsageRegistry ثبت می‌شود
۷. DocumentFlattener    نمای جدولی از همان سند
۸. MaskingService       JSON خام، پس از پوشاندن
۹. LogController        mongoOperations = 1 در پاسخ
۱۰. UsageFilter         مدت و وضعیت ثبت می‌شود
```

سه نمای خروجی (گراف، جدول، JSON) همه از **یک** سند ساخته می‌شوند.

---

## پیکربندی

### دو فایل، دو مسئولیت

| فایل | محتوا | ویرایش از UI |
|---|---|---|
| `config/config.yaml` | اتصال، فیلد زمان، مسیرها، محدودیت‌ها، قواعد ماسک | ❌ فقط نمایش |
| `config/config.json` | برچسب فارسی، گراف، جستجو، پروفایل حریم خصوصی | ✅ |

`config.yaml` از UI قابل ویرایش نیست چون آدرس اتصال MongoDB داخلش است؛
تغییرش از مرورگر یعنی کسی بتواند سرویس را به پایگاه دادهٔ دیگری وصل کند.

### متغیرهای محیطی

| متغیر | پیش‌فرض | کاربرد |
|---|---|---|
| `MONGO_URI` | `mongodb://localhost:27017` | آدرس اتصال |
| `MONGO_DB` | `saga` | نام پایگاه داده |
| `MONGO_COLLECTION` | `sagaSequence` | نام مجموعه |
| `ADMIN_TOKEN` | *(خالی)* | توکن مدیریتی؛ **خالی = API مدیریتی غیرفعال** |
| `ADMIN_AUDIT_FILE` | `data/admin-audit.log` | مسیر تاریخچه |

### پارامترهای JVM

| پارامتر | پیش‌فرض |
|---|---|
| `-Dlogexplorer.config` | `config/config.yaml` |
| `-Dlogexplorer.labels` | `config/config.json` |
| `-Dlogexplorer.cors.allowed-origins` | `http://localhost:5173,…` |
| `-Dserver.port` | `8080` |

---

## قراردادهای همیشگی

این پنج قاعده در تست و در `tools/verify_generic.py` سنجیده می‌شوند:

1. **نمایش یک لاگ = دقیقاً یک پرس‌وجو.** `mongoOperations` در پاسخ برمی‌گردد.
2. **هرگز نوشتن در MongoDB.** `LogCollection` متد نوشتن ندارد؛ `ReadOnlyGuard`
   با فهرست مجاز روی درایور می‌نشیند.
3. **هرگز پویش کل مجموعه.** `aggregate`/`count` از دروازه حذف شده‌اند.
4. **جستجوی عادی فقط روی فیلد ایندکس‌شده.** ادعای `indexed` با `listIndexes`
   راستی‌آزمایی می‌شود.
5. **هیچ برچسبی «نامشخص» نمی‌شود.** نبودِ ترجمه یعنی نمایش مقدار خام.

---

## Endpointها

پیشوند همه: `/api/v1`

### ۱) نمایش لاگ — `/api/v1/log`

بدون احراز هویت (کنترل دسترسی در لایهٔ پروکسی).

---

#### `GET /log/{id}`

**کاربرد:** یافتن یک لاگ و آماده‌سازی هر سه نما. مسیر اصلی سرویس.

| ورودی | نوع | الزامی | توضیح |
|---|---|---|---|
| `id` | path | ✅ | مقدار فیلد جستجو (پیش‌فرض `_id`) |
| `field` | query | ❌ | فیلد جستجو؛ باید در config.json هم `enabled` باشد هم `indexed` |

**خروجی ۲۰۰:**

```jsonc
{
  "id": "68a1b2c3…",
  "found": true,
  "searchedField": "_id",
  "header": {                       // کارت خلاصه
    "title": "بررسی اطلاعات مقصد انتقال",
    "rawTitle": "SEQ__TRANSACTION_GET_DESTINATION_INFO__2026-08-24_12:46:59",
    "status": "بازگشت خورده", "rawStatus": "ROLL_BACKED",
    "severity": "error",            // success | error | unknown
    "startedAt": "…", "completedAt": "…", "durationText": "۱٫۳ ثانیه",
    "stepCount": 5, "errorCount": 1
  },
  "summary":  [ { "path": "registerId", "label": "شناسهٔ مشتری",
                  "value": "…", "rawValue": "…", "copy": true } ],
  "graph":    { "nodes": [...], "edges": [...], "summary": {...}, "notes": [] },
  "table":    [ { "path": "commandList[0].status", "label": "وضعیت مرحله",
                  "type": "text", "value": "COMPLETED", "depth": 2,
                  "masked": false, "sizeBytes": 9 } ],
  "tableTruncated": false,
  "rawJson":  "{ … }",              // پس از پوشاندن دادهٔ حساس
  "rawSizeBytes": 5432,
  "maskingProfile": "secretsOnly",
  "warnings": [],
  "mongoOperations": 1,             // ← ادعای «یک find» قابل بررسی
  "operations": ["findOne"]
}
```

**خروجی ۴۰۴** (پیدا نشد — خطا نیست، پاسخ معتبر است):

```jsonc
{ "found": false, "message": "لاگی با این شناسه پیدا نشد.",
  "hint": "شناسه را از ELK دوباره کپی کنید…", "searchedField": "_id",
  "mongoOperations": 1 }
```

**خروجی ۴۰۰:** فیلد درخواست‌شده مجاز نیست (در config `indexed`/`enabled` نیست).

---

#### `GET /log/search/fields`

**کاربرد:** فهرست فیلدهای مجاز جستجوی عادی. UI از همین dropdown می‌سازد،
پس افزودن فیلد تازه نیازی به build فرانت‌اند ندارد.

**ورودی:** ندارد.

**خروجی:**

```jsonc
{
  "fields": [ { "field": "_id", "label": "شناسهٔ لاگ (_id)", "type": "auto",
                "indexed": true, "enabled": true, "usable": true,
                "default": true, "placeholder": "…", "hint": "…" } ],
  "note": "در حالت عادی فقط فیلدهای ایندکس‌شده قابل جستجو هستند…"
}
```

`usable = enabled && indexed` — همان شرطی که سرور هم اعمال می‌کند.

---

#### `GET /log/advanced/config`

**کاربرد:** عملگرها، فیلدهای پیشنهادی و متن هشدار جستجوی پیشرفته.

**ورودی:** ندارد.

**خروجی:** `{ enabled, maxResults, maxTimeMs, warning, operators[], suggestedFields[], resultFields[] }`

---

#### `POST /log/advanced`

**کاربرد:** جستجوی سنگین روی فیلدهای بدون ایندکس. عمداً `POST` است تا از
نوار آدرس، bookmark یا prefetch مرورگر اجرا نشود.

**ورودی (بدنه):**

```jsonc
{ "filters": [ { "field": "commandList.rollbackDescription",
                 "op": "contains", "value": "Exception" } ] }
```

عملگرها: `eq` · `ne` · `contains` · `prefix` · `gt` · `gte` · `lt` · `lte` · `exists` · `in`

**خروجی:**

```jsonc
{
  "hits": [ { "id": "…", "fields": { "_id": "…", "title": "…", "status": "…" } } ],
  "capped": false, "limit": 20,
  "columns": [ { "path": "_id", "label": "شناسه" } ],
  "notes": [ "نتایج به ترتیب زمانی نیستند — مرتب‌سازی روی فیلد بدون ایندکس سنگین است…" ],
  "mongoOperations": 1
}
```

**محافظ‌ها:** حداقل یک فیلتر، حداکثر ۱۰ فیلتر، سقف نتیجه، `maxTimeMS`،
و **بدون `sort`** — چون sort روی فیلد بی‌ایندکس یا حافظه را پر می‌کند یا
سرور را وادار به اسکن کامل.

---

### ۲) فراداده — `/api/v1/meta`

---

#### `GET /meta/ui`

**کاربرد:** آنچه UI برای ساختن خودش لازم دارد. فرانت‌اند هیچ برچسب یا رنگی
را از پیش نمی‌شناسد.

**خروجی:**

```jsonc
{
  "graph": { "layout": "horizontal-rtl", "colors": {...}, "showStartEnd": true,
             "startLabel": "درخواست کاربر", "endLabel": "پایان فرایند",
             "detailFields": [...] },
  "timezone": "Asia/Tehran",
  "maskingProfile": "secretsOnly",
  "counts": { "routingKeys": 23, "commandTypes": 42, "titles": 51, "statuses": 12 },
  "adminEnabled": true,             // پیوند «مدیریت» فقط وقتی نمایش داده می‌شود
  "warnings": [], "labelsPath": "config/config.json", "loadedAt": "…"
}
```

---

#### `GET /meta/health`

**کاربرد:** سلامت برای پایش خارجی. تنها پرس‌وجویش `estimatedDocumentCount`
است که از فراداده می‌آید و مجموعه را اسکن نمی‌کند.

**خروجی:** `{ readOnly{}, mongo{}, searchFields{}, configWarnings[], labelWarnings[], maskingProfile }`

| کلید مهم | مقدار درست |
|---|---|
| `readOnly.blockedWriteAttempts` | `0` |
| `mongo.reachable` | `true` |
| `searchFields.problems` | `[]` |

---

#### `POST /meta/config/reload`

**کاربرد:** بازخوانی هر دو فایل از دیسک، بدون ری‌استارت. برای وقتی فایل
از بیرون (استقرار، Ansible) عوض شده.

**خروجی:** `{ reloaded, loadedAt, routingKeys, commandTypes, titles, labelWarnings[], configWarnings[] }`

> اگر فایل جدید خراب باشد، نسخهٔ سالم قبلی حفظ می‌شود و دلیل در `warnings` می‌آید.

---

#### `POST /meta/indexes/inspect`

**کاربرد:** مقایسهٔ ادعای `indexed: true` در config با `listIndexes` واقعی.

**خروجی:** `{ reachable, existingIndexes[], fields[], problems[], note }`

---

### ۳) مدیریتی — `/api/v1/admin`

**همهٔ این مسیرها پشت `AdminSecurity` هستند:**

- توکن در هدر `Authorization: Bearer <token>` یا `X-Admin-Token`
- اگر `ADMIN_TOKEN` تنظیم نشده باشد → **۵۰۳** برای همهٔ مسیرها (fail-closed)
- توکن کوتاه‌تر از ۱۶ نویسه پذیرفته نمی‌شود
- مقایسه ثابت‌زمان (`MessageDigest.isEqual`)
- پس از ۵ تلاش ناموفق → **۴۲۹** به مدت ۵ دقیقه

| کد | معنا |
|---|---|
| `401` | توکن نامعتبر یا نیامده |
| `429` | تلاش ناموفق زیاد |
| `503` | API مدیریتی غیرفعال است |

---

#### `GET /admin/config`

**کاربرد:** متن خام `config.json` + نتیجهٔ اعتبارسنجی فعلی.

**خروجی:** `{ content, path, editable: true, validation{}, loadedAt }`

---

#### `GET /admin/config/base`

**کاربرد:** نمایش `config.yaml`. **فقط خواندن.**

**خروجی:** `{ content, path, editable: false, reason, warnings[], loadedAt }`

> رمز داخل آدرس اتصال پیش از نمایش به `********` تبدیل می‌شود.

---

#### `POST /admin/config/validate`

**کاربرد:** بررسی بدون ذخیره. UI با تأخیر ۵۰۰ میلی‌ثانیه صدایش می‌زند.

**ورودی:** `{ "content": "{ … }" }`

**خروجی:**

```jsonc
{
  "ok": false,
  "issues": [ { "severity": "error",   // error | warning
                "path": "routingKeyPatterns[3]",
                "message": "الگوی نامعتبر: …" } ],
  "summary": { "routingKeys": 23, "patterns": 10, "commandTypes": 42,
               "statuses": 12, "titles": 51, "fieldLabels": 31,
               "normalFields": 2, "usableSearchFields": 1,
               "maskingProfile": "secretsOnly", "advancedEnabled": true }
}
```

**قواعد اعتبارسنجی:**

| بررسی | شدت |
|---|---|
| JSON معتبر و ریشه یک شیء | error |
| حجم زیر ۲ مگابایت | error |
| هر الگو `match` و `label` دارد و regex معتبر است | error |
| الگوی `.*` که بقیه را بی‌اثر می‌کند | warning |
| `statusSeverity` فقط `success`/`error`/`unknown` | error |
| نام فیلد جستجو با `$` شروع نشود | error |
| `type` فیلد جستجو از فهرست مجاز | error |
| فیلد `enabled` ولی بدون `indexed` | warning |
| هیچ فیلد قابل استفاده‌ای نماند | error |
| `maxResults` خارج از ۱..۲۰۰ | warning |
| `maxTimeMs` بیش از ۳۰ ثانیه | warning |
| عملگر MongoDB بدون `$` | error |
| `maskingProfile` از فهرست مجاز | error |
| `maskingProfile: off` | warning |
| رنگ‌های گراف به شکل `#rrggbb` | error |

---

#### `POST /admin/config`

**کاربرد:** ذخیره و اعمال فوری.

**ورودی:** `{ "content": "{ … }" }`

**ترتیب کار:**

```
اعتبارسنجی → پشتیبان → نوشتن اتمیک (temp + move) → بارگذاری
   ↳ اگر پس از بارگذاری ناسالم بود، نسخهٔ قبلی خودکار برمی‌گردد
→ ثبت در تاریخچه
```

**خروجی ۲۰۰:** `{ saved: true, validation{}, backup: "config.json.20260901-101500.bak", summary{}, warnings[] }`

**خروجی ۴۰۰:** `{ saved: false, validation{}, message }` — فایل روی دیسک دست‌نخورده.

---

#### `GET /admin/config/versions`

**کاربرد:** فهرست پشتیبان‌ها (حداکثر ۲۰ نسخهٔ آخر نگه داشته می‌شود).

**خروجی:** `{ versions: [ { name, at, sizeBytes } ], directory }`

---

#### `GET /admin/config/versions/{name}`

**کاربرد:** دیدن محتوای یک نسخه پیش از بازگشت.

> `name` باید با `[A-Za-z0-9._-]{1,128}` بخواند و به `.bak` ختم شود؛
> مسیر نهایی هم باید داخل دایرکتوری پشتیبان بماند (ضد path traversal).

---

#### `POST /admin/config/versions/{name}/restore`

**کاربرد:** بازگشت به یک نسخه. خودش هم یک پشتیبان تازه می‌سازد، پس هیچ
نسخه‌ای از دست نمی‌رود.

**خروجی:** `{ restored, validation{}, backup }`

---

#### `GET /admin/status`

**کاربرد:** نمای کامل وضعیت — همان چیزی که پیش از تماس با تیم فنی باید دید.

**خروجی:** خروجی `/meta/health` به‌علاوهٔ:

```jsonc
{ "auditFile": "data/admin-audit.log",
  "runtime": { "heapUsedMb": 210, "heapMaxMb": 1024,
               "processors": 4, "javaVersion": "21.0.4" } }
```

---

#### `GET /admin/usage`

**کاربرد:** آمار استفاده و — مهم‌تر — **برچسب‌هایی که هنوز ترجمه ندارند**.

**خروجی:**

```jsonc
{
  "startedAt": "…", "uptimeSeconds": 86400,
  "endpoints": [ { "endpoint": "GET /api/v1/log/{id}", "calls": 1520,
                   "errors": 3, "p50Ms": 25, "p95Ms": 100, "maxMs": 812,
                   "lastCall": "…" } ],
  "unknownLabels": [ { "kind": "routingKey",       // routingKey|commandType|title
                       "value": "orchestration27.wallet.service.routing.key",
                       "count": 14, "firstSeen": "…", "lastSeen": "…",
                       "sampleLog": "68a1b2c3…" } ],
  "unknownOverflow": 0,
  "recentLookups": [ { "id": "…", "field": "_id", "found": true,
                       "tookMs": 31, "mongoOps": 1, "at": "…" } ],
  "slowest": [ { "endpoint": "…", "tookMs": 2400, "at": "…" } ],
  "mongoOperations": { "1": 1520 },   // باید همیشه فقط کلید «۱» باشد
  "totalCalls": 1600, "totalErrors": 3
}
```

**`unknownLabels` چطور پر می‌شود؟** هر بار `FlowGraphBuilder` گرهی می‌سازد که
`routingKey` یا `commandType` یا `title`اش ترجمه ندارد، همان‌جا ثبت می‌شود.
**هیچ پرس‌وجوی اضافه‌ای زده نمی‌شود** — فهرست از استفادهٔ واقعی ساخته می‌شود.

> همهٔ ساختارها کران‌دارند (۵۰۰ برچسب، ۶۴ مسیر، ۵۰ نمایش اخیر) و با
> ری‌استارت صفر می‌شوند. برای روند بلندمدت، actuator را به Prometheus بدهید.

---

#### `POST /admin/usage/unknown/clear`

**کاربرد:** پس از افزودن برچسب‌ها، صفر کردن فهرست.

**خروجی:** `{ cleared: 5 }`

---

#### `GET /admin/audit`

**کاربرد:** تاریخچهٔ تغییرات مدیریتی.

| ورودی | پیش‌فرض |
|---|---|
| `limit` | `100` (حداکثر ۲۰۰) |

**خروجی:** `{ entries: [ { at, action, actor, client, … } ], file }`

**رویدادها:** `config.save` · `config.save.rejected` · `config.restore` ·
`config.restore.rejected` · `config.reload` · `usage.unknown.clear`

`actor` از هدرهای `X-Forwarded-User` / `X-Remote-User` / `X-Auth-User` خوانده
می‌شود (اگر nginx احراز هویت سازمانی انجام داده باشد)، وگرنه `admin-token`.
**توکن هرگز ثبت نمی‌شود.**

---

#### `POST /admin/reload`

**کاربرد:** بازخوانی هر دو فایل، با ثبت در تاریخچه.

**خروجی:** `{ reloaded, labelWarnings[], configWarnings[], loadedAt }`

---

### ۴) actuator

| مسیر | کاربرد |
|---|---|
| `GET /actuator/health` | برای probe لیورنس/ردینس |
| `GET /actuator/metrics` | متریک‌های JVM و HTTP |
| `GET /actuator/info` | اطلاعات نسخه |

---

## قالب خطا

همهٔ خطاها از `ApiExceptionHandler` می‌گذرند و یک شکل دارند:

```jsonc
{ "message": "پرس‌وجو در زمان مجاز کامل نشد.",
  "hint": "بازهٔ زمانی را کوتاه‌تر کنید یا فیلتر دقیق‌تری اضافه کنید.",
  "traceId": "a1b2c3d4" }
```

| وضعیت | حالت |
|---|---|
| `400` | ورودی نامعتبر (فیلد غیرمجاز، فیلتر خالی، عملگر ناشناخته) |
| `401` / `429` / `503` | احراز هویت مدیریتی |
| `403` | نبود دسترسی خواندن روی MongoDB، یا تلاش نوشتن مسدودشده |
| `404` | لاگ پیدا نشد (با بدنهٔ کامل، نه خطای خالی) |
| `500` | خطای غیرمنتظره — با `traceId` برای پیگیری در لاگ سرور |
| `503` | ارتباط با MongoDB برقرار نشد |
| `504` | `maxTimeMS` تمام شد |
| `507` | پرس‌وجو از حافظهٔ مجاز MongoDB فراتر رفت |

پیام‌ها فارسی و برای پشتیبان قابل فهم‌اند؛ `hint` همیشه می‌گوید **چه کار کند**.

---

## مدل‌های داده

### گره گراف

```jsonc
{
  "id": "s2", "kind": "step",        // step | start | end
  "index": 2,
  "service": "سپرده",                 // برچسب فارسی
  "routingKey": "rabbitmq.yaghoot25.client.deposit.routing.key",
  "serviceSource": "exact",          // exact | pattern | fallback
  "title": "…", "rawTitle": "…",
  "commandType": "…", "rawCommandType": "…",
  "status": "بازگشت خورده", "rawStatus": "ROLL_BACKED",
  "severity": "error",               // success | error | unknown | marker
  "errorText": "ValidatorWSException",
  "startedAt": "2026-08-24T09:16:59Z",
  "detail": { "response": { "label": "خروجی پاسخ", "value": "…",
                            "json": "…", "type": "json-string",
                            "sizeBytes": 4210, "truncated": false } },
  "truncated": false
}
```

`serviceSource` مستقیماً به صفحهٔ مدیریت وصل است: مقدار `fallback` یعنی
این برچسب ترجمه ندارد و در فهرست «برچسب‌های ترجمه‌نشده» ظاهر می‌شود.

### خلاصهٔ گراف

```jsonc
{ "stepCount": 5, "successCount": 4, "errorCount": 1, "unknownCount": 0,
  "failedIndex": 3, "failedNodeId": "s3", "failedService": "احراز هویت",
  "failedErrorText": "AuthenticationWSException",
  "overallStatus": "بازگشت خورده", "overallSeverity": "error" }
```

اگر وضعیت کلی سند با وضعیت مراحل نخواند، **مراحل معتبرترند** و یک یادداشت
در `graph.notes` اضافه می‌شود.

---

← قبلی: [۵ — استقرار و عیب‌یابی](05-operations.md) · ادامه: [۷ — استقرار عملیاتی](07-deployment.md)
