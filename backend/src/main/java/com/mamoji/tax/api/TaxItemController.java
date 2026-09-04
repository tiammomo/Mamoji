package com.mamoji.tax.api;

import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.CurrentActor;
import com.mamoji.platform.product.RequiresProductModule;
import com.mamoji.tax.application.TaxItemApplicationService;
import com.mamoji.tax.domain.TaxItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiresProductModule("tax")
@RequestMapping("/api/v1/enterprise/tax-items")
public class TaxItemController {
    private final TaxItemApplicationService service;

    public TaxItemController(TaxItemApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<TaxItem> list(
        @CurrentActor ActorContext actor,
        @Positive @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.list(actor, companyId);
    }

    @PostMapping
    public TaxItem create(
        @CurrentActor ActorContext actor,
        @Valid @RequestBody TaxItemCreateRequest request
    ) {
        return service.create(actor, request);
    }

    @PutMapping("/{id}")
    public TaxItem update(
        @CurrentActor ActorContext actor,
        @Positive @PathVariable long id,
        @Valid @RequestBody TaxItemUpdateRequest request
    ) {
        return service.update(actor, id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@CurrentActor ActorContext actor, @Positive @PathVariable long id) {
        service.delete(actor, id);
    }
}
