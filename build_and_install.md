# HonorBass 构建与安装备忘

## 模块是什么

LSPosed (API 100+/102) 模块，单 hook：`system_server` 内 `com.android.server.audio.HwAudioServiceEx.getHwParameters(String)`。
拦截 `bt_bass_sound_effect_whitelist` 查询 → 返回全部正常应用（有 launcher 入口 + enabled，含应用分身）的全量名单并写回
`Settings.System["bass_sound_effect_app_list"]`。UI/iAware/DSP 全链端到端生效，原理见 `../HONOR_Sound_尊享低音_逆向研究报告.md` §2.4/§4.5。

## 目录

```
AndroidManifest.xml                     xposedmodule 元数据 + xposedscope=system
assets/META-INF/xposed/java_init.list   入口类
assets/META-INF/xposed/scope.list       作用域: system（现代API里 system_server 的虚拟包名，
                                        写 android 只会注入 android:process=":ui" 进程，进不了 system_server）
assets/META-INF/xposed/module.prop      现代API必需: minApiVersion=100 / targetApiVersion=102
                                        （没有它 LSPosed 管理器不识别模块）
src/me/sakura/honorbass/HonorBass.java  hook 实现（~180 行）
HonorBass.apk                           签名好的成品
```

## 构建（JDK17 + pixel/lsposed_build/dl 工具链）

```sh
DL="C:/Users/littl/phone/pixel/lsposed_build/dl"
B="C:/Users/littl/phone/Honor Sound/bassunlock"
JC="/c/Program Files/Microsoft/jdk-17.0.10.7-hotspot/bin/javac.exe"

# 1. 编译（api 用 classes.jar；dl/api.jar 是坏文件是404页面，勿用）
"$JC" -encoding UTF-8 -cp "$DL/android.jar;$DL/classes.jar" -d "$B/out" "$B/src/me/sakura/honorbass/HonorBass.java"

# 2. dex（D8 会自动脱糖 lambda）
java -cp "$DL/r8.jar" com.android.tools.r8.D8 --release --min-api 30 --output "$B/dexout" "$B/out/me/sakura/honorbass/HonorBass.class"

# 3. 打包（aapt2 flag 补 versionCode/targetSdk，缺省会是 targetSdk=1 的远古应用）
"$DL/aapt2.exe" link -I "$DL/android.jar" --manifest "$B/AndroidManifest.xml" \
  --min-sdk-version 30 --target-sdk-version 34 --version-code 100 --version-name 1.0 \
  -o "$B/base-unsigned.apk"
cd "$B" && cp dexout/classes.dex . && jar uf base-unsigned.apk classes.dex \
  assets/META-INF/xposed/java_init.list assets/META-INF/xposed/scope.list \
  assets/META-INF/xposed/module.prop && rm classes.dex

# 4. 签名
java -jar "$DL/uber-apk-signer.jar" -a "$B/base-unsigned.apk" -o "$B/out_apk"
cp "$B/out_apk/base-aligned-debugSigned.apk" "$B/HonorBass.apk"
```

## 安装与启用

MagicOS 的 adb install 会被「USB 安装确认」拦（INSTALL_FAILED_ABORTED: User rejected permissions），
两条路任选：
- 文件管理器安装：`adb push HonorBass.apk /sdcard/Download/` → 手机文件管理 → Download → 点击安装（覆盖旧版）
- 开发人员选项打开「USB安装」后 `adb install -r HonorBass.apk` 直装

启用：
1. LSPosed 管理器（若已开着，从后台杀掉重开刷新模块列表）→ 模块 → HonorBass → 启用
2. 作用域勾选 **系统（system）** —— 注意不是 android，现代 API 里 system_server 的虚拟包名是 system
3. 重启手机（system_server hook 必须重启生效）
4. 回滚：LSPosed 关闭模块 + 重启；或 `adb uninstall me.sakura.honorbass`

## 验证清单

1. `adb logcat -d | grep HonorBass` → 出现 `hooked com.android.server.audio.HwAudioServiceEx.getHwParameters`
2. 设置→声音与触感→HONOR Sound 音效→外放音效→尊享低音→支持的应用：列表 = 全部正常应用（有图标有名字，无 GMS 组件/服务类，应用分身在列）
3. `adb shell settings get system bass_sound_effect_app_list` 含非白名单应用且多次进列表后**不被清洗**（原生的 `remove pgName:` 不再出现于 `logcat -s HwAudioServiceEx`）
4. 对非白名单应用切开关 → 播放音频 → `adb logcat -s xisten_mobile_multi_732` 有参数应用动作 + 听感对比（可对照关闭外放音效总开关）
5. 回归：原白名单应用开关状态保留；外放音效模式/音效风格/耳机音效等其它设置正常

## 已知边界

- 只拦截 bass 前缀 key，该方法其余查询原样放行；hook 抛异常自动回退原实现
- 新安装应用要等下次列表刷新（重进音效页）才入列
- OTA 后若类名变化：logcat 报 `hook failed`，用字符串 `bt_bass_sound_effect_whitelist` 在 services-magic.jar 里反查新类名，改 `TARGET_CLASS` 一行重编
