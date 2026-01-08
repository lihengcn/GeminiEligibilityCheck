package com.google.gemini.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "gem_accounts")
public class Account {
    /**
     * 唯一标识（login/email）
     */
    @Id
    @Column(name = "email")
    private String email;
    /**
     * 账号密码
     */
    @Column(name = "password")
    private String password;
    /**
     * 恢复邮箱
     */
    @Column(name = "recovery_email")
    private String recoveryEmail;
    /**
     * 2FA 密钥
     */
    @Column(name = "authenticator_token")
    private String authenticatorToken;
    /**
     * 应用密码
     */
    @Column(name = "app_password")
    private String appPassword;
    /**
     * 2FA 管理地址
     */
    @Column(name = "authenticator_url")
    private String authenticatorUrl;
    /**
     * 消息回调地址
     */
    @Column(name = "messages_url")
    private String messagesUrl;
    /**
     * SheerID 认证链接（单独表/可选）
     */
    @Transient
    private String sheeridUrl;
    /**
     * 是否已出售（已出售账号默认不再分配）
     */
    @Column(name = "sold")
    private boolean sold;
    /**
     * 是否成品号
     */
    @Column(name = "finished")
    private boolean finished;
    /**
     * 当前状态
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AccountStatus status;
}
