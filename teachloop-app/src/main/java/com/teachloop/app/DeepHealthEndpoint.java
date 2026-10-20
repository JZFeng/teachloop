package com.teachloop.app;

import com.teachloop.report.service.export.PdfFontProbe;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部署探针（T160）：普通 /healthz 只表示进程活着；?deep=1 额外检查数据库与 PDF 中文字体。
 * 缺字体、数据库不可用时返回 503，便于负载均衡与容器编排摘流量。
 */
@RestController
public class DeepHealthEndpoint {

    private final PdfFontProbe fontProbe;
    private final JdbcTemplate jdbc;

    public DeepHealthEndpoint(PdfFontProbe fontProbe, JdbcTemplate jdbc) {
        this.fontProbe = fontProbe;
        this.jdbc = jdbc;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> health(@RequestParam(required = false) String deep) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        if (!"1".equals(deep) && !"true".equalsIgnoreCase(String.valueOf(deep))) {
            return ResponseEntity.ok(body);
        }
        boolean db = true;
        try {
            jdbc.queryForObject("select 1", Integer.class);
        } catch (RuntimeException ex) {
            db = false;
        }
        boolean font = fontProbe.available();
        body.put("db", db ? "UP" : "DOWN");
        body.put("pdfFont", font ? "UP" : "DOWN");
        body.put("status", db && font ? "UP" : "DOWN");
        return ResponseEntity.status(db && font ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
