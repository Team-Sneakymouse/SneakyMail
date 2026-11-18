package com.danidipp.sneakymail

/** Item reward helpers without Bukkit. */
internal object RewardItems {
    /** Item names among [rewards] that [exists] doesn't know, each once, in order. */
    fun missing(rewards: List<MailReward>, exists: (String) -> Boolean): List<String> =
        rewards.filterIsInstance<MailReward.ItemReward>().map { it.item }.distinct().filterNot(exists)

    /**
     * Gives every reward with [give], which returns false for a reward that didn't work. A reward that fails or throws
     * goes to [failed] (with the exception, if any) and the rest still run. Returns how many failed.
     */
    fun giveAll(rewards: List<MailReward>, give: (MailReward) -> Boolean, failed: (MailReward, Exception?) -> Unit): Int {
        var failures = 0
        for (reward in rewards) {
            val error = try {
                if (give(reward)) continue
                null
            } catch (error: Exception) {
                error
            }
            failures++
            failed(reward, error)
        }
        return failures
    }

    /** [amount] split into stacks of at most [maxStack]. */
    fun stackAmounts(amount: Int, maxStack: Int): List<Int> {
        require(maxStack >= 1) { "maxStack must be at least 1" }
        if (amount <= 0) return emptyList()
        return List((amount + maxStack - 1) / maxStack) { index -> minOf(maxStack, amount - index * maxStack) }
    }
}
