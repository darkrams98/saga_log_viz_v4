package com.citydi.logexplorer.admin;

import com.citydi.logexplorer.config.ConfigProvider;
import com.citydi.logexplorer.labels.LabelConfig;
import com.citydi.logexplorer.labels.LabelConfigLoader;
import com.citydi.logexplorer.labels.LabelConfigProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ویرایش امن `config.json` از صفحهٔ مدیریتی.
 *
 * چهار قاعده که ترتیبشان مهم است:
 *
 *   ۱) **اول اعتبارسنجی، بعد نوشتن.** فایل خراب هرگز روی دیسک نمی‌رود.
 *   ۲) **پشتیبان قبل از هر تغییر.** نسخهٔ قبلی همیشه یک کلیک دورتر است.
 *   ۳) **نوشتن اتمیک.** فایل موقت + جابه‌جایی، تا اگر برق رفت نیمه‌کاره نماند.
 *   ۴) **بازگشت خودکار.** اگر پس از نوشتن، بارگذاری شکست بخورد، نسخهٔ
 *      قبلی برمی‌گردد. سرویسِ در حال کار نباید قربانی یک ویرایش شود.
 *
 * `config.yaml` عمداً **فقط خواندنی** است: آدرس اتصال MongoDB داخلش است و
 * ویرایشش از مرورگر یعنی کسی بتواند سرویس را به پایگاه دادهٔ دیگری وصل کند.
 * تغییرش کار تیم عملیات است، روی فایل و با استقرار.
 */
@Service
public class ConfigEditorService {

    private static final Logger log = LoggerFactory.getLogger(ConfigEditorService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final int MAX_BACKUPS = 20;
    private static final long MAX_SIZE_BYTES = 2_000_000L;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** رمز داخل آدرس اتصال، برای نمایش پنهان می‌شود */
    private static final Pattern CREDENTIALS = Pattern.compile("://([^:/@\\s]+):([^@/\\s]+)@");

    private final LabelConfigProvider labels;
    private final ConfigProvider baseConfig;

    public ConfigEditorService(LabelConfigProvider labels, ConfigProvider baseConfig) {
        this.labels = labels;
        this.baseConfig = baseConfig;
    }

    // ------------------------------------------------------------ مدل‌ها

    public record ValidationIssue(String severity, String path, String message) {
    }

    /**
     * @param ok        آیا قابل ذخیره است
     * @param issues    خطاها (error) و هشدارها (warning)
     * @param summary   شمار برچسب‌ها پس از اعمال — برای دیدن تأثیر تغییر
     */
    public record Validation(boolean ok, List<ValidationIssue> issues, Summary summary) {
    }

    public record Summary(int routingKeys, int patterns, int commandTypes, int statuses,
                          int titles, int fieldLabels, int normalFields, int usableSearchFields,
                          String maskingProfile, boolean advancedEnabled) {
    }

    public record Version(String name, String at, long sizeBytes) {
    }

    public record SaveResult(boolean saved, Validation validation, String backup,
                             List<String> warnings) {
    }

    // -------------------------------------------------------------- خواندن

    /** متن خام `config.json` روی دیسک */
    public String readLabelsText() throws IOException {
        Path path = labels.path();
        if (!Files.isReadable(path)) {
            return "{}";
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public Path labelsPath() {
        return labels.path();
    }

    /**
     * `config.yaml` فقط برای دیدن — با پنهان‌سازی رمز اتصال.
     *
     * بدون این پنهان‌سازی، هر کسی که به صفحهٔ مدیریتی دسترسی دارد رمز
     * کاربر پایگاه داده را هم می‌بیند، حتی اگر فقط بخواهد محدودیت‌ها را
     * ببیند.
     */
    public String readBaseConfigRedacted() throws IOException {
        Path path = baseConfig.path();
        if (!Files.isReadable(path)) {
            return "# فایل پیدا نشد: " + path;
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Matcher m = CREDENTIALS.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("://" + m.group(1) + ":********@"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------- اعتبارسنجی

    /**
     * بررسی کامل بدون دست‌زدن به دیسک.
     *
     * سه لایه: JSON معتبر باشد → با loader واقعی ساخته شود → قواعد معنایی
     * (مثل «فیلد جستجو نمی‌تواند فعال باشد ولی ایندکس نداشته باشد»).
     */
    public Validation validate(String text) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (text == null || text.isBlank()) {
            issues.add(new ValidationIssue("error", "", "محتوا خالی است."));
            return new Validation(false, issues, null);
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_SIZE_BYTES) {
            issues.add(new ValidationIssue("error", "",
                    "حجم فایل از " + (MAX_SIZE_BYTES / 1000) + " کیلوبایت بیشتر است."));
            return new Validation(false, issues, null);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (Exception e) {
            issues.add(new ValidationIssue("error", "", "JSON معتبر نیست: " + shortMessage(e)));
            return new Validation(false, issues, null);
        }
        if (root == null || !root.isObject()) {
            issues.add(new ValidationIssue("error", "", "ریشهٔ فایل باید یک شیء JSON باشد."));
            return new Validation(false, issues, null);
        }

        LabelConfig parsed = LabelConfigLoader.loadFromStream(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        for (String warning : parsed.warnings) {
            issues.add(new ValidationIssue("warning", "", warning));
        }

        checkRegexes(root, issues);
        checkSeverity(root, issues);
        checkSearch(root, parsed, issues);
        checkPrivacy(root, issues);
        checkGraph(root, issues);

        boolean ok = issues.stream().noneMatch(i -> "error".equals(i.severity()));
        return new Validation(ok, List.copyOf(issues), summaryOf(parsed));
    }

    private void checkRegexes(JsonNode root, List<ValidationIssue> issues) {
        JsonNode patterns = root.get("routingKeyPatterns");
        if (patterns == null || !patterns.isArray()) {
            return;
        }
        for (int i = 0; i < patterns.size(); i++) {
            JsonNode item = patterns.get(i);
            String path = "routingKeyPatterns[" + i + "]";
            String match = text(item, "match");
            String label = text(item, "label");
            if (match == null || label == null) {
                issues.add(new ValidationIssue("error", path,
                        "هر الگو باید هم «match» داشته باشد هم «label»."));
                continue;
            }
            try {
                Pattern.compile(match);
            } catch (Exception e) {
                issues.add(new ValidationIssue("error", path,
                        "الگوی نامعتبر: " + shortMessage(e)));
            }
            // الگوی «همه‌چیز» بی‌خطر نیست: بقیهٔ الگوها را بی‌اثر می‌کند
            if (".*".equals(match) || "".equals(match)) {
                issues.add(new ValidationIssue("warning", path,
                        "این الگو با همه‌چیز تطبیق می‌کند و الگوهای بعدی را بی‌اثر می‌کند."));
            }
        }
    }

    private void checkSeverity(JsonNode root, List<ValidationIssue> issues) {
        JsonNode severity = root.get("statusSeverity");
        if (severity == null || !severity.isObject()) {
            return;
        }
        severity.fields().forEachRemaining(entry -> {
            if (entry.getKey().startsWith("_")) {
                return;
            }
            String value = entry.getValue().asText("");
            if (!List.of("success", "error", "unknown").contains(value)) {
                issues.add(new ValidationIssue("error", "statusSeverity." + entry.getKey(),
                        "مقدار باید یکی از success، error یا unknown باشد (مقدار فعلی: «"
                                + value + "»)."));
            }
        });
    }

    private void checkSearch(JsonNode root, LabelConfig parsed, List<ValidationIssue> issues) {
        JsonNode fields = root.at("/search/normalFields");
        if (fields.isArray()) {
            for (int i = 0; i < fields.size(); i++) {
                JsonNode f = fields.get(i);
                String path = "search.normalFields[" + i + "]";
                String name = text(f, "field");
                if (name == null) {
                    issues.add(new ValidationIssue("error", path, "«field» الزامی است."));
                    continue;
                }
                if (name.startsWith("$")) {
                    issues.add(new ValidationIssue("error", path,
                            "نام فیلد نمی‌تواند با $ شروع شود."));
                }
                boolean enabled = f.path("enabled").asBoolean(true);
                boolean indexed = f.path("indexed").asBoolean(false);
                if (enabled && !indexed) {
                    issues.add(new ValidationIssue("warning", path,
                            "فیلد «" + name + "» فعال است ولی indexed نیست، پس در جستجوی "
                            + "عادی کار نمی‌کند. اول ایندکس بسازید."));
                }
                String type = f.path("type").asText("string");
                if (!List.of("auto", "string", "objectId", "number").contains(type)) {
                    issues.add(new ValidationIssue("error", path + ".type",
                            "نوع باید auto، string، objectId یا number باشد."));
                }
            }
        }
        if (parsed.search.defaultField() == null) {
            issues.add(new ValidationIssue("error", "search.normalFields",
                    "هیچ فیلد فعال و ایندکس‌شده‌ای نمانده — جستجوی عادی از کار می‌افتد."));
        }

        JsonNode advanced = root.at("/search/advanced");
        if (advanced.isObject()) {
            int max = advanced.path("maxResults").asInt(20);
            if (max < 1 || max > 200) {
                issues.add(new ValidationIssue("warning", "search.advanced.maxResults",
                        "مقدار بین ۱ تا ۲۰۰ محدود می‌شود (مقدار فعلی: " + max + ")."));
            }
            long time = advanced.path("maxTimeMs").asLong(15000);
            if (time > 30000) {
                issues.add(new ValidationIssue("warning", "search.advanced.maxTimeMs",
                        "سقف زمان بیش از ۳۰ ثانیه یعنی یک جستجوی اشتباه می‌تواند نیم دقیقه "
                        + "به پایگاه داده فشار بیاورد."));
            }
            for (JsonNode op : advanced.path("operators")) {
                String mongo = text(op, "mongo");
                if (mongo != null && !mongo.startsWith("$")) {
                    issues.add(new ValidationIssue("error", "search.advanced.operators",
                            "عملگر MongoDB باید با $ شروع شود: «" + mongo + "»"));
                }
            }
        }
    }

    private void checkPrivacy(JsonNode root, List<ValidationIssue> issues) {
        String profile = root.at("/privacy/maskingProfile").asText("secretsOnly");
        if (!List.of("secretsOnly", "partial", "off").contains(profile)) {
            issues.add(new ValidationIssue("error", "privacy.maskingProfile",
                    "مقدار باید secretsOnly، partial یا off باشد."));
        }
        if ("off".equals(profile)) {
            issues.add(new ValidationIssue("warning", "privacy.maskingProfile",
                    "با «off» حتی رمز و توکن هم پوشانده نمی‌شوند. فقط در محیط کاملاً "
                    + "قابل اعتماد استفاده کنید — این تغییر در تاریخچه ثبت می‌شود."));
        }
    }

    private void checkGraph(JsonNode root, List<ValidationIssue> issues) {
        JsonNode graph = root.get("graph");
        if (graph == null || !graph.isObject()) {
            return;
        }
        String source = graph.path("source").asText("");
        if (!source.isEmpty() && source.startsWith("$")) {
            issues.add(new ValidationIssue("error", "graph.source",
                    "مسیر نمی‌تواند با $ شروع شود."));
        }
        JsonNode colors = graph.get("colors");
        if (colors != null && colors.isObject()) {
            colors.fields().forEachRemaining(entry -> {
                String value = entry.getValue().asText("");
                if (!value.matches("#[0-9a-fA-F]{3,8}")) {
                    issues.add(new ValidationIssue("error", "graph.colors." + entry.getKey(),
                            "رنگ باید به شکل #rrggbb باشد (مقدار فعلی: «" + value + "»)."));
                }
            });
        }
    }

    private static Summary summaryOf(LabelConfig c) {
        int usable = (int) c.search.normalFields().stream()
                .filter(f -> f.enabled() && f.indexed()).count();
        return new Summary(c.routingKeys.size(), c.routingKeyPatterns.size(),
                c.commandTypes.size(), c.statuses.size(), c.titles.size(),
                c.fieldLabels.size(), c.search.normalFields().size(), usable,
                c.maskingProfile, c.search.advanced().enabled());
    }

    // ---------------------------------------------------------- نوشتن

    /**
     * ذخیره با پشتیبان، نوشتن اتمیک و بازگشت خودکار در صورت شکست.
     */
    public synchronized SaveResult save(String text) throws IOException {
        Validation validation = validate(text);
        if (!validation.ok()) {
            return new SaveResult(false, validation, null, List.of());
        }

        Path target = labels.path().toAbsolutePath();
        Path dir = target.getParent();
        if (dir == null) {
            throw new IOException("مسیر فایل پیکربندی معتبر نیست: " + target);
        }
        Files.createDirectories(dir);

        String backupName = null;
        Path backup = null;
        if (Files.exists(target)) {
            backupName = target.getFileName() + "." + STAMP.format(Instant.now()) + ".bak";
            backup = backupDir().resolve(backupName);
            Files.createDirectories(backupDir());
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        }

        // نوشتن اتمیک: فایل موقت در همان دایرکتوری، بعد جابه‌جایی
        Path temp = Files.createTempFile(dir, ".config-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        LabelConfig applied = labels.reload();
        List<String> warnings = new ArrayList<>(applied.warnings);

        // اگر بارگذاری واقعی شکست خورد، برگرد — سرویس نباید قربانی شود
        boolean healthy = applied.search.defaultField() != null;
        if (!healthy && backup != null) {
            Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
            labels.reload();
            log.error("پیکربندی جدید سالم نبود؛ نسخهٔ قبلی بازگردانده شد.");
            List<ValidationIssue> issues = new ArrayList<>(validation.issues());
            issues.add(new ValidationIssue("error", "",
                    "پیکربندی جدید پس از اعمال سالم نبود و نسخهٔ قبلی بازگردانده شد."));
            return new SaveResult(false,
                    new Validation(false, List.copyOf(issues), validation.summary()),
                    backupName, warnings);
        }

        pruneBackups();
        log.info("پیکربندی برچسب‌ها ذخیره شد (پشتیبان: {})", backupName);
        return new SaveResult(true, validation, backupName, warnings);
    }

    // ---------------------------------------------------------- نسخه‌ها

    public Path backupDir() {
        Path parent = labels.path().toAbsolutePath().getParent();
        return (parent == null ? Path.of(".") : parent).resolve("backups");
    }

    public List<Version> versions() {
        Path dir = backupDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".bak"))
                    .map(p -> {
                        try {
                            return new Version(p.getFileName().toString(),
                                    Files.getLastModifiedTime(p).toInstant().toString(),
                                    Files.size(p));
                        } catch (IOException e) {
                            return new Version(p.getFileName().toString(), "", 0);
                        }
                    })
                    .sorted(Comparator.comparing(Version::name).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("خواندن فهرست نسخه‌ها ناموفق بود: {}", e.toString());
            return List.of();
        }
    }

    public String readVersion(String name) throws IOException {
        Path file = resolveBackup(name);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** بازگشت به یک نسخهٔ قبلی — خودش هم یک پشتیبان تازه می‌سازد */
    public SaveResult restore(String name) throws IOException {
        return save(readVersion(name));
    }

    /**
     * جلوگیری از path traversal: نام نسخه فقط می‌تواند یک نام فایل ساده باشد
     * و باید واقعاً داخل دایرکتوری پشتیبان‌ها قرار بگیرد.
     */
    private Path resolveBackup(String name) throws IOException {
        if (name == null || name.isBlank() || !name.matches("[A-Za-z0-9._-]{1,128}")
                || !name.endsWith(".bak")) {
            throw new IOException("نام نسخه معتبر نیست.");
        }
        Path dir = backupDir().toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isReadable(file)) {
            throw new IOException("نسخهٔ خواسته‌شده پیدا نشد.");
        }
        return file;
    }

    private void pruneBackups() {
        List<Version> all = versions();
        if (all.size() <= MAX_BACKUPS) {
            return;
        }
        for (Version old : all.subList(MAX_BACKUPS, all.size())) {
            try {
                Files.deleteIfExists(backupDir().resolve(old.name()));
            } catch (IOException e) {
                log.debug("حذف پشتیبان قدیمی ناموفق بود: {}", e.toString());
            }
        }
    }

    // ------------------------------------------------------------- کمکی

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String s = value.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        if (m == null) {
            return e.getClass().getSimpleName();
        }
        int newline = m.indexOf('\n');
        String first = newline > 0 ? m.substring(0, newline) : m;
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }
}
