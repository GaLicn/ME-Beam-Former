package com.mebeamformer.blockentity;

import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * 可以被光束绑定器连接的方块实体接口
 */
public interface ILinkable {
    /**
     * 添加连接
     */
    void addLink(BlockPos other);

    /**
     * 移除连接
     */
    void removeLink(BlockPos other);

    /**
     * 获取所有连接
     */
    Set<BlockPos> getLinks();
} 