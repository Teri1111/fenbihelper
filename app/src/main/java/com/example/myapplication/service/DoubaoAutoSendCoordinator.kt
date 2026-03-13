package com.example.myapplication.service

import android.os.SystemClock

object DoubaoAutoSendCoordinator {

    private const val REQUEST_TIMEOUT_MS = 15_000L
    private const val INITIAL_SETTLE_DELAY_MS = 900L
    private const val MIN_ATTEMPT_INTERVAL_MS = 350L
    private const val MAX_ATTEMPTS = 8

    @Volatile
    private var pendingUntilElapsedRealtime: Long = 0L

    @Volatile
    private var readyAtElapsedRealtime: Long = 0L

    @Volatile
    private var lastAttemptAtElapsedRealtime: Long = 0L

    @Volatile
    private var attemptCount: Int = 0

    fun markPending() {
        val now = SystemClock.elapsedRealtime()
        pendingUntilElapsedRealtime = now + REQUEST_TIMEOUT_MS
        readyAtElapsedRealtime = now + INITIAL_SETTLE_DELAY_MS
        lastAttemptAtElapsedRealtime = 0L
        attemptCount = 0
    }

    fun clear() {
        pendingUntilElapsedRealtime = 0L
        readyAtElapsedRealtime = 0L
        lastAttemptAtElapsedRealtime = 0L
        attemptCount = 0
    }

    fun isPending(): Boolean {
        val pendingUntil = pendingUntilElapsedRealtime
        if (pendingUntil <= 0L) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now > pendingUntil) {
            clear()
            return false
        }
        return true
    }

    fun shouldAttemptNow(): Boolean {
        if (!isPending()) {
            return false
        }
        if (attemptCount >= MAX_ATTEMPTS) {
            clear()
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now < readyAtElapsedRealtime) {
            return false
        }
        return now - lastAttemptAtElapsedRealtime >= MIN_ATTEMPT_INTERVAL_MS
    }

    fun recordAttempt(clicked: Boolean) {
        lastAttemptAtElapsedRealtime = SystemClock.elapsedRealtime()
        if (clicked) {
            attemptCount += 1
        }
    }
}

