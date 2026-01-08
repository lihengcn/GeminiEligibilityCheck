package com.google.gemini.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "gem_sheerid_links")
public class SheeridLink {
    @Id
    @Column(name = "email")
    private String email;

    @Column(name = "sheerid_url")
    private String sheeridUrl;
}
