package com.integration.intake;

import com.integration.core.ValidationRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 * ValidationRuleLoader — Loads Per-Use-Case Validation Rules
 * ═══════════════════════════════════════════════════════════════════
 *
 * Reads optional validation rules from Spring Cloud Config Server
 * (served as {usecase}.properties) via Spring Environment.
 *
 * If no rules configured for a use case — returns empty list.
 * Validator falls back to Layer 1 (placeholder presence only).
 * Not an error.
 *
 * Config Server format (in {usecase}.properties):
 *
 *   # Per-field rules — only declare what you need
 *   dmt.validations.partnerCode.min_length=3
 *   dmt.validations.partnerCode.max_length=20
 *   dmt.validations.bankCode.allowed_values=HDFC,ICICI,SBI,AXIS
 *   dmt.validations.callbackUrl.required=false
 *   dmt.validations.callbackUrl.pattern=https?://.+
 *   dmt.validations.maxTxnLimit.pattern=[0-9]+
 *
 *   # List of fields that have rules (required to discover rule entries)
 *   dmt.validations.fields=partnerCode,bankCode,callbackUrl,maxTxnLimit
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValidationRuleLoader {

    private final Environment environment;

    /**
     * Loads validation rules for a use case from Config Server.
     * Returns empty list if no rules configured — not an error.
     *
     * @param useCase use case name e.g. "dmt"
     * @return        list of ValidationRule objects, may be empty
     */
    public List<ValidationRule> load(String useCase) {
        /*
         * Read the field list declared in Config Server.
         * e.g. dmt.validations.fields=partnerCode,bankCode,callbackUrl
         * If property absent — no rules configured, return empty list.
         */
        String fieldsProperty = useCase + ".validations.fields";
        String fieldsValue    = environment.getProperty(fieldsProperty, "");

        if (StringUtils.isBlank(fieldsValue)) {
            log.debug("No validation rules configured for use case '{}' — "
                + "Layer 1 placeholder presence check only", useCase);
            return List.of();
        }

        List<ValidationRule> rules = new ArrayList<>();
        String prefix = useCase + ".validations.";

        /*
         * For each declared field, read its rule properties.
         * Only properties that exist in Config Server are applied.
         * Missing properties use ValidationRule @Builder.Default values.
         */
        Arrays.stream(fieldsValue.split(","))
            .map(StringUtils::trimToEmpty)
            .filter(StringUtils::isNotBlank)
            .forEach(field -> {
                String fieldPrefix = prefix + field + ".";

                // required — default true if not declared
                boolean required = Boolean.parseBoolean(
                    environment.getProperty(fieldPrefix + "required", "true"));

                // min_length — default 0 (not checked) if not declared
                int minLength = Integer.parseInt(
                    environment.getProperty(fieldPrefix + "min_length", "0"));

                // max_length — default 0 (not checked) if not declared
                int maxLength = Integer.parseInt(
                    environment.getProperty(fieldPrefix + "max_length", "0"));

                // allowed_values — comma-separated, empty list if not declared
                String allowedValuesStr = environment.getProperty(
                    fieldPrefix + "allowed_values", "");
                List<String> allowedValues = StringUtils.isBlank(allowedValuesStr)
                    ? List.of()
                    : Arrays.asList(allowedValuesStr.split(","));

                // pattern — empty string (not checked) if not declared
                String pattern = environment.getProperty(fieldPrefix + "pattern", "");

                rules.add(ValidationRule.builder()
                    .field(field)
                    .required(required)
                    .minLength(minLength)
                    .maxLength(maxLength)
                    .allowedValues(allowedValues)
                    .pattern(pattern)
                    .build());

                log.debug("Validation rule loaded: useCase={} field={} required={} "
                    + "minLength={} maxLength={} allowedValues={} pattern={}",
                    useCase, field, required, minLength, maxLength, allowedValues, pattern);
            });

        log.info("Validation rules loaded: useCase={} ruleCount={}", useCase, rules.size());
        return rules;
    }
}
