package com.mamoji.controller;

import com.mamoji.service.ReportingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {
    private final ReportingService service;

    public StatsController(ReportingService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Map<String, BigDecimal> overview(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.overview(authorization);
    }

    @GetMapping("/trend")
    public List<Map<String, Object>> trend(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.trend(authorization, params);
    }

    @GetMapping("/category")
    public List<Map<String, Object>> category(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.categoryStats(authorization, params);
    }

    @GetMapping("/yearly")
    public Map<String, Object> yearly(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "year", required = false) Integer year
    ) {
        return service.yearly(authorization, year == null ? LocalDate.now().getYear() : year);
    }

    @GetMapping("/asset-liability")
    public Map<String, Object> assetLiability(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.assetLiability(authorization);
    }

    @GetMapping("/comparison")
    public Map<String, Object> comparison(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.comparison(authorization, params);
    }

    @GetMapping("/insights")
    public Map<String, Object> insights(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.insights(authorization);
    }
}
