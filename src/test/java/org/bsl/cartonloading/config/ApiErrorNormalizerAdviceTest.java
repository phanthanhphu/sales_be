package org.bsl.cartonloading.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ApiErrorNormalizerAdviceTest {
    private final ApiErrorNormalizerAdvice advice = new ApiErrorNormalizerAdvice();
    private Object write(Object body, MockHttpServletResponse response) {
        return advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, null,
                new ServletServerHttpResponse(response));
    }
    private MockHttpServletResponse response(int status) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        return response;
    }
    @Test void preservesSuccessfulBody() {
        Map<String, Object> body = Map.of("message", "OK");
        assertSame(body, write(body, response(200)));
    }
    @Test void keepsFieldErrorsForDtoModel() {
        org.bsl.cartonloading.dto.ApiError body =
                new org.bsl.cartonloading.dto.ApiError(400, "Invalid input");
        body.setFieldErrors(Map.of("quantity", "must be positive"));
        Map<?, ?> result = (Map<?, ?>) write(body, response(400));
        assertEquals(body.getFieldErrors(), result.get("fieldErrors"));
        assertEquals("VALIDATION_FAILED", result.get("errorCode"));
        assertEquals(body.getTimestamp(), result.get("timestamp"));
    }
    @Test void keepsLegacyErrorList() {
        org.bsl.cartonloading.error.ApiError body = new org.bsl.cartonloading.error.ApiError(
                HttpStatus.CONFLICT, "Duplicate", List.of("duplicate SKU"));
        Map<?, ?> result = (Map<?, ?>) write(body, response(409));
        assertEquals(body.getErrors(), result.get("errors"));
        assertEquals("CONFLICT", result.get("errorCode"));
    }
    @Test void handlesNonEnumStatusWithoutThrowing() {
        MockHttpServletResponse response = response(499);
        Map<?, ?> result = (Map<?, ?>) write(Map.of("message", "Cancelled"), response);
        assertEquals(499, result.get("status"));
        assertEquals(499, response.getStatus());
    }
    @Test void doesNotMutateImmutableMap() {
        Map<String, Object> body = Map.of("message", "Not found", "errorCode", "CUSTOM");
        Map<?, ?> result = (Map<?, ?>) write(body, response(404));
        assertFalse(body.containsKey("timestamp"));
        assertEquals("CUSTOM", result.get("errorCode"));
    }
    @Test void leavesNonJsonConverterAlone() {
        assertFalse(advice.supports(null, StringHttpMessageConverter.class));
        byte[] body = {1, 2};
        Object result = advice.beforeBodyWrite(body, null, MediaType.IMAGE_PNG,
                MappingJackson2HttpMessageConverter.class, null,
                new ServletServerHttpResponse(response(500)));
        assertSame(body, result);
    }
    @Test void leavesNullAndUnsupportedBodyAlone() {
        assertNull(write(null, response(500)));
        Object body = new Object();
        assertSame(body, write(body, response(500)));
    }
    @Test void leavesCommittedResponseAlone() throws Exception {
        MockHttpServletResponse response = response(500);
        response.flushBuffer();
        Map<String, String> body = Map.of("message", "error");
        assertSame(body, write(body, response));
    }
}
