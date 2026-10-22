package com.lostfound.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Map<String, String> fieldErrors) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
