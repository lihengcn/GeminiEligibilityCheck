package com.google.gemini.dto;

import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class StatusView {
    private List<AccountItem> accounts;
    private Map<AccountStatus, Integer> counts;
    private int total;

    public static StatusView from(List<Account> accounts) {
        List<AccountItem> items = new ArrayList<>();
        Map<AccountStatus, Integer> countMap = new EnumMap<>(AccountStatus.class);
        for (AccountStatus status : AccountStatus.values()) {
            countMap.put(status, 0);
        }
        for (Account account : accounts) {
            items.add(new AccountItem(
                    account.getEmail(),
                    account.getPassword(),
                    account.getAuthenticatorToken(),
                    account.getSheeridUrl(),
                    account.getStatus().name(),
                    account.isSold(),
                    account.isFinished()
            ));
            countMap.put(account.getStatus(), countMap.get(account.getStatus()) + 1);
        }
        return new StatusView(items, countMap, items.size());
    }

    @Data
    @AllArgsConstructor
    public static class AccountItem {
        private String email;
        private String password;
        private String authenticatorToken;
        private String sheeridUrl;
        private String status;
        private boolean sold;
        private boolean finished;
    }
}
