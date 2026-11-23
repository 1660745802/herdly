# 工程

界面行为契约见 [design/interaction-spec.md](design/interaction-spec.md)，范围与验收见 [product.md](product.md)，命令见 [development.md](development.md)。本文件只写实现层的约束与取舍。

## 1. 栈与版本

| 项 | 选择 |
| --- | --- |
| 语言 / UI | Kotlin 2.1.0，Jetpack Compose + 自绘设计系统 `ui/design`（Material 3 只用于表单控件与系统弹层，颜色与字号由应用调色板和 `DsTypography` 派生） |
| 构建 | AGP 8.7.3，Gradle 8.14（wrapper），JDK 17 或 21（字节码目标 17） |
| SDK | compileSdk / targetSdk 35，minSdk 26 |
| SSH | JSch 0.2.20，纯 JVM |
| 序列化 | kotlinx.serialization JSON 1.7.3 |
| 存储 | SharedPreferences + Android Keystore AES-GCM |
| 字体 | 随包 `res/font/terminal_mono.ttf`（等宽 + CJK 等宽回退），不依赖系统等宽字体 |
| 测试 | JUnit4、coroutines-test；Python unittest / Paramiko |

应用名 Herdly，包名 `dev.herdwatch`。`uxTest` 构建用独立包名 `dev.herdwatch.uxtest`，不覆盖正式应用数据。

## 2. 传输

Herdr bridge 一行一个 JSON 请求，一条 exec 通道处理一次请求，完成后关闭。默认命令经登录 shell 运行，用于恢复非交互 SSH 缺少的 PATH：

```bash
bash -lc 'herdr [--session <name>] remote-api-bridge'
```

自定义命令按配置运行。请求按字符串 `id` 匹配响应，最多跳过 20 行启动输出；RPC 错误转成 `HerdrRpcException`。每个主机一条逻辑客户端，客户端内串行请求。阻塞 SSH 工作放在可取消的执行器任务里：取消断开 session，关闭的客户端不复用。读屏取消只上抛 `CancellationException`，不拆会话。一次请求失败不等于连接坏了：`useHost` 的 `dropDeadSession` 只在 `client.isConnected == false`（socket 真的没了）或主机密钥出错时才关客户端，其余异常原样上抛给调用方。早先任何异常都 `fail()` 掉整个会话，于是"一次读超时"变成"重新握手"，在蜂窝网络下就是用户看到的"连接很不稳定"。主路径不需要主机额外安装 Python、代理或守护进程。

## 3. 数据流与并发

```text
Compose Screen → AppViewModel → HerdRepository
                               ├─ HerdrClient → Herdr bridge
                               ├─ BridgeCodec
                               └─ HostStore → Keystore
AppViewModel → AskParser / ApprovalPolicy / OperationState / AgentTranscript
```

- `gate` 只保护短内存状态转换，不跨网络调用持有；`configGate` 串行化配置持久化事务；每主机独立 Mutex。
- 连接持有客户端身份、配置版本、前后台 generation 的 lease，过期结果不能发布快照、错误或就绪状态。页 generation 与读请求 generation 防止旧异步结果覆盖新页。
- 全量同步用 Semaphore 限 4 个主机任务，单主机 25 秒超时，一台失败不取消其他主机，`refreshing` 在 finally 收尾。
- 写入 RPC 的接受结果与后续刷新分离：刷新失败不把已接受的写入改判为发送失败；所有 mutation 都不自动重试。
- 重连按 `hostId` 去重，一次尝试未结束时的重复点击直接返回。

## 4. 生命周期与同步节奏

- `ProcessLifecycleOwner` ON_START 恢复连接并回到前台节奏；ON_STOP 不关客户端，只置 `visible=false` 并放慢轮询。
- 前台 5 秒同步，后台 20 秒（`HerdRepository.backgroundPollIntervalMillis`），回前台立即刷新一次，手动刷新始终可立即触发。
- 有主机时 `ConnectionService`（`dataSync` 前台服务）持有进程，通知文案由纯函数 `connectionSummary(RepoState)` 生成，只在文案变化时重发；主机清空或用户退出即 `stopSelf()`。Android 14 的 `dataSync` 预算耗尽走 `onTimeout`：先离开前台再停服务，并停掉 watcher，避免留下一条没人清理的常驻通知。
- Android 13+ 拒绝 `POST_NOTIFICATIONS` 时服务照常运行，只是通知不可见。
- 失败、断线、返回前台都保留最后快照并标注状态与成功同步时间；加密缓存只保存最新成功快照里真实存在的 agent。
- 详情每 3 秒尝试读屏，已有读取时不取消重发；操作记录与草稿按 agent 保存，切页不解除操作锁。草稿只在 ViewModel 内，支持切页 / 后台 / 旋转，不承诺进程死亡后恢复。
- 阅读位置按 pane 记在内存 LRU（上限 80，按访问顺序淘汰）：滚动偏移、是否跟随底部、用户手动暂停。TalkBack 触发的自动暂停与用户暂停分开记录。冻结期间状态序号变化标为待同步，读取期间发生变化的证据保持 stale。
- 没有 WorkManager 或 Alarm；唯一常驻是上面那个由连接数决定生死的前台服务。

整机终端另有自己的重连策略（`ui/TerminalController.kt`）：可重试失败按 1 / 2 / 4 / 8 / 16 / 30 秒退避，六次后停下等人处理；`retryDelayMs(attempt, error)` 是纯函数，自动重连不重置计数（否则永远停在第一档）。认证失败、指纹不符、需要动态口令不重试。网络恢复回调立刻重试一次并取消已排队的等待；回调注册在应用 Context 上，`AppViewModel.onCleared()` 必须 `terminal.dispose()` 注销。滚动历史 5000 行（`SCROLLBACK_LINES`）。

尚未量化 4G RTT、耗电与大量 agent 的成本；事件订阅应在测量之后决定。

## 5. 身份与排序

BridgeCodec 映射 `blocked → WAITING`、`unknown → UNKNOWN`、`done → REVIEW`、`working → WORKING`、`idle → IDLE`。每个 pane 一行，稳定 key `hostId:paneId`。等待时长由客户端观察同状态持续时间计算，不是服务端排队时间；REVIEW 只代表 Herdr 的 done，没有本地已读游标。

列表按机器分块（`hostQueueBlocks`），机器顺序为待处理数降序 → agent 数 → 分屏数 → 名称，保证刷新不跳格。agent 顺序由 `ui/InboxQueue.kt` 的 `inboxQueue(agents, filter, query)` 决定：WAITING → UNKNOWN → REVIEW → WORKING → IDLE，WAITING 内按等待开始时间升序，其余按状态变化序号降序，同序按名称。搜索不区分大小写，匹配名称、仓库、主机、类型、pane ID、工作区、标签页、cwd；收起搜索框保留 query 与结果。宽屏两栏各自调用同一个 `hostQueueBlocks(..., emitAgents, emitPanes)`，与竖屏一一对应。

## 6. 读屏、解析与发送

`pane.read` 没有宽度参数，`pane.resize` 改的是分屏布局而不是列数（见 `docs/herdr-api-schema.json`），所以远端 pty 永远按它自己的宽度渲染，手机拿到的行可能比屏幕宽得多。`ui/TerminalDump.kt` 因此分两类画：散文行按视口宽度折行（否则右侧内容直接看不见），含制表符框线（`hasBoxDrawing`）的行保持原宽、单行裁剪并可横向平移（`horizontalScroll` 常开），整行都是框线（`isBoxRule`）时当作一条线画出来。早先统一折行会把一条 `──────` 折成三四行、并给每一行加上幻影 `│`。

读取是静默的：`pane.read(recent_unwrapped, lines, format=ansi, strip_ansi=false)`。`lines` 从 `PANE_READ_LINES = 1200` 起步，上滑到顶按 `historyLines` 翻倍，直到 `truncated=false`。`ReadSource` 没有历史游标，"更早"只能请求更长的尾部，每次响应都是上一次的超集；所以唯一的天然终点是主机自己说"没有了"。`MAX_HISTORY_LINES = 1_000_000` 只是防溢出的护栏，不是产品上限 —— 早先把它压到 12k/20k 的理由是"更大的请求会超时"，那是传输问题，不该变成"历史只有这么多"的规则；超时就报超时，让读者决定要不要再试。读取失败保留旧原文并标注错误与过期状态。

历史读取有三种结局，界面必须能区分：`truncated=false` 是主机真的没有更早的内容（顶部写「更早的屏幕内容服务端已经不留了」）；主机返回了 `truncated=true` 就继续翻倍；超时或读失败置 `historyFailed`，顶部出现可点的「读取更早内容 / 读取失败，点一下重试」。`historyFrontier` 记住最后一次**真正拿到**的宽度：失败后重试请求的是这个宽度，而不是更大的宽度——失败后继续加宽，放大的正是刚刚超时的那次请求。`TimeoutCancellationException` 也是 `CancellationException`，`loadPane` 必须先捕获它，否则重试提示永远不会出现。

两条渲染路径：整机终端走 `term/VtScreen.kt`（真 VT 解析：备用屏幕、SGR、括号粘贴、OSC 标题、BEL），分屏走 `ui/TerminalDump.kt`（按列对齐的等宽网格，默认自动换行——快照没有横向滚动，不换行等于丢掉右半边）。`ui/ContrastColor.kt` 把远端假设为浅底终端的墨色提到深底可读。审批证据、复制与核对始终用原始文本；复制整屏只做去 ANSI，不做压缩。

终端输入只有一条链路：`ui/TerminalInputView.kt` 是 `onCheckIsTextEditor()` 为真的自定义 View，`InputConnection` 把 IME 的 commit / deleteSurroundingText（UTF-16 与码点两种）/ 方向键译成 PTY 字节，本地只留一份诊断镜像行；会话切换与重连按 generation 丢弃迟到提交。输入类型是 `TYPE_CLASS_TEXT | MULTI_LINE | NO_SUGGESTIONS | TYPE_TEXT_VARIATION_URI`：URI 变体让 MIUI/EMUI 给出拉丁键盘而不是拼音候选，同时避开 `VISIBLE_PASSWORD` 触发的安全键盘黑屏。`⇧Tab`、`⌫`、`PgUp`、`PgDn` 绕过输入框直接发字节（`ESC[Z` / DEL / `ESC[5~` / `ESC[6~`），桥的按键词表没有这些名字。

AskParser 去 ANSI / 控制字符后只解析末尾的连续编号菜单、y/n、Enter/Esc：编号必须从 1 连续开始，菜单后出现新业务输出时不复用旧选项，英文意图按词匹配（避免把 token 当作 ok）。问题与全部选项完整展示。

辅助审批的条件：

1. agent 仍存在且状态为 WAITING / UNKNOWN，主机在线且应用在前台；
2. 已展示并重新读取的证据均完整、非截断；
3. 问题、全部选项、按键与完整屏幕上下文一致；
4. 可用 revision 没有变化，revision 为 0 时仍必须比较完整证据；
5. 发送所选原始按键，不依据按钮标题重新推导。

发送结果区分 RPC 已接受、屏幕变化、未变化、后续读取失败。屏幕未变化不能证明没送到，变化也不能证明任务成功。同一份审批证据禁止重复辅助提交；结果不确定时阻止继续写入，需要刷新、阅读并显式核对后解除。长期授权二次确认，中断始终确认 Ctrl-C。回复草稿只在 RPC 接受后清除同一版本，失败或用户已编辑新草稿时保留。三个发送入口（prompt / keys / input）都过同一个 `canSend` 闸门。

### 对话拆分的诚实边界

`domain/AgentTranscript.kt` 把一屏快照拆成 user / assistant / tool / system / approval 块，是**启发式**，宁可拒绝也不猜：

- `>` 开头的行只有在整行没有制表符（│ ┃ ├ ┼ 等）、长度 ≤160、不是 `$ ` 命令、也不含 `405 ~ 105` 这类区间时才认作用户发言，引用的表格行因此不会被标成「你」。
- ``` 围栏内是字面代码，不再做角色识别。
- 只有 `coverage ≥ 0.6 && 块数 ≥ 2 && 至少一个 user 或 assistant 块` 才置 `reliable`；不可信时退化成一整块正文（去装饰行），不弹「无法识别」。
- 我发的消息只来自本地发送记录，绝不从屏幕猜。
- 审批、复制、核对永远用原始证据。

**剩余限制**：读屏与发送是两次 RPC，无法消除中间的远端状态变化窗口；严格原子审批需要 Herdr 提供带 expected revision / evidence 的条件写入。解析器无法识别时提供原文、手动按键与回复。

界面材质由 `ui/design` 的 `DsTokens` + `neoRaised` / `neoInset` / `neoState` 实现：单一光源的同心圆角矩形合成阴影，兼容 API 26，不用系统 backdrop blur。终端表面共用 `LocalTerminalTokens`，保持不透明与高对比。

## 7. 结构与任务

结构按 workspace / tab / pane 展示，机器页与会话页点同一行都走 `push(Screen.Detail)`，`openPane` 先解析目标再入栈（消失的 pane 不该多花一次返回）。新建工作区 / 标签页带用户填写的起始目录（`createWorkspace(hostId, label, cwd)`），标签页先选工作区，不静默落到"第一个工作区"。新建工作区、标签页、`agent.start`、分屏、聚焦、重命名、关闭都对应 Herdr API，关闭先确认。启动或恢复只提供已连接主机中可保守判定为空 shell 的 pane，并结合当前 agents 排除占用；模型不能证明时不给危险默认值。名称做基本冲突检查，额外参数每行一个并保留行内空格。

resume 参数集中在 `domain/AgentKinds.kt`：claude/pi/opencode 用 `--continue`，codex 用 `resume --last`，gemini 用 `--resume latest`。指令面板的候选由 `domain/AgentCommandCatalog.kt` 按 agent 类型给出，优先采用当前屏幕真实画出的 `/xxx`。

## 8. 信任、认证与存储

- 连接地址：`HostConfig.endpoints` 给出主地址与可选备用地址（备用端口 0 时复用主端口）。桥与终端同一策略：只有异常链里出现 `ConnectException` / `NoRouteToHostException` / `UnknownHostException` / `SocketTimeoutException` / `PortUnreachableException`（`isUnreachableAddress()`）才换下一个地址；认证失败与指纹不符立即上报。成功后 `activeAddress` 记录实际路由，卡片显示「经主地址 / 经备用地址」。
- 密码登录同时支持 `password` 与 `keyboard-interactive`：`keyboardInteractiveAnswers` 只用存储的密码回答**隐藏**字段；`keyboardInteractiveRefusal` 区分「要动态口令」与「要的不是密码」两种拒绝，文案不同。一次性口令提示按整词匹配（`pin` 不能命中 `pinning`）。错答一次会消耗尝试机会并看起来像密码错了，所以宁可拒绝。
- 认证失败按传输报文分类（`isAuthRejection()` 走 cause 链找 `auth fail` / `auth cancel` / `userauth`），不比对给用户看的译文——改一句文案不该把错密码变成可重试失败。
- 首次连接不静默 TOFU：没有已保存指纹时握手阶段中止并抛 `UnknownHostKeyException`（带 SHA-256 指纹与算法名），凭证不发给未确认的主机。终端页弹确认框，确认后以 `allowUnknownKey=true` 重连；桥连接把待确认指纹放进 `HostRuntime.pendingKey`，卡片显示「确认主机密钥」，`trustNewFingerprint` 先落盘再重连。已有指纹在握手阶段校验（`StrictHostKeyChecking=yes`），JSch 认证与校验配置按 session 设置。session 只在握手成功后发布，握手中途退出不留孤儿 socket。
- 交互选项（模型列表、权限确认、diff 审阅、会话选择）由远端 TUI 自己画，应用只解析不发明：`ui/ModelMenu.kt` 的 `parseScreenMenu(screen)` 返回 `ScreenMenu(rows, cursor)`。只看屏幕末尾 30 行非空行（`MENU_WINDOW_LINES`），必须找到指针行（`❯ › > »`），从指针向上下收拢同一个块：允许 2 行非选项间隔（`MENU_GAP_LINES`，高亮项下的说明行），最多 20 行（`MAX_MENU_ROWS`），标签超过 80 字符的行不算选项。不足两行或没有指针都返回 null——编号输出到处都是（构建日志、`git log`），只有编号不算菜单；解析的目标是屏幕上真实存在的选项，散文与 shell 提示符要被拒绝，发送前复核屏幕。重绘可能留下多个指针字形，只有最靠下的一个是活的：`cursor` 取最后一个选中行，否则方向键会从错误的位置起步。`AgentScreen` 解析出菜单时在输入框旁显示「交互选项」入口（chevron，槽位常驻以免发送按钮横移），打开 `ScreenMenuSheet`：点一项只是选中，再按确认才发送；圆点标出分屏指针当前所在行，与读者选中的行不一定是同一行。`AppViewModel.chooseScreenRow` 把选择换算成 |目标行 − cursor| 个 up/down 加一个 enter，走 `sendManual` → `pane.send_input`，与手动发送共用连接检查与操作锁，接受后自动再读一次屏幕；应用从不输入自己的答案。
- 备用地址不是"两个主机"：`HostConfig.endpoints` 先给主地址，再给备用地址（端口 0 表示沿用主端口），两者共用凭证与指纹。只有地址本身没应答（`ConnectException` / `NoRouteToHostException` / `UnknownHostException` / `SocketTimeoutException` / `PortUnreachableException`，沿异常 cause 链识别，因为 JSch 会把 socket 异常包进 `JSchException`）才换下一条；认证失败、指纹不符立刻停，换地址重试只会把一次错密码变成两次并掩盖原因。成功连接后把实际路由写进 `HostRuntime.activeAddress`（卡片显示「经主地址 / 经备用地址」），并用 `HostConfig.lastGoodEndpoint` 记住它：下次连接把这个地址排在第一位，否则回到前台时总要先等主地址超时——移动网络下这就是"回首页要等很久"。`lastGoodEndpoint` 用 `store.save` 单独落盘，不递增 `versions[hostId]`，因为它不是用户配置。单条地址握手超时 8 秒（`HerdrClient.HANDSHAKE_TIMEOUT_MS` / `ShellSession.CONNECT_TIMEOUT_MS`），够局域网握手、也够一条死地址及早让位。
- 主机密钥算法固定 `ssh-ed25519, rsa-sha2-512/256, ecdsa-sha2-nistp256/384/521`（`HostKeyPolicy`），与 OpenSSH 默认顺序一致，用户核对的指纹能和 `ssh-keygen -lf` 对上；不提供 SHA-1 的 `ssh-rsa`/`ssh-dss`。
- 首次指纹必须同步持久化成功再发布就绪；保存失败暴露错误，不能只在内存里信任。指纹落盘在 IO 线程，失败有自己的文案，不伪装成连接失败。
- 密钥变化展示保存值与本次观察值，显式确认后更新；认证错误不伪装成密钥变化。
- 主机列表、密码 / 私钥 / passphrase 加密保存，配置用 `commit()`；主副恢复副本保存同一代信任状态，防止回退旧副本复活已删凭据或旧 pin；两副本都无法解密时拒绝覆盖；删除主机与清理缓存同一事务完成。
- 缓存按主机单独加密；屏幕文本只在内存，复制由用户触发；云备份与设备迁移显式排除应用数据。
- 无账号、遥测、中继。非 SSH 网络请求只有用户手动触发的更新：`data/UpdateChecker.kt` 读 GitHub Release（12 秒超时），`data/UpdateInstaller.kt` 下载 APK 并校验包名与 versionCode，经 `FileProvider` 交系统安装器；需要 `REQUEST_INSTALL_PACKAGES`，未授权时下载不浪费。两者都不上传数据。未做生物识别、截图保护、证书、跳板机。

Repository 通过 `HostConnection` 接口与 internal 构造函数注入传输、协程 scope / dispatcher 与时间参数。同步配置保存 / 加解密的主线程耗时仍需测量。

## 9. 验证

JVM 单元测试 309 个，覆盖 AskParser、BridgeCodec、ApprovalPolicy、ApprovalCopy、OperationState、OperationRpc、HostStore、HostEndpoint、HerdRepository、HerdrClientTransport、ConnectionSummary、KeyboardInteractive、ReadingPause、ReadingFold、HistoryLoad、SentMessage、PageStack、PaneInput、DesignTokens、NeoGlyph、ContrastColor、TextLinks、TerminalDump、TerminalInputView、TerminalRetryPolicy、TerminalScreen、DailyWorkflow、AgentTranscript、AgentCommandCatalog、ModelMenu、MenuAnswer、VtScreen、UpdateChecker、AttentionAlerts、SeenMarks。

- DailyWorkflow：队列顺序与 pane ID 搜索、收起搜索保留查询、结构返回来源页（含重复打开）、阅读位置离开后重进保留、连接挂起期间重复重连合并。
- Repository：编辑后迟到连接、后台迟到快照、回前台立即刷新、取消清理、慢 / 故障主机隔离、主机锁等待超时、同代刷新合并、离线缓存、已接受写入后的刷新失败、首次 pin 保存失败、密钥变化与逐台信任；用虚拟时间与 `runTest` 的 `backgroundScope`。
- 终端：VT 解析（备用屏幕、SGR、2004 括号粘贴、OSC 标题、BEL）、重连退避 ladder 与不可重试分类、输入提交的 enter 语义、链接提取（含成对括号）。

Python 模拟桥接测试覆盖状态转换、并发 mutation、故障原子性、方法级延迟、聚合状态发布，以及无回复 / 不执行 shell 就关闭传输，含真实 Paramiko socket-pair 传输。JVM 传输测试直接跑 JSch → 回环 Paramiko SSH，验证自身请求超时与外部取消的区别、取消关闭 socket 与新连接恢复、写入已提交但回包丢失且不自动重发、认证失败与 pin 不匹配。无 Python / Paramiko 时相关测试明确跳过，用 `HERDR_TEST_PYTHON` 指定解释器。

模拟主机只绑定 loopback，共享状态可重现命令变化、输出不变、接受后读失败、RPC 错误、延迟与多种审批表单；本地 shell 执行必须显式 opt-in，默认 canned 不执行命令。详见 [tools/README.md](../tools/README.md)。

`tools/sandbox.sh` 是验证的唯一入口：`up` 先停正式包、只起测试包（`dev.herdwatch.uxtest`）与回环主机并建 `adb reverse`，`shot` 同时存视图树和截图，`do` 把单个 adb 命令关在同一个守卫里，`down` 只停它自己起的进程与反向端口。理由是两条已经踩过的坑：`am start` 会把之前的前台包（uxtest）拉回前台，让人误以为看到的是正式包；`pkill` 式的清理会误杀不是自己起的 fixture。

状态提醒分三层：`data/SeenMarks.kt` 记录「哪个分屏的第几轮已经看过」（只升不降，进页面时写一次），`data/AttentionAlerts.kt` 决定一次状态变化要不要提醒（被阻塞 / 报错 / 未知一定提醒；只是完成时，只有当前不在看着这一屏才提醒；重连后第一次快照静默种下历史），`data/AppPresence.kt` 是唯一的前台与当前页真相，服务与界面都读它。终端页另解析 OSC 7501（`VtScreen` 的 `VtProgramStatus` / `turn`），有上报才显示状态条，没有就什么都不显示。

尚未覆盖：真实审批端到端、多主机复杂网络竞争、完整 TalkBack、平板 / 横屏、API 26 设备运行、弱网耗电。Lint 检查 minSdk 兼容性，不能替代旧系统运行测试。

## 10. 国际化与偏好

- 简体中文（`res/values/`）与英文（`res/values-en/`），`res/xml/locales_config.xml` 声明支持语言。Android 13+ 用 `LocaleManager` 应用级语言，切换靠 `configChanges=locale|layoutDirection` + `key(localeRevision)` 原地重组，不重建 Activity（避免黑屏闪烁）；Android 12 及以下提示跟随系统。
- 非界面层（Repository、SSH、解析器、ViewModel）用 `dev.herdwatch.i18n.Str.get(R.string.x, args)`，provider 在 `HerdwatchApp.onCreate` 安装一次；单元测试用 `TestStrings.install()`。资源与文案规则见 [development.md](development.md)。
- 偏好只有习惯项：语言、主题、终端字号（`data/Preferences.kt`，独立文件 `herdwatch.prefs`，`commit()` 写入，`StateFlow` 暴露，`PreferencesStore.inMemory()` 供测试注入）。动效跟随系统「减少动态效果」，排版节奏由 `DsSpacing` 固定，不做成开关。`HostStore` 只存主机与凭据。

### Host keys, credential store and visibility
- A pin is written only after the user confirms the fingerprint the dialog showed
  (`AppViewModel.trustNewFingerprint`, which also rejects a pending key that changed since it was
  rendered). The connect path never persists a key it merely saw: a host with no pin stays in the
  "needs confirmation" state until someone taps 信任.
- If the Keystore entry is lost, `HostStore` raises `HostStoreUnavailable` instead of deleting the
  stored payload; the repository exposes it as `RepoState.storeError` and the UI tells the reader
  that machine config cannot be read or saved, rather than appearing to work.
- A process recreated without an Activity (service restart) starts hidden, so polling runs at the
  background cadence until ON_START says otherwise. The pill keeps a `dataSync` foreground service
  that stops itself when the last host disconnects; it is restarted from ON_START and host changes,
  not from the background, because Android 14 forbids that.
