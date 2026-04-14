package io.locklane.service

import com.intellij.util.messages.Topic

interface LockLaneStateListener {
    fun stateChanged()

    companion object {
        val TOPIC = Topic.create("LockLane State Changed", LockLaneStateListener::class.java)
    }
}
