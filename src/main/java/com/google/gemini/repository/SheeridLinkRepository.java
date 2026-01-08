package com.google.gemini.repository;

import com.google.gemini.entity.SheeridLink;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheeridLinkRepository extends JpaRepository<SheeridLink, String> {
    List<SheeridLink> findAllByEmailIn(Collection<String> emails);
}
