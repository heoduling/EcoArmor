package com.willfp.ecoarmor.upgrades

import com.willfp.ecoarmor.api.event.ArmorTierEvent
import com.willfp.ecoarmor.sets.ArmorUtils
import com.willfp.ecoarmor.plugin
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack

object CrystalListener : Listener {
    @Suppress("USELESS_ELVIS")
    @EventHandler
    fun onDrag(event: InventoryClickEvent) {
        if (event.whoClicked.gameMode == GameMode.CREATIVE) {
            return
        }
        val current = event.currentItem ?: return
        val cursor = event.cursor ?: return

        val crystalTier = ArmorUtils.getCrystalTier(cursor) ?: return

        if (ArmorUtils.getSetOnItem(current) == null) {
            return
        }

        if (current.type == Material.AIR) {
            return
        }
        val previousTier = ArmorUtils.getTier(current)
        var allowed = false
        val requiredTiers = crystalTier.getRequiredTiersForApplication()
        if (requiredTiers.isEmpty() || requiredTiers.contains(previousTier)) {
            allowed = true
        }
        if (!allowed) {
            return
        }
        val player = event.whoClicked as? Player ?: return
        val tierEvent = ArmorTierEvent(player, current, crystalTier, previousTier)
        Bukkit.getPluginManager().callEvent(tierEvent)
        if (tierEvent.isCancelled) {
            return
        }

        // Cancel immediately, then defer item modification to next tick
        // to avoid triggering synchronous entity attribute recalculation
        // which can cause ReferenceOpenHashSet corruption in Folia's tracker.
        event.isCancelled = true

        val rawSlot = event.rawSlot
        val cursorAmount = cursor.amount
        val view = event.view

        plugin.scheduler.run {
            if (!player.isOnline) return@run

            // Get fresh references to the items at the clicked slot
            val currentItem = view.getItem(rawSlot) ?: return@run
            val cursorItem = player.itemOnCursor

            if (ArmorUtils.getSetOnItem(currentItem) == null) return@run

            ArmorUtils.setTier(currentItem, crystalTier)
            view.setItem(rawSlot, currentItem)

            if (cursorAmount > 1) {
                cursorItem.amount -= 1
                player.setItemOnCursor(cursorItem)
            } else {
                player.setItemOnCursor(ItemStack(Material.AIR))
            }
        }
    }

    @EventHandler
    fun onPlaceCrystal(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (ArmorUtils.getCrystalTier(item) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlaceCrystalEntity(event: EntityPlaceEvent) {
        val player = event.player ?: return
        val item = player.inventory.getItem(event.hand) ?: return
        if (ArmorUtils.getCrystalTier(item) != null) {
            event.isCancelled = true
        }
    }
}