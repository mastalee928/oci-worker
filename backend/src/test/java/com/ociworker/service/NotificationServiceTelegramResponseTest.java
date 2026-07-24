package com.ociworker.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationServiceTelegramResponseTest {

    @Test
    void acceptsTelegramOkResponse() throws Exception {
        NotificationService service = new NotificationService();
        HttpResponse<String> response = mockResponse(200, "{\"ok\":true,\"result\":{\"message_id\":1}}");

        assertTrue(invokeResponseCheck(service, response));
    }

    @Test
    void rejectsTelegramOkFalseResponse() throws Exception {
        NotificationService service = new NotificationService();
        HttpResponse<String> response = mockResponse(200,
                "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\"}");

        assertFalse(invokeResponseCheck(service, response));
    }

    private static boolean invokeResponseCheck(NotificationService service,
                                                HttpResponse<String> response) throws Exception {
        Method method = NotificationService.class.getDeclaredMethod(
                "telegramResponseOk", HttpResponse.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, response, "test");
    }

    private static HttpResponse<String> mockResponse(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
