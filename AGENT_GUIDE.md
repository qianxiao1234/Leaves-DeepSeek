# AI 躯壳操作指南

这份指南面向通过 WebSocket 控制 LeavesDeepSeek 躯壳的 AI。目标是用尽量少的请求完成动作，并根据游戏状态确认结果。

## 基本原则

1. 先读取附近状态，再选择动作；移动中的玩家、实体和目标方块都可能改变位置，执行前使用新坐标。
2. RPC 返回 `taskId` 只表示任务已提交。订阅并匹配同一个 `taskId` 的 `task.done` 或 `task.failed`。
3. `task.done` 表示任务逻辑结束，不一定代表游戏效果成功。攻击后查目标血量/是否仍存在；交互后重新查方块状态。
4. 遇到 `lineOfSight:false` 或 `NO_PATH` 时先处理墙、门等遮挡，再重试。攻击会尝试打开视线射线正前方、且在交互距离内的可手动开门、活板门或栅栏门；其他墙体需要先找门或绕到可见侧。
5. 一次只发一个会改变动作状态的任务。感知和动作默认限速分别为每秒 4 次和 8 次；收到 `RATE_LIMITED` 后等待再发。

## 连接与状态

地址通常是 `ws://127.0.0.1:8123/agent`。先鉴权，再按返回的 `scope` 决定是否需要 attach：躯壳专属 token 会在鉴权时自动绑定；全局 token 要调用 `body.attach`。

```json
{"jsonrpc":"2.0","id":1,"method":"auth","params":{"token":"<token>"}}
{"jsonrpc":"2.0","id":2,"method":"body.attach","params":{"name":"<body>"}}
{"jsonrpc":"2.0","id":3,"method":"sense.snapshot"}
```

WebSocket 同时承载 RPC 响应和服务端推送。按 `id` 匹配请求响应；没有 `id` 的推送帧也要保留，不能在等待 RPC 响应时丢掉。推送帧可能先于对应 RPC 响应到达。

常用状态：

- `sense.snapshot`：位置、朝向、生命值、手持物、护甲等；它**不是完整背包**。
- attach 后的 `t:"I", full:true`：背包全量物品在 `chg` 数组中。全量帧可能先于 attach 的 RPC 响应到达。
- `sense.player`：玩家位置和距离。
- `sense.entity`：附近实体的位置、距离、生命值和 `lineOfSight`。
- `sense.blockAt` / `sense.blocks`：方块 ID、`blockData`、碰撞属性；可开关方块还包含 `open`。
- `body.help`：服务器支持的方法清单。

空闲时间较长时每 20 秒调用一次 `body.keepalive`，避免躯壳进入旁观态。状态为 `paused` 时调用 `body.wake` 或再次 `body.attach`。

## 动作与结果

动作 RPC 的响应通常只有 `taskId`、`status` 和任务类型。随后处理同一任务的 `task.progress`、`task.done` 或 `task.failed`。不要把进度达到 1、任务完成事件或 HTTP/WebSocket 请求成功单独当作游戏结果。

```json
{"jsonrpc":"2.0","id":10,"method":"act.follow","params":{"player":"<player>","distance":1.5}}
```

`act.follow` 是一次性任务：进入 `distance + 0.5` 格并稳定一段时间后结束。到达后用 `sense.snapshot` 与 `sense.player` 核对最终距离；玩家继续移动时，再提交一次跟随。

`act.goto` 的回执可能带 `adjustedY`，表示目标高度被上调。以最终位置和任务事件为准；若路径失败，重新读取地形后选择可站立的位置，不要盲目重复相同坐标。

`act.follow` 失败并返回 `NO_PROGRESS` 时，先读最新的 `sense.snapshot` 和目标玩家位置，再用 `sense.blocks` 查看脚下与前方的方块状态。任务自身会给寻路和转身最多 5 秒的无进展观察期；导航识别到持续卡住时也可能更早失败。重新跟随前先换到可站立且有空间的位置，避免原坐标上连续重试。

## 开门并攻击

### 开门

1. 用 `sense.blocks` 扫描目标附近，找出门的上下半块，查看 `blockData` 和 `open`。区域体积不可超过 `16³`。
2. 门关闭时，对**下半块坐标**调用 `act.interact`。
3. 等待该任务的终态，然后用 `sense.blockAt` 重新查询门。只有读到 `open:true` 才能认定门已打开。
4. 若任务以 `NO_PATH` 失败，通常是视线被墙或其他方块挡住。移动到门的可见一侧，再检查位置并交互；不要在原地重复请求。

### 攻击

1. 用 `sense.entity` 找到目标，记录其 `id`、位置、血量和 `lineOfSight`。
2. 若目标在关门房间里，优先找到门并调用 `act.interact`；该任务会尝试走到能看见门的一侧，并在门状态未改变时返回失败。
3. 对指定实体调用 `act.attack`，优先使用 `entityId`，避免误选其他生物。追击会尝试打开攻击视线射线正前方的可手动开门、活板门或栅栏门；隔墙且未打开遮挡时不会挥剑。
4. 攻击进度按服务端确认的伤害计算，不按挥剑次数计算。任务结束后仍要重新查询目标；血量下降或目标从足够大的扫描范围中消失，才可报告伤害/击杀。

```json
{"jsonrpc":"2.0","id":11,"method":"act.interact","params":{"x":10,"y":64,"z":20}}
{"jsonrpc":"2.0","id":12,"method":"act.attack","params":{"entityId":123,"times":5,"chase":true}}
```

### 装备

`act.equip` 可按物品 ID 选取并装备物品，例如 `{"item":"diamond_sword"}` 或 `{"item":"netherite_chestplate"}`。操作后用 `sense.snapshot` 检查 `held` 或 `armor`；不要假定物品一定在主手。

## 汇报

向玩家简短汇报可验证的结果，并区分“任务已完成”和“目标效果已确认”。例如：门状态已变为 `open:true`；攻击任务结束，但目标仍有 20 点生命，尚未击杀。
