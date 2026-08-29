package com.ticketrush

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TicketRushApplication

fun main(args: Array<String>) {
    runApplication<TicketRushApplication>(*args)
}
