package com.danidipp.sneakymail

import kotlin.test.Test
import kotlin.test.assertEquals

class RewardItemsTest {
    @Test
    fun `missing lists unknown item names once, ignoring commands`() {
        val rewards = listOf(
            MailReward.ItemReward("known", 1), MailReward.ItemReward("gone", 2), MailReward.CommandReward("gone"),
            MailReward.ItemReward("gone", 3), MailReward.ItemReward("other", 1),
        )
        assertEquals(listOf("gone", "other"), RewardItems.missing(rewards) { it == "known" })
        assertEquals(emptyList(), RewardItems.missing(listOf(MailReward.CommandReward("x"))) { false })
    }

    @Test
    fun `giveAll runs every reward, reporting each given one before the next starts`() {
        val rewards = listOf(MailReward.CommandReward("a"), MailReward.CommandReward("b"), MailReward.ItemReward("c", 1), MailReward.CommandReward("d"))
        val events = mutableListOf<String>()
        val failed = RewardItems.giveAll(rewards, { index, reward ->
            events += "give $index"
            when (reward) {
                MailReward.CommandReward("b") -> false
                is MailReward.ItemReward -> throw IllegalStateException("boom")
                else -> true
            }
        }, given = { index, _ -> events += "given $index" }) { index, _, error -> events += "failed $index ${error?.message}" }
        assertEquals(2, failed)
        assertEquals(listOf("give 0", "given 0", "give 1", "failed 1 null", "give 2", "failed 2 boom", "give 3", "given 3"), events)
    }

    @Test
    fun `stack amounts split by the maximum stack size`() {
        assertEquals(listOf(1), RewardItems.stackAmounts(1, 64))
        assertEquals(listOf(64), RewardItems.stackAmounts(64, 64))
        assertEquals(listOf(64, 1), RewardItems.stackAmounts(65, 64))
        assertEquals(List(36) { 64 }, RewardItems.stackAmounts(2304, 64))
        assertEquals(listOf(1, 1, 1), RewardItems.stackAmounts(3, 1))
        assertEquals(listOf(16, 4), RewardItems.stackAmounts(20, 16))
        assertEquals(emptyList(), RewardItems.stackAmounts(0, 64))
    }
}
