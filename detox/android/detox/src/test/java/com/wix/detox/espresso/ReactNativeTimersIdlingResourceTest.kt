package com.wix.detox.espresso

import android.support.test.espresso.IdlingResource.ResourceCallback
import android.view.Choreographer
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.Timing
import com.nhaarman.mockito_kotlin.*
import org.assertj.core.api.Assertions.assertThat
import org.joor.Reflect
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers
import java.util.*

fun now() = System.nanoTime() / 1000000L

fun aTimer(interval: Int, isRepeating: Boolean) = aTimer(now() + interval + 10, interval, isRepeating)

fun aTimer(targetTime: Long, interval: Int, isRepeating: Boolean): Any {
    val timerClass = Class.forName("com.facebook.react.modules.core.Timing\$Timer")
    return Reflect.on(timerClass).create(-1, targetTime, interval, isRepeating).get()
}

fun aResourceCallback() = mock<ResourceCallback>()

class ReactNativeTimersIdlingResourceTest {

    private lateinit var reactAppContext: ReactApplicationContext
    private lateinit var choreographer: Choreographer
    private lateinit var pendingTimers: PriorityQueue<Any>

    @Before fun setUp() {
        pendingTimers = PriorityQueue(2) { _, _ -> 0}

        val timersNativeModule: Timing = mock()
        Reflect.on(timersNativeModule).set("mTimers", pendingTimers)
        Reflect.on(timersNativeModule).set("mTimerGuard", Object())

        choreographer = mock()

        reactAppContext = mock {
            on { hasNativeModule<Timing>(ArgumentMatchers.any()) }.doReturn(true)
            on { getNativeModule<Timing>(ArgumentMatchers.any()) }.doReturn(timersNativeModule)
        }
    }

    @Test fun `should be idle if there are no timers in queue`() {
        assertThat(uut().isIdleNow).isTrue()
    }

    @Test fun `should be busy if there's a pending timer`() {
        givenOneShotTimer(1500)
        assertThat(uut().isIdleNow).isFalse()
    }

    @Test fun `should be idle if pending timer is far away`() {
        givenOneShotTimer(1501)
        assertThat(uut().isIdleNow).isTrue()
    }

    @Test fun `should be idle if the only timer is a repeating one`() {
        givenRepeatingTimer(1500)
        assertThat(uut().isIdleNow).isTrue()
    }

    @Test fun `should be busy if a pending timer lies beyond a repeating one`() {
        givenRepeatingTimer(100)
        givenOneShotTimer(1499)
        assertThat(uut().isIdleNow).isFalse()
    }

    @Test fun `should be idle if the only timer is overdue`() {
        givenOverdueTimer()
        assertThat(uut().isIdleNow).isTrue()
    }

    @Test fun `should be busy if a pending timer lies beyond an overdue timer`() {
        givenOverdueTimer()
        givenOneShotTimer(123)
        assertThat(uut().isIdleNow).isFalse()
    }

    @Test fun `should be idle if paused`() {
        givenOneShotTimer(1500)

        val uut = uut().apply {
            pause()
        }

        assertThat(uut.isIdleNow).isTrue()
    }

    @Test fun `should be busy if paused and resumed`() {
        givenOneShotTimer(1500)

        val uut = uut().apply {
            pause()
            resume()
        }

        assertThat(uut.isIdleNow).isFalse()
    }

    @Test fun `should notify of transition to idle upon pausing`() {
        val callback = aResourceCallback()

        givenOneShotTimer(1500)

        with(uut()) {
            registerIdleTransitionCallback(callback)
            pause()
        }

        verify(callback).onTransitionToIdle()
    }

    @Test fun `should enqueue an is-idle check using choreographer when a callback gets registered`() {
        with(uut()) {
            registerIdleTransitionCallback(mock())
        }

        verify(choreographer).postFrameCallback(any())
    }

    @Test fun `should transition to idle when preregistered choreographer is dispatched`() {
        val callback = aResourceCallback()

        uut().registerIdleTransitionCallback(callback)

        argumentCaptor<Choreographer.FrameCallback>().apply {
            verify(choreographer).postFrameCallback(capture())
            firstValue.doFrame(0L)
        }
        verify(callback).onTransitionToIdle()
    }

    @Test fun `should NOT transition to idle if not idle when preregistered choreographer is dispatched`() {
        val callback = aResourceCallback()

        givenOneShotTimer(100)
        uut().registerIdleTransitionCallback(callback)

        argumentCaptor<Choreographer.FrameCallback>().apply {
            verify(choreographer).postFrameCallback(capture())

            firstValue.doFrame(0L)
        }
        verify(callback, never()).onTransitionToIdle()
    }

    @Test fun `should enqueue an additional idle check (using choreographer) if found busy`() {
        givenOneShotTimer(100)
        uut().isIdleNow
        verify(choreographer).postFrameCallback(any())
    }

    @Test fun `should NOT enqueue an additional idle check (using choreographer) if found idle`() {
        givenOneShotTimer(1501)
        uut().isIdleNow
        verify(choreographer, never()).postFrameCallback(any())
    }

    private fun uut() = ReactNativeTimersIdlingResourceKT(reactAppContext, choreographer)

    private fun givenOneShotTimer(interval: Int) = givenTimer(interval, false)
    private fun givenRepeatingTimer(interval: Int) = givenTimer(interval, true)

    private fun givenTimer(interval: Int, repeating: Boolean) {
        pendingTimers.add(aTimer(interval, repeating))
    }

    private fun givenOverdueTimer() {
        val timer = aTimer(now() - 100, 123, false)
        pendingTimers.add(timer)
    }
}
