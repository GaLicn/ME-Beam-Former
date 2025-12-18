---
navigation:
  parent: introduction/index.md
  title: 无线能源塔
  position: 4
  icon: me_beam_former:wireless_energy_tower
categories:
  - me_beam_former devices
item_ids:
  - me_beam_former:wireless_energy_tower
---

# 无线能源塔

<Row gap="20">
<ItemImage id="me_beam_former:wireless_energy_tower" scale="4" />
<GameScene zoom="8" background="transparent">
  <ImportStructure src="../../structure/wireless_energy_tower.snbt" />
</GameScene>
</Row>

用于绑定目标并转发能量的设备。

## 绑定方式

- 使用 <ItemLink id="me_beam_former:laser_binding_tool" /> 将塔与塔绑定（双向）。
- 也可以将塔绑定到具备能量接口的机器（单向：塔 -> 机器）。

## 有效范围

- 水平范围：X/Z 方向各不超过 20 格。
- 垂直范围：Y 方向不超过 256 格。
