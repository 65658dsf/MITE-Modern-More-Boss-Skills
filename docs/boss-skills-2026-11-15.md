# More Boss Skills：四类 Boss 技能与反卡位

开发署名：NingMeng123。适用 Minecraft 1.21.1 / NeoForge 21.1.259，扩展交付版本为 0.0.1；前置 MITE 与 ITE 均要求 >= 0.3.5。

本功能为独立可选扩展，需要 MITE: Modern 与 MITE: Modern ITE。卸下扩展后使用 ITE 原有 Boss 行为；安装方法见项目 README。

## 战斗行为

僵尸 BOSS、湮灭骷髅、骷髅暴君、不朽尸皇各有三项常规技能与一项半血技能。
技能有地面预警、固定生效位置或方向以及恢复窗口。施法期间与恢复期间不会重叠普通近战。
伤害通过独立的 `more_boss_skills:boss_skill` 来源结算，保留施法者归属，不继承武器攻击力和普通攻击附加效果。

| Boss | 常规技能 | 半血技能 |
| --- | --- | --- |
| 僵尸 BOSS | 震墓重踏、尸雷烙印、墓锁牵引 | 暴怒三震 |
| 湮灭骷髅 | 虚空裂斩、裂隙追刺、虚相移形 | 湮灭奇点 |
| 骷髅暴君 | 王骸穿刺、白骨轮刃、骸阵号令 | 白骨潮汐 |
| 不朽尸皇 | 帝陨重击、黄泉敕令、不朽军令 | 冥日临朝 |

暴怒三震每一震预警开始时，以目标的已知位置确定攻击方向：270° 攻击区朝向目标，90° 安全缺口位于相反方向。每一震保留 1 秒预警，方向在预警开始后锁定，下一震再重新瞄准。在冲击波覆盖范围内原地站立不再能无伤躲过；及时进入当轮缺口或跳过冲击波仍可躲避。指令预览采用相同方向规则。

新增技能冷却与原有挑战计时、召唤计数、湮灭消失计时分别存储。
僵尸 BOSS 的既有有效近战贡献规则保持；仍在 24 格战斗范围内躲避技能不会耗尽挑战宽限。
真正失去目标或离开战斗范围仍使用原有 200 tick 宽限，失败后回血、清贡献并重置技能阶段。

## 地形和目标约束

- 最近参与战斗的玩家持续卡位时，优先使用有预警的脚下技能，必要时寻找合法脱困落点。
- 位移检查完整碰撞箱、地面承托、世界边界、液体与危险方块；不传送玩家，不在无落点时强行嵌墙。
- 地面技能以局部脚下高度采样，不使用地表高度图，适合地下、台阶及低顶棚。
- 所有碎石、地裂、骨刺和魂焰为客户端视觉，不放置或破坏真实方块。四类 Boss 的挖掘、破门、破坏木活板门/栅栏门和毁田行为也被停用，普通怪物的原有行为保留。
- 飞行骨刃、光刃与锁链受墙体阻挡；反卡位记忆不能用于发现未交战的隐藏玩家。
- 号令只影响归属于该 Boss 的现存随从，不扩大既有召唤数量或跳过人口限制。

## 配置与兼容

`more_boss_skills-common.toml` 的 `[Entities]`：

- `boss_skill_damage_multiplier = 1.0`：范围 0～10；仅调整新增技能伤害。0 保留预警与控制效果。
- `boss_skill_cooldown_multiplier = 1.0`：范围 0.25～4；只调整技能冷却，不压缩预警与恢复窗口。

每次施法通过 S2C 参数包同步，客户端批量绘制网格，服务端独立结算命中。
最低画质也保留危险边界和安全缺口，装饰几何与震屏由客户端配置控制。
`more_boss_skills-client.toml` 的 `[boss_visuals]` 中，`quality` 为 0～2（默认 1），`screenShake` 可关闭轻微震屏。
不替换生物原有骨架、皮肤或普通战斗动画；原有装备、掉落、受伤限制和日期生命成长保持。
读档不会恢复一场没有预警的半途攻击；实体移除、换世界和技能结束清理视觉实例。

## 施法动作与音效

施法动作与地面预警使用相同的同步时间线，依次表现起势、蓄力、释放和收势。
重踏会沉身抬腿，标记与号令抬臂指向，锁链伸手后拉，光刃与骨刃侧身挥臂，奇点与冥日双手聚能。
盔甲、持握物品与发光层跟随施法姿态；只对这四类 Boss 生效，动作结束后恢复原有行走和战斗姿态。
用 `cast` 指定玩家时，Boss 朝本次技能锁定的位置施法，原 AI 仇恨目标不变。

技能声音按主题组合原版声音，包含蓄力、释放和持续阶段提示，使用“敌对生物”音量及空间衰减。
同一轮三条骨刃不会重复叠加三份音效；停止预览、实体移除或离开世界时会停止对应技能声音。
`preview` 同时预览声音和特效；因为不会生成 Boss，查看生物施法动作需要使用 `cast` 或实际战斗。

## 技能预览指令

需要开启作弊或拥有 OP 2 级权限。在创造、旁观或生存模式中，都可以直接预览特效，无需先召唤 Boss。

```mcfunction
/mite boss list
/mite boss preview annihilation_singularity
/mite boss preview emperor_sun at ~ ~ ~
/mite boss stop
```

`list` 显示全部技能，输入 `preview` 后按 Tab 补全技能 ID。默认在玩家前方 6 格的地面播放，`at` 可指定坐标。
预览仅对执行玩家显示，完整播放预警和多段效果；再次预览会清除自己的上一次预览，`stop` 立即清除。
预览不会造成伤害、牵引实体、生成随从或修改方块，号令展示施法光环。脚下没有可用地面时会提示更换位置。

| Boss | 技能 ID（按上表顺序） |
| --- | --- |
| 僵尸 BOSS | `zombie_stomp`、`zombie_mark`、`zombie_chain`、`zombie_triple` |
| 湮灭骷髅 | `annihilation_slash`、`annihilation_mark`、`annihilation_blink`、`annihilation_singularity` |
| 骷髅暴君 | `tyrant_spikes`、`tyrant_blades`、`tyrant_command`、`tyrant_tide` |
| 不朽尸皇 | `emperor_smash`、`emperor_edict`、`emperor_command`、`emperor_sun` |

也可以指定已有 Boss 实际施放，以下示例让最近的僵尸 BOSS 对执行玩家施放重踏：

```mcfunction
/mite boss cast @e[type=modern_ite:zombie_boss,sort=nearest,limit=1] zombie_stomp @s
```

`cast` 会实际造成伤害，需要同维度、相符的 Boss 类型和有效的生存/冒险玩家目标。
它跳过冷却就绪与半血门槛，但仍保留前摇、恢复、射程与安全落点检查，施放后正常记录冷却；Boss 正忙或号令没有己方随从时会拒绝执行。

## 验证入口

- `gradlew.bat test build compileGametestJava`
- `gradlew.bat runGameTestServer`
- `gradlew.bat runClient -PmodernIteBossSkillsAudit=true`
- `gradlew.bat runClient -PmodernIteBossSkillsAuditExtended=true`
- `gradlew.bat runClient -PmodernIteBossSkillsCommandAudit=true`
- `gradlew.bat runClient -PmodernIteBossPresentationAudit=true`

以上命令在独立扩展工程中执行；服务端测试使用 `more_boss_skills_test` 命名空间。
客户端审计开关保留迁移前的名称，以便复用脚本，不代表功能仍内置于 ITE。
视觉审计使用独立开发世界，结果位于本扩展工程的 `build/verification/boss-skills`，不修改玩家的正常世界。
逻辑测试覆盖前摇/命中分离、标记躲避、墙体阻挡、波浪缺口、跳跃、脱困落点、低顶棚反卡位、零地形修改和挑战计时。
基础与扩展视觉审计回放正式渲染参数；指令审计通过真实服务端指令和 S2C 数据包，检查多段预览、到期清理与主动停止。
这两类视觉审计与服务端战斗测试分别验证渲染、网络和命中行为，不替代外部光影包及多人延迟环境的兼容测试。
音画审计单独输出 `build/verification/boss-presentation/audit.json`：检查真实模型施法与恢复、普通怪物对照、共享盔甲模型还原，以及原生音频声道的播放和停止。
可选 EMF/Fresh Animations 的姿态保护钩子已迁移；下述历史默认客户端检查未加载这些外部模组，不能据此认定其运行兼容性已验证。

**独立扩展验收（2026-11-15）：**`build` 成功，24 项服务端 GameTest 全部通过。
新增检查覆盖独立存档、旧内置版存档迁移、原有字段保留，以及 Boss 不破坏门/农田而普通僵尸仍能执行相同行为的实际对照。
真实指令审计 7 个场景通过，26 张阶段截图和停止后截图已检查；音画审计 8 个场景通过，实际模型/盔甲还原及声音声道停止检查通过。
音画的 23 张阶段截图、8 张基线与停止后截图也已检查；466 次活动姿态检查中，模型和盔甲还原误差均为 0，停止后活动声道和特效实例均为 0，后续波次不会重新发声。
日志为本工程的 `build/verification/split-server-tests.log`、`split-command-audit.log` 和 `split-presentation-audit.log`，报告为 `boss-skills/command-audit.json` 与 `boss-presentation/audit.json`。
不安装扩展的 ITE 对照测试通过：没有技能指令、技能字段及扩展存档键，僵尸 Boss 使用原挑战规则；恢复后的 ITE 还通过 40 项 JUnit。
对应日志位于原 ITE 工程的 `build/verification/boss-split-base-absence.log` 与 `boss-split-base-build.log`。
独立扩展发布包已检查不包含前置模组、GameTest 或客户端审计类；配套 ITE 包已检查不含内置技能代码。当前交付路径为 `build/libs/MITE-Modern-More-Boss-Skills-0.0.1.jar`。

**历史记录：原 ITE 内置实现（2026-11-15，拆分之前）。**当时 `build` 成功，40 项 JUnit 和 21 项服务端 GameTest 全部通过。
基础与扩展视觉审计共 13 个场景、26 张阶段截图；真实指令审计 7 个场景、26 张阶段截图及 1 张停止后截图，当时均通过且逐张检查。
日志和报告属于原 `MITE Modern ITE` 工程：`build/verification/boss-skills-final-build.log`，以及 `build/verification/boss-skills` 下的 `client-audit.json`、`extended-audit.json` 和 `command-audit.json`。

原内置实现的音效与施法动作补充验证也已通过：8 个客户端场景、23 张阶段截图与 8 张基线图；当时的正式渲染记录中，模型和盔甲还原误差为 0，共享自定义盔甲复制后也正确还原。
该轮还在音频线程验证了停止预览后的原生声道停止，并观察至原时间线结束，确认后续波次没有重新播放。
对应历史构建与回归日志位于原 `MITE Modern ITE` 工程的 `build/verification/boss-presentation-final-build.log`。

2026-11-17 修正暴怒三震的攻击朝向与预览方向，并新增 3 项回归测试。前置 MITE/ITE 0.3.5 和 0.3.6 两套环境均通过全部 27 项服务端 GameTest；覆盖原地目标每波命中、后方缺口、跳跃、波内方向锁定与逐波重新瞄准。客户端真实指令审计 7 个场景通过，报告为 `build/verification/triple-direction/command-audit.json`，服务端日志为 `build/verification/triple-direction-server-0.3.5.log` 与 `triple-direction-server-0.3.6.log`。随后按独立扩展编号规则，将扩展版本修正为 0.0.1，ITE 依赖范围为 `[0.3.5,)`（大于等于 0.3.5）。

视觉结构参考 EnderDragonPlus 与 Wither-Plus 的 1.21.1 分支。技能代码和程序化视觉由本项目自行实现，不捆绑外部模组、代码或贴图。
