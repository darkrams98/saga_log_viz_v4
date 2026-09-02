package com.citydi.logexplorer.admin;

import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.labels.LabelConfig;
import com.citydi.logexplorer.labels.LabelConfigProvider;
import com.citydi.logexplorer.mongo.IndexInspector;
import com.citydi.logexplorer.mongo.LogCollection;
import com.citydi.logexplorer.mongo.OperationCounter;
import com.citydi.logexplorer.mongo.ReadOnlyGuard;
import com.citydi.logexplorer.service.MongoErrors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API صفحهٔ مدیریتی.
 *
 * همهٔ مسیرهای اینجا پشت {@link AdminSecurity} هستند و اگر توکن مدیریتی
 * تنظیم نشده باشد اصلاً پاسخ نمی‌دهند.
 *
 * محدودهٔ کار عمداً باریک است:
 *   • `config.json` (برچسب‌ها، گراف، جستجو، حریم خصوصی) → خواندن و نوشتن
 *   • `config.yaml` (اتصال، محدودیت‌ها)                  → فقط خواندن، با رمز پنهان
 *   • سلامت، آمار، برچسب‌های گم‌شده، تاریخچه            → فقط خواندن
 *
 * هیچ مسیری اینجا به MongoDB نمی‌نویسد و هیچ‌کدام کل مجموعه را نمی‌خوانند.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ConfigEditorService editor;
    private final UsageRegistry usage;
    private final AuditLog audit;
    private final LabelConfigProvider labels;
    private final ConfigProvider config;
    private final IndexInspector indexInspector;
    private final LogCollection collection;
    private final ReadOnlyGuard guard;

    public AdminController(ConfigEditorService editor, UsageRegistry usage, AuditLog audit,
                           LabelConfigProvider labels, ConfigProvider config,
                           IndexInspector indexInspector, LogCollection collection,
                           ReadOnlyGuard guard) {
        this.editor = editor;
        this.usage = usage;
        this.audit = audit;
        this.labels = labels;
        this.config = config;
        this.indexInspector = indexInspector;
        this.collection = collection;
        this.guard = guard;
    }

    // ------------------------------------------------------- پیکربندی

    /** متن خام config.json + خلاصهٔ وضعیت فعلی */
    @GetMapping("/config")
    public Map<String, Object> readConfig() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", editor.readLabelsText());
        out.put("path", editor.labelsPath().toString());
        out.put("editable", true);
        out.put("validation", editor.validate(editor.readLabelsText()));
        out.put("loadedAt", labels.loadedAt().toString());
        return out;
    }

    /**
     * config.yaml — فقط برای دیدن.
     *
     * چرا قابل ویرایش نیست؟ چون آدرس اتصال MongoDB داخلش است. ویرایشش از
     * مرورگر یعنی کسی که به این صفحه دسترسی دارد می‌تواند سرویس را به
     * پایگاه دادهٔ دیگری وصل کند. رمز هم پیش از نمایش پنهان می‌شود.
     */
    @GetMapping("/config/base")
    public Map<String, Object> readBaseConfig() throws IOException {
        AppConfig current = config.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", editor.readBaseConfigRedacted());
        out.put("path", config.path().toString());
        out.put("editable", false);
        out.put("reason", "این فایل آدرس اتصال پایگاه داده را دارد و فقط از طریق فایل و "
                + "متغیرهای محیطی تغییر می‌کند، نه از مرورگر.");
        out.put("warnings", current.warnings);
        out.put("loadedAt", config.loadedAt().toString());
        return out;
    }

    public record ConfigBody(String content) {
    }

    /** اعتبارسنجی بدون ذخیره — دکمهٔ «بررسی» در UI */
    @PostMapping("/config/validate")
    public ConfigEditorService.Validation validateConfig(@RequestBody ConfigBody body) {
        return editor.validate(body == null ? null : body.content());
    }

    /** ذخیره: اعتبارسنجی → پشتیبان → نوشتن اتمیک → بارگذاری → ثبت در تاریخچه */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody ConfigBody body,
                                                          HttpServletRequest request)
            throws IOException {
        String before = editor.readLabelsText();
        ConfigEditorService.SaveResult result = editor.save(body == null ? null : body.content());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", result.saved());
        out.put("validation", result.validation());
        out.put("backup", result.backup());
        out.put("warnings", result.warnings());

        if (result.saved()) {
            LabelConfig applied = labels.get();
            audit.record("config.save", actor(request), client(request), Map.of(
                    "backup", String.valueOf(result.backup()),
                    "bytesBefore", before.length(),
                    "bytesAfter", body == null || body.content() == null ? 0 : body.content().length(),
                    "maskingProfile", applied.maskingProfile,
                    "routingKeys", applied.routingKeys.size(),
                    "commandTypes", applied.commandTypes.size()));
            out.put("summary", result.validation().summary());
            return ResponseEntity.ok(out);
        }

        audit.record("config.save.rejected", actor(request), client(request), Map.of(
                "errors", result.validation().issues().stream()
                        .filter(i -> "error".equals(i.severity()))
                        .map(ConfigEditorService.ValidationIssue::message).toList()));
        out.put("message", "پیکربندی ذخیره نشد؛ خطاهای اعتبارسنجی را ببینید.");
        return ResponseEntity.badRequest().body(out);
    }

    // ---------------------------------------------------------- نسخه‌ها

    @GetMapping("/config/versions")
    public Map<String, Object> versions() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", editor.versions());
        out.put("directory", editor.backupDir().toString());
        return out;
    }

    @GetMapping("/config/versions/{name}")
    public Map<String, Object> readVersion(@PathVariable String name) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("content", editor.readVersion(name));
        return out;
    }

    /** بازگشت به یک نسخهٔ قبلی — خودش هم یک پشتیبان تازه می‌سازد */
    @PostMapping("/config/versions/{name}/restore")
    public ResponseEntity<Map<String, Object>> restore(@PathVariable String name,
                                                       HttpServletRequest request)
            throws IOException {
        ConfigEditorService.SaveResult result = editor.restore(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("restored", result.saved());
        out.put("validation", result.validation());
        out.put("backup", result.backup());

        audit.record(result.saved() ? "config.restore" : "config.restore.rejected",
                actor(request), client(request), Map.of("version", name));

        return result.saved() ? ResponseEntity.ok(out) : ResponseEntity.badRequest().body(out);
    }

    // ------------------------------------------------- سلامت و مانیتورینگ

    /**
     * نمای کامل وضعیت — همان چیزی که پیش از تماس با تیم فنی باید دید.
     *
     * تنها پرس‌وجویی که می‌زند `estimatedDocumentCount` است که از فراداده
     * می‌آید و مجموعه را اسکن نمی‌کند.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        OperationCounter.start();
        try {
            Map<String, Object> out = new LinkedHashMap<>();

            Map<String, Object> readOnly = new LinkedHashMap<>();
            readOnly.put("enforced", config.get().mongo.enforceReadOnly());
            readOnly.put("blockedWriteAttempts", guard.blockedCount());
            readOnly.put("readCommandsExecuted", guard.allowedCount());
            readOnly.put("clean", guard.isClean());
            readOnly.put("recentViolations", guard.recentViolations());
            out.put("readOnly", readOnly);

            Map<String, Object> mongo = new LinkedHashMap<>();
            try {
                mongo.putAll(collection.connectionInfo());
                mongo.put("estimatedDocuments", collection.estimatedTotal());
                mongo.put("reachable", true);
            } catch (Exception e) {
                mongo.put("reachable", false);
                mongo.put("error", MongoErrors.translate(e).getMessage());
            }
            out.put("mongo", mongo);

            out.put("searchFields", indexInspector.asMap());
            out.put("configWarnings", config.get().warnings);
            out.put("labelWarnings", labels.get().warnings);
            out.put("maskingProfile", labels.get().maskingProfile);
            out.put("auditFile", audit.path().toString());

            Map<String, Object> runtime = new LinkedHashMap<>();
            Runtime r = Runtime.getRuntime();
            runtime.put("heapUsedMb", (r.totalMemory() - r.freeMemory()) / 1_048_576);
            runtime.put("heapMaxMb", r.maxMemory() / 1_048_576);
            runtime.put("processors", r.availableProcessors());
            runtime.put("javaVersion", System.getProperty("java.version"));
            out.put("runtime", runtime);

            return out;
        } finally {
            OperationCounter.stop();
        }
    }

    /** آمار استفاده، برچسب‌های ترجمه‌نشده، آخرین جستجوها */
    @GetMapping("/usage")
    public UsageRegistry.Snapshot usage() {
        return usage.snapshot();
    }

    /** پس از افزودن برچسب‌ها، فهرست گم‌شده‌ها را صفر کن */
    @PostMapping("/usage/unknown/clear")
    public Map<String, Object> clearUnknown(HttpServletRequest request) {
        int before = usage.snapshot().unknownLabels().size();
        usage.clearUnknownLabels();
        audit.record("usage.unknown.clear", actor(request), client(request),
                Map.of("cleared", before));
        return Map.of("cleared", before);
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", audit.tail(limit));
        out.put("file", audit.path().toString());
        return out;
    }

    /** بازخوانی هر دو فایل از دیسک، بدون ویرایش — برای وقتی فایل از بیرون عوض شده */
    @PostMapping("/reload")
    public Map<String, Object> reload(HttpServletRequest request) {
        AppConfig base = config.reload();
        LabelConfig applied = labels.reload();
        audit.record("config.reload", actor(request), client(request),
                Map.of("routingKeys", applied.routingKeys.size()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reloaded", true);
        out.put("labelWarnings", applied.warnings);
        out.put("configWarnings", base.warnings);
        out.put("loadedAt", labels.loadedAt().toString());
        return out;
    }

    // ------------------------------------------------------------- کمکی

    /**
     * چه کسی این کار را کرد.
     *
     * با توکن مشترک، هویت فردی نداریم. ولی اگر nginx احراز هویت سازمانی
     * انجام داده باشد، نام کاربر را در هدر می‌گذارد و همان ثبت می‌شود.
     * فهرست هدرها عمداً کوتاه و ثابت است.
     */
    private static String actor(HttpServletRequest request) {
        for (String header : List.of("X-Forwarded-User", "X-Remote-User", "X-Auth-User")) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && value.length() <= 128) {
                return value.trim();
            }
        }
        return "admin-token";
    }

    private static String client(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
