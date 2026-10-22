package verification;

import com.willfp.ecoarmor.sets.*;
import com.willfp.ecoarmor.api.event.*;
import com.willfp.ecoarmor.upgrades.Tiers;
import com.willfp.libreforge.ProvidedHolder;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.management.ThreadMXBean;

/** Isolated test only. Baseline loads only ArmorUtils and its nested classes from the supplied JAR. */
public final class EcoArmorProbe extends JavaPlugin implements Listener {
    Class<?> old; Object oldInstance; Method oldScan, newScan;
    final AtomicInteger checks = new AtomicInteger();
    final List<String> events = new ArrayList<>();
    volatile Object sink; int failures; boolean recording;
    NamespacedKey setKey = new NamespacedKey("ecoarmor", "set");
    NamespacedKey advancedKey = new NamespacedKey("ecoarmor", "advanced");

    public void onEnable() {
        try {
            ClassLoader parent = ArmorUtils.class.getClassLoader();
            var loader = new URLClassLoader(new URL[]{new java.io.File(System.getProperty("ecoarmor.baseline")).toURI().toURL()}, parent) {
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.startsWith("com.willfp.ecoarmor.sets.ArmorUtils")) return super.loadClass(name, resolve);
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name); if (c == null) c = findClass(name);
                        if (resolve) resolveClass(c); return c;
                    }
                }
            };
            old = loader.loadClass(ArmorUtils.class.getName()); oldInstance = old.getField("INSTANCE").get(null);
            oldScan = old.getMethod("getActiveHolders", LivingEntity.class);
            newScan = ArmorUtils.class.getMethod("getActiveHolders", LivingEntity.class);
            // Keep automatic polling from contributing unrelated scans/events to the oracle comparison.
            com.willfp.libreforge.HolderProviderKt.unregisterHolderProvidersOwnedBy(parent);
            Bukkit.getPluginManager().registerEvents(this, this);
            getLogger().info("[EA] READY baseline=" + old.getProtectionDomain().getCodeSource().getLocation());
        } catch (Throwable e) { fail(e); }
    }
    void fail(Throwable e) { failures++; getLogger().severe("[EA] FAIL " + e); e.printStackTrace(); }
    void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks.incrementAndGet(); }
    @EventHandler public void equip(PlayerArmorSetEquipEvent e) { if (recording) events.add("equip:"+e.getSet().getId()+":"+e.getAdvanced()); }
    @EventHandler public void unequip(PlayerArmorSetUnequipEvent e) { if (recording) events.add("unequip:"+e.getSet().getId()+":"+e.getAdvanced()); }
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player p) p.getScheduler().run(this, task -> {
            try {
                check(Bukkit.isOwnedByCurrentRegion(p), "player owner");
                if (args.length > 0 && args[0].equals("bench")) startBench(p);
                else { semantics(p); eventSequence(p, true); }
            } catch (Throwable e) { fail(e); }
        }, () -> fail(new IllegalStateException("player retired")));
        return true;
    }
    Object oldCall(String name, Class<?> type, Object item) throws Exception {
        try { return old.getMethod(name, type).invoke(null, item); }
        catch (InvocationTargetException e) { throw e; }
    }
    String outcome(boolean baseline, String name, ItemStack i) {
        try {
            Object r = baseline ? oldCall(name, ItemStack.class, i) :
                name.equals("getSetOnItem") ? ArmorUtils.getSetOnItem(i) : ArmorUtils.isAdvanced(i);
            return String.valueOf(r);
        } catch (Throwable e) {
            while (e instanceof InvocationTargetException) e = e.getCause();
            return "throws:" + e.getClass().getName();
        }
    }
    ItemStack item(Material material, String set, int advanced, boolean rich) {
        var i = new ItemStack(material); var m = i.getItemMeta();
        if (m != null) {
            if (set != null) m.getPersistentDataContainer().set(setKey, PersistentDataType.STRING, set);
            if (advanced >= 0) m.getPersistentDataContainer().set(advancedKey, PersistentDataType.INTEGER, advanced);
            if (rich) {
                var lore = new ArrayList<String>(); for (int n=0;n<24;n++) lore.add("Armor performance fixture line " + n);
                m.setLore(lore); m.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
                for(int n=0;n<16;n++) m.getPersistentDataContainer().set(new NamespacedKey(this,"payload_"+n), PersistentDataType.STRING, "unrelated-data-"+n);
            }
            i.setItemMeta(m);
        }
        return CraftItemStack.asCraftCopy(i);
    }
    ItemStack[] armor(String set, int advanced, boolean rich) {
        return new ItemStack[]{item(Material.DIAMOND_BOOTS,set,advanced,rich), item(Material.DIAMOND_LEGGINGS,set,advanced,rich),
            item(Material.DIAMOND_CHESTPLATE,set,advanced,rich), item(Material.DIAMOND_HELMET,set,advanced,rich)};
    }
    @SuppressWarnings("unchecked") Collection<ProvidedHolder> scan(boolean baseline, LivingEntity e) throws Exception {
        return (Collection<ProvidedHolder>)(baseline?oldScan:newScan).invoke(baseline?oldInstance:ArmorUtils.INSTANCE, e);
    }
    List<String> signature(Collection<ProvidedHolder> holders) {
        var result = new ArrayList<String>();
        for (var h: holders) result.add(h.getHolder().getId()+":"+(h.getProvider() instanceof ItemStack i ? i.getType()+":"+i.hashCode() : "simple")+":"+h.hashCode());
        return result;
    }
    LivingEntity detached(ItemStack[] items) {
        var equipment = (EntityEquipment)Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{EntityEquipment.class}, (p,m,a) -> {
            if (m.getName().equals("getArmorContents")) return items.clone();
            throw new UnsupportedOperationException(m.getName());
        });
        return (LivingEntity)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{LivingEntity.class},(p,m,a)->{
            if(m.getName().equals("getEquipment")) return equipment;
            throw new UnsupportedOperationException(m.getName());
        });
    }
    static final class CountedItem extends ItemStack {
        final ItemStack delegate; int metaReads;
        CountedItem(ItemStack i) { super(i.getType()); delegate=i; }
        public ItemMeta getItemMeta() { metaReads++; return delegate.getItemMeta(); }
        public PersistentDataContainerView getPersistentDataContainer() { return delegate.getPersistentDataContainer(); }
        public int hashCode() { return delegate.hashCode(); }
    }
    void compare(LivingEntity e, String name) throws Exception {
        check(signature(scan(true,e)).equals(signature(scan(false,e))),"holder result "+name);
    }
    void semantics(Player p) throws Exception {
        p.setInvulnerable(true);p.setGravity(false);p.setGameMode(GameMode.CREATIVE);
        check(ArmorSets.getByID("probe_a") != null && ArmorSets.getByID("probe_b") != null,"fixture definitions loaded");
        for (var material : List.of(Material.AIR,Material.DIAMOND_BOOTS,Material.DIAMOND_LEGGINGS,Material.DIAMOND_CHESTPLATE,
                Material.DIAMOND_HELMET,Material.PLAYER_HEAD,Material.CARVED_PUMPKIN,Material.ELYTRA,Material.STONE)) {
            for (String set : Arrays.asList(null,"missing_definition","probe_a")) for (int advanced : new int[]{-1,0,1,2}) {
                ItemStack i=item(material,set,advanced,true);
                for (ItemStack form : List.of(i,new ItemStack(i))) {
                    byte[] before=form.getType().isAir()?new byte[0]:form.serializeAsBytes();
                    for(String method:List.of("getSetOnItem","isAdvanced")) check(outcome(true,method,form).equals(outcome(false,method,form)),"marker "+method+material+set+advanced);
                    check(Arrays.equals(before,form.getType().isAir()?new byte[0]:form.serializeAsBytes()),"read leaves item unchanged");
                }
            }
        }
        for (String key:List.of("set","advanced")) {
            var i=item(Material.DIAMOND_HELMET,null,-1,true);var m=i.getItemMeta();
            if(key.equals("set")) m.getPersistentDataContainer().set(setKey,PersistentDataType.INTEGER,1);
            else m.getPersistentDataContainer().set(advancedKey,PersistentDataType.STRING,"1");
            i.setItemMeta(m);
            for(String method:List.of("getSetOnItem","isAdvanced"))check(outcome(true,method,i).equals(outcome(false,method,i)),"wrong marker type "+key+method);
        }
        var mut=item(Material.DIAMOND_HELMET,"probe_a",0,true);
        check(!ArmorUtils.isAdvanced(mut),"ordinary");ArmorUtils.setAdvanced(mut,true);check(ArmorUtils.isAdvanced(mut),"same object advance fresh");
        ArmorUtils.setAdvanced(mut,false);check(!ArmorUtils.isAdvanced(mut),"same object downgrade fresh");
        ArmorUtils.setTier(mut,Tiers.INSTANCE.getDefaultTier());byte[] tierBefore=mut.serializeAsBytes();
        ArmorUtils.getSetOnItem(mut);ArmorUtils.isAdvanced(mut);check(Arrays.equals(tierBefore,mut.serializeAsBytes()),"tier attributes preserved by reads");
        var beforeDefinition=ArmorSets.getByID("probe_a");
        ArmorSets.INSTANCE.acceptConfig((com.willfp.ecoarmor.libreforge.loader.LibreforgePlugin)Bukkit.getPluginManager().getPlugin("EcoArmor"),"probe_a",beforeDefinition.getConfig());
        var afterDefinition=ArmorSets.getByID("probe_a");
        check(afterDefinition!=beforeDefinition,"definition replaced");
        check(ArmorUtils.getSetOnItem(mut)==afterDefinition && oldCall("getSetOnItem",ItemStack.class,mut)==afterDefinition,"same item sees reloaded definition immediately");
        var options=List.of("probe_a","probe_b");
        for(int mask=0;mask<256;mask++) {
            var items=armor("probe_a",0,mask%2==0);
            for(int n=0;n<4;n++) {int v=(mask>>(n*2))&3; if(v==0)items[n]=null; else if(v==2)items[n]=item(items[n].getType(),options.get(1),1,true); else if(v==3)ArmorUtils.setAdvanced(items[n],true);}
            compare(detached(items),"mixed "+mask);
            p.getInventory().setArmorContents(items);compare(p,"player "+mask);
        }
        for(var material:List.of(Material.PLAYER_HEAD,Material.CARVED_PUMPKIN,Material.ELYTRA,Material.STONE)) {
            var items=armor("probe_a",1,true);items[3]=item(material,"probe_a",1,true);compare(detached(items),"special slot "+material);
        }
        var stand=p.getWorld().spawn(p.getLocation(),ArmorStand.class);
        try {stand.setGravity(false);for(int n=0;n<16;n++){var items=armor(n%2==0?"probe_a":"probe_b",n%3==0?1:0,true);if(n%4==0)items[0]=null;stand.getEquipment().setArmorContents(items);compare(stand,"live nonplayer "+n);}}finally{stand.remove();}
        for(String mode:List.of("ordinary","full-normal","full-advanced")) {
            var raw=armor(mode.equals("ordinary")?null:"probe_a",mode.endsWith("advanced")?1:0,true);
            var counted=new ItemStack[4]; for(int n=0;n<4;n++)counted[n]=new CountedItem(raw[n]);
            LivingEntity e=detached(counted);scan(true,e);int oldReads=0;for(var i:counted){oldReads+=((CountedItem)i).metaReads;((CountedItem)i).metaReads=0;}
            scan(false,e);int newReads=0;for(var i:counted)newReads+=((CountedItem)i).metaReads;
            boolean candidate=System.getProperty("ecoarmor.variant","new").equals("new");
            check(newReads==(candidate?0:oldReads),"active direct ItemMeta reads "+mode);
            getLogger().info("[EA] META mode="+mode+" old="+oldReads+" new="+newReads);
        }
        p.getInventory().setArmorContents(new ItemStack[4]);
        getLogger().info("[EA] SEMANTICS_DONE checks="+checks);
    }
    void eventSequence(Player p, boolean baseline) throws Exception {
        // Temporarily replace the player's provider so only the selected implementation owns its state.
        recording=false;events.clear();ArmorUtils.clearCache();old.getMethod("clearCache").invoke(null);
        p.getInventory().setArmorContents(new ItemStack[4]);scan(baseline,p);
        p.getScheduler().runDelayed(this,t->{try{
            recording=true;events.clear(); eventStep(p,baseline,0,new ArrayList<>());
        }catch(Throwable e){fail(e);}},null,3);
    }
    final List<String> oldEventTrace=new ArrayList<>();
    void eventStep(Player p, boolean baseline, int step, List<String> trace) throws Exception {
        if(step==5){
            recording=false;trace.addAll(events);
            if(baseline){oldEventTrace.clear();oldEventTrace.addAll(trace);eventSequence(p,false);}
            else {check(oldEventTrace.equals(trace),"event trace "+oldEventTrace+" vs "+trace);check(!trace.isEmpty(),"events observed");getLogger().info("[EA] EVENTS "+trace);nativeSequence(p);}
            return;
        }
        // baseline first; the normal provider may also run, so clear candidate state on baseline rounds.
        ItemStack[] next=switch(step){case 0->armor("probe_a",0,true);case 1->armor("probe_a",1,true);case 2->armor("probe_b",0,true);default->new ItemStack[4];};
        p.getInventory().setArmorContents(next);scan(baseline,p);
        if(step==4){p.getInventory().setArmorContents(armor("probe_a",1,true));scan(baseline,p);p.getInventory().setArmorContents(new ItemStack[4]);scan(baseline,p);}
        p.getScheduler().runDelayed(this,t->{try{eventStep(p,baseline,step+1,trace);}catch(Throwable e){fail(e);}},null,3);
    }
    record Fixture(String name, LivingEntity entity) {}
    ItemStack[] enderite(boolean advanced){
        var set=ArmorSets.getByID("enderite");if(set==null)throw new AssertionError("supplied enderite set");
        var slots=new ArmorSlot[]{ArmorSlot.BOOTS,ArmorSlot.LEGGINGS,ArmorSlot.CHESTPLATE,ArmorSlot.HELMET};var result=new ItemStack[4];
        for(int n=0;n<4;n++)result[n]=CraftItemStack.asCraftCopy(advanced?set.getAdvancedItemStack(slots[n]):set.getItemStack(slots[n]));return result;
    }
    void nativeSequence(Player p){
        com.willfp.libreforge.HolderProviderKt.registerHolderProviderOwnedBy(d->d.getDispatcher()==p?ArmorUtils.INSTANCE.getActiveHolders(p):List.of(),getClass().getClassLoader());
        p.getInventory().clear();p.getInventory().setArmorContents(new ItemStack[4]);
        com.willfp.libreforge.HolderProviderKt.forceRefreshHolders(com.willfp.libreforge.DispatcherKt.toDispatcher(p));
        p.getScheduler().runDelayed(this,t->{try{double base=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();nativeStep(p,0,base);}catch(Throwable e){fail(e);}},null,3);
    }
    void nativeStep(Player p,int step,double base){
        if(step==4){
            double restored=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();check(Math.abs(restored-base)<0.00001,"native health restored "+restored+" base "+base);
            getLogger().info("[EA] REGRESSION_DONE checks="+checks+" failures="+failures);return;
        }
        ItemStack[] items=step==3?new ItemStack[4]:enderite(step==1);if(step==2)items[3]=null;
        p.getInventory().setArmorContents(items);com.willfp.libreforge.HolderProviderKt.forceRefreshHolders(com.willfp.libreforge.DispatcherKt.toDispatcher(p));
        p.getScheduler().runDelayed(this,t->{try{
            check(Bukkit.isOwnedByCurrentRegion(p),"native owner");
            var holders=ArmorUtils.INSTANCE.getActiveHolders(p);check(step==3?holders.isEmpty():!holders.isEmpty(),"native holders step "+step);
            if(step==0)check(Math.abs(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()-base-4)<0.00001,"supplied enderite health bonus applied");
            var ids=new ArrayList<String>();for(var h:holders)ids.add(h.getHolder().getId().toString());
            getLogger().info("[EA] NATIVE step="+step+" health="+p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()+" holders="+ids);
            nativeStep(p,step+1,base);
        }catch(Throwable e){fail(e);}},null,3);
    }
    void startBench(Player p) {
        var fixtures = new ArrayList<Fixture>();
        fixtures.add(new Fixture("empty",detached(new ItemStack[4])));
        fixtures.add(new Fixture("ordinary-plain",detached(armor(null,0,false))));
        fixtures.add(new Fixture("ordinary-rich",detached(armor(null,0,true))));
        fixtures.add(new Fixture("full-normal-rich",detached(armor("probe_a",0,true))));
        fixtures.add(new Fixture("full-advanced-rich",detached(armor("probe_a",1,true))));
        var partial=armor("probe_a",0,true);partial[2]=null;partial[3]=null;fixtures.add(new Fixture("partial-rich",detached(partial)));
        var mixed=armor("probe_a",0,true);mixed[2]=item(Material.ELYTRA,"probe_b",1,true);fixtures.add(new Fixture("mixed-rich",detached(mixed)));
        fixtures.add(new Fixture("production-enderite-normal",detached(enderite(false))));
        fixtures.add(new Fixture("production-enderite-advanced",detached(enderite(true))));
        // Only detached test items/proxies cross to this platform thread. No live Bukkit entity is read here.
        new Thread(()->{try{bench(fixtures);}catch(Throwable e){fail(e);}},"ecoarmor-detached-benchmark").start();
    }
    void bench(List<Fixture> fixtures) throws Exception {
        var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();check(bean.isThreadAllocatedMemorySupported(),"allocation measurement supported");
        bean.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().threadId();check(!Thread.currentThread().isVirtual(),"platform benchmark thread");
        for(var f:fixtures){
            for(int n=0;n<12000;n++)sink=scan(false,f.entity);
            for(int round=0;round<16;round++){
                int count=5000;
                long before=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();
                for(int n=0;n<count;n++)sink=scan(false,f.entity);
                long ns=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(tid)-before;
                check(before>=0 && bytes>=0,"valid allocation counters");
                getLogger().info("[EA] BENCH fixture="+f.name+" variant="+System.getProperty("ecoarmor.variant")+" round="+round+" count="+count+" ns="+ns+" bytes="+bytes);
            }
        }
        getLogger().info("[EA] BENCH_DONE checks="+checks+" failures="+failures);
    }
    public void onDisable(){getLogger().info("[EA] DISABLED checks="+checks+" failures="+failures);}
}
