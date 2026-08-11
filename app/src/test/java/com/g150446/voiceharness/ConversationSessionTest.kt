package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSessionTest {

    @Test
    fun turnsForInference_keepsPreviousExchangeAndCurrentUser() {
        val turns = listOf(
            ConversationTurn("user", "first"),
            ConversationTurn("assistant", "first answer"),
            ConversationTurn("user", "second"),
            ConversationTurn("assistant", "second answer"),
            ConversationTurn("user", "current")
        )

        assertEquals(
            listOf("second", "second answer", "current"),
            limitConversationTurnsForInference(turns).map { it.content }
        )
        assertEquals(5, turns.size)
    }
}
