package eu.swdev.kotlin.actor

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Test

class ChannelBasedActorTest {

    @Test
    fun `concurrent messages do not interfere`() {

        val actor  = object : ChannelBasedActor<Int>() {
            var sum = 0
            override suspend fun receive(msg: Int) {
                logger.debug("received: $msg")
                sum += msg
            }
        }

        val range = 1..1000

        runBlocking {
            val jobs = range.asSequence().map { launch { actor send it } }
            jobs.forEach { it.join() }
            actor.shutdown()
        }

        assertThat(actor.sum, equalTo(range.sum()))
    }
}
