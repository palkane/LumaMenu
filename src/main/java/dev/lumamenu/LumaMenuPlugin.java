package dev.lumamenu;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.set.RegistrySet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

import static dev.lumamenu.LumaMenuApi.Reason;

public final class LumaMenuPlugin extends JavaPlugin implements Listener, TabCompleter, CommandExecutor {
    private MenuEngine engine;
    private PacketListenerAbstract dialogMonitor;
    private PacketListenerAbstract inventoryGuard;
    private PacketListenerAbstract riderGuard;
    private Path layouts;
    private ClientInventory inventory;
    private DisplayRenderer renderer;
    private final OwnDialogs ownDialogs = new OwnDialogs();
    private Settings settings;
    private MenuCache cache;
    /** Players already offered the join calibration this login. */
    private final Set<UUID> joinPrompted = new HashSet<>();

    record Settings(boolean cornerClose, boolean hideWarning, int rotationHitStep, boolean calibrateOnJoin,
                    boolean closeWithEscape) {}

    @Override public void onEnable() {
        try {
            if (!Bukkit.getMinecraftVersion().equals("26.3"))
                throw new IllegalStateException("首版仅支持Paper/Leaf 26.3");
            var packetPlugin = Bukkit.getPluginManager().getPlugin("packetevents");
            if (packetPlugin == null || !packetPlugin.getPluginMeta().getVersion().startsWith("2.14.0"))
                throw new IllegalStateException("需要PacketEvents 2.14.0");
            Path data = getDataFolder().toPath();
            saveDefaultConfig();
            settings = readSettings(getConfig());
            layouts = data.resolve("layouts");
            Files.createDirectories(layouts);
            for (String name : List.of("demo.yml", "coordinates.yml", "transforms.yml", "features.yml"))
                if (!Files.exists(layouts.resolve(name))) saveResource("layouts/" + name, false);
            if (!Files.exists(data.resolve("themes.yml"))) saveResource("themes.yml", false);
            for (String theme : List.of("rift", "parchment"))
                for (String corner : List.of("tl", "tr", "bl", "br")) {
                    String name = "images/theme/" + theme + "/corner-" + corner + ".png";
                    if (!Files.exists(data.resolve(name))) saveResource(name, false);
                }
            Calibration.Store calibration = new Calibration.Store(data.resolve("calibration.properties"));
            cache = new MenuCache(data.resolve("cache/compiled.json.gz"));
            MenuCache.State state;
            if (cache.exists()) {
                MenuCache.State restored = cache.read();
                // Player profiles are no longer kept on disk; older caches lose theirs (rewritten in the background).
                state = MenuCache.pagesOnly(restored);
                if (state != restored)
                    cache.saveLater(state, error -> getLogger().warning("LumaMenu缓存后台保存失败: " + error));
                getLogger().info("Restored " + state.pages().size() + " compiled pages; no YAML recompilation."
                    + " Player profiles are built in memory when players join.");
            } else {
                state = MenuCache.compile(Layouts.load(layouts), List.of(), settings.rotationHitStep());
                cache.save(state);
                getLogger().info("Compiled and saved " + state.pages().size() + " initial pages.");
            }
            renderer = new DisplayRenderer((player, packet) ->
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet));
            inventory = new ClientInventory((player, packet) ->
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet));
            engine = new MenuEngine(this, new Platform(), renderer, calibration, cache, state,
                new RenderPreferences(data.resolve("render-modes.properties")));
            engine.themes(Themes.load(data.resolve("themes.yml")), new ThemeChoices(data.resolve("player-themes.properties")));
            Path pack = exportPack();
            Bukkit.getPluginManager().registerEvents(this, this);
            Objects.requireNonNull(getCommand("lumamenu")).setExecutor(this);
            Objects.requireNonNull(getCommand("lumamenu")).setTabCompleter(this);
            Bukkit.getServicesManager().register(LumaMenuApi.class, engine, this, ServicePriority.Normal);
            registerDialogMonitor();
            registerInventoryGuard();
            registerRiderGuard(renderer);
            Bukkit.getScheduler().runTaskTimer(this, engine::tick, 1, 1);
            Bukkit.getOnlinePlayers().forEach(engine::warm);
            getLogger().info("LumaMenu ready; Paper/Leaf26.3 protocol777. Merge pack: " + pack);
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "LumaMenu startup failed; existing files retained", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    /** Rescans {@code images/}, hands the catalog to the renderer and re-exports the pack with every used image. */
    private Path exportPack() throws java.io.IOException {
        ImageCatalog images = ImageCatalog.scan(Layouts.images(layouts));
        renderer.images(images);
        return MenuResources.export(getDataFolder().toPath().resolve("resourcepack"), settings.hideWarning(),
            images, engine.imageModels(images));
    }

    /** The menu API for scripts that reach plugins by name (also registered with the ServicesManager); null before startup. */
    public LumaMenuApi getMenuApi() { return engine; }

    @Override public void onDisable() {
        if (engine != null) {
            engine.closeAll(Reason.PLUGIN_DISABLED);
            engine.shutdown();
        }
        if (dialogMonitor != null) PacketEvents.getAPI().getEventManager().unregisterListener(dialogMonitor);
        if (inventoryGuard != null) PacketEvents.getAPI().getEventManager().unregisterListener(inventoryGuard);
        if (riderGuard != null) PacketEvents.getAPI().getEventManager().unregisterListener(riderGuard);
        Bukkit.getServicesManager().unregisterAll(this);
        if (cache != null) {
            try { cache.close(60); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (IllegalStateException error) { getLogger().warning(error.getMessage()); }
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (engine == null) { sender.sendMessage("LumaMenu未就绪，请检查启动日志"); return true; }
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        try {
            if (Set.of("reload", "status", "export").contains(sub)) {
                if (!sender.hasPermission("lumamenu.admin")) throw new IllegalStateException("没有管理权限");
                switch (sub) {
                    case "reload" -> {
                        long start = System.nanoTime();
                        if (args.length > 2) throw new IllegalArgumentException("/lumamenu reload [布局文件名]");
                        if (args.length == 2) {
                            String file = args[1].replaceFirst("\\.yml$", "");
                            engine.reloadFile(layouts, file);
                            Bukkit.getOnlinePlayers().forEach(engine::warm);
                            exportPack();
                            sender.sendMessage("LumaMenu：已重读 " + file + ".yml，共 " + engine.pages().size()
                                + " 页，其他布局沿用已编译版本，" + (System.nanoTime() - start) / 1_000_000 + "ms");
                            return true;
                        }
                        var candidate = new org.bukkit.configuration.file.YamlConfiguration();
                        candidate.load(getDataFolder().toPath().resolve("config.yml").toFile());
                        Settings next = readSettings(candidate);
                        engine.reload(layouts, next.rotationHitStep());
                        Bukkit.getOnlinePlayers().forEach(engine::warm);
                        settings = next;
                        exportPack();
                        sender.sendMessage("LumaMenu：已编译并保存 " + engine.pages().size() + " 页 / "
                            + engine.profileCount() + " 名在线玩家缓存后台重建，rotation-hit-step=" + engine.rotationHitStep()
                            + "，" + (System.nanoTime() - start) / 1_000_000 + "ms");
                    }
                    case "status" -> sender.sendMessage("LumaMenu " + getPluginMeta().getVersion()
                        + " pages=" + engine.pages().size() + " profiles=" + engine.profileCount()
                        + " sessions=" + engine.activeCount() + " rotation-hit-step=" + engine.rotationHitStep()
                        + " compiled-cache=ready");
                    case "export" -> sender.sendMessage("已导出：" + exportPack()
                        + "；资源变更后请重新合并并发布服务器资源包。");
                }
                return true;
            }
            if (sub.equals("list")) {
                if (!sender.hasPermission("lumamenu.use")) throw new IllegalStateException("没有菜单使用权限");
                engine.files().forEach((file, pages) -> sender.sendMessage(file + ".yml: " + String.join(", ", pages)));
                return true;
            }
            if (!(sender instanceof Player player)) throw new IllegalStateException("此操作仅供玩家使用");
            switch (sub) {
                case "open" -> engine.demo(player, args.length > 1 ? args[1] : "demo");
                case "calibrate", "align" -> engine.calibrate(player);
                case "close" -> engine.close(player);
                case "debug" -> {
                    if (!player.hasPermission("lumamenu.admin")) throw new IllegalStateException("没有管理权限");
                    if (args.length > 2) throw new IllegalArgumentException("/lumamenu debug [on|off]");
                    boolean enabled = args.length == 2 ? switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "on" -> true;
                        case "off" -> false;
                        default -> throw new IllegalArgumentException("/lumamenu debug [on|off]");
                    } : !engine.debug(player);
                    engine.debug(player, enabled);
                    player.sendMessage(enabled
                        ? "LumaMenu：已显示点击区域（彩色半透明为实际可点位置，悬停看控件和坐标）；需要已接收含调试字体的新资源包。"
                        : "LumaMenu：已关闭点击区域显示。");
                }
                case "mode" -> {
                    if (args.length > 2) throw new IllegalArgumentException("/lumamenu mode [compatible|enhanced]");
                    if (args.length == 2) {
                        engine.mode(player, RenderMode.parse(args[1]));
                        player.sendMessage("LumaMenu：已保存" + engine.mode(player).label() + "模式"
                            + (engine.mode(player) == RenderMode.ENHANCED ? "，请关闭光影；显示异常可切回兼容模式。" : "。"));
                    } else {
                        player.sendMessage("LumaMenu：当前为" + engine.mode(player).label() + "模式");
                        player.sendMessage(Component.text("[兼容]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/lumamenu mode compatible"))
                            .append(Component.text("  ", NamedTextColor.WHITE))
                            .append(Component.text("[增强：需关闭光影]", NamedTextColor.YELLOW)
                                .clickEvent(ClickEvent.runCommand("/lumamenu mode enhanced"))));
                    }
                }
                case "theme" -> {
                    if (args.length > 2) throw new IllegalArgumentException("/lumamenu theme [主题]");
                    var themes = engine.themes();
                    if (args.length == 2) {
                        engine.theme(player, args[1].toLowerCase(Locale.ROOT));
                        player.sendMessage("LumaMenu：已切换为" + themes.get(engine.theme(player)).label() + "主题。");
                    } else {
                        player.sendMessage("LumaMenu：当前为" + themes.get(engine.theme(player)).label() + "主题");
                        Component choices = Component.empty();
                        for (String name : themes.names())
                            choices = choices.append(Component.text("[" + themes.get(name).label() + "]  ", NamedTextColor.LIGHT_PURPLE)
                                .clickEvent(ClickEvent.runCommand("/lumamenu theme " + name)));
                        player.sendMessage(choices);
                    }
                }
                default -> sender.sendMessage("/lumamenu [open <页面>|list|calibrate|close|mode|theme|debug|reload [文件]|status|export]");
            }
        } catch (Exception error) {
            sender.sendMessage("LumaMenu：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            if (!(error instanceof IllegalArgumentException || error instanceof IllegalStateException))
                getLogger().log(Level.WARNING, "LumaMenu command failed", error);
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (engine == null) return List.of();
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("open", "list", "calibrate", "close", "mode", "theme"));
            if (sender.hasPermission("lumamenu.admin")) values.addAll(List.of("debug", "reload", "status", "export"));
            return values.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("open"))
            return engine.pages().stream().filter(s -> s.startsWith(args[1])).sorted().toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("lumamenu.admin"))
            return engine.files().keySet().stream().filter(s -> s.startsWith(args[1])).sorted().toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("debug"))
            return List.of("on", "off").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("theme"))
            return engine.themes().names().stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("mode"))
            return Arrays.stream(RenderMode.values()).map(RenderMode::key)
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    @EventHandler public void click(PlayerCustomClickEvent event) {
        if (!event.getIdentifier().namespace().equals("lumamenu")
            || !(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Runnable action = () -> engine.click(connection.getPlayer(), event.getIdentifier().value());
        if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(this, action);
    }
    /** A calibrated player's profile is built in the background right away; quitting releases it. */
    @EventHandler public void join(PlayerJoinEvent event) { engine.warm(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        engine.close(event.getPlayer(), Reason.PLAYER_QUIT, false);
        engine.forget(event.getPlayer().getUniqueId());
        inventory.forget(event.getPlayer().getUniqueId());
        ownDialogs.clear(event.getPlayer().getUniqueId());
        joinPrompted.remove(event.getPlayer().getUniqueId());
    }
    @EventHandler public void world(PlayerChangedWorldEvent event) { engine.close(event.getPlayer(), Reason.WORLD_CHANGED, true); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { engine.close(event.getPlayer(), Reason.TELEPORT, true); }
    @EventHandler public void death(PlayerDeathEvent event) { engine.close(event.getEntity(), Reason.PLAYER_DIED, true); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) engine.close(player, Reason.DAMAGED, true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mode(PlayerGameModeChangeEvent event) { engine.close(event.getPlayer(), Reason.REPLACED, true); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void inventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) engine.close(player, Reason.INVENTORY_OPENED, true);
    }
    @EventHandler public void ownerDisabled(PluginDisableEvent event) {
        if (event.getPlugin() != this) engine.ownerDisabled(event.getPlugin());
    }
    @EventHandler public void resource(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        if (event.getStatus() != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            engine.close(player, Reason.RESOURCE_PACK_CHANGED, true);
            return;
        }
        if (!settings.calibrateOnJoin() || !joinPrompted.add(player.getUniqueId())) return;
        // Give the client a second after the pack applies; the hit glyphs and markers come from that pack.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || engine.calibrated(player) || engine.busy(player.getUniqueId())) return;
            try { engine.calibrate(player); }
            catch (RuntimeException error) {
                getLogger().info("Join calibration skipped for " + player.getName() + ": " + error.getMessage());
            }
        }, 20);
    }

    private void registerDialogMonitor() {
        dialogMonitor = new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
            @Override public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.SHOW_DIALOG
                    && event.getPacketType() != PacketType.Play.Server.CLEAR_DIALOG) return;
                Object target = event.getPlayer();
                if (!(target instanceof Player player)) return;
                if (event.getPacketType() == PacketType.Play.Server.SHOW_DIALOG
                    && ownDialogs.consume(player.getUniqueId())) return;
                if (event.isCancelled()) return;
                MenuEngine.Stamp stamp = engine.stamp(player.getUniqueId());
                if (stamp == null) return;
                if (event.getPacketType() == PacketType.Play.Server.SHOW_DIALOG) {
                    try {
                        if (new MenuDialogPacket(event).isMenu()) return;
                    } catch (RuntimeException error) {
                        getLogger().log(Level.WARNING, "Unable to inspect replacement Dialog; cleaning LumaMenu", error);
                    }
                }
                UUID id = player.getUniqueId();
                if (isEnabled()) Bukkit.getScheduler().runTask(LumaMenuPlugin.this, () -> engine.replaced(id, stamp));
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(dialogMonitor);
    }

    private void registerInventoryGuard() {
        // Last, so whatever other plugins send still reaches the client once the menu closes and resyncs.
        inventoryGuard = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override public void onPacketSend(PacketSendEvent event) {
                var type = event.getPacketType();
                if (type != PacketType.Play.Server.WINDOW_ITEMS && type != PacketType.Play.Server.SET_SLOT
                    && type != PacketType.Play.Server.SET_PLAYER_INVENTORY
                    && type != PacketType.Play.Server.SET_CURSOR_ITEM) return;
                UUID id = event.getUser().getUUID();
                if (id == null || !inventory.masked(id) || event.isCancelled()) return;
                if (type == PacketType.Play.Server.WINDOW_ITEMS) {
                    var packet = new WrapperPlayServerWindowItems(event);
                    if (packet.getWindowId() != 0) return;
                    packet.setItems(ClientInventory.hidden());
                    packet.setCarriedItem(com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY);
                    event.markForReEncode(true);
                } else if (type == PacketType.Play.Server.SET_SLOT) {
                    int window = new WrapperPlayServerSetSlot(event).getWindowId();
                    if (window == 0 || window == -1 || window == -2) event.setCancelled(true);
                } else event.setCancelled(true);
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(inventoryGuard);
    }

    private void registerRiderGuard(DisplayRenderer renderer) {
        // Late, so other plugins' passenger edits are kept; vanilla SET_PASSENGERS would otherwise dismount the menu.
        riderGuard = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.SET_PASSENGERS || event.isCancelled()) return;
                UUID id = event.getUser().getUUID();
                if (id == null || !renderer.riding(id)) return;
                var packet = new WrapperPlayServerSetPassengers(event);
                int[] merged = renderer.passengers(id, packet.getEntityId(), packet.getPassengers());
                if (merged == null) return;
                packet.setPassengers(merged);
                event.markForReEncode(true);
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(riderGuard);
    }

    private final class Platform implements MenuEngine.Host {
        @Override public boolean cornerClose() { return settings.cornerClose(); }
        @Override public boolean closeWithEscape() { return settings.closeWithEscape(); }
        @Override public void begin(Player player) { inventory.begin(player); }
        @Override public void end(Player player) {
            ownDialogs.clear(player.getUniqueId());
            inventory.end(player);
        }
        @Override public boolean look(Player player, boolean down) {
            // Nether level lights point in opposite directions, so looking down cannot even out the faces.
            return down && player.getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER;
        }
        @Override public void requireMain() {
            if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("LumaMenu API仅供服务器主线程调用");
        }
        @Override public void checkClient(Player player) {
            var user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null || user.getClientVersion().getProtocolVersion() != 777)
                throw new IllegalStateException("首版需要26.3客户端");
            if (!player.hasResourcePack()) throw new IllegalStateException("请先接收服务器资源包");
        }
        @Override public void show(Player player, Component body, ClickEvent exit, int bodyWidth, boolean cornerClose,
                                   boolean escape) {
            var payload = (ClickEvent.Payload.Custom) exit.payload();
            Dialog dialog = Dialog.create(factory -> {
                // A notice adds a 33-high footer but drops the list's empty grid and its 10 spacing, so with two fewer
                // body rows its footer+content height equals the corner list's and the body sits at the same Y.
                DialogType type = cornerClose ? DialogType.dialogList(RegistrySet.keySet(RegistryKey.DIALOG, List.of())).build()
                    : DialogType.notice(ActionButton.builder(Component.text("关闭")).width(60)
                        .action(DialogAction.customClick(payload.key(), payload.nbt())).build());
                factory.empty().base(DialogBase.builder(Component.text(" ")).pause(false).canCloseWithEscape(!cornerClose || escape)
                    .afterAction(DialogBase.DialogAfterAction.NONE)
                    .body(List.of(DialogBody.plainMessage(body, bodyWidth))).build())
                    .type(type);
            });
            ownDialogs.expect(player.getUniqueId());
            try { player.showDialog(dialog); }
            catch (RuntimeException error) { ownDialogs.cancel(player.getUniqueId()); throw error; }
        }
        @Override public void hide(Player player) { player.closeDialog(); }
        @Override public void message(Player player, String text) { player.sendMessage(Component.text(text)); }
        @Override public void command(Player player, String command) {
            player.performCommand(command.startsWith("/") ? command.substring(1) : command);
        }
        @Override public void console(String command) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.startsWith("/") ? command.substring(1) : command);
        }
        @Override public Runnable later(Runnable task, long ticks) {
            var scheduled = Bukkit.getScheduler().runTaskLater(LumaMenuPlugin.this, task, ticks);
            return scheduled::cancel;
        }
        @Override public void closed(Player player, UUID session, String page, org.bukkit.plugin.Plugin owner,
                                     Reason reason) {
            Bukkit.getPluginManager().callEvent(new LumaMenuClosedEvent(player, session, page, owner, reason));
        }
        @Override public void sound(Player player, String sound) {
            String[] parts = sound.split("-");
            String key = parts[0].contains(":") ? parts[0] : "minecraft:" + parts[0].toLowerCase(Locale.ROOT).replace('_', '.');
            float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : .5f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1f;
            player.playSound(player.getLocation(), key, SoundCategory.MASTER, volume, pitch);
        }
        @Override public void failure(Throwable error) { getLogger().log(Level.WARNING, "LumaMenu operation failed", error); }
        /** Native multi-action Dialog with text inputs; Paper keeps the click callbacks, each used once. */
        @Override public void form(Player player, MenuEngine.FormView form,
                                   java.util.function.Consumer<Map<String, String>> submitted, Runnable cancelled) {
            boolean[] done = {false};
            java.util.function.Consumer<Runnable> once = task -> {
                Runnable run = () -> { if (done[0]) return; done[0] = true; task.run(); };
                if (Bukkit.isPrimaryThread()) run.run();
                else if (isEnabled()) Bukkit.getScheduler().runTask(LumaMenuPlugin.this, run);
            };
            var options = net.kyori.adventure.text.event.ClickCallback.Options.builder()
                .uses(1).lifetime(java.time.Duration.ofHours(1)).build();
            List<io.papermc.paper.registry.data.dialog.input.DialogInput> inputs = form.inputs().stream()
                .map(input -> {
                    var text = io.papermc.paper.registry.data.dialog.input.DialogInput.text(input.key(),
                        Component.text(input.label())).initial(input.initial()).maxLength(input.maxLength());
                    // Long limits: a wide box of 4..8 lines (9 px each plus padding) instead of one line.
                    if (input.large()) text.width(300).multiline(
                        io.papermc.paper.registry.data.dialog.input.TextDialogInput.MultilineOptions.create(null,
                            Math.clamp((input.maxLength() + 31) / 32, 4, 8) * 9 + 8));
                    return (io.papermc.paper.registry.data.dialog.input.DialogInput) text.build();
                })
                .toList();
            var submit = ActionButton.builder(Component.text(form.submit()))
                .action(DialogAction.customClick((view, audience) -> once.accept(() -> {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (var input : form.inputs()) {
                        String text = view.getText(input.key());
                        if (text != null) values.put(input.key(), text);
                    }
                    submitted.accept(values);
                }), options)).build();
            var cancel = ActionButton.builder(Component.text(form.cancel()))
                .action(DialogAction.customClick((view, audience) -> once.accept(cancelled), options)).build();
            Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(form.title())).pause(false).canCloseWithEscape(true)
                    .afterAction(DialogBase.DialogAfterAction.NONE).inputs(inputs).build())
                .type(DialogType.multiAction(List.of(submit)).exitAction(cancel).build()));
            ownDialogs.expect(player.getUniqueId());
            try { player.showDialog(dialog); }
            catch (RuntimeException error) { ownDialogs.cancel(player.getUniqueId()); throw error; }
        }
        /** Reflection, as DialogMenu did, so PlaceholderAPI stays optional. */
        @Override public String placeholders(Player player, String text) {
            var papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi == null || !papi.isEnabled()) return null;
            try {
                String value = (String) Class.forName("me.clip.placeholderapi.PlaceholderAPI", true,
                        papi.getClass().getClassLoader())
                    .getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class).invoke(null, player, text);
                return value == null || value.equals(text) ? null : value;
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("PAPI读取失败", error); }
        }
        /** Skript stores variable names in lower case; reflection keeps Skript optional. */
        @Override public Object skript(String name) {
            var skript = Bukkit.getPluginManager().getPlugin("Skript");
            if (skript == null || !skript.isEnabled()) return null;
            try {
                return Class.forName("ch.njol.skript.variables.Variables", true, skript.getClass().getClassLoader())
                    .getMethod("getVariable", String.class, org.bukkit.event.Event.class, boolean.class)
                    .invoke(null, name.toLowerCase(Locale.ROOT), null, false);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Skript变量读取失败", error); }
        }
        @Override public void skriptCall(String function, Object... arguments) {
            try {
                Object found = skriptClass("ch.njol.skript.lang.function.Functions")
                    .getMethod("getFunction", String.class).invoke(null, function);
                if (found == null) throw new IllegalStateException("Skript函数不存在：" + function);
                Object[][] parameters = new Object[arguments.length][];
                for (int i = 0; i < arguments.length; i++) parameters[i] = new Object[]{arguments[i]};
                Class<?> type = skriptClass("ch.njol.skript.lang.function.Function");
                try { type.getMethod("execute", Object[][].class).invoke(found, (Object) parameters); }
                finally { type.getMethod("resetReturnValue").invoke(found); }
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Skript函数调用失败：" + function, error); }
        }
        @Override public List<Object> skriptList(String name) {
            if (!skriptEnabled()) return List.of();
            try {
                var values = (Iterator<?>) skriptClass("ch.njol.skript.variables.Variables")
                    .getMethod("getVariableIterator", String.class, boolean.class, org.bukkit.event.Event.class)
                    .invoke(null, name, false, null);
                var second = skriptClass("ch.njol.util.Pair").getMethod("getSecond");
                List<Object> result = new ArrayList<>();
                while (values.hasNext()) result.add(second.invoke(values.next()));
                return result;
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Skript列表读取失败：" + name, error); }
        }
        @Override public void skriptSet(String name, Object value) {
            if (!skriptEnabled()) return;
            try {
                skriptClass("ch.njol.skript.variables.Variables")
                    .getMethod("setVariable", String.class, Object.class, org.bukkit.event.Event.class, boolean.class)
                    .invoke(null, name, value, null, false);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Skript变量写入失败：" + name, error); }
        }
        private boolean skriptEnabled() {
            var skript = Bukkit.getPluginManager().getPlugin("Skript");
            return skript != null && skript.isEnabled();
        }
        private Class<?> skriptClass(String name) throws ClassNotFoundException {
            var skript = Bukkit.getPluginManager().getPlugin("Skript");
            if (skript == null || !skript.isEnabled()) throw new IllegalStateException("Skript未启用");
            return Class.forName(name, true, skript.getClass().getClassLoader());
        }
        @Override public String server() {
            return getDataFolder().getAbsoluteFile().getParentFile().getParentFile().getName();
        }
    }

    static Settings readSettings(org.bukkit.configuration.ConfigurationSection config) {
        for (String key : List.of("corner-close", "hide-warning", "calibrate-on-join", "close-with-escape"))
            if (config.contains(key) && !config.isBoolean(key))
                throw new IllegalArgumentException(key + "需要true/false");
        return new Settings(config.getBoolean("corner-close", true), config.getBoolean("hide-warning", true),
            HitGeometry.step(config.get("rotation-hit-step")), config.getBoolean("calibrate-on-join", true),
            config.getBoolean("close-with-escape", true));
    }
}
