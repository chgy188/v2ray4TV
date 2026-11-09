package com.v2ray.ang.tv.core

/**
 * Reads the running flag of a gomobile-generated CoreController.
 *
 * AAR revisions expose this flag differently: current AndroidLibXrayLite builds emit the
 * Java Bean pair getIsRunning()/setIsRunning(boolean) (the :app module reads it as the
 * Kotlin property isRunning), while older builds emitted a plain isRunning() method.
 * Reflection must accept both, or startLoop() succeeding still looks like a failure.
 *
 * Ownership distinction: XrayCore owns the controller's lifecycle; this object owns only
 * the stateless method lookup, which is pure JVM and unit-testable.
 */
internal object CoreStateReader {
    fun isRunning(instance: Any?): Boolean {
        if (instance == null) return false
        return runCatching {
            val method = instance.javaClass.methods.firstOrNull { candidate ->
                candidate.parameterTypes.isEmpty() &&
                    candidate.returnType == java.lang.Boolean.TYPE &&
                    (candidate.name == "getIsRunning" || candidate.name == "isRunning")
            }
            if (method == null) false else method.invoke(instance) as? Boolean ?: false
        }.getOrDefault(false)
    }
}
