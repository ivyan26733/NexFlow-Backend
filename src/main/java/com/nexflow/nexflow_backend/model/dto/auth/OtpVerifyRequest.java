package com.nexflow.nexflow_backend.model.dto.auth;

public record OtpVerifyRequest(String email, String otp) {}
