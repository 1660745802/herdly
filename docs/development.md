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
.venv/bin/python -m unittest discover -s tools -p 'test_dev_fake_ssh.py' -v
```

Windows PowerShell：

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r tools/requirements.txt
$env:HERDR_TEST_PYTHON = (Resolve-Path .\.venv\Scripts\python.exe).Path
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleUxTest :app:lintDebug
.\.venv\Scripts\python.exe -m unittest discover -s tools -p 'test_dev_fake_ssh.py' -v
```

JVM 测试覆盖解析、协议、审批证据、操作状态、存储、仓库生命周期、阅读与连续使用流程。`HerdrClientTransportTest` 使用真实 JSch 连接本地 Paramiko 子进程，测试超时、取消、丢失回包、认证与主机指纹。

找不到可用 Python / Paramiko 时，SSH 传输测试会明确跳过；绿色构建本身不能证明这部分通过。完整检查应确认 XML 报告中没有跳过。报告位于 `app/build/reports/tests/testDebugUnitTest/`，Lint 报告位于 `app/build/reports/`。

## 本地模拟主机

```sh
.venv/bin/python tools/dev_fake_ssh.py --canned
adb reverse tcp:2222 tcp:2222
```

Windows 使用 `.\.venv\Scripts\python.exe`。在独立测试版添加 `127.0.0.1:2222`，用户名 `root`，密码 `secret`。这些仅为本地 fixture 凭据。默认模式只提供模拟数据，不执行界面上的命令；端口仅绑定 loopback。

结束后停止脚本并移除转发：

```sh
adb reverse --remove tcp:2222
```

故障模式、主机密钥和支持的 API 见 [tools/README.md](../tools/README.md)。

## 代码布局

```text
app/src/main/java/dev/herdwatch/
  model/    主机、agent、结构与协议模型
  domain/   审批解析与策略、操作状态、agent 参数
  ssh/      连接接口、JSch 实现、JSON-RPC 编解码
  data/     多主机仓库、加密配置和缓存
  ui/       ViewModel、Compose 页面、队列与主题
app/src/test/  JVM 单元及回环 SSH 测试
assets/        品牌素材和界面截图

docs/         当前产品、工程、开发说明与 API schema
tools/        本地 SSH fixture 与 Python 测试
```

变更保持以下约束：UI 不直接操作 SSH；写入不自动重试；异步结果发布前校验页面或连接代次；原文与展示加工分开；未知结果应明确告知用户。修改审批、并发或存储时运行对应回归；布局修改检查小屏、大字体、键盘和深色模式。

提交前检查 `git diff --check` 和暂存内容。构建产物、本机 SDK 路径、虚拟环境、凭据、私钥与发布签名不入库。不要将真实终端输出或主机配置写进测试、截图和日志。
