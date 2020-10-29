package eu.swdev.kotlin.actor

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

private val defaultScope = CoroutineScope(Dispatchers.Default)

interface Actor<M> {
    suspend infix fun send(msg: M)
}

abstract class ChannelBasedActor<M>(
        channelCapacity: Int = 10,
        scope: CoroutineScope = defaultScope,
) : Actor<M> {

    protected val logger = LoggerFactory.getLogger(javaClass)

    private val channel = Channel<M>(channelCapacity)

    private val job = scope.launch {
        for (msg in channel) {
            logger.debug("received - msg: $msg")
            receive(msg)
        }
        logger.debug("message processing stopped")
    }

    /**
     * Closes the actor's channel immediately and waits until remaining messages are processed.
     *
     * If another message is sent to the actor after it was shutdown a
     * [kotlinx.coroutines.channels.ClosedSendChannelException] is raised.
     */
    suspend fun shutdown() {
        channel.close()
        job.join()
    }

    override suspend infix fun send(msg: M) = channel.send(msg)

    protected abstract suspend fun receive(msg: M)

}

/**
 * Represents the current state of an actor.
 *
 * The current state is responsible for processing incoming events.
 * Subclasses have to implement event handler functions that return the next state.
 *
 * All functions must be non-blocking. They can fork coroutines (possibly on the IO dispatcher) and send other events.
 */
interface ActorState<Id> {
    val stateId: Id
    suspend fun enter()
}

/**
 * An actor that processes messages that are state transition functions.
 */
abstract class StateMachineActor<S : ActorState<*>>(
        channelCapacity: Int = 10,
        val scope: CoroutineScope = defaultScope,
) : ChannelBasedActor<suspend S.() -> S>(channelCapacity, scope) {

    private lateinit var state: S

    protected fun initialState(s: S) {
        state = s
        scope.launch { state.enter() }
    }

    override suspend fun receive(msg: suspend S.() -> S) {
        val before = state
        val after = msg(state)
        state = after
        if (before.stateId != after.stateId) {
            logger.debug("transition: ${before.stateId} -> ${after.stateId}")
            after.enter()
        }
    }

    protected suspend fun <T> sendWithResultAsync(f: (CompletableDeferred<T>) -> suspend S.() -> S): Deferred<T> =
            CompletableDeferred<T>().also { send(f(it)) }

}
