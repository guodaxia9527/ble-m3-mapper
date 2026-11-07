# 真机验证记录

## 0.1.3 配置删除与 YouTube 点赞

实现内容：

- 可删除当前 App 的配置，确认框显示目标 App 名称；删除后持久保存剩余配置。
  映射运行时更新剩余方案，删除最后一个配置则停止映射并释放遥控器。
  空列表重新打开后仍为空，可再次添加 App。
- 手机 App 新建配置的 `PHOTO` 默认为 `like`。升级时仅一次将旧 `PHOTO=none`
  改为 `like`，保留其他已分配动作；用户之后自行选择 `none` 会保留。
  旧数据不能区分默认未分配与用户曾主动选择 `none`，此次迁移会统一处理这些 `none`。
- 点赞仅允许 YouTube，检查 `ACTION_SET_RATING` 和 `RATING_THUMB_UP_DOWN`，
  发送 `Rating.newThumbRating(true)`，不发送取消、点踩或切换点赞的操作。
  `METADATA_KEY_USER_RATING` 已表明点赞时直接返回；没有同一内容的确认回传时，
  日志只显示已发送请求，不将命令发出视为已成功点赞。
- 电脑联机原型的 `config/profiles.json` 仍保留 `PHOTO=none`，本次不扩展其点赞能力。
  抖音兼容性留待后续处理，快手不在本轮范围内。

当前验证状态：

- `:app:assembleDebug :app:lintDebug` 通过；0.1.3 已安装，版本代码 4。
- 手机只读检查 YouTube 当前会话：`ratingType=2`、`actions=8615`，
  包含点赞评分能力；但 `userRating=null`，也没有媒体 ID。
  这证明播放器声明支持请求，不能证明实际点赞成功或重复按键效果。
- 真机 UI 已验证取消删除、删除一项保留其他配置及自定义秒数、删最后一项停止前台服务、
  删空后重开仍为空且开始按钮不可用、重新添加的第六键默认点赞、之后改 `none` 不再被迁移。
  使用临时配置完成测试后，逐字核对原有四个用户配置已恢复。
  记录：`logs/013-profile-ui-test.log`、`logs/013-profile-readd-test.log`。
  初次自动化测试遇到无变化状态反复刷新，已改为仅在内容变化时更新界面；
  后续输入对话框随键盘移动的测试定位问题也已修正，未改动用户配置。
- APK 内生产代码的只读后端检查通过 16 项，新增验证空配置、点赞配置以及拒绝非 YouTube 点赞。
  记录：`logs/013-backend-smoke.log`。配置页截图核对通过：`logs/013-ui.png`。
- 实体第六键测试通过：用户选择愿意点赞的视频后按第六键并重复按，明确反馈
  “点赞成功，再按也没有取消”。实时日志记录多次 `PHOTO -> like`，包括 STOPPED 状态，
  每次只发送 `requestedThumbUp=true`。由于 YouTube 没有回传用户评分/媒体 ID，
  程序仍如实记录 `confirmed=false`，实际生效由用户确认。
  记录：`logs/013-like-physical-live.log`、`logs/013-like-confirmation.json`。

## 0.1.2 返回 YouTube 后的媒体状态修复

复现：0.1.1 从 YouTube 回桌面再返回时，映射后台 PID 8020 保持不变，
原生捕获进程被正常释放并重新建立，日志显示已接管遥控器；但 YouTube 的媒体会话
报告 `STOPPED`（状态 1）。旧 `MediaControl` 只接受暂停和播放状态，因此会拒绝此时的按键。
该复现证明存在媒体状态判断问题，不能据此认定用户此前所有失效都由同一原因造成。

修复：允许 `STOPPED` 状态在明确提供 `ACTION_PLAY` 时由中键继续播放；
在提供 `ACTION_SEEK_TO` 且位置有效时由方向键跳转，不附带播放。
没有内容、错误或缓冲等其他状态仍不在本次放行范围内。

验证：

- `:app:assembleDebug :app:lintDebug` 通过；APK 已安装，版本号 0.1.2、版本代码 3。
- 通过真机 CLI 调用生产 `MediaControl`：状态 1、位置 170132 毫秒时执行后退 5 秒，
  观察到位置 165132 毫秒、状态 2（暂停），没有自动播放。
- 再回桌面并返回，状态 1、位置 165132 毫秒时执行播放／暂停操作，
  2 秒后观察到状态 3（播放）、位置 167315 毫秒。
- 安装后，以已安装 APK 优先的 `CLASSPATH` 运行 CLI 复核：回桌面再返回后，
  状态 1、位置 186682 毫秒，后退 5 秒得到位置 181682 毫秒、状态 2。
  再次回桌面并返回，状态为 1、位置仍为 181682 毫秒；映射后台 PID 11913 持续存活，
  原生捕获进程 PID 13079 已重新建立。证据：`logs/012-installed-stopped-seek.json`。

`logs/012-media-home-return.json` 中首次探索的第二轮未达到 `STOPPED`，且出现插播广告，
不能作为第二次停止状态跳转通过的证据。上述 CLI 测试验证实际媒体控制代码和前后台切换，
不等同于拔线后的完整实体按键联动；该项另于下述晚间复测确认。

同日稍晚继续测试时，手机开机时长约 23 分钟，版本仍为 0.1.2，
但无线调试已关闭、Shizuku/映射后台未运行，且 `persist.sys.permission.enable=true`。
这是与下午复现不同的运行环境，需要恢复已获授权的系统设置及启动服务后再测试；
不能将此次未启动状态记作 0.1.2 媒体修复失败，也不能推断所有重启都会恢复该权限设置。

晚间恢复及实体复测：

- 用户重新打开 USB 调试及“禁止权限监控”后，实读 `persist.sys.permission.enable=false`，
  无线调试开启。恢复仅充电模式后 `sys.usb.config=adb`，USB 与无线 ADB 均可连接。
- 从 Shizuku 自身无线启动按钮成功启动，界面“正在运行”；再点映射 App“开始使用”，
  日志显示 `已接管 BLE-M3 · YouTube`。Shizuku PID 11093、映射后台 PID 11683。
- 用户按要求拔掉 USB、回桌面再打开 YouTube，不先手动播放，测试上键和中键，
  明确反馈“后退和播放都正常”。无线侧记录 USB 已断开，两后台保持原 PID，
  捕获进程重新建立；另记录到实体上/下键的媒体控制结果。保存于
  `logs/012-wireless-presence.json`、`logs/012-physical-buttons.log`、
  `logs/012-physical-confirmation.json`。
- 本次确认了拔线与桌面往返后的完整联动；未作长期后台续航或跨厂商验证。

## 0.1.1 生命周期修复

真实故障：Shizuku 已运行，但同时存在两个旧映射后台；一个持有遥控器，
另一个反复 `GRAB_FAILED errno=16`。`.daemon(false)` 不能单独保证在 Shizuku
被重新启动后回收旧 UserService。已定向清理本项目遗留进程，没有结束其他 App。

修复：

- 将 App 本地 Binder 与当前 Shizuku Binder 作为 owner 传给后台。
  后台监听两者死亡，先停止读取和释放捕获，再退出自身进程。
- 映射期间使用包名专用 abstract socket 互斥，避免两个后台同时运行。
- 输入设备被占用时停止本次映射并保留具体错误，不再无限重试。
- 连接回调、开始/停止和状态轮询检查操作序号及绑定身份，拒绝过期结果。
- 前台服务的取消、启动超时与旧回调不会覆盖新的运行状态或重复停止新会话。

验证：

- 构建与 `lintDebug` 成功。
- 接管 YouTube 中的遥控器时，结束确切的 Shizuku server 测试故障：
  2秒后旧后台与捕获进程均消失，`--probe` 可重新接管并释放两个接口。
  主页保留“Shizuku 已停止，请重新启动后再开始映射”。
  证据：`logs/011-owner-death-test.json`。
- 手机无线启动再次成功后，重新开始映射，只出现一个后台与一个捕获进程。
- 强制结束 App 测试另一 owner：旧后台及捕获进程退出，Shizuku 继续运行。
  证据：`logs/011-client-death-test.json`。
- 已恢复正常映射，日志确认 `已接管 BLE-M3 · YouTube`。
  用户按要求打开普通视频并播放后测试上、下、中键，明确反馈“跳转和暂停都正常”。
  记录：`logs/011-physical-buttons.log`。

本轮还观察到无线启动偶尔停留在搜索服务；关闭再打开“无线调试”后恢复发现并启动。
这与映射后台占用问题独立，不应将“Shizuku已安装”当作“服务正在运行”。

## 0.1.0 初始验证

设备：realme GT Neo2 / RMX3370，Android 13，ARM64；官方 YouTube 21.43.523。

已通过：

- `gradlew :app:assembleDebug :app:lintDebug`：构建成功、Lint 无错误。
  仍有中文硬编码、内部 API 兼容性、仅 ARM64 等警告，本版为定向测试版。
- APK 安装，原生可执行文件正确解包到 `nativeLibraryDir`，shell 身份可执行。
- APK 内的原生程序回放真实六键记录：UP=2、DOWN=7、LEFT=4、RIGHT=90、
  CENTER=16、PHOTO=14，最后五次均为 PHOTO。
- `tools/BackendSmokeCheck.java` 在 Android app_process 中执行：13 项检查通过。
  覆盖默认第六键、无效配置边界、精确包名限制、媒体服务初始化、真实窗口/锁屏解析。
  真机发现并修正 ICU 正则需要转义右花括号的问题。
- Shizuku UserService 启动，双接口接管，进程运行在手机 shell 身份，父进程不是电脑脚本。
- 切出 YouTube 后释放捕获进程；主页停止后 `--probe` 可接管并释放两接口。
- 强制结束 App 后 UserService 与捕获进程消失，`--probe` 再次成功，无遗留占用。

首次拔线测试未通过：USB 文件传输模式下，断开后 Shizuku server 与 UserService
消失，遥控器恢复原始滑动。按照官方建议将默认 USB 改为不传输数据、当前仅充电，
重新启动 Shizuku 后复测。用户明确反馈“连接和拔线后都正常”，独立运行实测通过。
物理拔线后的效果由用户确认；电脑断开后未继续采集手机日志。

权限：Shizuku 授权与普通通知权限；没有无障碍服务、通知读取、存储、定位或网络权限。
本机最初被厂商权限监控拦截 Shizuku 授权，用户明确同意后打开了“禁止权限监控”。
该设置和 USB 默认模式是系统设置，不包含在 APK 权限列表里。

无线启动后续验证：首次故障页面显示 `SSLV3_ALERT_CERTIFICATE_UNKNOWN`，
当时系统无线调试列表只有电脑记录；允许 Shizuku 通知后开始配对排查。
下一次继续时已存在 `shizuku` 配对记录，无线调试关闭。通过系统 UI 重新开启，
点击 Shizuku 自身“启动”按钮，日志出现 `Service started`，管理界面显示“正在运行”。
恢复 USB 仅充电模式后，再次从手机 UI 无线启动成功，并恢复映射运行。
本轮没有通过电脑执行 Shizuku 的启动二进制。

未验证：其他品牌/系统、其他媒体 App、长期耗电与后台稳定性。
无线配对记录已确认可用，但最初那次从输入配对码到记录建立的全部过程未连续观察。
