package org.bsl.sales.support;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class MasterDataBeanValidator {

    private final Validator validator;

    public MasterDataBeanValidator(Validator validator) {
        this.validator = validator;
    }

    public List<String> validate(Object value) {
        Set<ConstraintViolation<Object>> violations = validator.validate(value);
        List<String> messages = new ArrayList<>();
        violations.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .forEach(v -> messages.add(v.getPropertyPath() + ": " + v.getMessage()));
        return messages;
    }
}
