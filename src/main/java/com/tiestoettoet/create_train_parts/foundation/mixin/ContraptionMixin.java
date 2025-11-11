package com.tiestoettoet.create_train_parts.foundation.mixin;

import com.google.common.collect.Multimap;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllTags;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.MountedStorageManager;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.pulley.PulleyBlock;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.redstone.contact.RedstoneContactBlock;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.simibubi.create.infrastructure.config.AllConfigs;
import com.tiestoettoet.create_train_parts.content.decoration.slidingWindow.SlidingWindowBlock;
import com.tiestoettoet.create_train_parts.content.decoration.trainSlide.TrainSlideBlock;
import com.tiestoettoet.create_train_parts.content.decoration.trainStep.TrainStepBlock;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.nbt.NBTProcessors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mixin(Contraption.class)
public abstract class ContraptionMixin {

    @Shadow
    public abstract ContraptionType getType();
    @Shadow
    public boolean disassembled;
    @Shadow
    protected Multimap<BlockPos, StructureTemplate.StructureBlockInfo> capturedMultiblocks;
    @Shadow
    protected Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks;
    @Shadow
    protected MountedStorageManager storage;
    @Shadow
    protected List<AABB> superglue;


    @Shadow
    protected boolean customBlockPlacement(LevelAccessor world, BlockPos pos, BlockState state) {
        return false;
    }

    @Shadow
    @Nullable
    protected CompoundTag getBlockEntityNBT(Level world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null)
            return null;
        CompoundTag nbt = blockEntity.saveWithFullMetadata(world.registryAccess());
        nbt.remove("x");
        nbt.remove("y");
        nbt.remove("z");

        return nbt;
    }
    @Overwrite
    protected Pair<StructureTemplate.StructureBlockInfo, BlockEntity> capture(Level world, BlockPos pos) {
        BlockState blockstate = world.getBlockState(pos);
        if (AllBlocks.REDSTONE_CONTACT.has(blockstate))
            blockstate = blockstate.setValue(RedstoneContactBlock.POWERED, true);
        if (AllBlocks.POWERED_SHAFT.has(blockstate))
            blockstate = BlockHelper.copyProperties(blockstate, AllBlocks.SHAFT.getDefaultState());
        if (blockstate.getBlock() instanceof ControlsBlock && AllTags.AllContraptionTypeTags.OPENS_CONTROLS.matches(this.getType()))
            blockstate = blockstate.setValue(ControlsBlock.OPEN, true);
        if (blockstate.hasProperty(SlidingDoorBlock.VISIBLE))
            blockstate = blockstate.setValue(SlidingDoorBlock.VISIBLE, false);
        if (blockstate.getBlock() instanceof ButtonBlock) {
            blockstate = blockstate.setValue(ButtonBlock.POWERED, false);
            world.scheduleTick(pos, blockstate.getBlock(), -1);
        }
        if (blockstate.getBlock() instanceof PressurePlateBlock) {
            blockstate = blockstate.setValue(PressurePlateBlock.POWERED, false);
            world.scheduleTick(pos, blockstate.getBlock(), -1);
        }
        if (blockstate.hasProperty(TrainStepBlock.VISIBLE))
            blockstate = blockstate.setValue(TrainStepBlock.VISIBLE, false);
        if (blockstate.hasProperty(TrainSlideBlock.VISIBLE))
            blockstate = blockstate.setValue(TrainSlideBlock.VISIBLE, false);
        if (blockstate.hasProperty(SlidingWindowBlock.VISIBLE))
            blockstate = blockstate.setValue(SlidingWindowBlock.VISIBLE, false);
        CompoundTag compoundnbt = getBlockEntityNBT(world, pos);
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof PoweredShaftBlockEntity)
            blockEntity = AllBlockEntityTypes.BRACKETED_KINETIC.create(pos, blockstate);
        if (blockEntity instanceof FactoryPanelBlockEntity fpbe)
            fpbe.writeSafe(compoundnbt, world.registryAccess());

        return Pair.of(new StructureTemplate.StructureBlockInfo(pos, blockstate, compoundnbt), blockEntity);
    }

    @Overwrite
    public void addBlocksToWorld(Level world, StructureTransform transform) {
        if (disassembled)
            return;
        disassembled = true;

        boolean shouldDropBlocks = !AllConfigs.server().kinetics.noDropWhenContraptionReplaceBlocks.get();

        translateMultiblockControllers(transform);

        for (boolean nonBrittles : Iterate.trueAndFalse) {
            for (StructureTemplate.StructureBlockInfo block : blocks.values()) {
                if (nonBrittles == BlockMovementChecks.isBrittle(block.state()))
                    continue;

                BlockPos targetPos = transform.apply(block.pos());
                BlockState state = transform.apply(block.state());

                if (customBlockPlacement(world, targetPos, state))
                    continue;

                if (nonBrittles)
                    for (Direction face : Iterate.directions)
                        state = state.updateShape(face, world.getBlockState(targetPos.relative(face)), world, targetPos,
                                targetPos.relative(face));

                BlockState blockState = world.getBlockState(targetPos);
                if (blockState.getDestroySpeed(world, targetPos) == -1 || (state.getCollisionShape(world, targetPos)
                        .isEmpty()
                        && !blockState.getCollisionShape(world, targetPos)
                        .isEmpty())) {
                    if (targetPos.getY() == world.getMinBuildHeight())
                        targetPos = targetPos.above();
                    world.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, targetPos, Block.getId(state));
                    if (shouldDropBlocks) {
                        Block.dropResources(state, world, targetPos, null);
                    }
                    continue;
                }
                if (state.getBlock() instanceof SimpleWaterloggedBlock
                        && state.hasProperty(BlockStateProperties.WATERLOGGED)) {
                    FluidState FluidState = world.getFluidState(targetPos);
                    state = state.setValue(BlockStateProperties.WATERLOGGED, FluidState.getType() == Fluids.WATER);
                }

                world.destroyBlock(targetPos, shouldDropBlocks);

                if (AllBlocks.SHAFT.has(state))
                    state = ShaftBlock.pickCorrectShaftType(state, world, targetPos);
                if (state.hasProperty(SlidingDoorBlock.VISIBLE))
                    state = state.setValue(SlidingDoorBlock.VISIBLE, !state.getValue(SlidingDoorBlock.OPEN))
                            .setValue(SlidingDoorBlock.POWERED, false);
                if (state.hasProperty(TrainStepBlock.VISIBLE))
                    state = state.setValue(TrainStepBlock.VISIBLE, !state.getValue(TrainStepBlock.OPEN))
                            .setValue(TrainStepBlock.POWERED, false);
                // Stop Sculk shriekers from getting "stuck" if moved mid-shriek.
                if (state.is(Blocks.SCULK_SHRIEKER)) {
                    state = Blocks.SCULK_SHRIEKER.defaultBlockState();
                }

                world.setBlock(targetPos, state, Block.UPDATE_MOVE_BY_PISTON | Block.UPDATE_ALL);

                boolean verticalRotation = transform.rotationAxis == null || transform.rotationAxis.isHorizontal();
                verticalRotation = verticalRotation && transform.rotation != Rotation.NONE;
                if (verticalRotation) {
                    if (state.getBlock() instanceof PulleyBlock.RopeBlock || state.getBlock() instanceof PulleyBlock.MagnetBlock
                            || state.getBlock() instanceof DoorBlock)
                        world.destroyBlock(targetPos, shouldDropBlocks);
                }

                BlockEntity blockEntity = world.getBlockEntity(targetPos);

                CompoundTag tag = block.nbt();

                // Temporary fix: Calling load(CompoundTag tag) on a Sculk sensor causes it to not react to vibrations.
                if (state.is(Blocks.SCULK_SENSOR) || state.is(Blocks.SCULK_SHRIEKER))
                    tag = null;

                if (blockEntity != null)
                    tag = NBTProcessors.process(state, blockEntity, tag, false);
                if (blockEntity != null && tag != null) {
                    tag.putInt("x", targetPos.getX());
                    tag.putInt("y", targetPos.getY());
                    tag.putInt("z", targetPos.getZ());

                    if (verticalRotation && blockEntity instanceof PulleyBlockEntity) {
                        tag.remove("Offset");
                        tag.remove("InitialOffset");
                    }

                    if (blockEntity instanceof IMultiBlockEntityContainer) {
                        if (tag.contains("LastKnownPos") || capturedMultiblocks.isEmpty()) {
                            tag.put("LastKnownPos", NbtUtils.writeBlockPos(BlockPos.ZERO.below(Integer.MAX_VALUE - 1)));
                            tag.remove("Controller");
                        }
                    }

                    blockEntity.loadWithComponents(tag, world.registryAccess());
                }

                storage.unmount(world, block, targetPos, blockEntity);

                if (blockEntity != null) {
                    transform.apply(blockEntity);
                }
            }
        }

        for (StructureTemplate.StructureBlockInfo block : blocks.values()) {
            if (!shouldUpdateAfterMovement(block))
                continue;
            BlockPos targetPos = transform.apply(block.pos());
            world.markAndNotifyBlock(targetPos, world.getChunkAt(targetPos), block.state(), block.state(),
                    Block.UPDATE_MOVE_BY_PISTON | Block.UPDATE_ALL, 512);
        }

        for (AABB box : superglue) {
            box = new AABB(transform.apply(new Vec3(box.minX, box.minY, box.minZ)),
                    transform.apply(new Vec3(box.maxX, box.maxY, box.maxZ)));
            if (!world.isClientSide)
                world.addFreshEntity(new SuperGlueEntity(world, box));
        }
    }

    @Shadow
    protected void translateMultiblockControllers(StructureTransform transform) {
        if (transform.rotationAxis != null && transform.rotationAxis != Direction.Axis.Y && transform.rotation != Rotation.NONE) {
            capturedMultiblocks.values().forEach(info -> {
                info.nbt().put("LastKnownPos", NbtUtils.writeBlockPos(BlockPos.ZERO.below(Integer.MAX_VALUE - 1)));
            });
            return;
        }

        capturedMultiblocks.keySet().forEach(controllerPos -> {
            Collection<StructureTemplate.StructureBlockInfo> multiblockParts = capturedMultiblocks.get(controllerPos);
            Optional<BoundingBox> optionalBoundingBox = BoundingBox.encapsulatingPositions(multiblockParts.stream().map(info -> transform.apply(info.pos())).toList());
            if (optionalBoundingBox.isEmpty())
                return;

            BoundingBox boundingBox = optionalBoundingBox.get();
            BlockPos newControllerPos = new BlockPos(boundingBox.minX(), boundingBox.minY(), boundingBox.minZ());
            BlockPos otherPos = transform.unapply(newControllerPos);

            multiblockParts.forEach(info -> info.nbt().put("Controller", NbtUtils.writeBlockPos(newControllerPos)));

            if (controllerPos.equals(otherPos))
                return;

            // swap nbt data to the new controller position
            StructureTemplate.StructureBlockInfo prevControllerInfo = blocks.get(controllerPos);
            StructureTemplate.StructureBlockInfo newControllerInfo = blocks.get(otherPos);
            if (prevControllerInfo == null || newControllerInfo == null)
                return;

            blocks.put(otherPos, new StructureTemplate.StructureBlockInfo(newControllerInfo.pos(), newControllerInfo.state(), prevControllerInfo.nbt()));
            blocks.put(controllerPos, new StructureTemplate.StructureBlockInfo(prevControllerInfo.pos(), prevControllerInfo.state(), newControllerInfo.nbt()));
        });
    }

    @Overwrite
    protected boolean shouldUpdateAfterMovement(StructureTemplate.StructureBlockInfo info) {
        if (PoiTypes.forState(info.state())
                .isPresent())
            return false;
        if (info.state().getBlock() instanceof SlidingDoorBlock)
            return false;
        if (info.state().getBlock() instanceof TrainStepBlock)
            return false;
        return true;
    }


}
