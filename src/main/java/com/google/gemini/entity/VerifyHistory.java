package com.google.gemini.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "gem_verify_history")
public class VerifyHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "success_at")
    private LocalDateTime successAt;

    @PrePersist
    public void prePersist() {
        if (successAt == null) {
            successAt = LocalDateTime.now();
        }
    }
}
