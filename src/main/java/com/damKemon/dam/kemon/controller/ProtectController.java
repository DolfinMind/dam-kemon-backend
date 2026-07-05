package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.ProtectedOrder;
import com.damKemon.dam.kemon.service.ProtectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * "Damkemon Protect" — public seller-check + scam-registry endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/protect/assess} — honest seller verdict: real trust
 *       data for catalog shops, report counts for everyone else.</li>
 *   <li>{@code POST /api/protect/reports} — file a scam report against any
 *       seller identifier (link, page, phone).</li>
 *   <li>{@code POST /api/protect/orders} — log a purchase, get a code.</li>
 *   <li>{@code GET /api/protect/orders/{code}} — track / claim by code.</li>
 *   <li>{@code POST /api/protect/orders/{code}/confirm|dispute} — resolve;
 *       outcomes feed the shop's public trust score.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/protect")
public class ProtectController {

    private final ProtectService protect;

    public ProtectController(ProtectService protect) {
        this.protect = protect;
    }

    @PostMapping("/assess")
    public ResponseEntity<Map<String, Object>> assess(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(protect.assessRisk(body == null ? Map.of() : body));
    }

    @PostMapping("/orders")
    public ResponseEntity<Object> create(@RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest req) {
        Map<String, Object> result = protect.createOrder(body == null ? Map.of() : body, req.getHeader("X-Anon-Id"));
        int status = result.get("status") instanceof Number n ? n.intValue() : 200;
        return ResponseEntity.status(status).body(result);
    }

    @PostMapping("/reports")
    public ResponseEntity<Object> report(@RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest req) {
        Map<String, Object> result = protect.report(body == null ? Map.of() : body, req.getHeader("X-Anon-Id"));
        int status = result.get("status") instanceof Number n ? n.intValue() : 200;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/orders/{code}")
    public ResponseEntity<ProtectedOrder> get(@PathVariable String code) {
        return protect.getByCode(code).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/orders/{code}/confirm")
    public ResponseEntity<Object> confirm(@PathVariable String code, HttpServletRequest req) {
        ProtectedOrder o = protect.confirm(code, req.getHeader("X-Anon-Id"));
        if (o == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(o);
    }

    @PostMapping("/orders/{code}/dispute")
    public ResponseEntity<Object> dispute(@PathVariable String code,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest req) {
        String reason = body == null || body.get("reason") == null ? null : body.get("reason").toString();
        ProtectedOrder o = protect.dispute(code, req.getHeader("X-Anon-Id"), reason);
        if (o == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(o);
    }
}
