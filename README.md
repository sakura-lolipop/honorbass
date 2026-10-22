# HonorBass

> HONOR Magic 系列外放「尊享低音」应用白名单放行模块（LSPosed，现代 API 100+）

让 设置 → 声音与触感 → HONOR Sound 音效 → 外放音效 → 尊享低音 →「支持的应用」
显示**全部正常应用**，并让非白名单应用**真实获得**尊享低音（UI / iAware / DSP 端到端）。

**作者：铃月**

## 原理（逆向结论）

- 权威白名单：`/odm/etc/audio/hw_sound_effect_config.xml`（固件内置 247 应用）
- UI 列表由 system_server 内 `com.android.server.audio.HwAudioServiceEx.getHwParameters()`
  以「XML 白名单 ∩ 已安装」构造（注入非白名单条目会被原生态过滤）
- 用户开关持久化于 `Settings.System["bass_sound_effect_app_list"]`（`pkg,uid,state;...`）
- 播放时 `com.hihonor.iaware` 的 SpkHistenSchedFeature 读该设置并
  `setParameters("updateHistenParams=<uid>;switch_status=<0|1>")` 下发 Histen 引擎（xisten），
  **对名单内容零校验** —— 因此 hook 服务层一处即可端到端生效

## 模块行为

- 单 hook（条件拦截 `bt_bass_sound_effect_whitelist` 前缀查询，其余 key 原样放行）
- 应用列表 = 应用抽屉同款标准：有 launcher 入口 + enabled，含应用分身；无 GMS 组件/纯服务
- 开关状态：用户开关 > 固件默认 > 新应用默认关；boot 早期残缺枚举不会覆盖你的设置（并集合并）
- hook 异常自动回退原生行为；卸载模块即完全还原

## 安装

1. LSPosed（v2.2.1+，需 Zygisk）安装本 APK
2. LSPosed 管理器 → 模块 → HonorBass → 启用，作用域勾选 **系统 (system)**
3. 重启手机

## 构建

见 [build_and_install.md](build_and_install.md)。无 Gradle，JDK17 + aapt2/D8 直链，
`dl/` 工具链不在仓库内（aapt2、r8.jar、android.jar、uber-apk-signer、api classes.jar）。

## License

MIT
