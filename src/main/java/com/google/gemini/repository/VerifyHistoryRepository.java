package com.google.gemini.repository;

import com.google.gemini.entity.VerifyHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VerifyHistoryRepository extends JpaRepository<VerifyHistory, Long> {
    List<VerifyHistory> findTop200ByOrderBySuccessAtDesc();
}
