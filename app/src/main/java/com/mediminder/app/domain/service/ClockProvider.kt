package com.mediminder.app.domain.service

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [Clock] so tests can freeze time. Binding lives in DI.
 */
@Singleton
class ClockProvider @Inject constructor(val clock: Clock)
