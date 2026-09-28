package com.resolveai.iam.web;

import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.iam.service.BusinessRegistrationService;
import com.resolveai.iam.web.dto.BusinessRegisterRequest;
import com.resolveai.iam.web.dto.TenantSummaryResponse;
import com.resolveai.iam.web.dto.TokenResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Both endpoints are public: the list backs the "pick your business" dropdown on the
 * login/register pages, and creating a tenant is how a business gets onto that list in the
 * first place. Thin on purpose, same convention as {@link AuthController}.
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantRepository tenants;
    private final BusinessRegistrationService businessRegistrationService;

    public TenantController(TenantRepository tenants,
                            BusinessRegistrationService businessRegistrationService) {
        this.tenants = tenants;
        this.businessRegistrationService = businessRegistrationService;
    }

    @GetMapping
    public List<TenantSummaryResponse> list() {
        return tenants.findAllByActiveTrueOrderByNameAsc().stream()
                .map(TenantSummaryResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse registerBusiness(@Valid @RequestBody BusinessRegisterRequest request) {
        return businessRegistrationService.registerBusiness(request);
    }
}
