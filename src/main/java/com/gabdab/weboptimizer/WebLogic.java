package com.gabdab.weboptimizer;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything runs at the START of the client tick (before the input is read):
 * decide -> (stop sprint) -> silent rotation packet -> place the web, all in one tick.
 */
public final class WebLogic {
    private static final double REACH = 4.5;           // eye -> clicked point
    private static final float ARRIVE_DEG = 0.5f;      // server-side rotation counts as "aimed"
    private static final int PLACE_COOLDOWN = 2;       // ticks
    private static final int ENEMY_COOLDOWN = 8;       // ticks between webs for the same enemy
    private static final long ATTACK_DELAY_MS = 200;   // pause after the player's own hit
    private static final int PATH_STEPS = 28;

    // weights for the predicted positions around the "lead" tick (offset -1 .. +3)
    private static final double[] WEIGHTS = {0.6, 1.0, 0.8, 0.5, 0.3};
    private static final double[] OFFSETS = {-0.3, 0.0, 0.3};

    private static final Map<UUID, Long> PLACED_AT = new HashMap<>();
    private static long tickCount = 0;
    private static volatile long lastAttackMs = 0;

    // silent rotation (what the server believes)
    private static boolean rotating = false;
    private static float srvYaw;
    private static float srvPitch;

    private static int cooldown = 0;

    // sprint stop
    private static boolean engaged = false;      // we are stopping the player for a placement
    private static int holdTicks = 0;            // extra ticks to stand after the placement
    private static boolean resumeSprint = false;
    private static boolean keysBlocked = false;  // movement keys were force-released in this tick

    private WebLogic() {}

    private record Placement(BlockPos support, Vec3d hit, UUID enemy) {}

    public static void markAttack() {
        lastAttackMs = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity me = mc.player;
        ClientWorld world = mc.world;

        if (me == null || world == null || mc.getNetworkHandler() == null) {
            reset();
            return;
        }

        tickCount++;
        Predictor.update(world);
        if (cooldown > 0) cooldown--;

        // keep the player standing for a moment after the placement, then let him sprint again
        if (holdTicks > 0) {
            blockMovement(mc, me);
            holdTicks--;
            if (holdTicks == 0) resumeSprint = true;
        } else if (resumeSprint) {
            resumeSprint = false;
            if (mc.options.forwardKey.isPressed()) me.setSprinting(true);
        }

        WebConfig cfg = WebConfig.INSTANCE;
        Placement best = null;
        int webSlot = -1;

        boolean active = cfg.enabled
                && mc.currentScreen == null
                && mc.interactionManager != null
                && me.isAlive()
                && !me.isSpectator()
                && !shieldHeld(mc, me)
                && System.currentTimeMillis() - lastAttackMs >= ATTACK_DELAY_MS;

        if (active) {
            webSlot = findHotbar(me.getInventory(), Items.COBWEB);
            if (webSlot >= 0) best = findTarget(mc, me, world, cfg);
        }

        if (best == null) {
            if (engaged) {
                engaged = false;
                resumeSprint = true;
            }
            releaseRotation(mc, me, cfg);
            return;
        }

        // sprinting -> stop the player first (he just stands), place, then he runs again
        if (!engaged && me.isSprinting()) engaged = true;
        if (engaged) blockMovement(mc, me);

        // aim point = top face of the block under the enemy
        Vec3d eye = new Vec3d(me.getX(), me.getEyeY(), me.getZ());
        float[] aim = aimAngles(eye, best.hit());

        if (!rotating) {
            srvYaw = me.getYaw();
            srvPitch = me.getPitch();
            rotating = true;
        }
        stepToward(aim[0], aim[1], cfg.smoothness);
        sendLook(mc, me, srvYaw, srvPitch);

        // place in the SAME tick the server-side rotation arrives
        if (remaining(aim[0], aim[1]) <= ARRIVE_DEG && cooldown == 0) {
            place(mc, me, best, webSlot, cfg);
            cooldown = PLACE_COOLDOWN;
            PLACED_AT.put(best.enemy(), tickCount);
            if (engaged) {
                engaged = false;
                holdTicks = 1; // one more tick of standing
            }
        }
    }

    /** end of tick: give the movement keys back */
    public static void afterTick(MinecraftClient mc) {
        if (keysBlocked) {
            KeyBinding.updatePressedStates();
            keysBlocked = false;
        }
    }

    private static void reset() {
        Predictor.clear();
        PLACED_AT.clear();
        rotating = false;
        engaged = false;
        holdTicks = 0;
        resumeSprint = false;
        keysBlocked = false;
    }

    // ------------------------------------------------------------------ conditions

    private static boolean shieldHeld(MinecraftClient mc, ClientPlayerEntity me) {
        if (me.isBlocking()) return true;
        if (!mc.options.useKey.isPressed()) return false;
        return me.getMainHandStack().isOf(Items.SHIELD) || me.getOffHandStack().isOf(Items.SHIELD);
    }

    private static void blockMovement(MinecraftClient mc, ClientPlayerEntity me) {
        GameOptions o = mc.options;
        o.forwardKey.setPressed(false);
        o.backKey.setPressed(false);
        o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);
        o.sprintKey.setPressed(false);
        me.setSprinting(false);
        keysBlocked = true;
    }

    // ------------------------------------------------------------------ target selection

    private static Placement findTarget(MinecraftClient mc, ClientPlayerEntity me, ClientWorld world, WebConfig cfg) {
        Vec3d eye = new Vec3d(me.getX(), me.getEyeY(), me.getZ());
        double pingTicks = pingTicks(mc, me);
        double maxStep = maxStepDeg(cfg.smoothness);

        Placement best = null;
        double bestDist = Double.MAX_VALUE;

        for (PlayerEntity other : world.getPlayers()) {
            if (other == me || !other.isAlive() || other.isSpectator() || me.isTeammate(other)) continue;
            if (me.squaredDistanceTo(other) > 64.0) continue;

            // never in water / lava
            if (other.isTouchingWater() || other.isSubmergedInWater() || other.isInLava()) continue;

            // already in / next to a web, or we just placed one for him
            if (webNear(world, other)) continue;
            Long placed = PLACED_AT.get(other.getUuid());
            if (placed != null && tickCount - placed < ENEMY_COOLDOWN) continue;

            // how far into the future: ping + client interpolation + time our head needs to turn
            double aimTicks = aimTicks(me, eye, other, maxStep);
            double lead = MathHelper.clamp(pingTicks + 2.0 + aimTicks, 1.0, 14.0);

            Predictor.Prediction pr = Predictor.predict(world, other, PATH_STEPS);
            for (Placement p : candidates(world, other, pr, lead)) {
                double d = eye.distanceTo(p.hit());
                if (d > REACH) continue;
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
                break; // candidates are sorted, first reachable one is the best for this enemy
            }
        }
        return best;
    }

    private static double pingTicks(MinecraftClient mc, ClientPlayerEntity me) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null) return 0;
        PlayerListEntry entry = handler.getPlayerListEntry(me.getUuid());
        return entry == null ? 0 : entry.getLatency() / 50.0;
    }

    private static double aimTicks(ClientPlayerEntity me, Vec3d eye, PlayerEntity enemy, double maxStep) {
        float[] a = aimAngles(eye, new Vec3d(enemy.getX(), enemy.getY(), enemy.getZ()));
        float curYaw = rotating ? srvYaw : me.getYaw();
        float curPitch = rotating ? srvPitch : me.getPitch();
        double dy = MathHelper.wrapDegrees(a[0] - curYaw);
        double dp = a[1] - curPitch;
        double dist = Math.sqrt(dy * dy + dp * dp);
        return Math.max(0.0, Math.ceil(dist / maxStep) - 1.0);
    }

    /** true if any cobweb touches the enemy's body or lies right around his feet */
    private static boolean webNear(ClientWorld world, PlayerEntity enemy) {
        Box box = enemy.getBoundingBox().expand(0.9, 0.3, 0.9);
        int x0 = MathHelper.floor(box.minX), x1 = MathHelper.floor(box.maxX);
        int y0 = MathHelper.floor(box.minY), y1 = MathHelper.floor(box.maxY);
        int z0 = MathHelper.floor(box.minZ), z1 = MathHelper.floor(box.maxZ);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    pos.set(x, y, z);
                    if (world.getBlockState(pos).isOf(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ where to put the web

    private static List<Placement> candidates(ClientWorld world, PlayerEntity enemy, Predictor.Prediction pr, double lead) {
        Vec3d[] path = pr.path();
        List<Placement> out = new ArrayList<>();

        // in the air -> the web goes where he will LAND
        if (!enemy.isOnGround()) {
            if (pr.landStep() < 0) return out;
            Vec3d lp = path[pr.landStep()];
            Placement p = placementAt(world, enemy, MathHelper.floor(lp.x), MathHelper.floor(lp.z), lp.y);
            if (p != null) out.add(p);
            return out;
        }

        // on the ground -> the cell his hitbox will overlap the most around the predicted tick
        int tc = MathHelper.clamp((int) Math.round(lead), 1, path.length - 4);
        double refY = path[tc].y;

        Map<Long, double[]> cells = new HashMap<>();
        for (int k = -1; k <= 3; k++) {
            int t = tc + k;
            if (t < 0 || t >= path.length) continue;
            Vec3d pt = path[t];
            for (double ox : OFFSETS) {
                for (double oz : OFFSETS) {
                    int cx = MathHelper.floor(pt.x + ox);
                    int cz = MathHelper.floor(pt.z + oz);
                    long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
                    if (!cells.containsKey(key)) cells.put(key, new double[]{cx, cz, 0.0});
                }
            }
        }

        List<double[]> list = new ArrayList<>(cells.values());
        for (double[] c : list) {
            double score = 0;
            for (int k = -1; k <= 3; k++) {
                int t = tc + k;
                if (t < 0 || t >= path.length) continue;
                score += WEIGHTS[k + 1] * overlap((int) c[0], path[t].x) * overlap((int) c[1], path[t].z);
            }
            c[2] = score;
        }
        list.sort((a, b) -> Double.compare(b[2], a[2]));

        for (double[] c : list) {
            if (c[2] <= 0.0001) break;
            Placement p = placementAt(world, enemy, (int) c[0], (int) c[1], refY);
            if (p != null) out.add(p);
            if (out.size() >= 3) break;
        }
        return out;
    }

    /** 1D overlap of the cell [cell, cell+1] with the player's hitbox (0.6 wide) centered at c */
    private static double overlap(int cell, double c) {
        return Math.max(0.0, Math.min(cell + 1.0, c + 0.3) - Math.max((double) cell, c - 0.3));
    }

    /** web cell + the floor block it is clicked on, or null when it makes no sense */
    private static Placement placementAt(ClientWorld world, PlayerEntity enemy, int cx, int cz, double y) {
        BlockPos feet = new BlockPos(cx, MathHelper.floor(y + 0.001), cz);

        BlockState fs = world.getBlockState(feet);
        if (fs.isOf(Blocks.COBWEB)) return null;
        if (!fs.getFluidState().isEmpty()) return null;               // water / lava
        if (!(fs.isAir() || fs.isReplaceable())) return null;

        for (int i = 1; i <= 5; i++) {
            BlockPos support = feet.down(i);
            BlockState s = world.getBlockState(support);
            if (!s.getFluidState().isEmpty()) return null;            // water / lava below
            if (s.isSideSolidFullSquare(world, support, Direction.UP)) {
                BlockPos place = support.up();
                BlockState ps = world.getBlockState(place);
                if (ps.isOf(Blocks.COBWEB)) return null;
                if (!ps.getFluidState().isEmpty()) return null;
                if (!(ps.isAir() || ps.isReplaceable())) return null;
                if (webAround(world, place)) return null;
                return new Placement(support,
                        new Vec3d(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5),
                        enemy.getUuid());
            }
            if (s.isOf(Blocks.COBWEB)) return null;
            if (!(s.isAir() || s.isReplaceable())) return null;
        }
        return null;
    }

    private static boolean webAround(ClientWorld world, BlockPos center) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (world.getBlockState(pos).isOf(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ silent rotation

    private static float[] aimAngles(Vec3d eye, Vec3d target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
    }

    /** max degrees per tick: 0 -> 180 (instant), 1000 -> 3 (very slow head turn) */
    private static double maxStepDeg(int smoothness) {
        return 180.0 * Math.pow(3.0 / 180.0, MathHelper.clamp(smoothness, 0, 1000) / 1000.0);
    }

    private static void stepToward(float targetYaw, float targetPitch, int smoothness) {
        float dy = MathHelper.wrapDegrees(targetYaw - srvYaw);
        float dp = targetPitch - srvPitch;
        double dist = Math.sqrt(dy * dy + dp * dp);
        double max = maxStepDeg(smoothness);
        if (dist <= max) {
            srvYaw = targetYaw;
            srvPitch = targetPitch;
        } else {
            double f = max / dist;
            srvYaw += (float) (dy * f);
            srvPitch += (float) (dp * f);
        }
    }

    private static double remaining(float targetYaw, float targetPitch) {
        float dy = MathHelper.wrapDegrees(targetYaw - srvYaw);
        float dp = targetPitch - srvPitch;
        return Math.sqrt(dy * dy + dp * dp);
    }

    /** nothing to do: turn the server-side rotation back to the real camera rotation */
    private static void releaseRotation(MinecraftClient mc, ClientPlayerEntity me, WebConfig cfg) {
        if (!rotating) return;

        float realYaw = me.getYaw();
        float realPitch = me.getPitch();
        stepToward(realYaw, realPitch, cfg.smoothness);

        if (remaining(realYaw, realPitch) <= ARRIVE_DEG) {
            sendLook(mc, me, realYaw, realPitch);
            rotating = false;
        } else {
            sendLook(mc, me, srvYaw, srvPitch);
        }
    }

    private static void sendLook(MinecraftClient mc, ClientPlayerEntity me, float yaw, float pitch) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null) return;
        handler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, me.isOnGround(), me.horizontalCollision));
    }

    // ------------------------------------------------------------------ placing

    private static void place(MinecraftClient mc, ClientPlayerEntity me, Placement p, int webSlot, WebConfig cfg) {
        PlayerInventory inv = me.getInventory();
        int previous = inv.getSelectedSlot();

        inv.setSelectedSlot(webSlot);
        BlockHitResult hit = new BlockHitResult(p.hit(), Direction.UP, p.support(), false);
        mc.interactionManager.interactBlock(me, Hand.MAIN_HAND, hit);
        me.swingHand(Hand.MAIN_HAND);

        if (cfg.returnSword) {
            inv.setSelectedSlot(swordSlotToReturn(inv, previous));
        }
    }

    /** previous slot if it holds a sword, otherwise the first sword in the hotbar, otherwise the previous slot */
    private static int swordSlotToReturn(PlayerInventory inv, int previous) {
        if (inv.getStack(previous).isIn(ItemTags.SWORDS)) return previous;
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) return i;
        }
        return previous;
    }

    private static int findHotbar(PlayerInventory inv, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) return i;
        }
        return -1;
    }
}
