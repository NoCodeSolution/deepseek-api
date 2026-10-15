package com.nocodesolution.deepseek_free_api.enums;

public enum AuthState {
    LOADING("loading"),
    READY("ready"),
    REFRESHING("refreshing"),
    EXPIRED("expired");

    private final String value;

    AuthState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
