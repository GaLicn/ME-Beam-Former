package com.mebeamformer;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.api.distmarker.Dist;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.slf4j.Logger;
import appeng.items.parts.PartItem;
import com.mebeamformer.part.BeamFormerPart;
import appeng.api.parts.PartModels; // AE2 零件模型注册（客户端烘焙模型）
import appeng.items.parts.PartModelsHelper; // 从 @PartModels 收集模型

import net.minecraft.world.level.block.entity.BlockEntityType;
import com.mebeamformer.block.BeamFormerBlock;
import com.mebeamformer.blockentity.BeamFormerBlockEntity;
import com.mebeamformer.client.render.BeamFormerBER;
import com.mebeamformer.block.OmniBeamFormerBlock;
import com.mebeamformer.blockentity.OmniBeamFormerBlockEntity;
import com.mebeamformer.client.render.OmniBeamFormerBER;
import com.mebeamformer.item.LaserBindingTool;
import com.mebeamformer.block.WirelessEnergyTowerBlock;
import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import com.mebeamformer.client.render.WirelessEnergyTowerRenderer;
import com.mebeamformer.block.EnergyNetworkMonitorBlock;
import com.mebeamformer.blockentity.EnergyNetworkMonitorBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;

// The value here should match an entry in the neoforge.mods.toml file
@Mod(MEBeamFormer.MODID)
public class MEBeamFormer {

    // Define mod id in a common place for everything to reference
    public static final String MODID = "me_beam_former";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "me_beam_former" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "me_beam_former" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Block Entities
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "me_beam_former" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // AE2 Part Item: Beam Former (uses AE2 PartItem factory to create our part)
    public static final DeferredItem<Item> BEAM_FORMER_PART_ITEM = ITEMS.register("beam_former_part", () ->
            new PartItem<>(new Item.Properties(), BeamFormerPart.class, BeamFormerPart::new)
    );

    // Beam Former Block + Item + BlockEntity
    public static final DeferredBlock<Block> BEAM_FORMER_BLOCK = BLOCKS.register("beam_former_block",
            () -> new BeamFormerBlock(BlockBehaviour.Properties
                    .of()
                    .mapColor(MapColor.METAL)
                    .strength(0.3f) // 与玻璃一致的硬度，空手可挖
                    .sound(SoundType.GLASS) // 破坏/交互音效与玻璃一致
                    .noOcclusion() // 非完整方块：禁用几何遮挡，避免错误的邻面裁剪/透视
            ));
    public static final DeferredItem<Item> BEAM_FORMER_BLOCK_ITEM = ITEMS.register("beam_former_block",
            () -> new BlockItem(BEAM_FORMER_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BeamFormerBlockEntity>> BEAM_FORMER_BE = BLOCK_ENTITIES.register("beam_former_block",
            () -> BlockEntityType.Builder.of(BeamFormerBlockEntity::new, BEAM_FORMER_BLOCK.get()).build(null));

    // Omni Beam Former Block + Item + BlockEntity
    public static final DeferredBlock<Block> OMNI_BEAM_FORMER_BLOCK = BLOCKS.register("omni_beam_former_block",
            () -> new OmniBeamFormerBlock(BlockBehaviour.Properties
                    .of()
                    .mapColor(MapColor.METAL)
                    .strength(0.3f)
                    .sound(SoundType.GLASS)
                    .noOcclusion()
            ));
    public static final DeferredItem<Item> OMNI_BEAM_FORMER_BLOCK_ITEM = ITEMS.register("omni_beam_former_block",
            () -> new BlockItem(OMNI_BEAM_FORMER_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OmniBeamFormerBlockEntity>> OMNI_BEAM_FORMER_BE = BLOCK_ENTITIES.register("omni_beam_former_block",
            () -> BlockEntityType.Builder.of(OmniBeamFormerBlockEntity::new, OMNI_BEAM_FORMER_BLOCK.get()).build(null));

    // Laser Binding Tool
    public static final DeferredItem<Item> LASER_BINDING_TOOL = ITEMS.register("laser_binding_tool",
            () -> new LaserBindingTool(new Item.Properties().stacksTo(1)));

    // Wireless Energy Tower Block + Item + BlockEntity
    public static final DeferredBlock<Block> WIRELESS_ENERGY_TOWER_BLOCK = BLOCKS.register("wireless_energy_tower",
            () -> new WirelessEnergyTowerBlock(BlockBehaviour.Properties
                    .of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5f)  // 与石头一致的硬度
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()  // 需要镐子才能掉落
                    .noOcclusion()
            ));
    public static final DeferredItem<Item> WIRELESS_ENERGY_TOWER_ITEM = ITEMS.register("wireless_energy_tower",
            () -> new BlockItem(WIRELESS_ENERGY_TOWER_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WirelessEnergyTowerBlockEntity>> WIRELESS_ENERGY_TOWER_BE = BLOCK_ENTITIES.register("wireless_energy_tower",
            () -> BlockEntityType.Builder.of(WirelessEnergyTowerBlockEntity::new, WIRELESS_ENERGY_TOWER_BLOCK.get()).build(null));

    // Energy Network Monitor Block + Item + BlockEntity (用于性能调试)
    public static final DeferredBlock<Block> ENERGY_NETWORK_MONITOR_BLOCK = BLOCKS.register("energy_network_monitor",
            () -> new EnergyNetworkMonitorBlock(BlockBehaviour.Properties
                    .of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
            ));
    public static final DeferredItem<Item> ENERGY_NETWORK_MONITOR_ITEM = ITEMS.register("energy_network_monitor",
            () -> new BlockItem(ENERGY_NETWORK_MONITOR_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyNetworkMonitorBlockEntity>> ENERGY_NETWORK_MONITOR_BE = BLOCK_ENTITIES.register("energy_network_monitor",
            () -> BlockEntityType.Builder.of(EnergyNetworkMonitorBlockEntity::new, ENERGY_NETWORK_MONITOR_BLOCK.get()).build(null));

    // 创造物品栏页签：使用"光束器方块"作为图标，展示本模组核心内容
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .title(Component.translatable("itemGroup.me_beam_former.example_tab"))
            .icon(() -> BEAM_FORMER_BLOCK_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                // 展示：AE2 部件与方块
                output.accept(BEAM_FORMER_PART_ITEM.get());
                output.accept(BEAM_FORMER_BLOCK_ITEM.get());
                output.accept(OMNI_BEAM_FORMER_BLOCK_ITEM.get());
                output.accept(LASER_BINDING_TOOL.get());
                output.accept(WIRELESS_ENERGY_TOWER_ITEM.get());
                // 性能监控方块已从创造物品栏移除，只能通过指令获取：
                // /give @s me_beam_former:energy_network_monitor
            }).build());

    public MEBeamFormer(IEventBus modEventBus, ModContainer modContainer) {
        // 生命周期：通用阶段回调
        modEventBus.addListener(this::commonSetup);
        
        // 注册Capability（MOD bus事件）
        modEventBus.addListener(MEBeamFormer::registerCapabilities);

        // 注册各类注册表
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // 注册配置规范
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // AE2：在模型烘焙前注册零件模型，避免渲染时出现"未注册零件模型"
        try {
            PartModels.registerModels(PartModelsHelper.createModels(BeamFormerPart.class));
        } catch (Throwable t) {
            LOGGER.error("Failed to register AE2 part models for BeamFormerPart", t);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 绑定 AEBaseEntityBlock 的方块实体类型与 tickers
        event.enqueueWork(() -> {
            try {
                if (BEAM_FORMER_BLOCK.get() instanceof BeamFormerBlock bf) {
                    bf.setBlockEntity(
                            com.mebeamformer.blockentity.BeamFormerBlockEntity.class,
                            BEAM_FORMER_BE.get(),
                            com.mebeamformer.blockentity.BeamFormerBlockEntity::clientTick,
                            com.mebeamformer.blockentity.BeamFormerBlockEntity::serverTick
                    );
                }
                if (OMNI_BEAM_FORMER_BLOCK.get() instanceof OmniBeamFormerBlock obf) {
                    obf.setBlockEntity(
                            com.mebeamformer.blockentity.OmniBeamFormerBlockEntity.class,
                            OMNI_BEAM_FORMER_BE.get(),
                            com.mebeamformer.blockentity.OmniBeamFormerBlockEntity::clientTick,
                            com.mebeamformer.blockentity.OmniBeamFormerBlockEntity::serverTick
                    );
                }
            } catch (Throwable t) {
                LOGGER.error("Failed to bind BlockEntities", t);
            }
        });
    }
    
    // 注册Capabilities（NeoForge 1.21.1新方式）
    // 注意：RegisterCapabilitiesEvent 在 MOD bus 上触发，所以我们在构造函数中直接监听
    private static void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
            // 注册AE2网络节点能力（让线缆能够识别和连接我们的方块）
            // 这是AE2线缆连接的关键能力！
            event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                BEAM_FORMER_BE.get(),
                (blockEntity, context) -> blockEntity
            );
            
            event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                OMNI_BEAM_FORMER_BE.get(),
                (blockEntity, context) -> blockEntity
            );
            
            event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                WIRELESS_ENERGY_TOWER_BE.get(),
                (blockEntity, context) -> blockEntity
            );
            
            // 🔥 注册标准能量存储能力（用于无线能源感应塔）
            event.registerBlockEntity(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                WIRELESS_ENERGY_TOWER_BE.get(),
                (blockEntity, context) -> blockEntity // 塔自身实现IEnergyStorage
            );
            
            // 🔥🔥 注册Flux Networks Long能量能力（突破Integer.MAX_VALUE限制！）
            // 使用动态代理实现软依赖，无需编译时依赖Flux Networks
            try {
                Class<?> fluxCapClass = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
                java.lang.reflect.Field blockCapField = fluxCapClass.getField("BLOCK");
                @SuppressWarnings("unchecked")
                net.neoforged.neoforge.capabilities.BlockCapability<Object, net.minecraft.core.Direction> fluxCap = 
                    (net.neoforged.neoforge.capabilities.BlockCapability<Object, net.minecraft.core.Direction>) blockCapField.get(null);
                
                event.registerBlockEntity(
                    fluxCap,
                    WIRELESS_ENERGY_TOWER_BE.get(),
                    (blockEntity, context) -> com.mebeamformer.energy.FluxEnergyAdapter.createFluxAdapter(
                        (com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity) blockEntity
                    )
                );
                LOGGER.info("✅ Successfully registered Flux Networks Long Energy capability for Wireless Energy Tower!");
            } catch (Exception e) {
                LOGGER.info("ℹ️ Flux Networks not installed, Long Energy capability not registered");
            }
            
            // 🔥🔥🔥 注册GregTech CEu能量能力（支持电压/电流系统，4 FE = 1 EU）
            // 使用动态代理实现软依赖，无需编译时依赖GregTech
            try {
                if (com.mebeamformer.energy.GTEnergyAdapter.isGTAvailable()) {
                    Object gtCap = com.mebeamformer.energy.GTEnergyAdapter.getGTCapability();
                    if (gtCap != null) {
                        @SuppressWarnings("unchecked")
                        net.neoforged.neoforge.capabilities.BlockCapability<Object, net.minecraft.core.Direction> gtCapability = 
                            (net.neoforged.neoforge.capabilities.BlockCapability<Object, net.minecraft.core.Direction>) gtCap;
                        
                        event.registerBlockEntity(
                            gtCapability,
                            WIRELESS_ENERGY_TOWER_BE.get(),
                            (blockEntity, context) -> com.mebeamformer.energy.GTEnergyAdapter.createGTAdapter(
                                (com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity) blockEntity
                            )
                        );
                        LOGGER.info("✅ Successfully registered GregTech CEu Energy capability for Wireless Energy Tower! (4 FE = 1 EU)");
                    }
                }
            } catch (Exception e) {
                LOGGER.info("ℹ️ GregTech CEu not installed, GT energy capability not registered: {}", e.getMessage());
            }
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // 注册方块实体渲染器与渲染层
            event.enqueueWork(() -> {
                BlockEntityRenderers.register(BEAM_FORMER_BE.get(), ctx -> new BeamFormerBER(ctx));
                BlockEntityRenderers.register(OMNI_BEAM_FORMER_BE.get(), ctx -> new OmniBeamFormerBER(ctx));
                BlockEntityRenderers.register(WIRELESS_ENERGY_TOWER_BE.get(), ctx -> new WirelessEnergyTowerRenderer(ctx));
                // 非完整方块模型：使用 cutout 渲染层，匹配模型中的 render_type: "cutout"
                // In NeoForge 1.21.1, render layers are set via RenderType in model JSON or BlockEntityWithoutLevelRenderer
                // ItemBlockRenderTypes is removed, render layers handled via model JSON
            });
        }
    }
}
