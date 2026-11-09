package com.v2ray.ang.tv.core

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Reflection adapter for the gomobile-generated libv2ray.aar.
 *
 * Boundary: the AAR is a replaceable external artifact built from AndroidLibXrayLite /
 * AndroidLibV2rayLite, and generated signatures differ between releases (notably
 * `CoreController.startLoop(String,int)` vs `(String,long)`, and the set of
 * CoreCallbackHandler methods). A compile-time binding would force this API-21 module to be
 * rebuilt and re-verified for every AAR swap, and would make the module uncompilable on a
 * checkout that has not downloaded the AAR yet. This is the only place that names AAR
 * classes; runtime failures are surfaced as a normal error instead of a crash.
 *
 * Removal condition: pin one AAR revision as a required compile-time dependency of this
 * module and drop support for user-supplied AAR replacements; then replace this object with
 * direct `libv2ray.*` calls.
 */
object XrayCore {
    private const val SEQ_CLASS = "go.Seq"
    private const val LIB_CLASS = "libv2ray.Libv2ray"
    private const val HANDLER_CLASS = "libv2ray.CoreCallbackHandler"

    @Volatile
    private var controller: Any? = null

    @Volatile
    private var initialized = false
    private val lock = Any()

    /** initCoreEnv is process-global and must run exactly once, even after a reconnect. */
    fun initialize(context: Context, deviceId: String) {
        synchronized(lock) {
            if (!initialized) {
                val seq = Class.forName(SEQ_CLASS)
                seq.getMethod("setContext", Context::class.java)
                    .invoke(null, context.applicationContext)
                val lib = Class.forName(LIB_CLASS)
                val init = lib.methods.firstOrNull {
                    it.name == "initCoreEnv" && it.parameterTypes.size == 2
                } ?: throw IllegalStateException("libv2ray.aar has no initCoreEnv(String,String)")
                val assetPath = context.getDir("assets", Context.MODE_PRIVATE).absolutePath
                init.invoke(null, assetPath, deviceId)
                initialized = true
            }
        }
    }

    fun start(config: String, tunFd: Int) {
        synchronized(lock) {
            stopLocked()
            // The :app module keeps one CoreController for the process and calls
            // startLoop/stopLoop on it; reuse it here instead of allocating a Go object per
            // connection.
            var instance: Any? = controller
            if (instance == null) {
                val lib = Class.forName(LIB_CLASS)
                val handlerInterface = Class.forName(HANDLER_CLASS)
                val handler = Proxy.newProxyInstance(
                    handlerInterface.classLoader,
                    arrayOf(handlerInterface),
                    InvocationHandler { _, method, _ ->
                        when (method.returnType.name) {
                            "long" -> 0L
                            "int" -> 0
                            "boolean" -> false
                            else -> null
                        }
                    }
                )
                val factory = lib.methods.firstOrNull {
                    it.name == "newCoreController" && it.parameterTypes.size == 1
                } ?: throw IllegalStateException("libv2ray.aar has no newCoreController(handler)")
                instance = factory.invoke(null, handler)
                    ?: throw IllegalStateException("libv2ray.aar returned no CoreController")
                controller = instance
            }
            val target = requireNotNull(instance)

            val startLoop = target.javaClass.methods.firstOrNull {
                it.name == "startLoop" && it.parameterTypes.size == 2
            } ?: throw IllegalStateException("CoreController has no startLoop(String, fd)")
            val fdArgument: Any = when (startLoop.parameterTypes[1].name) {
                "long" -> tunFd.toLong()
                "int" -> tunFd
                else -> throw IllegalStateException(
                    "Unsupported startLoop fd type: ${startLoop.parameterTypes[1]}"
                )
            }
            startLoop.invoke(target, config, fdArgument)
            if (!isRunning()) {
                throw IllegalStateException("Xray core did not enter running state")
            }
        }
    }

    /** The core controller, not a UI flag, is the authority for the running state. */
    fun isRunning(): Boolean = CoreStateReader.isRunning(controller)

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        val instance = controller ?: return
        runCatching {
            instance.javaClass.methods.firstOrNull {
                it.name == "stopLoop" && it.parameterTypes.isEmpty()
            }?.invoke(instance)
        }
    }
}
