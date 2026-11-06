package com.jaka.payment_service.web;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.jaka.payment_service.common.Correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    // Chỉ nhận chữ, số, gạch ngang, tối đa 64 ký tự — chặn người ngoài chèn ký tự
    // xuống dòng làm giả log
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(Correlation.HTTP_HEADER);
        String cid = (!Objects.isNull(incoming) && SAFE.matcher(incoming).matches()) ? incoming
                : UUID.randomUUID().toString();
        MDC.put(Correlation.MDC_KEY, cid);
        response.setHeader(Correlation.HTTP_HEADER, cid);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(Correlation.MDC_KEY);
        }
    }

}
