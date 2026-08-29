package com.ticketrush

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out of live demo events (seat booked, oversell, reset, summary) to every browser
 * currently watching the page via Server-Sent Events. This is what animates the grid in
 * real time as the server-side stampede runs.
 */
@Component
class SeatEventPublisher(private val mapper: ObjectMapper) {

    private val emitters = CopyOnWriteArrayList<SseEmitter>()

    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(0L) // never time out
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        emitters.add(emitter)
        return emitter
    }

    fun publish(event: Map<String, Any?>) {
        val payload = mapper.writeValueAsString(event)
        val dead = ArrayList<SseEmitter>()
        for (e in emitters) {
            try {
                e.send(SseEmitter.event().name("msg").data(payload))
            } catch (ex: Exception) {
                dead.add(e)
            }
        }
        emitters.removeAll(dead)
    }
}
