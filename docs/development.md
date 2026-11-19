# 开发指南

## 环境

- JDK 21。
- Android SDK Platform 35、Build Tools 35.0.0、Platform Tools。
- Python 3.10+ 与 Paramiko，用于模拟 SSH 和实际 JSch 传输测试。
- 设备或模拟器：Android API 26+。

用 Android Studio 打开项目并选择 SDK，或设置 `ANDROID_HOME`。也可在本地创建 `local.properties`，填写 `sdk.dir=/path/to/Android/Sdk`；该文件不提交。Windows 路径可使用正斜杠。

项目包含 Gradle Wrapper，无需另外安装 Gradle。下文使用 Unix 命令；Windows PowerShell 将 `./gradlew` 换为 `.\gradlew.bat`。

## 构建与安装

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

日常开发可使用独立测试版，避免覆盖正常应用配置：

```sh
./gradlew :app:assembleUxTest
adb install -r app/build/outputs/apk/uxTest/app-uxTest.apk
```

`debug` 包名为 `dev.herdwatch`；`uxTest` 为 `dev.herdwatch.uxtest`。这两种构建使用开发签名。正式分发需要自行配置发布签名，签名文件及密码不得提交。

## 完整检查

先安装 Python 测试依赖。Linux / macOS：

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r tools/requirements.txt
export HERDR_TEST_PYTHON="$PWD/.venv/bin/python"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleUxTest :app:lintDebug
.venv/bin/python -m unittest discover -s tools -p 'test_*.py' -v
```

Windows PowerShell：

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r tools/requirements.txt
$env:HERDR_TEST_PYTHON = (Resolve-Path .\.venv\Scripts\python.exe).Path
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleUxTest :app:lintDebug
.\.venv\Scripts\python.exe -m unittest discover -s tools -p 'test_*.py' -v
```

JVM 测试覆盖解析、协议、审批证据、操作状态、存储、仓库生命周期、阅读与连续使用流程。`HerdrClientTransportTest` 使用真实 JSch 连接本地 Paramiko 子进程，测试超时、取消、丢失回包、认证与主机指纹。

找不到可用 Python / Paramiko 时，SSH 传输测试会明确跳过；绿色构建本身不能证明这部分通过。完整检查应确认 XML 报告中没有跳过。报告位于 `app/build/reports/tests/testDebugUnitTest/`，Lint 报告位于 `app/build/reports/`。

## 本地模拟主机

两个 fixture，都只绑定 loopback、默认不执行本机命令：

```sh
.venv/bin/python tools/dev_fake_ssh.py --canned      # Herdr 桥：状态、审批、故障场景
.venv/bin/python tools/dev_shell_fixture.py          # 交互式 shell：VT、按键、输入模式
adb reverse tcp:2222 tcp:2222
```

Windows 用 `.\.venv\Scripts\python.exe`。在独立测试版里添加对应端口的主机，凭据见 [tools/README.md](../tools/README.md)。结束后停止脚本并 `adb reverse --remove tcp:<port>`。

## 代码布局

```text
app/src/main/java/dev/herdwatch/
  model/    主机、agent、结构与协议模型
  domain/   审批解析与策略、操作状态、agent 参数
  ssh/      连接接口、JSch 实现、JSON-RPC 编解码
  data/     多主机仓库、加密配置和缓存
  ui/       ViewModel、Compose 页面、队列与主题
  ui/design/ 自绘设计系统：token、材质、图标、组件
  term/     VT 解析器（终端网格与滚动回放）
  i18n/     非 Compose 层的文案 provider 与测试替身
app/src/test/  JVM 单元及回环 SSH 测试
assets/        品牌素材和界面截图

docs/         产品、工程、开发说明、视觉规范与 API schema
tools/        本地 SSH fixture 与 Python 测试
```

变更保持以下约束：UI 不直接操作 SSH；写入不自动重试；异步结果发布前校验页面或连接代次；原文与展示加工分开；未知结果应明确告知用户。修改审批、并发或存储时运行对应回归；布局修改检查小屏、大字体、键盘和深色模式。

提交前检查 `git diff --check` 和暂存内容。构建产物、本机 SDK 路径、虚拟环境、凭据、私钥与发布签名不入库。不要将真实终端输出或主机配置写进测试、截图和日志。

## 文案与资源

界面文案禁止硬编码在 Kotlin 里：

- Compose：`stringResource(R.string.x)`、`pluralStringResource(R.plurals.x, count, count)`。
- 非 Compose（Repository、SSH 层、解析器、ViewModel）：`dev.herdwatch.i18n.Str.get(R.string.x, args)` / `Str.quantity(...)`，provider 由 `HerdwatchApp.onCreate` 安装。
- 资源按模块拆文件：`res/values/strings_<module>.xml` 与 `res/values-en/strings_<module>.xml`，键名带模块前缀，中英键集合必须一致。当前模块：core、ssh、repo、store、vm、ask、policy、agent、inbox、terminal、structure、newtask、hosts、settings、addhost、components、app。
- 数字 + 单位一律用 `plurals`；含变量的句子整句进资源，不做字符串拼接。
- 新增语言只需加 `res/values-<lang>/` 并在 `res/xml/locales_config.xml` 登记，系统「应用语言」与设置页分段控件会一起生效。
- 单元测试断言用 `dev.herdwatch.i18n.TestStrings.text("键名")`（它解析 `res/values/*.xml` 并反射映射 `R` id），不要硬编码译文。`Str` 未安装 provider 时返回 `#<id>`，所以带文案断言的测试类要在 `@Before` 调 `TestStrings.install()`。
