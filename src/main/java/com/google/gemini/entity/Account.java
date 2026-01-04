package com.google.gemini.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    // 唯一标识（login/email）
    private String email;
    // 账号密码
    private String password;
    // 2FA 密钥
    private String authenticatorToken;
    // 应用密码
    private String appPassword;
    // 2FA 管理地址
    private String authenticatorUrl;
    // 消息回调地址
    private String messagesUrl;
    // 当前状态
    private AccountStatus status;
}
