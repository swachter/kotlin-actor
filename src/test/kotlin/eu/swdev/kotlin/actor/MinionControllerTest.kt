package eu.swdev.kotlin.actor

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class MinionControllerTest {

    val containerCounter = AtomicInteger()

    private fun sleep() = Thread.sleep(1000)

    val cc = object : ContainerController<Cfg, String> {
        override fun createContainer(cfg: Cfg): ContainerId = run {
            sleep()
            if (cfg.creationSucceeds) {
                val id = containerCounter.getAndIncrement()
                ContainerId(
                    if (cfg.removeSucceeds) {
                        "$id"
                    } else {
                        "$id!"
                    }
                )
            } else {
                throw Exception("container creation failed")
            }
        }

        override fun configureAndStartContainer(cfg: Cfg, id: ContainerId): String = run {
            sleep()
            if (cfg.startSucceeds) {
                cfg.memento
            } else {
                throw Exception("container start failed")
            }
        }

        override fun configureDynamic(cfg: Cfg, id: ContainerId, cfgMemento: String) {
        }

        override fun removeContainer(id: ContainerId) {
            sleep()
            if (id.str.endsWith("!")) {
                throw Exception("container removal failed")
            }
        }

        override fun compare(cfg1: Cfg, cfg2: Cfg): CfgChanges =
            CfgChanges(
                create = cfg1.createConf != cfg2.createConf,
                start = cfg1.startConf != cfg2.startConf,
                run = cfg1.runConf != cfg2.runConf
            )
    }

    @Test
    fun `initial state change`() {
        val mc = MinionController(cc)
        val cfg = Cfg()
        runBlocking {
            mc send { mc.Running(cfg, ContainerId("x"), "m") }
            mc.shutdown()
        }
    }

    @Test
    fun `change to running`() {
        val mc = MinionController(cc)
        val cfg = Cfg()
        runBlocking {
            mc.onConfig(cfg)
            delay(5000)
            mc.shutdown()
        }
    }

}

data class Cfg(
    val creationSucceeds: Boolean = true,
    val startSucceeds: Boolean = true,
    val removeSucceeds: Boolean = true,
    val createConf: Int = 0,
    val startConf: Int = 0,
    val runConf: Int = 0,
    val memento: String = ""
)

