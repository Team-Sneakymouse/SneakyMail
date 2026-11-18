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
