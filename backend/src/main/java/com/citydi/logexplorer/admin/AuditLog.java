package com.citydi.logexplorer.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ثبت تغییرات مدیریتی — فقط-افزودنی.
 *
 * این سرویس فقط-خواندنی از MongoDB است، ولی *پیکربندی* را می‌نویسد.
 * هر تغییر پیکربندی می‌تواند رفتار نمایش دادهٔ حساس را عوض کند، پس باید
 * ردی بماند که چه کسی، کِی، چه چیزی را عوض کرد.
 *
 * قالب JSONL است تا با ابزارهای موجود (grep، jq، همان ELK) قابل خواندن
 * باشد و به هیچ پایگاه داده‌ای وابسته نباشد.
 *
 * نکتهٔ امنیتی: توکن مدیریتی هرگز اینجا نوشته نمی‌شود — فقط آدرس و
 * نام کاربری‌ای که پروکسی معرفی کرده.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MEMORY_TAIL = 200;

    private final Path file;
    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();

    public AuditLog(@Value("${logexplorer.admin.audit-file:data/admin-audit.log}") String location) {
        this.file = Path.of(location);
        loadTail();
    }

    /**
     * @param action  چه کاری شد (config.save، config.restore، …)
     * @param actor   چه کسی — از هدر پروکسی یا «admin-token»
     * @param details جزئیات، بدون هیچ مقدار محرمانه
     */
    public synchronized void record(String action, String actor, String client,
                                    Map<String, Object> details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", Instant.now().toString());
        entry.put("action", action);
        entry.put("actor", actor == null ? "unknown" : actor);
        entry.put("client", client == null ? "unknown" : client);
        if (details != null) {
            entry.putAll(details);
        }

        recent.addLast(entry);
        while (recent.size() > MEMORY_TAIL) {
            recent.removeFirst();
        }

        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, MAPPER.writeValueAsString(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // نبودِ ممیزی نباید سرویس را زمین بزند، ولی باید بلند فریاد بزند
            log.error("نوشتن رویداد ممیزی «{}» ناموفق بود: {}", action, e.toString());
        }
    }

    /** آخرین رویدادها، تازه‌ترین اول */
    public synchronized List<Map<String, Object>> tail(int limit) {
        int n = Math.max(1, Math.min(limit, MEMORY_TAIL));
        List<Map<String, Object>> out = new java.util.ArrayList<>(Math.min(n, recent.size()));
        java.util.Iterator<Map<String, Object>> it = recent.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            out.add(it.next());
        }
        return List.copyOf(out);
    }

    public Path path() {
        return file;
    }

    /** خواندن انتهای فایل هنگام راه‌اندازی تا تاریخچه با ری‌استارت گم نشود */
    private void loadTail() {
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - MEMORY_TAIL);
            for (String line : lines.subList(from, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = MAPPER.readValue(line, Map.class);
                    recent.addLast(parsed);
                } catch (Exception ignored) {
                    // یک سطر خراب نباید بقیهٔ تاریخچه را از دست بدهد
                }
            }
            log.info("{} رویداد ممیزی از «{}» خوانده شد.", recent.size(), file);
        } catch (IOException e) {
            log.warn("خواندن فایل ممیزی ناموفق بود: {}", e.toString());
        }
    }
}
