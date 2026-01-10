package com.google.gemini.repository;

import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {
    @Modifying
    @Query("update Account a set a.status = com.google.gemini.entity.AccountStatus.IDLE where a.status = com.google.gemini.entity.AccountStatus.CHECKING")
    int resetCheckingToIdle();

    @Modifying
    @Query("update Account a set a.sold = :sold where a.email = :email")
    int updateSoldByEmail(@Param("email") String email, @Param("sold") boolean sold);

    @Modifying
    @Query("delete from Account a where a.sold = true")
    int deleteBySoldTrue();

    Optional<Account> findFirstByStatusOrderByEmailAsc(AccountStatus status);
}
