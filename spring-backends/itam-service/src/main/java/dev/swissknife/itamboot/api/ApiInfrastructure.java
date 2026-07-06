package dev.swissknife.itamboot.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

final class ApiInfrastructure { private ApiInfrastructure() {} }

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiSecurityFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_.-]{1,100}")) requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        String origin = System.getenv("SWISSKNIFE_CORS_ORIGIN");
        if (origin != null && !origin.isBlank()) response.setHeader("Access-Control-Allow-Origin", origin);
        if ("OPTIONS".equals(request.getMethod())) {
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type,X-Request-ID");
            response.setStatus(204); return;
        }
        String token = System.getenv("SWISSKNIFE_API_TOKEN");
        if (token != null && !token.isBlank()) {
            String actual = request.getHeader("Authorization");
            if (actual == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(401); response.setContentType("application/problem+json");
                response.getWriter().write("{\"title\":\"Não autorizado\",\"status\":401}"); return;
            }
        }
        chain.doFilter(request, response);
    }
}

@RestControllerAdvice
class ApiProblemHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a,b) -> a + "; " + b).orElse("Requisição inválida"));
        problem.setTitle("Falha de validação"); problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.badRequest().body(problem);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Requisição inválida"); problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.badRequest().body(problem);
    }
}
