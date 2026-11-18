package com.danidipp.sneakymail

import com.sneakyutils.util.DisplayOnlyInventoryHolder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** The /mail inventory: one icon per unclaimed mail of [player], at random slots. Display-only. */
class MailGUI(player: Player, mail: List<MailRecord>) : DisplayOnlyInventoryHolder {
    private val inventory: Inventory = Bukkit.getServer().createInventory(this, 27, Component.text("Mail"))

    init {
        openInventories.add(inventory)
        val bgItem = ItemStack(Material.JIGSAW)
        val bgMeta = bgItem.itemMeta
        bgMeta.isHideTooltip = true
        bgMeta.itemModel = BACKGROUND_MODEL
        bgItem.itemMeta = bgMeta
        inventory.setItem(0, bgItem)

        val mailItems = mutableListOf<ItemStack>()
        for (record in mail) {
            if (record.recipientUuid != player.uniqueId) continue
            val item = ItemStack(Material.RABBIT_FOOT)
            val meta = item.itemMeta
            val sender = record.senderName.ifEmpty { "Unknown" }

            meta.itemName(Component.text("Mail from ").append(MiniMessage.miniMessage().deserialize(sender))
                .colorIfAbsent(NamedTextColor.YELLOW))
            meta.lore(record.note.replace("\r\n", "\n").split('\n').map { line ->
                MiniMessage.miniMessage().deserialize(line)
                    .colorIfAbsent(NamedTextColor.GRAY)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            })
            meta.itemModel = ENVELOPE_MODEL
            meta.persistentDataContainer.set(SneakyMail.getInstance().mailKey, PersistentDataType.STRING, record.id.toString())

            item.itemMeta = meta
            mailItems += item
        }

        (1 until inventory.size).shuffled()
            .zip(mailItems)
            .forEach { (slot, item) -> inventory.setItem(slot, item) }
    }

    override fun getInventory(): Inventory {
        return inventory
    }

    companion object {
        // Pack item definitions (craftengine-resources); they replaced jigsaw/rabbit_foot custom_model_data 3024/75.
        private val BACKGROUND_MODEL = NamespacedKey("lom", "gui/mail")
        private val ENVELOPE_MODEL = NamespacedKey("lom", "items/envelope")
        val openInventories: MutableSet<Inventory> = mutableSetOf()
        val listener = object : Listener {
            @EventHandler
            fun onInventoryClick(event: InventoryClickEvent) {
                if (event.inventory.holder !is MailGUI) return
                event.isCancelled = true

                val claims = SneakyMail.getInstance().claims
                if (!SneakyMail.getInstance().isEnabled || claims == null) {
                    event.whoClicked.sendMessage(Component.text("SneakyMail is currently not available", NamedTextColor.RED))
                    event.whoClicked.closeInventory()
                    return
                }

                val item = event.currentItem ?: return
                val data = item.itemMeta.persistentDataContainer
                val mailId = data.get(SneakyMail.getInstance().mailKey, PersistentDataType.STRING)?.toLongOrNull() ?: return

                val slot = event.rawSlot
                val inventory = event.inventory
                if (slot !in 0 until inventory.size) return
                if (claims.collect(event.whoClicked as Player, mailId)) inventory.setItem(slot, null)
            }

            @EventHandler
            fun onInventoryDrag(event: InventoryDragEvent) {
                if (event.inventory.holder is MailGUI) event.isCancelled = true
            }

            @EventHandler
            fun onInventoryClose(event: InventoryCloseEvent) {
                if (event.inventory.holder !is MailGUI) return
                openInventories.remove(event.inventory)
            }
        }
    }

}
