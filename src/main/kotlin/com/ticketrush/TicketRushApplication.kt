package com.ticketrush

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class TicketRushApplication

fun main(args: Array<String>) {
    runApplication<TicketRushApplication>(*args)
}
