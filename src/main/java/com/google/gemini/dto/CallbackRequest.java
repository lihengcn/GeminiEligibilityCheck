package com.google.gemini.dto;

import lombok.Data;

@Data
public class CallbackRequest {
    // 回调账号
    private String email;
    // 回调结果（QUALIFIED/INVALID）
    private String result;
    // SheerID 认证链接（可选）
    private String sheeridUrl;
}
