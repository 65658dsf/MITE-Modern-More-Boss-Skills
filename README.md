# MITE: Modern More Boss Skills

为 MITE: Modern ITE 提供可选的 Boss 技能增强。开发者：**NingMeng123**。Minecraft 1.21.1，NeoForge 21.1.259，版本 **0.3.5**。

源码仓库：[MITE-Modern-More-Boss-Skills](https://github.com/65658dsf/MITE-Modern-More-Boss-Skills)。前置项目：[MITE: Modern](https://github.com/65658dsf/mite-neoforge-1.21.1) 与 [MITE: Modern ITE](https://github.com/65658dsf/MITE-Modern-ITE)。

## 安装与选择

客户端与服务端均安装以下三个 JAR：

1. `MITE-Modern-0.3.5.jar`
2. `MITE-Modern-ITE-0.3.5.jar`（拆分后的原版行为构建）
3. `MITE-Modern-More-Boss-Skills-0.3.5.jar`（本扩展）

只安装前两个模组时，Boss 使用原有行为，没有本扩展的技能、音效、动作或 `/mite boss` 指令。
第三个 JAR 安装后，僵尸 BOSS、湮灭骷髅、骷髅暴君与不朽尸皇获得共 16 项技能、地面预警、反卡位与安全脱困。
本扩展不破坏地形，不注册新的 Boss、方块或物品；移除扩展不会删除原有 Boss。已取消或卸载中断的施法不会在重新加载时直接造成伤害。

若此前使用了将技能直接内置到 ITE 的开发构建，需要先替换为本次拆分后的 ITE JAR，再添加扩展，避免两套实现同时生效。
技能的独立冷却存档支持读取旧开发构建的 `ModernIteAbilities.BossCombat` 数据，新数据写入独立的 `more_boss_skills:combat` 键。

## 配置

`config/more_boss_skills-common.toml`：

```toml
[Entities]
boss_skill_damage_multiplier = 1.0
boss_skill_cooldown_multiplier = 1.0
```

伤害倍率范围 0～10；冷却倍率范围 0.25～4。预警时间和收势时间不随冷却倍率缩短。
客户端特效质量与震屏设置位于 `config/more_boss_skills-client.toml` 的 `[boss_visuals]`。
声音受原版“敌对生物”音量控制。旧 ITE 配置中相同的两项技能倍率可复制到扩展配置；扩展不会改写前置模组的配置文件。

## 指令

需开启作弊或具备 OP 2 级权限，技能 ID 支持 Tab 补全。

```mcfunction
/mite boss list
/mite boss preview annihilation_singularity
/mite boss preview emperor_sun at ~ ~ ~
/mite boss stop
/mite boss cast @e[type=modern_ite:zombie_boss,sort=nearest,limit=1] zombie_mark Steve
```

`preview` 仅向执行者播放音效与特效，不生成 Boss 或造成伤害。`cast` 使现有 Boss 实际施放，最后的玩家名可换成 `@s`；目标必须是同维度、射程内的生存或冒险玩家。查看生物施法动作请用 `cast` 或进行正常战斗。

完整技能说明见 [技能设计与验证记录](docs/boss-skills-2026-11-15.md)。

## 构建与验证

需要 Java 21。默认依赖相邻的 MITE 与 ITE 工程 `build/libs` 中的 0.3.5 JAR；可通过 `-PmiteJar=...`、`-PiteJar=...` 指定文件。

```powershell
.\gradlew.bat build runGameTestServer
.\gradlew.bat runClient -PmodernIteBossSkillsCommandAudit=true
.\gradlew.bat runClient -PmodernIteBossPresentationAudit=true
```

审计开关保留迁移前的名称以便重用验证脚本，审计世界与报告均在此扩展工程的 `build/verification`，不使用玩家正常世界。
前置模组仅作为依赖，不打包进扩展 JAR；测试和客户端审计类也不包含在发布包中。

2026-11-15 拆分验证：24 项扩展 GameTest、7 项指令特效场景、8 项音画场景通过；仅安装前置模组的对照测试及恢复后 ITE 的 40 项 JUnit 通过。版本均保持 0.3.5。
