package com.danidipp.sneakymail

/** Item reward helpers without Bukkit. */
internal object RewardItems {
    /** Item names among [rewards] that [exists] doesn't know, each once, in order. */
    fun missing(rewards: List<MailReward>, exists: (String) -> Boolean): List<String> =
        rewards.filterIsInstance<MailReward.ItemReward>().map { it.item }.distinct().filterNot(exists)

    /** [amount] split into stacks of at most [maxStack]. */
    fun stackAmounts(amount: Int, maxStack: Int): List<Int> {
        require(maxStack >= 1) { "maxStack must be at least 1" }
        if (amount <= 0) return emptyList()
        return List((amount + maxStack - 1) / maxStack) { index -> minOf(maxStack, amount - index * maxStack) }
    }
}
