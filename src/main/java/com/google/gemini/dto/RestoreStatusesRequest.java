package com.google.gemini.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestoreStatusesRequest {
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String email;
        private String status;
    }
}

