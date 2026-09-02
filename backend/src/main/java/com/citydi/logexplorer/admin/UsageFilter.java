package com.citydi.logexplorer.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * زمان‌سنجی و شمارش درخواست‌ها.
 *
 * مسیرها **نرمال** می‌شوند: `/api/v1/log/68a1b2…` به `/api/v1/log/{id}`
 * تبدیل می‌شود. بدون این کار، هر شناسه یک ردیف جدا در آمار می‌ساخت و هم
 * حافظه را پر می‌کرد هم گزارش را بی‌فایده.
 *
 * پیش از AdminSecurity اجرا می‌شود تا تلاش‌های ناموفق ورود هم شمرده شوند —
 * افزایش ناگهانی خطای ۴۰۱ روی مسیر مدیریتی، خودش یک سیگنال است.
 */
@Component
@Order(1)
public class UsageFilter extends OncePerRequestFilter {

    private final UsageRegistry registry;

    public UsageFilter(UsageRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long tookMs = (System.nanoTime() - started) / 1_000_000;
            boolean error = response.getStatus() >= 400;
            registry.recordCall(request.getMethod() + " " + normalize(request.getRequestURI()),
                    tookMs, error);
        }
    }

    /** شناسه‌ها و نام نسخه‌ها با جای‌نگهدار جایگزین می‌شوند */
    static String normalize(String uri) {
        if (uri == null) {
            return "unknown";
        }
        if (uri.startsWith("/api/v1/log/") && !uri.startsWith("/api/v1/log/search")
                && !uri.startsWith("/api/v1/log/advanced")) {
            return "/api/v1/log/{id}";
        }
        if (uri.startsWith("/api/v1/admin/config/versions/")) {
            return "/api/v1/admin/config/versions/{name}";
        }
        return uri.length() > 120 ? uri.substring(0, 120) : uri;
    }
}
