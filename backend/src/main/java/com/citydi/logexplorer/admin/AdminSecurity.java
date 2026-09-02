package com.citydi.logexplorer.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * محافظت از مسیرهای مدیریتی.
 *
 * چرا این صفحه جدی‌تر از بقیه است؟ چون از آنجا می‌شود
 * `privacy.maskingProfile` را روی `off` گذاشت و همهٔ دادهٔ حساس را
 * بی‌پوشش دید. پس دسترسی به `/api/v1/admin` هم‌ارز دسترسی به دادهٔ خام است.
 *
 * سه تصمیم:
 *
 *   ۱) **fail-closed** — اگر توکن تنظیم نشده باشد، کل API مدیریتی
 *      غیرفعال است، نه باز. فراموش‌کردن پیکربندی نباید یعنی درِ باز.
 *
 *   ۲) **مقایسهٔ ثابت‌زمان** — `String.equals` روی اولین بایت متفاوت
 *      برمی‌گردد و از تفاوت زمان می‌شود توکن را حدس زد.
 *
 *   ۳) **کندسازی پس از تلاش ناموفق** — بدون آن، توکن ۳۲ نویسه‌ای هم
 *      با یک اسکریپت قابل حمله است.
 *
 * این فیلتر جایگزین TLS و کنترل دسترسی شبکه **نیست**. در استقرار،
 * `/admin` باید پشت nginx و روی HTTPS باشد و ترجیحاً به شبکهٔ داخلی
 * محدود شود — به docs/07-deployment.md مراجعه کنید.
 */
@Component
public class AdminSecurity extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurity.class);

    private static final String PATH_PREFIX = "/api/v1/admin";
    private static final int MIN_TOKEN_LENGTH = 16;
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(5);
    private static final int MAX_TRACKED_CLIENTS = 1000;

    private final byte[] token;
    private final boolean enabled;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public AdminSecurity(@Value("${logexplorer.admin.token:}") String configured) {
        String value = configured == null ? "" : configured.trim();
        this.enabled = value.length() >= MIN_TOKEN_LENGTH;
        this.token = enabled ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];

        if (!enabled && !value.isEmpty()) {
            log.error("توکن مدیریتی کوتاه‌تر از {} نویسه است و پذیرفته نشد؛ "
                    + "API مدیریتی غیرفعال ماند.", MIN_TOKEN_LENGTH);
        } else if (!enabled) {
            log.warn("متغیر ADMIN_TOKEN تنظیم نشده — صفحهٔ مدیریتی غیرفعال است. "
                    + "برای فعال‌سازی یک توکن تصادفی حداقل {} نویسه‌ای بدهید.", MIN_TOKEN_LENGTH);
        } else {
            log.info("API مدیریتی فعال است (توکن {} نویسه‌ای).", value.length());
        }
    }

    /** آیا اصلاً پیکربندی شده؟ برای نمایش وضعیت در صفحهٔ سلامت */
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // پیش‌پرواز CORS نباید نیاز به توکن داشته باشد
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        if (!enabled) {
            deny(response, 503, "API مدیریتی غیرفعال است.",
                    "متغیر محیطی ADMIN_TOKEN را تنظیم و سرویس را دوباره راه‌اندازی کنید.");
            return;
        }

        String client = clientKey(request);
        Failures state = failures.get(client);
        if (state != null && state.lockedUntil != null && Instant.now().isBefore(state.lockedUntil)) {
            deny(response, 429, "تلاش‌های ناموفق زیاد بوده است.",
                    "چند دقیقه صبر کنید و دوباره تلاش کنید.");
            return;
        }

        if (!matches(presentedToken(request))) {
            recordFailure(client);
            // پیام عمداً مبهم است: نگوییم توکن اشتباه بود یا اصلاً نیامده
            deny(response, 401, "دسترسی مدیریتی نامعتبر است.",
                    "توکن را در هدر Authorization: Bearer … یا X-Admin-Token بفرستید.");
            return;
        }

        failures.remove(client);
        chain.doFilter(request, response);
    }

    // ------------------------------------------------------------- توکن

    private String presentedToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        String direct = request.getHeader("X-Admin-Token");
        return direct == null ? null : direct.trim();
    }

    /**
     * مقایسهٔ ثابت‌زمان.
     * {@code MessageDigest.isEqual} در جاوای مدرن تضمین می‌کند که زمان
     * اجرا به محل اولین اختلاف وابسته نباشد.
     */
    private boolean matches(String presented) {
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------- کندسازی و پاسخ

    private void recordFailure(String client) {
        if (failures.size() > MAX_TRACKED_CLIENTS) {
            // جلوگیری از رشد بی‌کران با آدرس‌های جعلی
            failures.clear();
        }
        Failures state = failures.computeIfAbsent(client, k -> new Failures());
        if (state.count.incrementAndGet() >= MAX_FAILURES) {
            state.lockedUntil = Instant.now().plus(LOCKOUT);
            state.count.set(0);
            log.warn("دسترسی مدیریتی از «{}» به‌دلیل تلاش‌های ناموفق تا {} مسدود شد.",
                    client, state.lockedUntil);
        }
    }

    /**
     * کلید محدودسازی: اگر پشت پروکسی هستیم، اولین آدرس در X-Forwarded-For.
     * این هدر قابل جعل است، ولی برای کندکردن حملهٔ ساده کافی است — و
     * کنترل دسترسی واقعی کار nginx و شبکه است، نه این فیلتر.
     */
    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty() && first.length() <= 64) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    private static void deny(HttpServletResponse response, int status, String message, String hint)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + escape(message)
                + "\",\"hint\":\"" + escape(hint) + "\"}");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Failures {
        private final AtomicInteger count = new AtomicInteger();
        private volatile Instant lockedUntil;
    }
}
