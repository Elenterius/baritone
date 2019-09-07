/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.cache.IWaypoint;
import baritone.api.pathing.goals.*;
import baritone.api.process.IExcavateProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.cache.CachedChunk;
import baritone.cache.WorldScanner;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;

import java.util.*;
import java.util.stream.Collectors;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Excavation of falling blocks like {@link BlockSand}, {@link BlockGravel}, {@link BlockConcretePowder}.<br>
 * Modified version of {@link MineProcess}. Optimized for mining {@link BlockFalling} & excavation of sand in a desert.
 *
 * @author leijurv
 * @author Elenterius
 * @see MineProcess
 */
public final class ExcavateProcess extends BaritoneProcessHelper implements IExcavateProcess {

    private static final int ORE_LOCATIONS_COUNT = 64;

    private List<Block> excavationTargets;
    private List<BlockPos> knownTargetLocations;
    private List<BlockPos> blacklist; // inaccessible
    private GoalRunAway branchPointRunaway;
    private int desiredQuantity;
    private int tickCount;

    private BlockPos startPos;
    private BlockPos currMiddlePos;
    private double radiusSqr = 16 * 16;

    public ExcavateProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return excavationTargets != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (Baritone.settings().miningCheckInventory.value) {
            boolean inventoryIsFull = true;
            NonNullList<ItemStack> invy = ctx.player().inventory.mainInventory;
            List<Item> DropsHaveSpace = new ArrayList<>();
            for (ItemStack stack : invy) {
                if (stack.isEmpty()) {
                    inventoryIsFull = false;
                    break;
                }
                List<Item> miningDrops = new ArrayList<>();
                for (Block block : excavationTargets) {
                    miningDrops.add(block.getItemDropped(excavationTargets.get(0).getDefaultState(), ctx.world(), null, 0).asItem());
                }
                if (miningDrops.contains(stack.getItem()) && stack.getMaxStackSize() != stack.getCount()) {
                    DropsHaveSpace.add(stack.getItem());
                }
            }
            if (inventoryIsFull && !DropsHaveSpace.isEmpty()) {
                inventoryIsFull = false;
                for (Block block : excavationTargets) {
                    if (!DropsHaveSpace.contains(block.getItemDropped(excavationTargets.get(0).getDefaultState(), ctx.world(), null, 0).asItem())) {
                        excavationTargets.remove(block);
                    }
                }
            }
            if (inventoryIsFull) {
                logDirect("Cancel Mining Inventory Full");
                tryToReturnHome();
                cancel();
                return null;
            }
        }

        if (desiredQuantity > 0) {
            Item item = excavationTargets.get(0).getItemDropped(excavationTargets.get(0).getDefaultState(), ctx.world(), null, 0).asItem();
            int curr = ctx.player().inventory.mainInventory.stream().filter(stack -> item.equals(stack.getItem())).mapToInt(ItemStack::getCount).sum();
            System.out.println("Currently have " + curr + " " + item);
            if (curr >= desiredQuantity) {
                logDirect("Have " + curr + " " + item.getDisplayName(new ItemStack(item, 1)));
                cancel();
                return null;
            }
        }
        if (calcFailed) {
            if (!knownTargetLocations.isEmpty() && Baritone.settings().blacklistClosestOnFailure.value) {
                logDirect("Unable to find any path to " + excavationTargets + ", blacklisting presumably unreachable closest instance...");
                knownTargetLocations.stream().min(Comparator.comparingDouble(ctx.player()::getDistanceSq)).ifPresent(blacklist::add);
                knownTargetLocations.removeIf(blacklist::contains);
            } else {
                logDirect("Unable to find any path to " + excavationTargets + ", canceling Excavate");
                tryToReturnHome();
                cancel();
                return null;
            }
        }
        if (!Baritone.settings().allowBreak.value) {
            logDirect("Unable to mine when allowBreak is false!");
            tryToReturnHome();
            cancel();
            return null;
        }
        //TODO: remove searching of faraway/non-visible excavation targets?
        int mineGoalUpdateInterval = Baritone.settings().mineGoalUpdateInterval.value;

        List<BlockPos> currTargetLocations = new ArrayList<>(knownTargetLocations);
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            CalculationContext context = new CalculationContext(baritone, true);
            Baritone.getExecutor().execute(() -> rescan(currTargetLocations, context));
        }
        addNearbyTargets();

        final double reachDistanceSq = ctx.playerController().getBlockReachDistance();
        Optional<BlockPos> maxPos = currTargetLocations.stream()
                .filter(pos -> !(BlockStateInterface.get(ctx, pos).getBlock() instanceof BlockAir)) // after breaking a block, it takes mineGoalUpdateInterval ticks for it to actually update this list =(
                .filter(pos -> !(BlockStateInterface.get(ctx, pos.up()).getBlock() instanceof BlockFalling))
                .filter(pos -> ctx.player().getDistanceSq(pos) <= reachDistanceSq)
                .max(Comparator.comparingDouble(Vec3i::getY));
        final int layer = maxPos.map(Vec3i::getY).orElseGet(() -> ctx.playerFeet().y - 1);

        Optional<BlockPos> blockToBreak = currTargetLocations.stream()
                .filter(pos -> !(BlockStateInterface.get(ctx, pos).getBlock() instanceof BlockAir)) // after breaking a block, it takes mineGoalUpdateInterval ticks for it to actually update this list =(
                .filter(pos -> !(BlockStateInterface.get(ctx, pos.up()).getBlock() instanceof BlockFalling))
                .filter(pos -> pos.getY() >= layer)
                .min(Comparator.comparingDouble(ctx.player()::getDistanceSq));

        baritone.getInputOverrideHandler().clearAllKeys();
        if (blockToBreak.isPresent() && ctx.player().onGround) {
            BlockPos pos = blockToBreak.get();
            IBlockState state = baritone.bsi.get0(pos);
            boolean isFallingBlockAbove = baritone.bsi.get0(pos.up()) instanceof BlockFalling;
            if (!isFallingBlockAbove && !MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
                Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
                if (rot.isPresent() && isSafeToCancel) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                    if (ctx.player().isSneaking()) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                    }
                    if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        PathingCommand command = updateGoal();
        if (command == null) {
            // none in range
            // maybe say something in chat? (ahem impact)
            tryToReturnHome();
            cancel();
            return null;
        }
        return command;
    }

    private void tryToReturnHome() {
        if (Baritone.settings().miningGoHome.value) {
            IWaypoint waypoint = baritone.getWorldProvider().getCurrentWorld().getWaypoints().getMostRecentByTag(IWaypoint.Tag.HOME);
            if (waypoint != null) {
                Goal goal = new GoalBlock(waypoint.getLocation());
                baritone.getCustomGoalProcess().setGoalAndPath(goal);
            } else {
                logDirect("No recent home waypoint found, can't return");
            }
        }
    }

    private void addNearbyTargets() {
        knownTargetLocations.addAll(droppedItemsScan(excavationTargets, ctx.world()));
        BlockPos center = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        int searchDist = 10;
        double fakedBlockReachDistance = 20; // at least 10 * sqrt(3) with some extra space to account for positioning within the block
        for (int x = center.getX() - searchDist; x <= center.getX() + searchDist; x++) {
            for (int y = center.getY() - 1; y <= center.getY() + 8; y++) {
                for (int z = center.getZ() - searchDist; z <= center.getZ() + searchDist; z++) {
                    if (excavationTargets.contains(bsi.get0(x, y, z).getBlock())) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if ((Baritone.settings().legitMineIncludeDiagonals.value && knownTargetLocations.stream().anyMatch(ore -> ore.distanceSq(pos) <= 2)) || RotationUtils.reachable(ctx.player(), pos, fakedBlockReachDistance).isPresent()) {
                            knownTargetLocations.add(pos);
                        }
                    }
                }
            }
        }
        knownTargetLocations = prune(new CalculationContext(baritone), knownTargetLocations, excavationTargets, ORE_LOCATIONS_COUNT, blacklist);
    }

    @Override
    public void onLostControl() {
        mine(0, (Block[]) null);
    }

    @Override
    public String displayName0() {
        return "Excavate " + excavationTargets;
    }

    private PathingCommand updateGoal() {
        if (!knownTargetLocations.isEmpty()) {
            Goal goal = formulateGoals(knownTargetLocations);
            return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        if (startPos == null) {
            startPos = ctx.playerFeet();
        }
        if (branchPointRunaway == null) {
            branchPointRunaway = new GoalRunAway(1, startPos) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    return false;
                }
            };
        }

        return new PathingCommand(branchPointRunaway, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private GoalComposite formulateGoals(List<BlockPos> knownLocations) {
        CalculationContext ctx = new CalculationContext(baritone);
        List<BlockPos> locations = new ArrayList<>(knownLocations);
        int maxSize = ORE_LOCATIONS_COUNT;
        List<BlockPos> droppedItems = droppedItemsScan(excavationTargets, ctx.world);

        List<BlockPos> prunedLocations = locations.stream().distinct()
                // get all positions that are inside unloaded chunks, known block type targets or that are dropped items
                .filter(pos -> !ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ()) || excavationTargets.contains(ctx.getBlock(pos.getX(), pos.getY(), pos.getZ())) || droppedItems.contains(pos))
                .filter(pos -> ExcavateProcess.plausibleToBreak(ctx, pos))
                .filter(pos -> !blacklist.contains(pos))
                .sorted(Comparator.comparingDouble(ctx.getBaritone().getPlayerContext().player()::getDistanceSq))
                .collect(Collectors.toList());
        if (prunedLocations.size() > maxSize) {
            prunedLocations = prunedLocations.subList(0, maxSize);
        }

        GoalComposite goal = new GoalComposite(prunedLocations.stream().map(pos -> assignBreakOrCollectGoal(droppedItems, pos)).toArray(Goal[]::new));
        knownTargetLocations = prunedLocations;
        return goal;
    }

    private Goal assignBreakOrCollectGoal(List<BlockPos> droppedItems, BlockPos pos) {
        if (!droppedItems.contains(pos)) {
            Block blockUp = BlockStateInterface.getBlock(ctx, pos.up());
            Block blockUpTwo = BlockStateInterface.getBlock(ctx, pos.up(2));
            if (blockUp instanceof BlockAir && blockUpTwo instanceof BlockAir) {
                return new BuilderProcess.JankyGoalComposite(new GoalBreakNearbyFromTop(pos), new GoalGetToBlock(pos.up()) {
                    @Override
                    public boolean isInGoal(int x, int y, int z) {
                        if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                            return false;
                        }
                        return super.isInGoal(x, y, z);
                    }
                });
            }
        }
        // only positions of item entities are left now
        return new GoalBlock(pos);
    }

    private void rescan(List<BlockPos> alreadyKnown, CalculationContext context) {
        if (excavationTargets == null) {
            return;
        }
        if (Baritone.settings().legitMine.value) {
            return;
        }
        List<BlockPos> locations = searchWorld(context, excavationTargets, ORE_LOCATIONS_COUNT, alreadyKnown, blacklist);
        locations.addAll(droppedItemsScan(excavationTargets, ctx.world()));
        if (locations.isEmpty()) {
            logDirect("No locations for " + excavationTargets + " known, cancelling");
            tryToReturnHome();
            cancel();
            return;
        }
        knownTargetLocations = locations;
    }

    public static List<BlockPos> searchWorld(CalculationContext ctx, List<Block> targets, int maxSize, List<BlockPos> alreadyKnown, List<BlockPos> blacklist) {
        List<BlockPos> locations = new ArrayList<>();
        List<Block> uninteresting = new ArrayList<>();
        for (Block target : targets) {
            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(target)) {
                // maxRegionDistanceSq 2 means adjacent directly or adjacent diagonally; nothing further than that
                locations.addAll(ctx.worldData.getCachedWorld().getLocationsOf(BlockUtils.blockToString(target), Baritone.settings().maxCachedWorldScanCount.value, ctx.getBaritone().getPlayerContext().playerFeet().getX(), ctx.getBaritone().getPlayerContext().playerFeet().getZ(), 2));
            } else {
                uninteresting.add(target);
            }
        }
        locations = prune(ctx, locations, targets, maxSize, blacklist);
        if (locations.isEmpty() || (Baritone.settings().extendCacheOnThreshold.value && locations.size() < maxSize)) {
            uninteresting = targets;
        }
        if (!uninteresting.isEmpty()) {
            locations.addAll(WorldScanner.INSTANCE.scanChunkRadius(ctx.getBaritone().getPlayerContext(), uninteresting, maxSize, 10, 32)); // maxSearchRadius is NOT sq
        }
        locations.addAll(alreadyKnown);
        return prune(ctx, locations, targets, maxSize, blacklist);
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locations, List<Block> targetBlockTypes, int maxSize, List<BlockPos> blacklist) {
        List<BlockPos> dropped = droppedItemsScan(targetBlockTypes, ctx.world);
        // remove all drops next to known locations that are still an active mining target and plausible to be broken
//        dropped.removeIf(drop -> {
//            for (BlockPos pos : locations) {
//                if (pos.distanceSq(drop) <= 9 && targetBlockTypes.contains(ctx.getBlock(pos.getX(), pos.getY(), pos.getZ())) && ExcavateProcess.plausibleToBreak(ctx, pos)) {
//                    return true;
//                }
//            }
//            return false;
//        });
        List<BlockPos> prunedLocations = locations.stream().distinct()
                // get all positions that are inside unloaded chunks, known block type targets or that are dropped items
                .filter(pos -> !ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ()) || targetBlockTypes.contains(ctx.getBlock(pos.getX(), pos.getY(), pos.getZ())) || dropped.contains(pos))
                .filter(pos -> ExcavateProcess.plausibleToBreak(ctx, pos))
                .filter(pos -> !blacklist.contains(pos))
                .sorted(Comparator.comparingDouble(ctx.getBaritone().getPlayerContext().player()::getDistanceSq))
                .collect(Collectors.toList());
        if (prunedLocations.size() > maxSize) {
            return prunedLocations.subList(0, maxSize);
        }
        return prunedLocations;
    }

    public static List<BlockPos> droppedItemsScan(List<Block> mining, World world) {
        if (!Baritone.settings().mineScanDroppedItems.value) {
            return Collections.emptyList();
        }
        Set<Item> searchingFor = new HashSet<>();
        for (Block block : mining) {
            Item drop = block.getItemDropped(block.getDefaultState(), world, null, 0).asItem();
            Item ore = block.asItem();
            searchingFor.add(drop);
            searchingFor.add(ore);
        }
        List<BlockPos> ret = new ArrayList<>();
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityItem) {
                EntityItem ei = (EntityItem) entity;
                if (searchingFor.contains(ei.getItem().getItem())) {
                    ret.add(new BlockPos(entity));
                }
            }
        }
        return ret;
    }

    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos) {
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(), ctx.bsi.get0(pos), true) >= COST_INF) {
            return false;
        }

        // bedrock above and below makes it implausible, otherwise we're good
        return !(ctx.bsi.get0(pos.up()).getBlock() == Blocks.BEDROCK && ctx.bsi.get0(pos.down()).getBlock() == Blocks.BEDROCK);
    }

    @Override
    public void mineByName(int quantity, String... blocks) {
        mine(quantity, blocks == null || blocks.length == 0 ? null : Arrays.stream(blocks).map(BlockUtils::stringToBlockRequired).toArray(Block[]::new));
    }

    @Override
    public void mine(int quantity, Block... blocks) {
        excavationTargets = blocks == null || blocks.length == 0 ? null : Arrays.asList(blocks);
        if (excavationTargets != null && !Baritone.settings().allowBreak.value) {
            logDirect("Unable to excavate when allowBreak is false!");
            excavationTargets = null;
        }
        desiredQuantity = quantity;
        knownTargetLocations = new ArrayList<>();
        blacklist = new ArrayList<>();
        branchPointRunaway = null;
        startPos = null;
        if (excavationTargets != null) {
            rescan(new ArrayList<>(), new CalculationContext(baritone));
        }
    }

    public static class GoalBreakNearbyFromTop extends GoalGetToBlock {

        public GoalBreakNearbyFromTop(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (y < this.y) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }
    }
}
