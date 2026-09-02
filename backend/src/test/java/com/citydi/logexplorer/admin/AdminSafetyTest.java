package com.citydi.logexplorer.admin;

import com.citydi.logexplorer.config.AppConfig;
import com.citydi.logexplorer.config.ConfigLoader;
import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.labels.LabelConfigProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ایمنی بخش مدیریتی.
 *
 * این بخش تنها جایی است که سرویس *می‌نویسد* (روی فایل پیکربندی، نه
 * پایگاه داده) و تنها جایی است که می‌شود از آن پوشاندن دادهٔ حساس را
 * خاموش کرد. پس دو چیز باید ثابت شود:
 *
 *   ۱) بدون توکن، هیچ‌کس رد نمی‌شود — و نبودِ توکن یعنی درِ بسته، نه باز.
 *   ۲) پیکربندی خراب هرگز روی دیسک نمی‌رود، و اگر رفت برمی‌گردد.
 */
class AdminSafetyTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    /** یک config.json کوچک ولی معتبر — پایهٔ همهٔ تست‌های ویرایش */
    private static final String VALID_CONFIG = """
            {
              "_راهنما": "کلیدهای زیرخط‌دار توضیح‌اند و نادیده گرفته می‌شوند",
              "routingKeys": {
                "rabbitmq.yaghoot25.client.deposit.routing.key": "سپرده"
              },
              "routingKeyPatterns": [
                { "match": "\\\\.deposit\\\\.", "label": "سپرده" }
              ],
              "commandTypes": { "GET_CARD_DEPOSIT_LIST": "دریافت فهرست کارت و سپرده" },
              "statuses": { "COMPLETED": "موفق", "ROLL_BACKED": "بازگشت خورده" },
              "statusSeverity": { "COMPLETED": "success", "ROLL_BACKED": "error" },
              "titles": { "SEQ__GET_CARD_DEPOSIT_LIST": "دریافت فهرست کارت و سپرده" },
              "fieldLabels": { "commandList.response": "خروجی پاسخ" },
              "graph": {
                "layout": "horizontal-rtl",
                "source": "commandList",
                "colors": { "success": "#0ca30c", "error": "#d03b3b", "unknown": "#8a8f98" }
              },
              "search": {
                "normalFields": [
                  { "field": "_id", "label": "شناسهٔ لاگ", "type": "auto",
                    "indexed": true, "enabled": true, "default": true }
                ],
                "advanced": {
                  "enabled": true,
                  "maxResults": 20,
                  "maxTimeMs": 15000,
                  "warning": "این جستجو سنگین است.",
                  "operators": [ { "op": "eq", "label": "برابر است با", "mongo": "$eq" } ]
                }
              },
              "privacy": { "maskingProfile": "secretsOnly" }
            }
            """;

    // -------------------------------------------------- احراز هویت

    @Test
    @DisplayName("بدون ADMIN_TOKEN، کل API مدیریتی بسته است (fail-closed)")
    void missingTokenDisablesAdminApi() throws Exception {
        AdminSecurity security = new AdminSecurity("");
        assertFalse(security.isEnabled());

        Response response = call(security, "/api/v1/admin/status", "Bearer anything");
        assertEquals(503, response.status);
        assertFalse(response.chained, "درخواست نباید به کنترلر برسد");
        assertTrue(response.body.contains("غیرفعال"));
    }

    @Test
    @DisplayName("توکن کوتاه پذیرفته نمی‌شود")
    void shortTokenIsRejectedAtStartup() {
        assertFalse(new AdminSecurity("short").isEnabled(),
                "توکن کوتاه، توکن نیست؛ باید مثل نبودنش رفتار شود");
        assertTrue(new AdminSecurity(TOKEN).isEnabled());
    }

    @Test
    @DisplayName("توکن اشتباه، خالی یا نیامده رد می‌شود")
    void wrongTokenIsRejected() throws Exception {
        AdminSecurity security = new AdminSecurity(TOKEN);
        for (String header : new String[]{null, "", "Bearer ", "Bearer wrong",
                "Basic " + TOKEN, TOKEN}) {
            Response r = call(security, "/api/v1/admin/config", header);
            assertEquals(401, r.status, "هدر «" + header + "» نباید پذیرفته شود");
            assertFalse(r.chained);
        }
    }

    @Test
    @DisplayName("توکن درست، در هر دو هدر پذیرفته می‌شود")
    void correctTokenPasses() throws Exception {
        assertTrue(call(new AdminSecurity(TOKEN), "/api/v1/admin/config",
                "Bearer " + TOKEN).chained);

        HttpServletRequest request = request("/api/v1/admin/config", null);
        when(request.getHeader("X-Admin-Token")).thenReturn(TOKEN);
        Response r = run(new AdminSecurity(TOKEN), request);
        assertTrue(r.chained);
    }

    @Test
    @DisplayName("مسیرهای غیرمدیریتی اصلاً از این فیلتر رد نمی‌شوند")
    void nonAdminPathsAreUntouched() {
        AdminSecurity security = new AdminSecurity(TOKEN);
        assertTrue(security.shouldNotFilter(request("/api/v1/log/abc", null)));
        assertTrue(security.shouldNotFilter(request("/api/v1/meta/ui", null)));
        assertFalse(security.shouldNotFilter(request("/api/v1/admin/status", null)));
    }

    @Test
    @DisplayName("پس از چند تلاش ناموفق، درخواست‌های بعدی کند می‌شوند")
    void repeatedFailuresAreThrottled() throws Exception {
        AdminSecurity security = new AdminSecurity(TOKEN);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, call(security, "/api/v1/admin/config", "Bearer nope").status);
        }
        // ششمین تلاش — حتی با توکن درست — باید مسدود باشد
        assertEquals(429, call(security, "/api/v1/admin/config", "Bearer " + TOKEN).status,
                "بدون کندسازی، توکن با اسکریپت قابل حمله است");
    }

    // -------------------------------------------- اعتبارسنجی پیکربندی

    @Test
    @DisplayName("JSON نامعتبر رد می‌شود و روی دیسک نمی‌رود")
    void invalidJsonNeverReachesDisk(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir, VALID_CONFIG);
        String before = Files.readString(f.labels, StandardCharsets.UTF_8);

        ConfigEditorService.SaveResult result = f.editor.save("{ این JSON نیست }");

        assertFalse(result.saved());
        assertEquals(before, Files.readString(f.labels, StandardCharsets.UTF_8),
                "فایل نباید دست بخورد");
        assertTrue(result.validation().issues().stream()
                .anyMatch(i -> i.message().contains("JSON معتبر نیست")));
    }

    @Test
    @DisplayName("پیکربندی‌ای که جستجو را از کار می‌اندازد، خطاست")
    void configThatBreaksSearchIsRejected(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir, VALID_CONFIG);
        String broken = VALID_CONFIG.replace("\"indexed\": true", "\"indexed\": false");

        ConfigEditorService.Validation v = f.editor.validate(broken);
        assertFalse(v.ok());
        assertTrue(v.issues().stream().anyMatch(i -> "error".equals(i.severity())
                && i.message().contains("جستجوی عادی")));
    }

    @Test
    @DisplayName("الگوی regex نامعتبر و رنگ نادرست، خطای دقیق با مسیر می‌دهند")
    void preciseValidationErrors(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir, VALID_CONFIG);
        String bad = VALID_CONFIG
                .replace("\"\\\\.deposit\\\\.\"", "\"[unclosed\"")
                .replace("\"#0ca30c\"", "\"سبز\"");

        ConfigEditorService.Validation v = f.editor.validate(bad);
        assertFalse(v.ok());
        assertTrue(v.issues().stream().anyMatch(i -> i.path().startsWith("routingKeyPatterns")));
        assertTrue(v.issues().stream().anyMatch(i -> i.path().startsWith("graph.colors")));
    }

    @Test
    @DisplayName("خاموش‌کردن پوشاندن دادهٔ حساس مجاز است، ولی هشدار می‌دهد")
    void maskingOffIsAllowedButWarned(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir, VALID_CONFIG);
        ConfigEditorService.Validation v =
                f.editor.validate(VALID_CONFIG.replace("secretsOnly", "off"));

        assertTrue(v.ok(), "این یک تصمیم عملیاتی است، نه خطا");
        assertTrue(v.issues().stream().anyMatch(i -> "warning".equals(i.severity())
                && i.message().contains("رمز و توکن")));
        assertEquals("off", v.summary().maskingProfile());
    }

    @Test
    @DisplayName("مقدار نامعتبر برای شدت وضعیت رد می‌شود")
    void invalidSeverityIsRejected(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir, VALID_CONFIG);
        ConfigEditorService.Validation v =
                f.editor.validate(VALID_CONFIG.replace("\"success\"", "\"maybe\""));
        assertFalse(v.ok());
        assertTrue(v.issues().stream().anyMatch(i -> i.path().startsWith("statusSeverity")));
    }

    // ------------------------------------------------ ذخیره و بازگشت

    @Test
    @DisplayName("ذخیرهٔ سالم: پشتیبان می‌سازد و پیکربندی را اعمال می‌کند")
    void healthySaveCreatesBackup(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir, VALID_CONFIG);
        String updated = VALID_CONFIG.replace("\"سپرده\"", "\"سپرده (ویرایش‌شده)\"");

        ConfigEditorService.SaveResult result = f.editor.save(updated);

        assertTrue(result.saved());
        assertNotNull(result.backup(), "پشتیبان قبل از هر تغییر الزامی است");
        assertEquals(updated, Files.readString(f.labels, StandardCharsets.UTF_8));
        assertEquals(1, f.editor.versions().size());
        assertTrue(Files.readString(dir.resolve("backups").resolve(result.backup()),
                StandardCharsets.UTF_8).contains("\"سپرده\""),
                "پشتیبان باید *نسخهٔ قبلی* باشد، نه نسخهٔ تازه");
    }

    @Test
    @DisplayName("بازگشت به نسخهٔ قبلی، خودش هم پشتیبان می‌سازد")
    void restoreAlsoBacksUp(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir, VALID_CONFIG);
        f.editor.save(VALID_CONFIG.replace("\"سپرده\"", "\"نسخهٔ دوم\""));
        String backupName = f.editor.versions().get(0).name();

        ConfigEditorService.SaveResult restored = f.editor.restore(backupName);

        assertTrue(restored.saved());
        assertTrue(Files.readString(f.labels, StandardCharsets.UTF_8).contains("\"سپرده\""));
        assertEquals(2, f.editor.versions().size(),
                "هیچ نسخه‌ای نباید با بازگشت از دست برود");
    }

    @Test
    @DisplayName("نام نسخه نمی‌تواند از دایرکتوری پشتیبان بیرون بزند")
    void versionNameCannotEscapeDirectory(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir, VALID_CONFIG);
        for (String evil : List.of("../../etc/passwd", "..%2Fconfig.json", "/etc/shadow",
                "config.json", "x.bak/../../../y.bak", "")) {
            assertThrows(IOException.class, () -> f.editor.readVersion(evil),
                    "نام «" + evil + "» نباید پذیرفته شود");
        }
    }

    @Test
    @DisplayName("رمز اتصال پیش از نمایش config.yaml پنهان می‌شود")
    void connectionPasswordIsRedacted(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("config.yaml");
        Files.writeString(yaml, """
                mongo:
                  uri: mongodb://log_viewer_ro:SuperSecret123@db-1.internal:27017/saga
                """, StandardCharsets.UTF_8);
        Fixture f = fixture(dir, VALID_CONFIG, yaml);

        String shown = f.editor.readBaseConfigRedacted();

        assertFalse(shown.contains("SuperSecret123"),
                "کسی که فقط می‌خواهد محدودیت‌ها را ببیند، نباید رمز را هم ببیند");
        assertTrue(shown.contains("log_viewer_ro"), "نام کاربر برای عیب‌یابی لازم است");
        assertTrue(shown.contains("********"));
    }

    // --------------------------------------------------- آمار استفاده

    @Test
    @DisplayName("برچسب‌های ترجمه‌نشده جمع می‌شوند و سقف حافظه دارند")
    void unknownLabelsAreCollectedAndBounded() {
        UsageRegistry registry = new UsageRegistry();
        registry.recordUnknownLabel("routingKey", "orchestration27.wallet", "log-1");
        registry.recordUnknownLabel("routingKey", "orchestration27.wallet", "log-2");
        registry.recordUnknownLabel("commandType", "WALLET_CHARGE", "log-1");

        List<UsageRegistry.UnknownView> unknown = registry.snapshot().unknownLabels();
        assertEquals(2, unknown.size());
        assertEquals(2, unknown.get(0).count(), "پرتکرارترین باید اول باشد");
        assertEquals("log-1", unknown.get(0).sampleLog(), "نمونهٔ اول نگه داشته می‌شود");

        for (int i = 0; i < 2000; i++) {
            registry.recordUnknownLabel("routingKey", "svc-" + i, null);
        }
        assertTrue(registry.snapshot().unknownLabels().size() <= 500, "حافظه نباید رشد کند");
        assertTrue(registry.snapshot().unknownOverflow() > 0, "سرریز باید گزارش شود");

        registry.clearUnknownLabels();
        assertTrue(registry.snapshot().unknownLabels().isEmpty());
    }

    @Test
    @DisplayName("آمار مسیرها شمارش و صدک می‌دهد و ورودی بی‌کران نمی‌پذیرد")
    void endpointStatsAreBounded() {
        UsageRegistry registry = new UsageRegistry();
        for (int i = 0; i < 100; i++) {
            registry.recordCall("GET /api/v1/log/{id}", i, i % 10 == 0);
        }
        UsageRegistry.EndpointView view = registry.snapshot().endpoints().get(0);
        assertEquals(100, view.calls());
        assertEquals(10, view.errors());
        assertTrue(view.p95Ms() >= view.p50Ms());
        assertEquals(99, view.maxMs());

        for (int i = 0; i < 500; i++) {
            registry.recordCall("GET /random/" + i, 1, false);
        }
        assertTrue(registry.snapshot().endpoints().size() <= 64);
    }

    @Test
    @DisplayName("مسیرهای شناسه‌دار برای آمار نرمال می‌شوند")
    void endpointPathsAreNormalised() {
        assertEquals("/api/v1/log/{id}", UsageFilter.normalize("/api/v1/log/68a1b2c3d4e5f607"));
        assertEquals("/api/v1/log/search/fields",
                UsageFilter.normalize("/api/v1/log/search/fields"));
        assertEquals("/api/v1/log/advanced", UsageFilter.normalize("/api/v1/log/advanced"));
        assertEquals("/api/v1/admin/config/versions/{name}",
                UsageFilter.normalize("/api/v1/admin/config/versions/config.json.2026.bak"));
    }

    // ----------------------------------------------------- تاریخچه

    @Test
    @DisplayName("هر تغییر در تاریخچه می‌ماند و توکن هرگز ثبت نمی‌شود")
    void auditRecordsChangesWithoutSecrets(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("audit.log");
        AuditLog audit = new AuditLog(file.toString());

        audit.record("config.save", "ali", "10.0.0.7",
                java.util.Map.of("backup", "config.json.20260901-101500.bak"));
        audit.record("config.restore", "ali", "10.0.0.7", java.util.Map.of("version", "x.bak"));

        List<java.util.Map<String, Object>> tail = audit.tail(10);
        assertEquals(2, tail.size());
        assertEquals("config.restore", tail.get(0).get("action"), "تازه‌ترین اول");

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(written.contains(TOKEN), "توکن نباید هرگز در تاریخچه بیفتد");
        assertEquals(2, written.lines().count(), "قالب JSONL: هر رویداد یک سطر");

        // پس از ری‌استارت، تاریخچه از فایل برمی‌گردد
        assertEquals(2, new AuditLog(file.toString()).tail(10).size());
    }

    @Test
    @DisplayName("نبودِ فایل تاریخچه، سرویس را زمین نمی‌زند")
    void auditSurvivesUnwritablePath() {
        assertDoesNotThrow(() -> {
            AuditLog audit = new AuditLog("/proc/definitely-not-writable/audit.log");
            audit.record("config.save", "x", "y", java.util.Map.of());
            assertEquals(1, audit.tail(5).size(), "دست‌کم در حافظه باید بماند");
        });
    }

    // ------------------------------------------------------------- کمکی

    private record Fixture(ConfigEditorService editor, Path labels) {
    }

    private static Fixture fixture(Path dir, String content) throws IOException {
        return fixture(dir, content, null);
    }

    private static Fixture fixture(Path dir, String content, Path yaml) throws IOException {
        Path labels = dir.resolve("config.json");
        Files.writeString(labels, content, StandardCharsets.UTF_8);

        LabelConfigProvider labelProvider = new LabelConfigProvider(labels.toString());
        Path yamlPath = yaml == null ? dir.resolve("missing.yaml") : yaml;
        AppConfig appConfig = ConfigLoader.loadFromStream(
                new ByteArrayInputStream("time: { candidates: [ts] }".getBytes(StandardCharsets.UTF_8)));
        ConfigProvider configProvider = new ConfigProvider(yamlPath.toString()) {
            @Override
            public AppConfig get() {
                return appConfig;
            }
        };
        return new Fixture(new ConfigEditorService(labelProvider, configProvider), labels);
    }

    private record Response(int status, boolean chained, String body) {
    }

    private static HttpServletRequest request(String uri, String authorization) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");
        when(request.getHeader(anyString())).thenReturn(null);
        if (authorization != null) {
            when(request.getHeader("Authorization")).thenReturn(authorization);
        }
        return request;
    }

    private static Response call(AdminSecurity security, String uri, String authorization)
            throws Exception {
        return run(security, request(uri, authorization));
    }

    private static Response run(AdminSecurity security, HttpServletRequest request)
            throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        int[] status = {200};
        org.mockito.Mockito.doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(response).setStatus(org.mockito.ArgumentMatchers.anyInt());

        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chained.set(true);
        security.doFilterInternal(request, response, chain);
        return new Response(status[0], chained.get(), body.toString());
    }
}
