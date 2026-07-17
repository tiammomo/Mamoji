package com.mamoji.platform.product;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;

class ProductModuleInterceptorTest {

    @Test
    void disabledOptionalModuleIsRejectedAtTheApiBoundary() throws Exception {
        ProductModuleInterceptor interceptor = new ProductModuleInterceptor(catalog(false));
        HandlerMethod handler = handler("taxEndpoint");

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> interceptor.preHandle(null, null, handler)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void enabledOptionalModuleAndCoreEndpointAreAccepted() throws Exception {
        ProductModuleInterceptor interceptor = new ProductModuleInterceptor(catalog(true));

        assertDoesNotThrow(() -> interceptor.preHandle(null, null, handler("taxEndpoint")));
        assertDoesNotThrow(() -> interceptor.preHandle(null, null, handler("coreEndpoint")));
    }

    private ProductModuleCatalog catalog(boolean taxEnabled) {
        return new ProductModuleCatalog(
            "internal-module", false, true, true, false, taxEnabled, false, false
        );
    }

    private HandlerMethod handler(String method) throws Exception {
        OptionalController controller = new OptionalController();
        return new HandlerMethod(controller, OptionalController.class.getDeclaredMethod(method));
    }

    private static final class OptionalController {
        @RequiresProductModule("tax")
        public void taxEndpoint() {
        }

        public void coreEndpoint() {
        }
    }
}
