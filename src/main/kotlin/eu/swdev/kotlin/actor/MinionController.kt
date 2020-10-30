package eu.swdev.kotlin.actor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Optional

/**
 * Allows the [MinionController] to interact with containers.
 *
 * In addition to interacting with containers a container controller can classify kinds of configuration changes.
 *
 * When containers are created they may contain some default configuration data that needs to be remembered.
 * When applying external configuration that data is changed. For example a container may contain some logging
 * configuration that is augmented by external configuration. When logging is reconfigured dynamically the default
 * configuration must still be available. For that means the [configureAndStartContainer] method returns a memento
 * that is passed into the [configureDynamic] method later on.
 *
 * @param C the type of configuration
 * @param M the type of a memento that is used to remember parts of the default configuration
 */
interface ContainerController<C, M> {

    fun createContainer(cfg: C): ContainerId

    fun configureAndStartContainer(cfg: C, id: ContainerId): M

    fun configureDynamic(cfg: C, id: ContainerId, memento: M)

    fun removeContainer(id: ContainerId)

    fun compare(cfg1: C, cfg2: C): CfgChanges

}

/**
 * Describes configuration changes.
 *
 * Configuration changes are classified with respect if they must take effect at container creation,
 * container start, or at runtime.
 */
data class CfgChanges(
    val create: Boolean,
    val start: Boolean,
    val run: Boolean,
) {
    val changed: Boolean get() = create || start || run
    val onlyRunChanged: Boolean get() = !(create || start) && run

}

class MinionController<C, M>(
    val ctrl: ContainerController<C, M>,
) : StateMachineActor<MinionController.State<C, M>>() {

    init {
        initialState(Idle())
    }

    suspend fun onDestroy() = send { doOnDestroy() }
    suspend fun onConfig(cfg: C) = send { doOnConfig(cfg) }
    suspend fun onGetState(): Deferred<String> = sendWithResultAsync { { doOnGetStatus(it) } }

    //
    //
    //

    interface State<C, M> : ActorState<String> {
        val logger: Logger

        suspend fun doOnDestroy(): State<C, M>
        suspend fun doOnConfig(cfg: C): State<C, M>
        suspend fun doOnGetStatus(cd: CompletableDeferred<String>): State<C, M>

        suspend fun doOnCreated(id: ContainerId): State<C, M>
        suspend fun doOnCreationFailed(): State<C, M>
        suspend fun doOnStarted(id: ContainerId, memento: M): State<C, M>
        suspend fun doOnStartFailed(id: ContainerId): State<C, M>
        suspend fun doOnRemoved(id: ContainerId): State<C, M>
        suspend fun doOnRemoveFailed(id: ContainerId): State<C, M>

    }

    abstract inner class BaseState : State<C, M> {
        override val logger: Logger = LoggerFactory.getLogger(javaClass)

        // states are identified by their simple class name (and not by the data they may contain)
        // -> whenever the state id changes the `enter` method is called
        override val stateId: String get() = javaClass.simpleName

        override suspend fun enter() {}

        override suspend fun doOnGetStatus(deferred: CompletableDeferred<String>) = run {
            deferred.complete(stateId)
            this
        }

        protected fun idleOrCreating(optCfg: Optional<C>) =
                optCfg.map<State<C, M>> { Creating(it) }.orElseGet { Idle() }

    }

    //
    //
    //

    interface UnexpectedEventsMixIn<C, M> : State<C, M> {

        fun unexpected(event: String, state: () -> State<C, M>): State<C, M> {
            logger.trace("process unexpected event - event: $event; state: $this")
            return state()
        }

        fun stayOnUnexpected(event: String) = unexpected(event) { this }
    }

    interface UnexpectedRemoveMixin<C, M> : UnexpectedEventsMixIn<C, M> {

        override suspend fun doOnRemoved(id: ContainerId) = stayOnUnexpected("onRemoved")

        override suspend fun doOnRemoveFailed(id: ContainerId) = stayOnUnexpected("onRemoveFailed")
    }

    interface UnexpectedStartMixin<C, M> : UnexpectedEventsMixIn<C, M> {

        override suspend fun doOnStarted(id: ContainerId, memento: M) = stayOnUnexpected("onStarted")

        override suspend fun doOnStartFailed(id: ContainerId) = stayOnUnexpected("onStartFailed")
    }

    interface UnexpectedCreateMixin<C, M> : UnexpectedEventsMixIn<C, M> {

        override suspend fun doOnCreated(id: ContainerId) = stayOnUnexpected("onCreated")

        override suspend fun doOnCreationFailed() = stayOnUnexpected("onCreationFailed")
    }

    //
    //
    //


    inner class Idle : BaseState(), UnexpectedCreateMixin<C, M>, UnexpectedStartMixin<C, M>, UnexpectedRemoveMixin<C, M> {
        override suspend fun doOnDestroy() = this
        override suspend fun doOnConfig(cfg: C) = Creating(cfg)
    }

    inner class Creating(val cfg: C) : BaseState(), UnexpectedRemoveMixin<C, M>, UnexpectedStartMixin<C, M> {

        override suspend fun enter() {
            // use the io dispatcher because container creation is a blocking operation
            withContext(Dispatchers.IO) {
                try {
                    val id = ctrl.createContainer(cfg)
                    logger.debug("container created - id: $id")
                    send { doOnCreated(id) }
                } catch (t: Throwable) {
                    logger.error("container creation failed", t)
                    send { doOnCreationFailed() }
                }
            }
        }

        override suspend fun doOnDestroy() = AbortCreating(Optional.empty())

        override suspend fun doOnConfig(cfg: C) = run {
            // determine if a configuration change requires a new container creation or not
            val changes = ctrl.compare(this.cfg, cfg)
            when {
                !changes.create -> Creating(cfg)
                else -> AbortCreating(Optional.of(cfg))
            }
        }

        override suspend fun doOnCreated(id: ContainerId) = Starting(cfg, id)

        // give up when container creation failed
        // -> another option would be to introduce another state that determines if a container for the configuration
        //    already exists (with the corresponding container name) and in that case removes that container
        //    before trying container creation again
        override suspend fun doOnCreationFailed() = Idle()
    }

    inner class AbortCreating(val optCfg: Optional<C>) : BaseState(), UnexpectedRemoveMixin<C, M>, UnexpectedStartMixin<C, M> {

        override suspend fun doOnDestroy() = AbortCreating(Optional.empty())

        override suspend fun doOnConfig(cfg: C) = AbortCreating(Optional.of(cfg))

        // the expected container creation happened -> remove it immediately
        override suspend fun doOnCreated(id: ContainerId) = Removing(optCfg, id)

        // the expected container creation failed -> change to `idle` or `creating` depending on the configuration
        override suspend fun doOnCreationFailed() = idleOrCreating(optCfg)
    }

    inner class Starting(val cfg: C, val id: ContainerId) : BaseState(), UnexpectedCreateMixin<C, M>,
            UnexpectedRemoveMixin<C, M> {

        override suspend fun enter() {
            // use the io dispatcher because container creation is a blocking operation
            withContext(Dispatchers.IO) {
                try {
                    val memento = ctrl.configureAndStartContainer(cfg, id)
                    logger.debug("container started - id: $id")
                    send { doOnStarted(id, memento) }
                } catch (t: Throwable) {
                    logger.error("container start failed - id: $id", t)
                    send { doOnStartFailed(id) }
                }
            }
        }

        override suspend fun doOnDestroy() = Removing(Optional.empty(), id)

        override suspend fun doOnConfig(cfg: C) = run {
            if (ctrl.compare(this.cfg, cfg).changed) {
                Removing(Optional.of(cfg), id)
            } else {
                this
            }
        }

        override suspend fun doOnStarted(id: ContainerId, memento: M) = if (id == this.id) {
            Running(this.cfg, id, memento)
        } else {
            stayOnUnexpected("onStarted (unexpected container id: $id)")
        }

        override suspend fun doOnStartFailed(id: ContainerId) = if (id == this.id) {
            Removing(Optional.of(cfg), id) // keep the configuration -> retry creation and start
        } else {
            stayOnUnexpected("onStartFailed (unexpected container id: $id)")
        }
    }

    inner class Running(val cfg: C, val id: ContainerId, val memento: M) : BaseState(),
            UnexpectedCreateMixin<C, M>, UnexpectedRemoveMixin<C, M>, UnexpectedStartMixin<C, M> {
        override suspend fun doOnDestroy() = Removing(Optional.empty(), id)

        override suspend fun doOnConfig(cfg: C) = run {
            val changes = ctrl.compare(this.cfg, cfg)
            when {
                !changes.changed -> this
                changes.onlyRunChanged -> {
                    withContext(Dispatchers.IO) {
                        ctrl.configureDynamic(cfg, id, memento)
                    }
                    Running(cfg, id, memento)
                }
                else -> Removing(Optional.of(cfg), id)
            }
        }
    }

    inner class Removing(val optCfg: Optional<C>, val id: ContainerId) : BaseState(), UnexpectedCreateMixin<C, M> {

        override suspend fun enter() {
            // use the io dispatcher because container creation is a blocking operation
            withContext(Dispatchers.IO) {
                try {
                    ctrl.removeContainer(id)
                    logger.debug("container removed - id: $id")
                    send { doOnRemoved(id) }
                } catch (t: Throwable) {
                    logger.error("container removal failed - id: $id", t)
                    send { doOnRemoveFailed(id) }
                }
            }
        }

        override suspend fun doOnDestroy() = Removing(Optional.empty(), id)

        override suspend fun doOnConfig(cfg: C) = Removing(Optional.of(cfg), id)

        override suspend fun doOnStarted(id: ContainerId, memento: M) = if (id == this.id) {
            // the minion has been reconfigured or destroyed while starting -> ignore corresponding `started` event
            this
        } else {
            stayOnUnexpected("onStarted (unexpected container id: $id)")
        }


        override suspend fun doOnStartFailed(id: ContainerId) = if (id == this.id) {
            // the minion has been reconfigured or destroyed while starting -> ignore corresponding `start failed` event
            this
        } else {
            stayOnUnexpected("onStartFailed (unexpected container id: $id)")
        }

        override suspend fun doOnRemoved(id: ContainerId) = if (id == this.id) {
            idleOrCreating(optCfg)
        } else {
            stayOnUnexpected("onnRemoved (unexpected container id: $id)")
        }


        override suspend fun doOnRemoveFailed(id: ContainerId) = if (id == this.id) {
            idleOrCreating(optCfg)
        } else {
            stayOnUnexpected("onRemoveFailed (unexpected container id: $id)")
        }
    }

}

data class ContainerId(val str: String) {
    override fun toString(): String {
        return str
    }
}
