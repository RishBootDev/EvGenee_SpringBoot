package com.voltx.evgenee.enums;

public enum ChatMessageRole {
    USER,
    AI,
    ASSISTANT;

    public boolean isAssistant() {
        return this == AI || this == ASSISTANT;
    }
}
