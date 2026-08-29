package com.ticketrush

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/demo")
class DemoController(
    private val stampede: StampedeService,
    private val events: SeatEventPublisher,
    private val seats: SeatRepository,
    @Value("\${demo.show-id}") private val showId: Long,
    @Value("\${demo.seat-count}") private val seatCount: Int,
) {

    @PostMapping("/stampede")
    fun stampede(@RequestBody req: StampedeRequest): StampedeResult = stampede.run(req)

    @PostMapping("/reset")
    fun reset(): Map<String, Any> {
        seats.ensureSeats(showId, seatCount)
        seats.reset(showId)
        val list = seats.seats(showId)
        events.publish(
            mapOf(
                "type" to "reset",
                "total" to list.size,
                "seats" to list.map { mapOf("id" to it.id, "label" to it.label) },
            ),
        )
        return mapOf("total" to list.size)
    }

    @GetMapping("/seats")
    fun seats(): List<SeatDto> = seats.seats(showId)

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter = events.subscribe()
}
