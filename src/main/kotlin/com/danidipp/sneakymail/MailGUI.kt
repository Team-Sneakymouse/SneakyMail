package com.danidipp.sneakymail

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

class MailGUI(private val player: Player) : InventoryHolder {
    private val inventory: Inventory
    init {
        inventory = Bukkit.getServer().createInventory(this, 27, Component.text("Mail"))
        openInventories.add(inventory)
        val bgItem = ItemStack(Material.JIGSAW)
        val bgMeta = bgItem.itemMeta
        bgMeta.isHideTooltip = true
        bgMeta.setCustomModelData(3024)
        bgItem.itemMeta = bgMeta
        inventory.setItem(inventory.size - 1, bgItem)

        for (mail in SneakyMail.mail) {
            if(!mail.value.available) continue
            if (player.uniqueId != UUID.fromString(mail.value.recipient_uuid)) continue
            val item = ItemStack(Material.RABBIT_FOOT)
            val meta = item.itemMeta
            val sender = mail.value.sender_name.ifEmpty { "Unknown" }

            meta.itemName(Component.text("Mail from ").append(MiniMessage.miniMessage().deserialize(sender))
                .colorIfAbsent(NamedTextColor.YELLOW))
            meta.lore(mail.value.note.replace("\r\n", "\n").split('\n').map { line ->
                MiniMessage.miniMessage().deserialize(line)
                    .colorIfAbsent(NamedTextColor.GRAY)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            })
            meta.setCustomModelData(75)
            val data = meta.persistentDataContainer
            data.set(SneakyMail.getInstance().mailKey, PersistentDataType.STRING, mail.key)

            item.itemMeta = meta
            val overflow = inventory.addItem(item)
            if(overflow.isNotEmpty()) {
                break
            }
        }
    }

    override fun getInventory(): Inventory {
        return inventory
    }

    companion object {
        val openInventories: MutableSet<Inventory> = mutableSetOf()
        val listener = object : Listener {
            @EventHandler
            fun onInventoryClick(event: InventoryClickEvent) {
                if (event.inventory.holder !is MailGUI) return
                event.isCancelled = true

                if (!SneakyMail.getInstance().isEnabled) {
                    event.whoClicked.sendMessage(Component.text("SneakyMail is currently not available", NamedTextColor.RED))
                    event.whoClicked.closeInventory()
                    return
                }

                val item = event.currentItem ?: return
                val data = item.itemMeta.persistentDataContainer
                val mailId = data.get(SneakyMail.getInstance().mailKey, PersistentDataType.STRING) ?: return
                val mail = SneakyMail.mail[mailId] ?: return

                val result = mail.redeem(event.whoClicked as Player)
                if (result) {
                    event.whoClicked.sendMessage("Mail redeemed")
                    event.whoClicked.closeInventory()
                } else {
                    event.whoClicked.sendMessage("Something went wrong, please tell Dani")
                    event.whoClicked.closeInventory()
                }
            }

            @EventHandler
            fun onInventoryClose(event: InventoryCloseEvent) {
                if (event.inventory.holder !is MailGUI) return
                openInventories.remove(event.inventory)
            }
        }
    }

}