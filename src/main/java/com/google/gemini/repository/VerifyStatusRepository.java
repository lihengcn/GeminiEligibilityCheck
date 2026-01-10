package com.google.gemini.repository;

import com.google.gemini.entity.VerifyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VerifyStatusRepository extends JpaRepository<VerifyStatus, String> {
    List<VerifyStatus> findByEmailIn(List<String> emails);
}
