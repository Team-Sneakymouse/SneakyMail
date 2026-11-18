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
    fun `giveAll runs every reward even when some fail or throw`() {
        val rewards = listOf(MailReward.CommandReward("a"), MailReward.CommandReward("b"), MailReward.ItemReward("c", 1), MailReward.CommandReward("d"))
        val given = mutableListOf<MailReward>()
        val failures = mutableListOf<Pair<MailReward, String?>>()
        val failed = RewardItems.giveAll(rewards, { reward ->
            given += reward
            when (reward) {
                MailReward.CommandReward("b") -> false
                is MailReward.ItemReward -> throw IllegalStateException("boom")
                else -> true
            }
        }) { reward, error -> failures += reward to error?.message }
        assertEquals(rewards, given)
        assertEquals(2, failed)
        assertEquals(listOf<Pair<MailReward, String?>>(MailReward.CommandReward("b") to null, MailReward.ItemReward("c", 1) to "boom"), failures)
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
