package dev.oxion;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** Oxion DLC - Modern Fabric Client for Minecraft 1.21.4. RIGHT SHIFT = ClickGUI. */
public class OxionClient implements ClientModInitializer {
    public static final MinecraftClient mc = MinecraftClient.getInstance();
    public static final List<Module> MODULES = new ArrayList<>();
    private static KeyBinding menuKey;
    static int ACCENT = 0xFF9B5CFF;
    static double prevX, prevZ, bps;

    public enum Category { COMBAT, MOVEMENT, RENDER, PLAYER }

    // ---------------- Settings ----------------
    public static class Setting {
        final String name;
        double value;
        final double min, max;
        final boolean bool;

        Setting(String name, double value, double min, double max) {
            this.name = name; this.value = value; this.min = min; this.max = max; this.bool = false;
        }
        Setting(String name, boolean on) {
            this.name = name; this.value = on ? 1 : 0; this.min = 0; this.max = 1; this.bool = true;
        }
        double d() { return value; }
        float f() { return (float) value; }
        boolean on() { return value > 0.5; }
        void setFromRatio(double r) {
            double v = min + (max - min) * MathHelper.clamp(r, 0, 1);
            value = Math.round(v * 100.0) / 100.0;
        }
    }

    @Override
    public void onInitializeClient() {
        // Combat
        MODULES.add(new TriggerBot());
        MODULES.add(new AimAssist());
        MODULES.add(new KillAura());
        MODULES.add(new AutoClicker());
        MODULES.add(new AutoTotem());

        // Movement
        MODULES.add(new Sprint());
        MODULES.add(new Speed());
        MODULES.add(new BHop());
        MODULES.add(new Fly());
        MODULES.add(new Glide());
        MODULES.add(new Spider());
        MODULES.add(new Jesus());
        MODULES.add(new NoFall());
        MODULES.add(new Step());
        MODULES.add(new Scaffold());
        MODULES.add(new AirJump());

        // Render
        MODULES.add(new Fullbright());
        MODULES.add(new ESP());
        MODULES.add(new BoxESP());
        MODULES.add(new Tracers());
        MODULES.add(new Theme());
        MODULES.add(new HUD());

        // Player
        MODULES.add(new AutoSneak());
        MODULES.add(new AntiAFK());
        MODULES.add(new AutoRespawn());
        MODULES.add(new AutoWalk());

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Oxion DLC Menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "Oxion DLC"));
        for (Module m : MODULES) {
            m.key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "Toggle " + m.name, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "Oxion DLC"));
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.wasPressed()) {
                if (client.player != null) client.setScreen(new ClickGui());
            }
            if (client.player == null || client.world == null) return;

            tickCount++;
            if (client.targetedEntity instanceof LivingEntity te && te != client.player && te.isAlive()) {
                lastTarget = te;
                lastTargetTick = tickCount;
            }
            if (client.options.attackKey.isPressed()) {
                LivingEntity n = nearest(6, 60f, true, true);
                if (n != null) { lastTarget = n; lastTargetTick = tickCount; }
            }
            if (lastTarget != null && (tickCount - lastTargetTick > 60 || !lastTarget.isAlive() || client.player.distanceTo(lastTarget) > 12)) {
                lastTarget = null;
            }

            bps = Math.hypot(client.player.getX() - prevX, client.player.getZ() - prevZ) * 20;
            prevX = client.player.getX();
            prevZ = client.player.getZ();

            for (Module m : new ArrayList<>(MODULES)) {
                while (m.key.wasPressed()) m.toggle();
                if (m.enabled) m.onTick();
            }
        });

        WorldRenderEvents.AFTER_ENTITIES.register(OxionClient::renderWorld);

        loadConfig();
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> saveConfig());

        HudRenderCallback.EVENT.register((ctx, tick) -> {
            if (mc.player == null || mc.options.hudHidden || mc.options.debugEnabled) return;
            if (find("HUD") instanceof HUD h && h.enabled) renderHud(ctx, h);
        });
    }

    static Module find(String n) {
        for (Module m : MODULES) if (m.name.equals(n)) return m;
        return null;
    }

    // ---------------- Helpers ----------------
    static boolean validTarget(Entity e, boolean players, boolean mobs) {
        if (!(e instanceof LivingEntity le) || e == mc.player || !le.isAlive()) return false;
        return le instanceof PlayerEntity ? players : mobs;
    }

    static LivingEntity nearest(double range, float fov, boolean players, boolean mobs) {
        if (mc.player == null || mc.world == null) return null;
        return mc.world.getEntitiesByClass(LivingEntity.class,
                        mc.player.getBoundingBox().expand(range), e -> validTarget(e, players, mobs)).stream()
                .filter(e -> mc.player.distanceTo(e) <= range)
                .filter(e -> Math.abs(yawDelta(e)) <= fov)
                .min(Comparator.comparingDouble(e -> mc.player.distanceTo(e)))
                .orElse(null);
    }

    static float[] rotsTo(Entity e) {
        Vec3d d = e.getEyePos().subtract(mc.player.getEyePos());
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90f);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new float[]{yaw, MathHelper.clamp(pitch, -90f, 90f)};
    }

    static float yawDelta(Entity e) { return MathHelper.wrapDegrees(rotsTo(e)[0] - mc.player.getYaw()); }

    static void attack(Entity e) {
        if (mc.interactionManager == null || mc.player == null) return;
        mc.interactionManager.attackEntity(mc.player, e);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    static boolean moving() {
        return mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed();
    }

    // ---------------- Module base ----------------
    public static abstract class Module {
        public final String name;
        public final Category category;
        public boolean enabled;
        public KeyBinding key;
        public final List<Setting> settings = new ArrayList<>();

        Module(String name, Category c) { this.name = name; this.category = c; }
        <T extends Setting> T add(T s) { settings.add(s); return s; }
        void onTick() {}
        void onEnable() {}
        void onDisable() {}

        void toggle() {
            enabled = !enabled;
            if (enabled) onEnable(); else onDisable();
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("§5[Oxion] §f" + name + (enabled ? " §aON" : " §cOFF")), true);
            }
        }
    }

    // ---------------- Combat ----------------
    static class TriggerBot extends Module {
        Setting cd = add(new Setting("Cooldown", 0.92, 0.5, 1.0));
        Setting pl = add(new Setting("Players", true)), mb = add(new Setting("Mobs", true));
        TriggerBot() { super("TriggerBot", Category.COMBAT); }
        @Override void onTick() {
            if (mc.currentScreen != null || mc.player == null) return;
            HitResult hr = mc.crosshairTarget;
            if (hr instanceof EntityHitResult ehr && validTarget(ehr.getEntity(), pl.on(), mb.on())
                    && mc.player.getAttackCooldownProgress(0.5f) >= cd.f()) {
                attack(ehr.getEntity());
            }
        }
    }

    static class AimAssist extends Module {
        Setting range = add(new Setting("Range", 4.2, 1, 6));
        Setting speed = add(new Setting("Speed", 0.25, 0.05, 1));
        Setting fov = add(new Setting("FOV", 90, 10, 360));
        Setting pl = add(new Setting("Players", true)), mb = add(new Setting("Mobs", true));
        Setting needClick = add(new Setting("OnlyOnClick", true));
        AimAssist() { super("AimAssist", Category.COMBAT); }
        @Override void onTick() {
            if (mc.currentScreen != null || mc.player == null || (needClick.on() && !mc.options.attackKey.isPressed())) return;
            LivingEntity t = nearest(range.d(), fov.f(), pl.on(), mb.on());
            if (t == null) return;
            float[] r = rotsTo(t);
            mc.player.setYaw(mc.player.getYaw() + MathHelper.wrapDegrees(r[0] - mc.player.getYaw()) * speed.f());
            mc.player.setPitch(MathHelper.clamp(mc.player.getPitch() + (r[1] - mc.player.getPitch()) * speed.f(), -90f, 90f));
        }
    }

    static class KillAura extends Module {
        Setting range = add(new Setting("Range", 4.2, 1, 6));
        Setting cd = add(new Setting("Cooldown", 0.92, 0.5, 1.0));
        Setting pl = add(new Setting("Players", true)), mb = add(new Setting("Mobs", true));
        KillAura() { super("KillAura", Category.COMBAT); }
        @Override void onTick() {
            if (mc.player == null) return;
            LivingEntity t = nearest(range.d(), 360f, pl.on(), mb.on());
            if (t == null || mc.player.getAttackCooldownProgress(0.5f) < cd.f()) return;
            float[] r = rotsTo(t);
            mc.player.setYaw(r[0]); mc.player.setPitch(r[1]);
            attack(t);
        }
    }

    static class AutoClicker extends Module {
        Setting cps = add(new Setting("CPS", 12, 1, 20));
        int tick;
        AutoClicker() { super("AutoClicker", Category.COMBAT); }
        @Override void onTick() {
            if (mc.currentScreen != null || mc.player == null || !mc.options.attackKey.isPressed()) return;
            if (++tick >= Math.max(1, Math.round(20 / cps.f()))) {
                tick = 0;
                if (mc.crosshairTarget instanceof EntityHitResult e) attack(e.getEntity());
                else mc.player.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    static class AutoTotem extends Module {
        int cd;
        AutoTotem() { super("AutoTotem", Category.COMBAT); }
        @Override void onTick() {
            if (mc.player == null || mc.interactionManager == null) return;
            if (cd > 0) { cd--; return; }
            if (mc.currentScreen != null || mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
            var inv = mc.player.getInventory();
            for (int i = 0; i < 36; i++) {
                if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                    int slot = i < 9 ? 36 + i : i;
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, mc.player);
                    cd = 5;
                    return;
                }
            }
        }
    }

    // ---------------- Movement ----------------
    static class Sprint extends Module {
        Sprint() { super("Sprint", Category.MOVEMENT); }
        @Override void onTick() {
            if (mc.player != null && mc.options.forwardKey.isPressed() && !mc.player.isSneaking()
                    && mc.player.getHungerManager().getFoodLevel() > 6) mc.player.setSprinting(true);
        }
    }

    static class Speed extends Module {
        Setting mult = add(new Setting("Multiplier", 1.12, 1.0, 1.6));
        Speed() { super("Speed", Category.MOVEMENT); }
        @Override void onTick() {
            if (mc.player == null || !moving() || !mc.player.isOnGround()) return;
            Vec3d v = mc.player.getVelocity();
            if (Math.hypot(v.x, v.z) < 0.6) mc.player.setVelocity(v.x * mult.d(), v.y, v.z * mult.d());
        }
    }

    static class BHop extends Module {
        BHop() { super("BHop", Category.MOVEMENT); }
        @Override void onTick() { if (mc.player != null && moving() && mc.player.isOnGround()) mc.player.jump(); }
    }

    static class Fly extends Module {
        Setting speed = add(new Setting("Speed", 0.6, 0.1, 3));
        Fly() { super("Fly", Category.MOVEMENT); }
        @Override void onTick() {
            if (mc.player == null) return;
            double yaw = Math.toRadians(mc.player.getYaw()), sp = speed.d();
            double f = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
            double s = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
            double x = (-Math.sin(yaw) * f + Math.cos(yaw) * s) * sp;
            double z = (Math.cos(yaw) * f + Math.sin(yaw) * s) * sp;
            double y = mc.options.jumpKey.isPressed() ? sp : mc.options.sneakKey.isPressed() ? -sp : 0;
            mc.player.setVelocity(x, y, z);
            mc.player.fallDistance = 0;
        }
    }

    static class Glide extends Module {
        Setting fall = add(new Setting("FallSpeed", 0.05, 0.01, 0.3));
        Glide() { super("Glide", Category.MOVEMENT); }
        @Override void onTick() {
            if (mc.player == null) return;
            Vec3d v = mc.player.getVelocity();
            if (!mc.player.isOnGround() && v.y < -fall.d()) mc.player.setVelocity(v.x, -fall.d(), v.z);
        }
    }

    static class Spider extends Module {
        Spider() { super("Spider", Category.MOVEMENT); }
        @Override void onTick() {
            if (mc.player != null && mc.player.horizontalCollision) {
                Vec3d v = mc.player.getVelocity();
                mc.player.setVelocity(v.x, 0.2, v.z);
            }
        }
    }

    static class Jesus extends Module {
        Jesus() { super("Jesus", Category.MOVEMENT); }
        @Override void onTick() {
            if (mc.player != null && mc.player.isTouchingWater() && !mc.player.isSneaking()) {
                Vec3d v = mc.player.getVelocity();
                mc.player.setVelocity(v.x, 0.11, v.z);
            }
        }
    }

    static class NoFall extends Module {
        NoFall() { super("NoFall", Category.MOVEMENT); }
        @Override void onTick() {
            if (mc.player != null && mc.player.fallDistance > 2.5f && mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
            }
        }
    }

    static class Step extends Module {
        Setting h = add(new Setting("Height", 1.0, 0.6, 2.5));
        Step() { super("Step", Category.MOVEMENT); }
        @Override void onTick() {
            if (mc.player == null) return;
            var a = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
            if (a != null) a.setBaseValue(h.d());
        }
        @Override void onDisable() {
            if (mc.player == null) return;
            var a = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
            if (a != null) a.setBaseValue(0.6);
        }
    }

    static class Scaffold extends Module {
        Scaffold() { super("Scaffold", Category.MOVEMENT); }
        @Override void onTick() {
            if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
            if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) return;
            BlockPos below = mc.player.getBlockPos().down();
            if (!mc.world.getBlockState(below).isReplaceable()) return;
            for (Direction d : Direction.values()) {
                if (d == Direction.UP) continue;
                BlockPos n = below.offset(d);
                if (!mc.world.getBlockState(n).isReplaceable()) {
                    Direction face = d.getOpposite();
                    Vec3d hit = Vec3d.ofCenter(n).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, n, false));
                    mc.player.swingHand(Hand.MAIN_HAND);
                    return;
                }
            }
        }
    }

    static class AirJump extends Module {
        int cd;
        AirJump() { super("AirJump", Category.MOVEMENT); }
        @Override void onTick() {
            if (mc.player == null) return;
            if (cd > 0) cd--;
            if (mc.options.jumpKey.isPressed() && !mc.player.isOnGround() && cd == 0) { mc.player.jump(); cd = 6; }
        }
    }

    // ---------------- Render ----------------
    static class Fullbright extends Module {
        Fullbright() { super("Fullbright", Category.RENDER); }
        @Override void onTick() {
            if (mc.player != null) {
                mc.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 1000, 0, false, false, false));
            }
        }
        @Override void onDisable() {
            if (mc.player != null) mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
        }
    }

    static class ESP extends Module {
        Setting pl = add(new Setting("Players", true)), mb = add(new Setting("Mobs", true)),
                items = add(new Setting("Items", false));
        ESP() { super("ESP", Category.RENDER); }
        @Override void onTick() {
            if (mc.world == null) return;
            for (Entity e : mc.world.getEntities()) {
                if (validTarget(e, pl.on(), mb.on())) e.setGlowing(true);
                else if (items.on() && e instanceof ItemEntity) e.setGlowing(true);
            }
        }
        @Override void onDisable() {
            if (mc.world == null) return;
            for (Entity e : mc.world.getEntities()) {
                if ((e instanceof LivingEntity && e != mc.player) || e instanceof ItemEntity) e.setGlowing(false);
            }
        }
    }

    static class BoxESP extends Module {
        Setting pl = add(new Setting("Players", true)), mb = add(new Setting("Mobs", true));
        Setting range = add(new Setting("Range", 64, 8, 128));
        BoxESP() { super("BoxESP", Category.RENDER); }
    }

    static class Tracers extends Module {
        Setting pl = add(new Setting("Players", true)), mb = add(new Setting("Mobs", true));
        Setting range = add(new Setting("Range", 64, 8, 128));
        Tracers() { super("Tracers", Category.RENDER); }
    }

    static void renderWorld(WorldRenderContext ctx) {
        if (mc.player == null || mc.world == null) return;
        if (!(find("BoxESP") instanceof BoxESP box) || !(find("Tracers") instanceof Tracers tr)) return;
        if (!box.enabled && !tr.enabled) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null || ctx.consumers() == null) return;

        Vec3d cam = ctx.camera().getPos();
        Vec3d start = cam.add(Vec3d.fromPolar(mc.player.getPitch(), mc.player.getYaw()).multiply(0.6));
        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);
        VertexConsumer vc = ctx.consumers().getBuffer(RenderLayer.getLines());

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || e == mc.player || !le.isAlive()) continue;
            boolean isPlayer = e instanceof PlayerEntity;
            float r = isPlayer ? 0.61f : 1.0f, g = isPlayer ? 0.36f : 0.6f, b = isPlayer ? 1.0f : 0.2f;
            double dist = mc.player.distanceTo(e);

            if (box.enabled && (isPlayer ? box.pl.on() : box.mb.on()) && dist <= box.range.d()) {
                VertexRendering.drawBox(ms, vc, e.getBoundingBox(), r, g, b, 1.0f);
            }
            if (tr.enabled && (isPlayer ? tr.pl.on() : tr.mb.on()) && dist <= tr.range.d()) {
                Vec3d end = e.getBoundingBox().getCenter();
                Vec3d d = end.subtract(start).normalize();
                MatrixStack.Entry en = ms.peek();
                int ri = (int) (r * 255), gi = (int) (g * 255), bi = (int) (b * 255);
                vc.vertex(en, (float) start.x, (float) start.y, (float) start.z)
                        .color(ri, gi, bi, 255).normal(en, (float) d.x, (float) d.y, (float) d.z);
                vc.vertex(en, (float) end.x, (float) end.y, (float) end.z)
                        .color(ri, gi, bi, 255).normal(en, (float) d.x, (float) d.y, (float) d.z);
            }
        }
        ms.pop();
    }

    static class Theme extends Module {
        static final int[] PAL = {0xFF9B5CFF, 0xFF4DA6FF, 0xFF3DDC84, 0xFFFF5C7A, 0xFFFFB347, 0xFFFFFFFF};
        Setting color = add(new Setting("Color", 0, 0, 5));
        Setting rainbow = add(new Setting("Rainbow", false));
        Theme() { super("Theme", Category.RENDER); enabled = true; }
        @Override void onTick() {
            if (rainbow.on()) ACCENT = 0xFF000000 | MathHelper.hsvToRgb((System.currentTimeMillis() % 5000) / 5000f, 0.6f, 1f);
            else ACCENT = PAL[Math.min(5, (int) Math.floor(color.d() + 0.5))];
        }
    }

    static class HUD extends Module {
        Setting opacity = add(new Setting("Opacity", 0.80, 0.2, 1.0));
        Setting scale = add(new Setting("Scale", 1.0, 0.6, 1.6));
        Setting border = add(new Setting("AccentLine", true));
        Setting showArmor = add(new Setting("TargetArmor", true));
        Setting showDist = add(new Setting("TargetDist", true));
        final List<HudElem> elems = new ArrayList<>();

        HudElem el(String name, boolean on, float fx, float fy) {
            HudElem e = new HudElem(name, add(new Setting(name, on)), fx, fy);
            elems.add(e);
            return e;
        }

        HUD() {
            super("HUD", Category.RENDER);
            enabled = true;
            el("Watermark", true, 0.01f, 0.01f);
            el("TargetHUD", true, 0.56f, 0.42f);
            el("Potions", true, 0.01f, 0.50f);
            el("Cooldowns", true, 0.78f, 0.50f);
            el("Keybinds", true, 0.01f, 0.70f);
            el("Coords", true, 0.01f, 0.93f);
            el("Armor", true, 0.94f, 0.15f);
            el("AttackBar", false, 0.45f, 0.53f);
            el("ArrayList", true, 0.88f, 0.01f);
        }
    }

    // ---------------- Player ----------------
    static class AutoSneak extends Module {
        AutoSneak() { super("AutoSneak", Category.PLAYER); }
        @Override void onTick() { mc.options.sneakKey.setPressed(true); }
        @Override void onDisable() { mc.options.sneakKey.setPressed(false); }
    }

    static class AntiAFK extends Module {
        int t;
        AntiAFK() { super("AntiAFK", Category.PLAYER); }
        @Override void onTick() {
            if (mc.player != null && ++t >= 600) { t = 0; mc.player.jump(); mc.player.swingHand(Hand.MAIN_HAND); }
        }
    }

    static class AutoRespawn extends Module {
        AutoRespawn() { super("AutoRespawn", Category.PLAYER); }
        @Override void onTick() { if (mc.player != null && mc.player.isDead()) mc.player.requestRespawn(); }
    }

    static class AutoWalk extends Module {
        AutoWalk() { super("AutoWalk", Category.PLAYER); }
        @Override void onTick() { mc.options.forwardKey.setPressed(true); }
        @Override void onDisable() { mc.options.forwardKey.setPressed(false); }
    }

    // ---------------- HUD Rendering ----------------
    static class HudElem {
        final String name;
        final Setting on;
        float fx, fy;
        int w = 80, h = 16;
        HudElem(String n, Setting on, float fx, float fy) { this.name = n; this.on = on; this.fx = fx; this.fy = fy; }
    }

    static float hudAlpha = 0.8f, shownHp = -1;
    static LivingEntity lastTarget;
    static int lastTargetTick, tickCount;

    static int al(int c) {
        int a = Math.max(4, (int) (((c >>> 24) & 0xFF) * hudAlpha));
        return (a << 24) | (c & 0xFFFFFF);
    }

    static void box(DrawContext ctx, int w, int h, String title, boolean accentLine) {
        ctx.fill(0, 0, w, h, al(0xFF0B0B10));
        if (title != null) {
            ctx.fill(0, 0, w, 13, al(0xFF14141E));
            if (accentLine) ctx.fill(0, 13, w, 14, ACCENT);
            ctx.drawCenteredTextWithShadow(mc.textRenderer, title, w / 2, 3, 0xFFFFFFFF);
        } else if (accentLine) {
            ctx.fill(0, h - 1, w, h, ACCENT);
        }
    }

    static String fmt(int ticks) {
        if (ticks < 0) return "inf";
        int sec = ticks / 20;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    static void renderHud(DrawContext ctx, HUD h) {
        hudAlpha = h.opacity.f();
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        for (HudElem e : h.elems) {
            if (e.on.on()) drawElem(ctx, h, e, sw, sh, false);
        }
    }

    static void drawElem(DrawContext ctx, HUD h, HudElem e, int sw, int sh, boolean preview) {
        if (mc.player == null) return;
        float sc = h.scale.f();
        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate((int) (e.fx * sw), (int) (e.fy * sh), 0);
        ms.scale(sc, sc, 1f);
        boolean line = h.border.on();
        int[] size;
        switch (e.name) {
            case "Watermark" -> size = dWatermark(ctx, line);
            case "TargetHUD" -> size = dTarget(ctx, h, preview, line);
            case "Potions" -> size = dPotions(ctx, preview, line);
            case "Cooldowns" -> size = dCooldowns(ctx, preview, line);
            case "Keybinds" -> size = dKeybinds(ctx, h, preview, line);
            case "Coords" -> size = dCoords(ctx, line);
            case "Armor" -> size = dArmor(ctx, preview);
            case "AttackBar" -> size = dAttack(ctx, line);
            default -> size = dArray(ctx, h);
        }
        ms.pop();
        e.w = Math.max(20, (int) (size[0] * sc));
        e.h = Math.max(12, (int) (size[1] * sc));
    }

    static int[] dWatermark(DrawContext ctx, boolean line) {
        var tr = mc.textRenderer;
        int ping = 0;
        if (mc.getNetworkHandler() != null) {
            var en = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (en != null) ping = en.getLatency();
        }
        String t = "OXION  |  " + mc.getCurrentFps() + " FPS  |  " + ping + " ms  |  " + String.format("%.1f", bps) + " bps";
        int w = tr.getWidth(t) + 14;
        box(ctx, w, 16, null, line);
        ctx.drawTextWithShadow(tr, t, 7, 4, 0xFFFFFFFF);
        return new int[]{w, 16};
    }

    static int[] dTarget(DrawContext ctx, HUD h, boolean preview, boolean line) {
        var tr = mc.textRenderer;
        LivingEntity t = lastTarget;
        if (t == null && !preview) { shownHp = -1; return new int[]{0, 0}; }
        String name = t != null ? t.getName().getString() : "Target";
        float hp = t != null ? t.getHealth() : 14f, max = t != null ? t.getMaxHealth() : 20f;
        float abs = t != null ? t.getAbsorptionAmount() : 4f;
        float dist = t != null ? mc.player.distanceTo(t) : 3.2f;
        int armor = t != null ? t.getArmor() : 12;

        if (shownHp < 0) shownHp = hp;
        shownHp += (hp - shownHp) * 0.15f;

        int w = 156, hh = 48;
        box(ctx, w, hh, null, line);
        ctx.drawTextWithShadow(tr, name, 6, 5, 0xFFFFFFFF);

        if (h.showDist.on()) {
            String ds = String.format("%.1fm", dist);
            ctx.drawTextWithShadow(tr, ds, w - 6 - tr.getWidth(ds), 5, 0xFF9B9BAA);
        }

        float ratio = MathHelper.clamp(shownHp / Math.max(1f, max), 0f, 1f);
        int barW = w - 12;
        ctx.fill(6, 18, 6 + barW, 26, 0xFF22222C);
        int hpColor = 0xFF000000 | MathHelper.hsvToRgb(ratio * 0.33f, 0.8f, 1f);
        ctx.fill(6, 18, 6 + (int) (barW * ratio), 26, hpColor);
        if (abs > 0) ctx.fill(6, 18, 6 + (int) (barW * MathHelper.clamp(abs / Math.max(1f, max), 0f, 1f)), 20, 0xFFFFD84D);

        String hs = String.format("HP %.1f / %.0f", hp, max) + (abs > 0 ? String.format(" (+%.0f)", abs) : "");
        ctx.drawTextWithShadow(tr, hs, 6, 32, 0xFFDDDDDD);

        if (h.showArmor.on()) {
            String as = "ARM " + armor;
            ctx.drawTextWithShadow(tr, as, w - 6 - tr.getWidth(as), 32, ACCENT);
        }
        return new int[]{w, hh};
    }

    static int[] dPotions(DrawContext ctx, boolean preview, boolean line) {
        var tr = mc.textRenderer;
        List<String[]> rows = new ArrayList<>();
        for (StatusEffectInstance ei : mc.player.getStatusEffects()) {
            rows.add(new String[]{ei.getEffectType().value().getName().getString() + " " + (ei.getAmplifier() + 1), fmt(ei.getDuration())});
        }
        if (rows.isEmpty() && preview) {
            rows.add(new String[]{"Speed 2", "0:45"});
            rows.add(new String[]{"Strength 1", "1:20"});
        }
        if (rows.isEmpty()) return new int[]{0, 0};
        int w = 125, h = 18 + rows.size() * 11;
        box(ctx, w, h, "Potions", line);
        int ry = 17;
        for (String[] r : rows) {
            ctx.drawTextWithShadow(tr, r[0], 5, ry, 0xFFDDDDDD);
            ctx.drawTextWithShadow(tr, r[1], w - 5 - tr.getWidth(r[1]), ry, 0xFF9B9BAA);
            ry += 11;
        }
        return new int[]{w, h};
    }

    static int[] dCooldowns(DrawContext ctx, boolean preview, boolean line) {
        var tr = mc.textRenderer;
        ItemStack[] items = {new ItemStack(Items.ENDER_PEARL), new ItemStack(Items.GOLDEN_APPLE),
                new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), new ItemStack(Items.CHORUS_FRUIT),
                new ItemStack(Items.SHIELD), new ItemStack(Items.WIND_CHARGE), new ItemStack(Items.ENDER_EYE),
                new ItemStack(Items.TOTEM_OF_UNDYING)};
        List<Object[]> rows = new ArrayList<>();
        for (ItemStack st : items) {
            if (mc.player.getItemCooldownManager().isCoolingDown(st)) {
                rows.add(new Object[]{st, mc.player.getItemCooldownManager().getCooldownProgress(st, 0f)});
            }
        }
        if (rows.isEmpty() && preview) rows.add(new Object[]{new ItemStack(Items.ENDER_PEARL), 0.6f});
        if (rows.isEmpty()) return new int[]{0, 0};
        int w = 135, h = 18 + rows.size() * 18;
        box(ctx, w, h, "Cooldowns", line);
        int ry = 17;
        for (Object[] r : rows) {
            ItemStack st = (ItemStack) r[0];
            float prog = (Float) r[1];
            ctx.drawItem(st, 3, ry);
            ctx.drawTextWithShadow(tr, st.getName().getString(), 22, ry + 1, 0xFFDDDDDD);
            String pc = (int) (prog * 100) + "%";
            ctx.drawTextWithShadow(tr, pc, w - 5 - tr.getWidth(pc), ry + 1, 0xFF9B9BAA);
            ctx.fill(22, ry + 12, w - 5, ry + 14, 0xFF22222C);
            ctx.fill(22, ry + 12, 22 + (int) ((w - 27) * prog), ry + 14, ACCENT);
            ry += 18;
        }
        return new int[]{w, h};
    }

    static int[] dKeybinds(DrawContext ctx, HUD h, boolean preview, boolean line) {
        var tr = mc.textRenderer;
        List<String[]> rows = new ArrayList<>();
        for (Module m : MODULES) {
            if (!m.enabled || m == h) continue;
            rows.add(new String[]{m.name, m.key.isUnbound() ? "NONE" : m.key.getBoundKeyLocalizedText().getString().toUpperCase()});
        }
        if (rows.isEmpty() && preview) rows.add(new String[]{"TriggerBot", "R"});
        if (rows.isEmpty()) return new int[]{0, 0};
        int w = 115, hh = 18 + rows.size() * 11;
        box(ctx, w, hh, "Keybinds", line);
        int ry = 17;
        for (String[] r : rows) {
            ctx.drawTextWithShadow(tr, r[0], 5, ry, 0xFFDDDDDD);
            ctx.drawTextWithShadow(tr, r[1], w - 5 - tr.getWidth(r[1]), ry, ACCENT);
            ry += 11;
        }
        return new int[]{w, hh};
    }

    static int[] dCoords(DrawContext ctx, boolean line) {
        var tr = mc.textRenderer;
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        String l1 = String.format("XYZ: %.1f %.1f %.1f", x, y, z);
        String dim = mc.world.getRegistryKey().getValue().getPath();
        String l2 = mc.player.getHorizontalFacing().asString().toUpperCase();
        if (dim.equals("overworld")) l2 += String.format("  |  Nether: %.0f %.0f", x / 8, z / 8);
        else if (dim.equals("the_nether")) l2 += String.format("  |  Overworld: %.0f %.0f", x * 8, z * 8);
        int w = Math.max(tr.getWidth(l1), tr.getWidth(l2)) + 12;
        box(ctx, w, 27, null, line);
        ctx.drawTextWithShadow(tr, l1, 6, 4, 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, l2, 6, 15, 0xFF9B9BAA);
        return new int[]{w, 27};
    }

    static int[] dArmor(DrawContext ctx, boolean preview) {
        var tr = mc.textRenderer;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int ay = 0, w = 22;
        for (EquipmentSlot es : slots) {
            ItemStack st = mc.player.getEquippedStack(es);
            if (st.isEmpty()) continue;
            ctx.drawItem(st, 0, ay);
            if (st.isDamageable()) {
                String left = String.valueOf(st.getMaxDamage() - st.getDamage());
                ctx.drawTextWithShadow(tr, left, 18, ay + 4, 0xFFDDDDDD);
                w = Math.max(w, 20 + tr.getWidth(left));
            }
            ay += 18;
        }
        if (ay == 0 && preview) return new int[]{40, 18};
        return new int[]{w, ay};
    }

    static int[] dAttack(DrawContext ctx, boolean line) {
        float p = MathHelper.clamp(mc.player.getAttackCooldownProgress(0f), 0f, 1f);
        box(ctx, 64, 10, null, line);
        ctx.fill(2, 3, 2 + (int) (60 * p), 7, ACCENT);
        return new int[]{64, 10};
    }

    static int[] dArray(DrawContext ctx, HUD h) {
        var tr = mc.textRenderer;
        int y = 0, w = 40;
        for (Module m : MODULES) {
            if (!m.enabled || m == h) continue;
            ctx.drawTextWithShadow(tr, m.name, 0, y, ACCENT);
            w = Math.max(w, tr.getWidth(m.name));
            y += 10;
        }
        return new int[]{w, Math.max(10, y)};
    }

    // ---------------- Config ----------------
    static Path cfgPath() { return FabricLoader.getInstance().getConfigDir().resolve("oxion-dlc.properties"); }

    static void saveConfig() {
        try {
            Properties p = new Properties();
            for (Module m : MODULES) {
                p.setProperty("m." + m.name + ".on", String.valueOf(m.enabled));
                for (Setting st : m.settings) p.setProperty("m." + m.name + "." + st.name, String.valueOf(st.value));
            }
            if (find("HUD") instanceof HUD h) for (HudElem e : h.elems) {
                p.setProperty("h." + e.name + ".x", String.valueOf(e.fx));
                p.setProperty("h." + e.name + ".y", String.valueOf(e.fy));
            }
            try (var out = Files.newOutputStream(cfgPath())) { p.store(out, "Oxion DLC Config"); }
        } catch (Exception ex) { ex.printStackTrace(); }
    }

    static void loadConfig() {
        try {
            if (!Files.exists(cfgPath())) return;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(cfgPath())) { p.load(in); }
            for (Module m : MODULES) {
                String on = p.getProperty("m." + m.name + ".on");
                if (on != null) m.enabled = Boolean.parseBoolean(on);
                for (Setting st : m.settings) {
                    String v = p.getProperty("m." + m.name + "." + st.name);
                    if (v != null) st.value = Double.parseDouble(v);
                }
            }
            if (find("HUD") instanceof HUD h) for (HudElem e : h.elems) {
                String x = p.getProperty("h." + e.name + ".x"), y = p.getProperty("h." + e.name + ".y");
                if (x != null) e.fx = Float.parseFloat(x);
                if (y != null) e.fy = Float.parseFloat(y);
            }
        } catch (Exception ex) { ex.printStackTrace(); }
    }

    // ---------------- HUD Editor ----------------
    static class HudEditor extends Screen {
        HudElem drag;
        int dx, dy;
        HudEditor() { super(Text.literal("HUD Editor")); }

        @Override public void render(DrawContext ctx, int mx, int my, float delta) {
            ctx.fill(0, 0, width, height, 0x88000000);
            if (find("HUD") instanceof HUD h) {
                hudAlpha = h.opacity.f();
                for (HudElem e : h.elems) {
                    if (!e.on.on()) continue;
                    drawElem(ctx, h, e, width, height, true);
                    int x = (int) (e.fx * width), y = (int) (e.fy * height);
                    ctx.drawBorder(x - 1, y - 1, e.w + 2, e.h + 2, e == drag ? 0xFFFFFFFF : ACCENT);
                    ctx.drawTextWithShadow(textRenderer, e.name, x, y - 10, 0xFFAAAAAA);
                }
            }
            ctx.drawCenteredTextWithShadow(textRenderer, "HUD Editor: перетаскивай элементы. ESC - сохранить", width / 2, height - 16, 0xFFFFFFFF);
        }

        @Override public boolean mouseClicked(double mx, double my, int button) {
            if (find("HUD") instanceof HUD h && button == 0) {
                for (int i = h.elems.size() - 1; i >= 0; i--) {
                    HudElem e = h.elems.get(i);
                    if (!e.on.on()) continue;
                    int x = (int) (e.fx * width), y = (int) (e.fy * height);
                    if (mx >= x && mx < x + e.w && my >= y && my < y + e.h) {
                        drag = e; dx = (int) mx - x; dy = (int) my - y; return true;
                    }
                }
            }
            return super.mouseClicked(mx, my, button);
        }

        @Override public boolean mouseDragged(double mx, double my, int button, double ddx, double ddy) {
            if (drag != null) {
                drag.fx = MathHelper.clamp((float) (mx - dx) / width, 0f, 0.98f);
                drag.fy = MathHelper.clamp((float) (my - dy) / height, 0f, 0.98f);
                return true;
            }
            return super.mouseDragged(mx, my, button, ddx, ddy);
        }

        @Override public boolean mouseReleased(double mx, double my, int button) { drag = null; return super.mouseReleased(mx, my, button); }
        @Override public void removed() { saveConfig(); }
        @Override public boolean shouldPause() { return false; }
    }

    // ---------------- ClickGUI ----------------
    static class ClickGui extends Screen {
        static Category selCat = Category.COMBAT;
        static Module selMod;
        static int scroll, setScroll;
        static final int WW = 440, WH = 260, SIDE = 88, LISTW = 165, ROWH = 19, SPOFF = 266;
        final long openT = System.currentTimeMillis();
        Setting dragSetting;
        int dragX, dragW;
        Module binding;

        ClickGui() { super(Text.literal("Oxion DLC")); }

        float anim() {
            float t = Math.min(1f, (System.currentTimeMillis() - openT) / 220f);
            return 1 - (1 - t) * (1 - t);
        }
        int wx() { return (width - WW) / 2; }
        int wy() { return (height - WH) / 2 + (int) ((1 - anim()) * 16); }
        static int a(int c, float m) { int al = Math.max(4, (int) (((c >>> 24) & 0xFF) * m)); return (al << 24) | (c & 0xFFFFFF); }
        static boolean in(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
        static void rrect(DrawContext ctx, int x, int y, int w, int h, int c) {
            ctx.fill(x + 1, y, x + w - 1, y + h, c);
            ctx.fill(x, y + 1, x + w, y + h - 1, c);
        }
        static String pretty(Category c) { return c.name().charAt(0) + c.name().substring(1).toLowerCase(); }
        static List<Module> mods() {
            List<Module> l = new ArrayList<>();
            for (Module m : MODULES) if (m.category == selCat) l.add(m);
            return l;
        }
        static int visRows() { return (WH - 38) / ROWH; }
        static int visSettings() { return (WH - 38 - 64) / 20; }

        @Override public void render(DrawContext ctx, int mx, int my, float delta) {
            float an = anim();
            int x = wx(), y = wy();
            ctx.fill(0, 0, width, height, a(0x99000000, an));
            rrect(ctx, x - 1, y - 1, WW + 2, WH + 2, a((ACCENT & 0xFFFFFF) | 0x66000000, an));
            rrect(ctx, x, y, WW, WH, a(0xF00B0B10, an));
            rrect(ctx, x, y, WW, 24, a(0xFF13131B, an));

            ctx.drawTextWithShadow(textRenderer, "OXION", x + 10, y + 8, a(ACCENT, an));
            ctx.drawTextWithShadow(textRenderer, "DLC", x + 10 + textRenderer.getWidth("OXION "), y + 8, a(0xFFFFFFFF, an));

            boolean hovE = in(mx, my, x + WW - 96, y + 4, 88, 16);
            rrect(ctx, x + WW - 96, y + 4, 88, 16, a(hovE ? 0xFF2A2A38 : 0xFF1D1D28, an));
            ctx.drawCenteredTextWithShadow(textRenderer, "HUD Editor", x + WW - 52, y + 8, a(0xFFFFFFFF, an));

            long now = System.currentTimeMillis();
            for (int i = 0; i < WW; i += 2) {
                int c = 0xFF000000 | MathHelper.hsvToRgb(((i / (float) WW) + (now % 4000) / 4000f) % 1f, 0.55f, 1f);
                ctx.fill(x + i, y + 24, x + i + 2, y + 25, a(c, an));
            }

            ctx.fill(x, y + 25, x + SIDE, y + WH, a(0xFF0F0F16, an));
            Category[] cats = Category.values();
            for (int i = 0; i < cats.length; i++) {
                int ry = y + 32 + i * 24;
                boolean sel = cats[i] == selCat, hov = in(mx, my, x + 6, ry, SIDE - 12, 19);
                if (sel) {
                    rrect(ctx, x + 6, ry, SIDE - 12, 19, a((ACCENT & 0xFFFFFF) | 0x55000000, an));
                    ctx.fill(x + 6, ry + 3, x + 8, ry + 16, a(ACCENT, an));
                } else if (hov) rrect(ctx, x + 6, ry, SIDE - 12, 19, a(0x22FFFFFF, an));
                ctx.drawTextWithShadow(textRenderer, pretty(cats[i]), x + 14, ry + 6, a(sel ? 0xFFFFFFFF : 0xFFAAAAB5, an));
            }

            List<Module> L = mods();
            scroll = MathHelper.clamp(scroll, 0, Math.max(0, L.size() - visRows()));
            int lx = x + SIDE + 8, ly = y + 32;
            for (int i = 0; i < visRows(); i++) {
                int idx = i + scroll;
                if (idx >= L.size()) break;
                Module m = L.get(idx);
                int ry = ly + i * ROWH;
                boolean hov = in(mx, my, lx, ry, LISTW, ROWH - 2);
                int bg = m.enabled ? (ACCENT & 0xFFFFFF) | 0x77000000 : 0xFF16161E;
                rrect(ctx, lx, ry, LISTW, ROWH - 2, a(bg, an));
                if (hov) rrect(ctx, lx, ry, LISTW, ROWH - 2, a(0x18FFFFFF, an));
                if (m == selMod) ctx.fill(lx, ry + 2, lx + 2, ry + ROWH - 4, a(ACCENT, an));
                ctx.drawTextWithShadow(textRenderer, m.name, lx + 8, ry + 5, a(0xFFFFFFFF, an));
                ctx.fill(lx + LISTW - 10, ry + 5, lx + LISTW - 4, ry + 11, a(m.enabled ? ACCENT : 0xFF33333B, an));
            }

            if (L.size() > visRows()) {
                int total = L.size(), barH = (WH - 38) * visRows() / total;
                int barY = ly + (WH - 38 - barH) * scroll / Math.max(1, total - visRows());
                ctx.fill(lx + LISTW + 2, barY, lx + LISTW + 4, barY + barH, a(ACCENT, an));
            }

            int sx = x + SPOFF, sw = WW - SPOFF - 8, sy = y + 32;
            rrect(ctx, sx, sy, sw, WH - 38, a(0xFF101017, an));
            if (selMod == null) {
                ctx.drawCenteredTextWithShadow(textRenderer, "Выбери модуль", sx + sw / 2, sy + 12, a(0xFF8888A0, an));
            } else {
                Module m = selMod;
                ctx.drawTextWithShadow(textRenderer, m.name, sx + 8, sy + 6, a(ACCENT, an));
                int by = sy + 20;
                rrect(ctx, sx + 8, by, sw - 16, 16, a(m.enabled ? (ACCENT & 0xFFFFFF) | 0x88000000 : 0xFF1B1B24, an));
                ctx.drawTextWithShadow(textRenderer, "Enabled: " + (m.enabled ? "ON" : "OFF"), sx + 14, by + 4, a(0xFFFFFFFF, an));
                int by2 = sy + 40;
                rrect(ctx, sx + 8, by2, sw - 16, 16, a(0xFF1B1B24, an));
                String bk = binding == m ? "..." : (m.key.isUnbound() ? "NONE" : m.key.getBoundKeyLocalizedText().getString().toUpperCase());
                ctx.drawTextWithShadow(textRenderer, "Bind: " + bk, sx + 14, by2 + 4, a(0xFFFFFFFF, an));
                int py = sy + 64;
                setScroll = MathHelper.clamp(setScroll, 0, Math.max(0, m.settings.size() - visSettings()));
                for (int i = setScroll; i < m.settings.size() && i < setScroll + visSettings(); i++) {
                    Setting st = m.settings.get(i);
                    if (st.bool) {
                        ctx.fill(sx + 8, py + 3, sx + 18, py + 13, a(st.on() ? ACCENT : 0xFF33333B, an));
                        ctx.drawTextWithShadow(textRenderer, st.name, sx + 24, py + 4, a(0xFFDDDDDD, an));
                    } else {
                        rrect(ctx, sx + 8, py, sw - 16, 16, a(0xFF1B1B24, an));
                        double r = (st.value - st.min) / (st.max - st.min);
                        rrect(ctx, sx + 8, py, Math.max(2, (int) ((sw - 16) * r)), 16, a((ACCENT & 0xFFFFFF) | 0x88000000, an));
                        ctx.drawTextWithShadow(textRenderer, st.name, sx + 12, py + 4, a(0xFFFFFFFF, an));
                        String val = String.valueOf(st.value);
                        ctx.drawTextWithShadow(textRenderer, val, sx + sw - 12 - textRenderer.getWidth(val), py + 4, a(0xFFFFFFFF, an));
                    }
                    py += 20;
                }
            }
            ctx.drawTextWithShadow(textRenderer, "ЛКМ - вкл/выкл | ПКМ - настройки | Колесо - скролл", x + 10, y + WH - 11, a(0xFF77778A, an));
        }

        @Override public boolean mouseClicked(double mx, double my, int button) {
            int x = wx(), y = wy();
            if (in(mx, my, x + WW - 96, y + 4, 88, 16)) { client.setScreen(new HudEditor()); return true; }
            Category[] cats = Category.values();
            for (int i = 0; i < cats.length; i++) {
                if (in(mx, my, x + 6, y + 32 + i * 24, SIDE - 12, 19)) {
                    selCat = cats[i]; scroll = 0; selMod = null; return true;
                }
            }
            List<Module> L = mods();
            int lx = x + SIDE + 8, ly = y + 32;
            for (int i = 0; i < visRows(); i++) {
                int idx = i + scroll;
                if (idx >= L.size()) break;
                if (in(mx, my, lx, ly + i * ROWH, LISTW, ROWH - 2)) {
                    Module m = L.get(idx);
                    if (button == 0) m.toggle();
                    if (selMod != m) setScroll = 0;
                    selMod = m;
                    return true;
                }
            }
            if (selMod != null) {
                int sx = x + SPOFF, sw = WW - SPOFF - 8, sy = y + 32;
                if (in(mx, my, sx + 8, sy + 20, sw - 16, 16)) { selMod.toggle(); return true; }
                if (in(mx, my, sx + 8, sy + 40, sw - 16, 16)) { binding = selMod; return true; }
                int py = sy + 64;
                for (int i = setScroll; i < selMod.settings.size() && i < setScroll + visSettings(); i++) {
                    Setting st = selMod.settings.get(i);
                    if (in(mx, my, sx + 8, py, sw - 16, 16)) {
                        if (st.bool) st.value = st.on() ? 0 : 1;
                        else { dragSetting = st; dragX = sx + 8; dragW = sw - 16; st.setFromRatio((mx - dragX) / dragW); }
                        return true;
                    }
                    py += 20;
                }
            }
            return super.mouseClicked(mx, my, button);
        }

        @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
            if (dragSetting != null) { dragSetting.setFromRatio((mx - dragX) / dragW); return true; }
            return super.mouseDragged(mx, my, button, dx, dy);
        }

        @Override public boolean mouseReleased(double mx, double my, int button) {
            dragSetting = null;
            return super.mouseReleased(mx, my, button);
        }

        @Override public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
            int d = (int) -Math.signum(vAmount);
            if (mx >= wx() + SPOFF) setScroll += d; else scroll += d;
            return true;
        }

        @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (binding != null) {
                binding.key.setBoundKey(keyCode == GLFW.GLFW_KEY_ESCAPE ? InputUtil.UNKNOWN_KEY : InputUtil.fromKeyCode(keyCode, scanCode));
                KeyBinding.updateKeysByCode();
                binding = null;
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override public void removed() { saveConfig(); }
        @Override public boolean shouldPause() { return false; }
    }
}
