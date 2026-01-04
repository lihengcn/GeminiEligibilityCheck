package com.google.gemini.entity;

public enum AccountStatus {
    // 空闲可分配
    IDLE,
    // 已被分配，检查中
    CHECKING,
    // 通过
    QUALIFIED,
    // 无效
    INVALID
}
