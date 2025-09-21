package com.mebeamformer;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
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
import appeng.api.parts.IPart;
import com.mebeamformer.part.BeamFormerPart;
import appeng.api.parts.PartModels; // AE2 part model registry (client baked models)
import appeng.items.parts.PartModelsHelper; // helper to collect models from @PartModels

import net.minecraft.world.level.block.entity.BlockEntityType;
import com.mebeamformer.block.BeamFormerBlock;
import com.mebeamformer.blockentity.BeamFormerBlockEntity;
import com.mebeamformer.client.render.BeamFormerBER;

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

    // Creates a new Block with the id "me_beam_former:example_block", combining the namespace and path
    public static final RegistryObject<Block> EXAMPLE_BLOCK = BLOCKS.register("example_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE)));
    // Creates a new BlockItem with the id "me_beam_former:example_block", combining the namespace and path
    public static final RegistryObject<Item> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block", () -> new BlockItem(EXAMPLE_BLOCK.get(), new Item.Properties()));

    // Creates a new food item with the id "examplemod:example_id", nutrition 1 and saturation 2
    public static final RegistryObject<Item> EXAMPLE_ITEM = ITEMS.register("example_item", () -> new Item(new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEat().nutrition(1).saturationMod(2f).build())));

    // AE2 Part Item: Beam Former (uses AE2 PartItem factory to create our part)
    public static final RegistryObject<Item> BEAM_FORMER_PART_ITEM = ITEMS.register("beam_former_part", () ->
            new PartItem<>(new Item.Properties(), BeamFormerPart.class, BeamFormerPart::new)
    );

    // Beam Former Block + Item + BlockEntity
    public static final RegistryObject<Block> BEAM_FORMER_BLOCK = MY_BLOCKS.register("beam_former_block",
            () -> new BeamFormerBlock(BlockBehaviour.Properties
                    .of()
                    .mapColor(MapColor.METAL)
                    .strength(3.0f, 6.0f)
                    .noOcclusion() // 非完整方块：禁用几何遮挡，避免错误的邻面裁剪/透视
            ));
    public static final RegistryObject<Item> BEAM_FORMER_BLOCK_ITEM = ITEMS.register("beam_former_block",
            () -> new BlockItem(BEAM_FORMER_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<BeamFormerBlockEntity>> BEAM_FORMER_BE = BLOCK_ENTITIES.register("beam_former_block",
            () -> BlockEntityType.Builder.of(BeamFormerBlockEntity::new, BEAM_FORMER_BLOCK.get()).build(null));

    // Creates a creative tab with the id "examplemod:example_tab" for the example item, that is placed after the combat tab
    public static final RegistryObject<CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
            output.accept(EXAMPLE_ITEM.get()); // Add the example item to the tab. For your own tabs, this method is preferred over the event
            // Show our part item for quick testing
            output.accept(BEAM_FORMER_PART_ITEM.get());
            }).build());

    public ME_Beam_Former() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // AE2: Register part models used by our cable-bus part(s) before model baking
        // This prevents "Trying to use an unregistered part model" at render time.
        try {
            PartModels.registerModels(PartModelsHelper.createModels(BeamFormerPart.class));
        } catch (Throwable t) {
            LOGGER.error("Failed to register AE2 part models for BeamFormerPart", t);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");
        LOGGER.info("DIRT BLOCK >> {}", ForgeRegistries.BLOCKS.getKey(Blocks.DIRT));

        if (Config.logDirtBlock)
            LOGGER.info("DIRT BLOCK >> {}", ForgeRegistries.BLOCKS.getKey(Blocks.DIRT));

        LOGGER.info(Config.magicNumberIntroduction + Config.magicNumber);

        Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item.toString()));

        // 绑定 AEBaseEntityBlock 的方块实体类型与 tickers，避免 blockEntityClass 为空导致的 NPE
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
            } catch (Throwable t) {
                LOGGER.error("Failed to bind BlockEntity to BeamFormerBlock", t);
            }
        });
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS)
            event.accept(EXAMPLE_BLOCK_ITEM);
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(BEAM_FORMER_BLOCK_ITEM);
        }
    }
    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

            // Register BER for our block entity
            event.enqueueWork(() -> {
                BlockEntityRenderers.register(BEAM_FORMER_BE.get(), ctx -> new BeamFormerBER(ctx));
                // 非完整方块模型：使用 cutout 渲染层，匹配模型中的 render_type: "cutout"
                net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                        BEAM_FORMER_BLOCK.get(),
                        net.minecraft.client.renderer.RenderType.cutout()
                );
            });
        }
    }
}
