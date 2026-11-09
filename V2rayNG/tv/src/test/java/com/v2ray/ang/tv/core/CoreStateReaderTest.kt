package com.v2ray.ang.tv.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BeanStyleController {
    fun getIsRunning(): Boolean = true
}

class StoppedController {
    fun getIsRunning(): Boolean = false
}

class LegacyController {
    fun isRunning(): Boolean = true
}

class NoGetterController

class WrongReturnTypeController {
    fun getIsRunning(): Int = 1
}

class CoreStateReaderTest {
    @Test
    fun readsBeanStyleGetterUsedByCurrentAar() {
        assertTrue(CoreStateReader.isRunning(BeanStyleController()))
        assertFalse(CoreStateReader.isRunning(StoppedController()))
    }

    @Test
    fun stillReadsLegacyIsRunningMethod() {
        assertTrue(CoreStateReader.isRunning(LegacyController()))
    }

    @Test
    fun returnsFalseWhenGetterIsMissingOrHasWrongType() {
        assertFalse(CoreStateReader.isRunning(null))
        assertFalse(CoreStateReader.isRunning(NoGetterController()))
        assertFalse(CoreStateReader.isRunning(WrongReturnTypeController()))
    }
}
