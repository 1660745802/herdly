# 工程

## 1. 栈与版本

| 项 | 选择 |
| --- | --- |
| 语言 / UI | Kotlin 2.1.0，Jetpack Compose + 自绘设计系统 `ui/design`（Material 3 仅剩添加主机表单的密码输入框） |
| 构建 | AGP 8.7.3，Gradle 8.14，JDK 21 |
| SDK | compileSdk / targetSdk 35，minSdk 26 |
| SSH | JSch 0.2.20，纯 JVM |
| 序列化 | kotlinx.serialization JSON 1.7.3 |
| 存储 | SharedPreferences + Android Keystore AES-GCM |
| 测试 | JUnit4、coroutines-test；Python unittest / Paramiko |

应用名称为 Herdly，包名 `dev.herdwatch`。`uxTest` 构建使用独立包名 `dev.herdwatch.uxtest`，不覆盖正式应用的数据。

## 2. 传输

Herdr bridge 一行一个 JSON 请求，一条 exec 通道处理一次请求，完成后关闭。默认命令通过登录 shell 运行：

```bash
bash -lc 'herdr [--session <name>] remote-api-bridge'
```

自定义命令按配置运行；默认命令的登录 shell 用于恢复非交互 SSH 缺少的 PATH。请求按字符串 `id` 匹配响应，最多跳过 20 行启动输出。RPC 错误转换为 `HerdrRpcException`。

每个主机一条逻辑客户端，客户端内串行请求。阻塞 SSH 工作放在可取消的执行器任务中；取消会断开 session，关闭的客户端不会重用。认证、连接失败与真实主机密钥不匹配分别处理。主路径不需要主机额外安装 Python、代理或守护进程。

## 3. 数据流与并发

```text
Compose Screen → AppViewModel → HerdRepository
                               ├─ HerdrClient → Herdr bridge
                               ├─ BridgeCodec
                               └─ HostStore → Keystore
AppViewModel → AskParser / ApprovalPolicy / OperationState
```

仓库的 `gate` 保护短内存状态转换；不跨网络调用持有。`configGate` 串行化配置的持久化事务，每个主机有独立 Mutex。连接持有客户端身份、配置版本、前后台 generation 的 lease；过期连接结果不能发布快照、错误或就绪状态。

全量同步通过 Semaphore 限制最多 4 个主机任务，单主机 25 秒超时；一个主机失败不取消其他主机。`refreshing` 在 finally 中收尾。写入 RPC 的接受结果与后续刷新分离：刷新失败不把已接受的写入改判为发送失败。所有 mutation 均不自动重试。

重连按 `hostId` 去重：同一次连接尝试未结束时的重复点击直接返回，不会并发发起第二个连接；尝试结束（成功或失败）后才允许再次重连。

## 4. 同步与生命周期

- `ProcessLifecycleOwner` ON_START 恢复连接，ON_STOP 取消轮询、使 lease 失效并断开客户端。
- 前台每 5 秒同步，手动刷新可立即触发。
- 失败、断线与返回前台时保留最后快照，界面标注连接状态和成功同步时间；旧数据不代表实时状态。
- 加密缓存用于启动时展示上次 agent 列表及按原 pane 恢复；只有新快照能确认当前远端状态。
- 详情每 3 秒尝试读屏，已有读取时不取消重发。阅读历史时仅停止自动跟随，读取仍继续，有新内容入口可回到底部；“暂停更新”、选择复制和全屏原文阅读会冻结已显示证据，首次读取仍会完成，手动刷新始终可用。页面打开时检测 TalkBack 触摸探索并默认暂停更新。
- 阅读位置按 agent 记在 ViewModel 的内存 LRU（上限 80，按访问顺序淘汰）中：页滚动偏移、原文滚动偏移、是否跟随底部、用户手动暂停。切页、返回和重新进入会恢复；不写盘，进程结束即丢失。TalkBack 触发的自动暂停与用户手动暂停分开记录，恢复自动更新不改写用户的暂停选择。
- 冻结期间状态序号变化会标为待同步；读取期间发生状态变化，返回的证据也保持 stale，需重新读取后才允许辅助审批。
- 详情离线时提供“重连并读取”，同主机重连合并重复点击；成功后仅在原页面 ticket 仍有效时读回当前任务，不跳页或清空草稿。
- 没有 WorkManager、Alarm、前台服务或通知。

尚未量化 4G RTT、耗电和大量 agent 的成本；事件订阅应在测量之后决定。

## 5. 收件箱与详情身份

BridgeCodec 映射 `blocked → WAITING`、`unknown → UNKNOWN`、`done → REVIEW`、`working → WORKING`、`idle → IDLE`。每个 pane 一行，稳定 key 为 `hostId:paneId`。等待时长由客户端观察同状态持续时间计算，不是服务端排队时间。REVIEW 只代表 Herdr 的 done，没有本地已读游标。

列表展示机器、工作区 / 标签页、类型和 pane 身份。顺序由 `ui/InboxQueue.kt` 的 `inboxQueue(agents, filter, query)` 统一决定，列表与前后导航共用同一份结果：WAITING → UNKNOWN → REVIEW → WORKING → IDLE；WAITING 内按等待开始时间升序（等得最久在前），其余按状态变化序号降序，同序再按名称。ATTENTION 过滤只保留前三类。搜索不区分大小写，匹配名称、仓库、主机、类型、pane ID、工作区、标签页和 cwd，可直接用 pane 身份找任务。收起搜索框只隐藏输入框，保留 query 与过滤结果，并在列表上方以一行显示当前生效的搜索。

详情前后导航使用打开时的筛选 / 搜索队列快照，跳过已消失目标。页 generation 与读请求 generation 防止 A → B → A 时旧异步结果覆盖新页。操作记录和草稿按 agent 保存，切页不会解除操作锁。草稿留在 ViewModel 内，支持切页、后台和旋转；不承诺进程死亡后的恢复。

## 6. 审批和发送

读取是静默的：`pane.read(recent_unwrapped, lines, format=ansi, strip_ansi=false)`，`lines` 由 `AppViewModel.PANE_READ_LINES = 1200` 起步，上滑到顶时按 `historyLines` 翻倍（上限 `MAX_HISTORY_LINES = 19_200`），直到服务端返回 `truncated=false`。`ReadSource` 没有历史游标，"更早"只能靠请求更长的尾部，每次响应都是上一次的超集；界面不显示请求行数，只在截断时给一句轻提示。读取失败保留旧原文并标注错误与过期状态。

终端使用独立权重视口；状态条只在错误、操作结果、主机断开、证据过期或 TalkBack 暂停时出现，正常阅读不显示时钟与暂停开关。底部回复与审批入口固定，小高度时收起页头细节。页面骨架契约见 [design/interaction-spec.md](design/interaction-spec.md)。审批面板暂停轮询；发送启动后关闭，校验拒绝则保留并展示原因。恢复阅读时若正在发送，不触发竞争读请求。

AskParser 去 ANSI / 控制字符后，仅解析末尾的连续编号菜单、y/n、Enter/Esc。编号必须从 1 连续开始；菜单后出现新的业务输出时不复用旧选项。英文意图按词匹配，避免把 token 等文本当作 ok。问题与所有选项完整展示。

辅助审批的条件：

1. agent 仍存在且状态为 WAITING / UNKNOWN，主机在线且应用在前台；
2. 已展示并重新读取的证据均完整、非截断；
3. 问题、全部选项、按键和完整屏幕上下文一致；
4. 可用 revision 没有变化；revision 为 0 时仍必须比较完整证据；
5. 发送所选原始按键，不依据按钮标题重新推导。

发送结果区分 RPC 请求已接受、屏幕变化、未变化和后续读取失败。屏幕未变化不能证明没送到，变化也不能证明任务成功。已接受的同一份审批证据禁止重复辅助提交；结果不确定时阻止继续写入，需要刷新、阅读并显式核对远端后解除。长期授权独立二次确认，中断始终确认 Ctrl-C。

回复草稿只在 RPC 接受后清除同一版本；失败或用户已经编辑新草稿时保留。终端模式默认显示原始屏幕（`ui/AnsiText.kt` 渲染 SGR 颜色，并裁掉快照为对齐补的行尾空格）；聚焦模式只额外折叠装饰线与多余空行。审批证据、复制与核对始终使用原始文本。

### 对话视图的诚实边界

`domain/AgentTranscript.kt` 把一屏快照拆成 user / assistant / tool / system / approval 块。它是**启发式**，因此宁可拒绝也不猜：

- `>` 开头的行只有在整行没有制表符（│ ┃ ├ ┼ 等）、长度 ≤160、不是 `$ ` 命令、也不含 `405 ~ 105` 这类区间时才认作用户发言；引用的表格行因此不会被标成「你」。
- ``` 围栏内的内容是字面代码，不再做任何角色识别（否则命令行注释会被拆成新的一段对话）。
- 只有 `coverage ≥ 0.6 && 块数 ≥ 2 && 至少一个 user 或 assistant 块` 才置 `reliable`；不可信时对话视图退化成一整块正文（去掉装饰行），不再弹「无法识别」的提示——切到终端模式随时能看到原文。
- 审批、复制、看原文永远使用原始证据，绝不使用拆分结果。

**剩余限制**：读屏与发送是两次 RPC，无法消除其中的远端状态变化窗口。严格原子审批需要 Herdr 提供带 expected revision / evidence 的条件写入 API。解析器仍是启发式，无法识别时提供原文、手动按键和回复。

界面材质由 `ui/design` 的 `DsTokens` + `neoRaised` / `neoInset` / `neoState` 统一实现：单一光源的同心圆角矩形合成阴影，兼容 API 26，不使用系统 backdrop blur。`GlassBackdrop` 只画底盘色与左上角极淡提亮。终端表面共用 `LocalTerminalTokens`，保持不透明和高对比。

## 7. 结构与任务

结构按 workspace / tab / pane 展示。进入结构时记录来源页，返回回到该来源（收件箱或机器页等）；重复打开结构不会把来源改写成结构页本身。新建工作区、标签页、agent.start、分屏、聚焦、重命名、关闭均对应 Herdr API。关闭先确认。启动或恢复只提供已连接主机中可保守判定为空 shell 的 pane，并结合当前 agents 排除占用；模型不能证明时不给危险默认值。名称做基本冲突检查，额外参数每行一个，保留参数内空格。

resume 参数集中在 `domain/AgentKinds.kt`：claude/pi/opencode 使用 `--continue`，codex 使用 `resume --last`，gemini 使用 `--resume latest`。

## 8. 信任与存储

- 首次连接不再静默 TOFU，终端和 Herdr 桥两条连接一致：没有已保存指纹时 SSH 层在握手阶段中止并抛出 `UnknownHostKeyException`（携带 SHA-256 指纹与算法名），凭证不会发给未确认的主机。终端页弹出指纹确认框，确认后以 `allowUnknownKey=true` 重连；桥连接把待确认指纹放进 `HostRuntime.pendingKey`，机器卡片显示「确认主机密钥」，确认后 `trustNewFingerprint` 先落盘再重连。已有指纹在握手阶段校验，`StrictHostKeyChecking=yes`；JSch 认证与校验配置按 session 设置。
- 主机密钥算法固定为 `ssh-ed25519, rsa-sha2-512/256, ecdsa-sha2-nistp256/384/521`（`HostKeyPolicy`），与 OpenSSH 默认顺序一致，用户核对的指纹能和 `ssh-keygen -lf` 对上；不提供 SHA-1 的 `ssh-rsa`/`ssh-dss`。
- 首次指纹必须同步持久化成功，再发布连接就绪。保存失败会暴露错误，不能只在内存中信任。
- 密钥变化展示保存值与本次观察值，显式确认后更新；认证错误不会伪装成密钥变化。
- 主机列表、密码 / 私钥 / passphrase 加密保存。配置使用 `commit()`，主副恢复副本保存同一代信任状态，防止回退旧副本复活已删凭据或旧 pin。
- 主副副本均无法解密时拒绝覆盖；删除主机与清理其缓存在同一 preferences 事务完成。
- 缓存按主机单独加密。屏幕文本仅在内存中，复制由用户触发。Android 云备份和设备迁移均显式排除应用数据。
- 无账号、遥测、中继。唯一的非 SSH 网络请求是用户手动触发的更新检查（`data/UpdateChecker.kt` 读取 GitHub Release，12 秒超时，不上传任何数据）。未做生物识别、截图保护、证书、跳板机。

同步配置保存 / 加解密的主线程耗时仍需测量。Repository 通过 `HostConnection` 接口与 internal 构造函数注入传输、协程 scope / dispatcher 和时间参数。刷新合并按生命周期 generation 进行，避免旧批次挡住返回前台的立即刷新。受控协程回归覆盖关键竞争，仍不等同真实网络压力测试。

## 9. 验证

构建、安装与测试命令见 [development.md](development.md)。

JVM 单元测试共 150 个，覆盖 AskParser、BridgeCodec、ApprovalPolicy、OperationState、OperationRpc、HostStore、HerdRepository、HerdrClientTransport、ReadingPause、SentMessage、PageStack、DesignTokens、NeoGlyph、DailyWorkflow、AgentTranscript（对话/终端两种读法的启发式拆分与真实快照夹具）、VtScreen（VT 解析器，含备用屏幕与 2004 括号粘贴）与 UpdateChecker（Release 解析与版本比较）。DailyWorkflow 覆盖队列顺序与 pane ID 搜索、收起搜索保留查询与导航队列、结构返回来源页（含重复打开）、阅读位置离开后重进保留、连接尝试挂起期间重复重连合并。Repository 测试覆盖编辑后迟到连接、后台迟到快照、返回前台立即刷新、取消清理、慢 / 故障主机隔离、主机锁等待超时、同代刷新合并、离线缓存、已接受写入后的刷新失败、首次 pin 保存失败，使用虚拟时间与 `runTest` 的 `backgroundScope`。

Python 模拟桥接测试覆盖状态转换、并发 mutation、故障原子性、方法级延迟、聚合状态发布，以及无回复 / 不执行 shell 就关闭传输；其中包含真实 Paramiko socket-pair 传输。JVM 传输测试直接运行 JSch → 回环 Paramiko SSH，验证自身请求超时与外部取消的区别、取消关闭 socket 与新连接恢复、写入已提交但回包丢失且不自动重发、认证失败和 pin 不匹配。无 Python / Paramiko 时相关传输测试明确跳过；使用 `HERDR_TEST_PYTHON` 指定可用解释器，详见 [tools/README.md](../tools/README.md)。

模拟主机只绑定 loopback，共享状态可重现命令变化、输出不变、接受后读失败、RPC 错误、延迟及多种审批表单；本地 shell 执行必须显式 opt-in，默认 canned 不执行命令。详见 [tools/README.md](../tools/README.md)。

尚未覆盖：真实审批端到端、多主机复杂网络竞争、完整 TalkBack、平板 / 横屏、API 26 设备运行、弱网耗电。Lint 已检查 minSdk 兼容性，不能替代旧系统运行测试。

## 10. 国际化与偏好

- 简体中文（`res/values/`）与英文（`res/values-en/`）；`res/xml/locales_config.xml` 声明支持语言。Android 13+ 用 `LocaleManager` 应用级语言，切换时通过 `configChanges=locale|layoutDirection` + `key(localeRevision)` 原地重组，不重建 Activity（避免黑屏闪烁）；Android 12 及以下明确提示跟随系统。
- 非界面层（Repository、SSH、解析器、ViewModel）用 `dev.herdwatch.i18n.Str.get(R.string.x, args)`，provider 在 `HerdwatchApp.onCreate` 安装一次。资源与文案规则见 [development.md](development.md)。
- 用户偏好集中在 `data/Preferences.kt` 的 `PreferencesStore`（独立 SharedPreferences 文件 `herdwatch.prefs`，`commit()` 写入，`StateFlow` 暴露）；`HostStore` 只存主机与凭据。`PreferencesStore.inMemory()` 供单元测试注入，避免依赖 Android Context。
