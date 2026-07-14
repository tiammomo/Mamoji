package com.mamoji.controller;

import com.mamoji.service.GlobalSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public class GlobalSearchController {
    private final GlobalSearchService service;

    public GlobalSearchController(GlobalSearchService service) {
        this.service = service;
    }

    @GetMapping
    public GlobalSearchService.SearchResponse search(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam String keyword,
        @RequestParam(required = false) Long companyId,
        @RequestParam(required = false) Integer limit
    ) {
        return service.search(authorization, companyId, keyword, limit);
    }
}
