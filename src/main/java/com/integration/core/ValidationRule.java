package com.integration.core;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Builder pattern — one validation rule per field.
 * Loaded from Config Server by ValidationRuleLoader.
 * Used by Validator in Layer 2 checks.
 *
 * All fields have safe defaults — only declare what you need
 * in the Config Server properties.
 *
 * Config Server format ({usecase}.properties):
 *   dmt.validations.partnerCode.min_length=3
 *   dmt.validations.partnerCode.max_length=20
 *   dmt.validations.bankCode.allowed_values=HDFC,ICICI,SBI,AXIS
 *   dmt.validations.callbackUrl.required=false
 *   dmt.validations.callbackUrl.pattern=https?://.+
 */
@Getter
@Builder
public class ValidationRule {

    private final String       field;
    @Builder.Default
    private final boolean      required      = true;
    @Builder.Default
    private final int          minLength     = 0;
    @Builder.Default
    private final int          maxLength     = 0;
    @Builder.Default
    private final List<String> allowedValues = List.of();
    @Builder.Default
    private final String       pattern       = "";
}
