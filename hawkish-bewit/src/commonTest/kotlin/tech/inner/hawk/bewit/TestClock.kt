package tech.inner.hawk.bewit

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A test clock, generally used for testing and prototyping.
 */
class TestClock(var seed: Instant): Clock {
  override fun now(): Instant = seed
  fun fastForward(duration: Duration) {
    seed = seed.plus(duration)
  }
}

/**
 * Access pattern similar to `Clock.System`.
 */
@Suppress("FunctionName")
fun Clock.Companion.Fixed(seed: Instant = Clock.System.now()) = TestClock(seed)
