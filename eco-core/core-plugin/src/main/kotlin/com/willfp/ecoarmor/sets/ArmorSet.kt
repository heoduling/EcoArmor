package com.willfp.ecoarmor.sets

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.eco.core.display.Display
import com.willfp.eco.core.items.CustomItem
import com.willfp.eco.core.items.Items
import com.willfp.eco.core.items.builder.ItemBuilder
import com.willfp.eco.core.items.builder.ItemStackBuilder
import com.willfp.eco.core.recipe.Recipes
import com.willfp.eco.core.registry.Registrable
import com.willfp.ecoarmor.plugin
import com.willfp.ecoarmor.sets.ArmorSlot.Companion.getSlot
import com.willfp.ecoarmor.sets.ArmorUtils.getSetOnItem
import com.willfp.ecoarmor.sets.ArmorUtils.getShardSet
import com.willfp.ecoarmor.sets.ArmorUtils.isAdvanced
import com.willfp.ecoarmor.sets.ArmorUtils.setAdvanced
import com.willfp.ecoarmor.sets.ArmorUtils.setTier
import com.willfp.ecoarmor.upgrades.Tier
import com.willfp.ecoarmor.upgrades.Tiers
import com.willfp.libreforge.Holder
import com.willfp.libreforge.ItemProvidedHolder
import com.willfp.libreforge.SimpleHolder
import com.willfp.libreforge.ViolationContext
import com.willfp.libreforge.conditions.Conditions
import com.willfp.libreforge.effects.Effects
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.Locale
import java.util.Objects


@Suppress("DEPRECATION")
class ArmorSet(
    val id: String,
    val config: Config
) : Registrable {
    /** The advanced holder. */
    val advancedHolder: Holder

    /** The regular holder. */
    val regularHolder: Holder

    /** Items in set. */
    private val items = mutableMapOf<ArmorSlot, ItemStack>()

    /** Holders in set. */
    private val slotHolders = mutableMapOf<ArmorSlot, Holder>()

    /** Items in advanced set. */
    private val advancedItems = mutableMapOf<ArmorSlot, ItemStack>()

    /** Holders in advanced set. */
    private val advancedSlotHolders = mutableMapOf<ArmorSlot, Holder>()

    /** Advancement shard item. */
    val advancementShardItem: ItemStack

    val setRequirements = (config.getIntOrNull("amount_for_set") ?: 4).coerceIn(1, 4)

    val partialSetEnabled: Boolean = config.getBool("partialEffects.enabled")

    val stackedPartialSets: Boolean = config.getBool("partialEffects.stacked")

    val fullSetDisablesPartialSet: Boolean =
        if (config.has("partialEffects.disabledByFull")) config.getBool("partialEffects.disabledByFull")
        else if (config.has("fullSetDisablesPartialSet")) config.getBool("fullSetDisablesPartialSet")
        else true

    val partialHolders = mutableMapOf<Int, Holder>()

    /** Create a new Armor Set. */
    init {
        val conditions = Conditions.compile(
            config.getSubsections("conditions"),
            ViolationContext(plugin, "Armor Set $id")
        )

        val effects = Effects.compile(
            config.getSubsections("effects"),
            ViolationContext(plugin, "Armor Set $id")
        )

        val advancedEffects = Effects.compile(
            config.getSubsections("advancedEffects"),
            ViolationContext(plugin, "Armor Set $id (advanced)")
        )

        regularHolder = SimpleHolder(plugin.namespacedKeyFactory.create(id), effects, conditions)
        advancedHolder = SimpleHolder(plugin.namespacedKeyFactory.create("${id}_advanced"), advancedEffects, conditions)

        if (partialSetEnabled) {
            val amountsConfig = config.getSubsection("partialEffects.amounts")
            for (key in amountsConfig.getKeys(false)) {
                val amount = key.toIntOrNull() ?: continue
                val amountConfig = amountsConfig.getSubsection(key)
                
                val partialEffects = Effects.compile(
                    amountConfig.getSubsections("effects"),
                    ViolationContext(plugin, "Armor Set $id (partial-set-$amount)")
                )
                
                if (partialEffects.isNotEmpty()) {
                    partialHolders[amount] = SimpleHolder(plugin.namespacedKeyFactory.create("${id}_partial_set_$amount"), partialEffects, conditions)
                }
            }
        }

        for (slot in ArmorSlot.entries) {
            val slotConfig = config.getSubsection(slot.name.lowercase(Locale.ENGLISH))
            // Skip slots not configured in the set (no item defined).
            if (!slotConfig.has("item")) continue

            val item = construct(slot, slotConfig, false)
            items[slot] = item
            constructRecipe(slot, slotConfig, item)
            val advancedItem = construct(slot, slotConfig, true)
            advancedItems[slot] = advancedItem

            slotHolders[slot] = SimpleHolder(
                plugin.createNamespacedKey("${id}_${slot.name.lowercase()}"),
                Effects.compile(
                    slotConfig.getSubsections("effects"),
                    ViolationContext(plugin, "Armor Set $id)")
                        .with(slot.name.lowercase())
                ),
                Conditions.compile(
                    slotConfig.getSubsections("conditions"),
                    ViolationContext(plugin, "Armor Set $id)")
                        .with(slot.name.lowercase())
                )
            )

            advancedSlotHolders[slot] = SimpleHolder(
                plugin.createNamespacedKey("${id}_${slot.name.lowercase()}_advanced"),
                Effects.compile(
                    slotConfig.getSubsections("advancedEffects"),
                    ViolationContext(plugin, "Armor Set $id (advanced)")
                        .with(slot.name.lowercase())
                ),
                Conditions.compile(
                    slotConfig.getSubsections("conditions"),
                    ViolationContext(plugin, "Armor Set $id (advanced)")
                        .with(slot.name.lowercase())
                )
            )
        }

        advancementShardItem = constructShard()
    }

    private fun constructShard(): ItemStack {
        val shardLore = config.getStrings("shard.lore")
        shardLore.replaceAll { Display.PREFIX + it }
        val shard = ItemStackBuilder(
            Items.lookup(config.getString("shard.item"))
        )
            .setDisplayName(config.getFormattedString("shard.name"))
            .addLoreLines(shardLore)
            .writeMetaKey(plugin.namespacedKeyFactory.create("advancement-shard"), PersistentDataType.STRING, id)
            .build()
        if (config.getBool("shard.craftable")) {
            val recipeStrings = config.getStrings("shard.recipe")
            if (recipeStrings.isNotEmpty()) {
                plugin.scheduler.run {
                    Recipes.createAndRegisterRecipe(
                        plugin,
                        "${id}_shard",
                        shard,
                        recipeStrings,
                        config.getStringOrNull("shard.crafting-permission"),
                        config.getBool("shard.shapeless")
                    )
                }
            }
        }
        CustomItem(
            plugin.namespacedKeyFactory.create("shard_" + id.lowercase(Locale.getDefault())),
            { test: ItemStack? -> test != null && this == getShardSet(test) },
            shard
        ).register()
        return shard
    }

    private fun construct(
        slot: ArmorSlot,
        slotConfig: Config,
        advanced: Boolean
    ): ItemStack {
        val builder: ItemBuilder = ItemStackBuilder(Items.lookup(slotConfig.getString("item")).item).apply {
            setDisplayName(
                if (advanced) slotConfig.getFormattedString("advancedName") else slotConfig.getFormattedString(
                    "name"
                )
            )
        }
        builder.writeMetaKey(
            plugin.namespacedKeyFactory.create("set"),
            PersistentDataType.STRING,
            id
        ).writeMetaKey(
            plugin.namespacedKeyFactory.create("effective-durability"),
            PersistentDataType.INTEGER,
            slotConfig.getInt("effectiveDurability")
        )
        val itemStack = builder.build()
        setAdvanced(itemStack, advanced)
        val defaultTierId = slotConfig.getString("defaultTier")
        val defaultTier = Tiers.getByID(defaultTierId)
        if (defaultTier == null) {
            Bukkit.getLogger()
                .warning("Default tier specified in " + id + " " + slot.name.lowercase(Locale.getDefault()) + " is invalid! Defaulting to 'default'")
            setTier(itemStack, Tiers.defaultTier)
        } else {
            setTier(itemStack, defaultTier)
        }
        if (advanced) {
            CustomItem(
                plugin.namespacedKeyFactory.create(
                    "set_" + id.lowercase(Locale.getDefault()) + "_" + slot.name.lowercase(
                        Locale.getDefault()
                    ) + "_advanced"
                ), { test ->
                    if (getSlot(test) !== getSlot(itemStack)) {
                        return@CustomItem false
                    }
                    if (!isAdvanced(test)) {
                        return@CustomItem false
                    }
                    if (getSetOnItem(test) == null) {
                        return@CustomItem false
                    }
                    this == getSetOnItem(test)
                }, itemStack
            ).register()
        } else {
            CustomItem(
                plugin.namespacedKeyFactory.create(
                    "set_" + id.lowercase(Locale.getDefault()) + "_" + slot.name.lowercase(
                        Locale.getDefault()
                    )
                ),
                { test ->
                    if (getSlot(test) !== getSlot(itemStack)) {
                        return@CustomItem false
                    }
                    if (isAdvanced(test)) {
                        return@CustomItem false
                    }
                    if (getSetOnItem(test) == null) {
                        return@CustomItem false
                    }
                    this == getSetOnItem(test)
                }, itemStack
            ).register()
        }
        return itemStack
    }

    @Suppress("DEPRECATION")
    private fun constructRecipe(
        slot: ArmorSlot,
        slotConfig: Config,
        out: ItemStack
    ) {
        if (!slotConfig.getBool("craftable")) return

        val recipeStrings = slotConfig.getStrings("recipe")
        if (recipeStrings.isEmpty()) return

        val formattedOut = out.clone().apply {
            itemMeta = itemMeta?.apply {
                val metaLore = lore ?: emptyList()
                lore = metaLore.map { it.replace("%tier%", Tiers.defaultTier.displayName) }
            }
        }

        plugin.scheduler.run {
            Recipes.createAndRegisterRecipe(
                plugin,
                "${id}_${slot.name.lowercase(Locale.getDefault())}",
                formattedOut,
                recipeStrings,
                slotConfig.getStringOrNull("crafting-permission"),
                slotConfig.getBool("shapeless")
            )
        }
    }

    /**
     * Get item stack from slot.
     *
     * @param slot The slot.
     * @return The item, or null if the slot is not configured for this set.
     */
    fun getItemStack(slot: ArmorSlot): ItemStack? {
        return items[slot]
    }

    /**
     * Get item stack from slot.
     *
     * @param slot The slot.
     * @return The item, or null if the slot is not configured for this set.
     */
    fun getAdvancedItemStack(slot: ArmorSlot): ItemStack? {
        return advancedItems[slot]
    }

    /**
     * Get default tier for slot.
     *
     * @param slot The slot.
     * @return The tier.
     */
    fun getDefaultTier(slot: ArmorSlot?): Tier {
        slot ?: return Tiers.defaultTier
        val tier = Tiers.getByID(config.getSubsection(slot.name.lowercase()).getString("defaultTier"))
        return tier ?: Tiers.defaultTier
    }

    fun getSpecificHolder(itemStack: ItemStack): ItemProvidedHolder? {
        val slot = getSlot(itemStack) ?: return null
        val advanced = isAdvanced(itemStack)

        return if (advanced) {
            val holder = advancedSlotHolders[slot] ?: return null
            ItemProvidedHolder(holder, itemStack)
        } else {
            val holder = slotHolders[slot] ?: return null
            ItemProvidedHolder(holder, itemStack)
        }
    }

    override fun getID(): String {
        return this.id
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ArmorSet) {
            return false
        }

        return id == other.id
    }

    override fun hashCode(): Int {
        return Objects.hash(id)
    }

    override fun toString(): String {
        return ("ArmorSet{"
                + id
                + "}")
    }
}
