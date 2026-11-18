package com.danidipp.sneakymail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MailRewardsTest {
    @Test
    fun `parses rewards as PostgreSQL prints jsonb`() {
        assertEquals(
            listOf(MailReward.CommandReward("lootbox {player}"), MailReward.ItemReward("item-EXP", 3)),
            MailRewards.parse("""[{"type": "command", "command": "lootbox {player}"}, {"item": "item-EXP", "type": "item", "amount": 3}]"""),
        )
        assertEquals(emptyList(), MailRewards.parse("[]"))
    }

    @Test
    fun `encodes the shape that sneakymail rewards_valid accepts`() {
        assertEquals("""[{"type":"command","command":"say {player}"}]""", MailRewards.encode(listOf(MailReward.CommandReward("say {player}"))))
        assertEquals("""[{"type":"item","item":"item-EXP","amount":2}]""", MailRewards.encode(listOf(MailReward.ItemReward("item-EXP", 2))))
    }

    @Test
    fun `an unknown reward type does not parse`() {
        assertFailsWith<Exception> { MailRewards.parse("""[{"type":"money","amount":5}]""") }
    }
}
