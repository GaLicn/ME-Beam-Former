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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
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

// The value here should match an entry in the META-INF/mods.toml file
@Mod(ME_Beam_Former.MODID)
public class ME_Beam_Former {

    // Define mod id in a common place for everything to reference
    public static final String MODID = "me_beam_former";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "me_beam_former" namespace
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "me_beam_former" namespace
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    // Blocks
    public static final DeferredRegister<Block> MY_BLOCKS = BLOCKS; // alias
    // Block Entities
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "examplemod" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // AE2 Part Item: Beam Former (uses AE2 PartItem factory to create our part)
    public static final RegistryObject<Item> BEAM_FORMER_PART_ITEM = ITEMS.register("beam_former_part", () ->
            new PartItem<>(new Item.Properties(), BeamFormerPart.class, BeamFormerPart::new)
    );

    // Beam Former Block + Item + BlockEntity
    public static final RegistryObject<Block> BEAM_FORMER_BLOCK = MY_BLOCKS.register("beam_former_block",
            () -> new BeamFormerBlock(BlockBehaviour.Properties
                    .of()
                    .mapColor(MapColor.METAL)
                    .strength(0.3f) // 与玻璃一致的硬度，空手可挖
                    .sound(SoundType.GLASS) // 破坏/交互音效与玻璃一致
                    .noOcclusion() // 非完整方块：禁用几何遮挡，避免错误的邻面裁剪/透视
            ));
    public static final RegistryObject<Item> BEAM_FORMER_BLOCK_ITEM = ITEMS.register("beam_former_block",
            () -> new BlockItem(BEAM_FORMER_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<BeamFormerBlockEntity>> BEAM_FORMER_BE = BLOCK_ENTITIES.register("beam_former_block",
            () -> BlockEntityType.Builder.of(BeamFormerBlockEntity::new, BEAM_FORMER_BLOCK.get()).build(null));

    // Omni Beam Former Block + Item + BlockEntity
    public static final RegistryObject<Block> OMNI_BEAM_FORMER_BLOCK = MY_BLOCKS.register("omni_beam_former_block",
            () -> new OmniBeamFormerBlock(BlockBehaviour.Properties
                    .of()
                    .mapColor(MapColor.METAL)
                    .strength(0.3f)
                    .sound(SoundType.GLASS)
                    .noOcclusion()
            ));
    public static final RegistryObject<Item> OMNI_BEAM_FORMER_BLOCK_ITEM = ITEMS.register("omni_beam_former_block",
            () -> new BlockItem(OMNI_BEAM_FORMER_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<OmniBeamFormerBlockEntity>> OMNI_BEAM_FORMER_BE = BLOCK_ENTITIES.register("omni_beam_former_block",
            () -> BlockEntityType.Builder.of(OmniBeamFormerBlockEntity::new, OMNI_BEAM_FORMER_BLOCK.get()).build(null));

    // Laser Binding Tool
    public static final RegistryObject<Item> LASER_BINDING_TOOL = ITEMS.register("laser_binding_tool",
            () -> new LaserBindingTool(new Item.Properties().stacksTo(1)));

    // Wireless Energy Tower Block + Item + BlockEntity
    public static final RegistryObject<Block> WIRELESS_ENERGY_TOWER_BLOCK = MY_BLOCKS.register("wireless_energy_tower",
            () -> new WirelessEnergyTowerBlock(BlockBehaviour.Properties
                    .of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5f)  // 与石头一致的硬度
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()  // 需要镐子才能掉落
                    .noOcclusion()
            ));
    public static final RegistryObject<Item> WIRELESS_ENERGY_TOWER_ITEM = ITEMS.register("wireless_energy_tower",
            () -> new BlockItem(WIRELESS_ENERGY_TOWER_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<WirelessEnergyTowerBlockEntity>> WIRELESS_ENERGY_TOWER_BE = BLOCK_ENTITIES.register("wireless_energy_tower",
            () -> BlockEntityType.Builder.of(WirelessEnergyTowerBlockEntity::new, WIRELESS_ENERGY_TOWER_BLOCK.get()).build(null));

    // Energy Network Monitor Block + Item + BlockEntity (用于性能调试)
    public static final RegistryObject<Block> ENERGY_NETWORK_MONITOR_BLOCK = MY_BLOCKS.register("energy_network_monitor",
            () -> new EnergyNetworkMonitorBlock(BlockBehaviour.Properties
                    .of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
            ));
    public static final RegistryObject<Item> ENERGY_NETWORK_MONITOR_ITEM = ITEMS.register("energy_network_monitor",
            () -> new BlockItem(ENERGY_NETWORK_MONITOR_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<EnergyNetworkMonitorBlockEntity>> ENERGY_NETWORK_MONITOR_BE = BLOCK_ENTITIES.register("energy_network_monitor",
            () -> BlockEntityType.Builder.of(EnergyNetworkMonitorBlockEntity::new, ENERGY_NETWORK_MONITOR_BLOCK.get()).build(null));

    // 创造物品栏页签：使用"光束器方块"作为图标，展示本模组核心内容
    public static final RegistryObject<CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
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

    public ME_Beam_Former() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 生命周期：通用阶段回调
        modEventBus.addListener(this::commonSetup);

        // 注册各类注册表
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // 订阅通用事件总线
        MinecraftForge.EVENT_BUS.register(this);

        // 注册配置规范
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // AE2：在模型烘焙前注册零件模型，避免渲染时出现“未注册零件模型”
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


    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // 注册方块实体渲染器与渲染层
            event.enqueueWork(() -> {
                BlockEntityRenderers.register(BEAM_FORMER_BE.get(), ctx -> new BeamFormerBER(ctx));
                BlockEntityRenderers.register(OMNI_BEAM_FORMER_BE.get(), ctx -> new OmniBeamFormerBER(ctx));
                BlockEntityRenderers.register(WIRELESS_ENERGY_TOWER_BE.get(), ctx -> new WirelessEnergyTowerRenderer(ctx));
                // 非完整方块模型：使用 cutout 渲染层，匹配模型中的 render_type: "cutout"
                net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                        BEAM_FORMER_BLOCK.get(),
                        net.minecraft.client.renderer.RenderType.cutout()
                );
                net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                        OMNI_BEAM_FORMER_BLOCK.get(),
                        net.minecraft.client.renderer.RenderType.cutout()
                );
            });
        }
    }
}
