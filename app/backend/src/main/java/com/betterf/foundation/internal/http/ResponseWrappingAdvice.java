package com.betterf.foundation.internal.http;

import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestControllerAdvice(basePackages = "com.betterf.foundation.controller")
class ResponseWrappingAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return !StringHttpMessageConverter.class.isAssignableFrom(converter);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter method, MediaType mediaType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        int status = response instanceof ServletServerHttpResponse servlet ? servlet.getServletResponse().getStatus() : 200;
        if (body == null || body instanceof ApiEnvelope<?> || body instanceof Resource
                || body instanceof StreamingResponseBody || status == 204 || status == 304
                || !MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) {
            return body;
        }
        return ApiEnvelope.success(body);
    }
}
