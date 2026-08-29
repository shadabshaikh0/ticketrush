package com.ticketrush

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class AppConfig {

    /**
     * TransactionTemplate is not auto-registered by Spring Boot. We need it so the
     * PESSIMISTIC and OPTIMISTIC strategies can run their read + write inside a single
     * transaction (a SELECT ... FOR UPDATE lock is only held until the tx commits).
     */
    @Bean
    fun transactionTemplate(tm: PlatformTransactionManager) = TransactionTemplate(tm)
}
