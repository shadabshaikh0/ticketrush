package com.ticketrush

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Books a single seat on whichever node the load balancer routed this request to, and reports
 * which node that was. The cluster stampede fans out to this endpoint through nginx so that
 * concurrent requests for the same seat land on different nodes.
 */
@RestController
class InternalController(
    private val bookings: BookingService,
    @Value("\${demo.node-name}") private val node: String,
) {
    @PostMapping("/internal/book")
    fun book(@RequestBody req: InternalBookRequest): Map<String, Any> {
        val res = bookings.book(req.strategy, req.seatId, req.userId, req.gapMs)
        return mapOf("outcome" to res.outcome.name, "node" to node)
    }
}
