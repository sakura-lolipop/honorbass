package com.lingyue.honorbass;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Binder;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * HONOR Sound「尊享低音-支持的应用」放行模块（单 hook 设计，见逆向报告 §2.4/§4.5）。
 *
 * 拦截 system_server 内 HwAudioServiceEx.getHwParameters("bt_bass_sound_effect_whitelist")：
 * - 返回"全部正常应用"的全量名单（应用抽屉同款标准：有 launcher 入口 + enabled）
 * - 把全量名单写回 Settings.System["bass_sound_effect_app_list"]
 *
 * 效果链：UI 显示全量 → iAware(SpkHistenSchedFeature, 零校验) 读 Settings → 播放时下发
 * switch_status 给 xisten 引擎 → DSP 真实应用低音。用户开关经原生 setHwParameters 持久化。
 *
 * 只拦截 bass 前缀的 key；其余 key（honor_auto_device_selection_capability 等）原样放行。
 */
public class HonorBass extends XposedModule {

    private static final String TAG = "HonorBass";
    private static final String TARGET_CLASS = "com.android.server.audio.HwAudioServiceEx";
    private static final String TARGET_METHOD = "getHwParameters";
    private static final String KEY_WHITELIST = "bt_bass_sound_effect_whitelist";
    private static final String SETTING_KEY = "bass_sound_effect_app_list";
    /** 托管标记：缺失时说明 Settings 里的状态可能被旧版"默认开"污染，首跑自愈 */
    private static final String MARKER_KEY = "bass_sound_effect_app_list_managed";

    /** hidden API，system_server 内无限制，但 android.jar 编译期不可见 → 反射 */
    private Method mGetInstalledApplicationsAsUser;
    private Method mQueryIntentActivitiesAsUser;
    private Method mAudioSystemGetParameters;
    private Method mUserHandleGetIdentifier;
    private Constructor<?> mUserHandleCtor;

    public HonorBass() {
        super();
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            ClassLoader cl = param.getClassLoader();
            mGetInstalledApplicationsAsUser = PackageManager.class
                    .getDeclaredMethod("getInstalledApplicationsAsUser", int.class, int.class);
            mQueryIntentActivitiesAsUser = PackageManager.class
                    .getDeclaredMethod("queryIntentActivitiesAsUser", Intent.class, int.class, int.class);
            Class<?> audioSystem = Class.forName("android.media.AudioSystem", false, cl);
            mAudioSystemGetParameters = audioSystem.getDeclaredMethod("getParameters", String.class);
            mUserHandleGetIdentifier = UserHandle.class.getDeclaredMethod("getIdentifier");
            mUserHandleCtor = UserHandle.class.getConstructor(int.class);

            Class<?> svc = cl.loadClass(TARGET_CLASS);
            var m = svc.getDeclaredMethod(TARGET_METHOD, String.class);
            hook(m).intercept(this::interceptGetHwParameters);
            log(4, TAG, "hooked " + TARGET_CLASS + "." + TARGET_METHOD);
        } catch (Throwable t) {
            log(6, TAG, "hook failed (OTA renamed " + TARGET_CLASS + "?), module inactive", t);
        }
    }

    private Object interceptGetHwParameters(XposedInterface.Chain chain) throws Throwable {
        String key = (String) chain.getArg(0);
        if (key == null || !key.startsWith(KEY_WHITELIST)) {
            return chain.proceed();
        }
        try {
            String result = buildFullList(chain.getThisObject());
            // 空结果说明枚举异常（如调用方身份受限/PM未就绪），绝不能返回空串毒化 UI 和 Settings
            if (result != null && !result.isEmpty()) {
                return result;
            }
            log(5, TAG, "empty enumeration, falling back to original");
        } catch (Throwable t) {
            log(6, TAG, "buildFullList failed, falling back to original", t);
        }
        return chain.proceed();
    }

    /**
     * 枚举全部正常应用（主用户 + 应用分身 profile），合并用户开关/XML默认值后拼全量名单。
     * 格式与原生一致：pkg,uid,state;pkg,uid,state;...
     *
     * 全程以 system 身份执行（clearCallingIdentity）：被 UI binder 调用时若带着调用方身份，
     * 包可见性/跨用户检查会导致枚举出 0 个应用（实测踩坑）。
     */
    private String buildFullList(Object service) throws Exception {
        Field f = service.getClass().getDeclaredField("mContext");
        f.setAccessible(true);
        Context ctx = (Context) f.get(service);
        long token = Binder.clearCallingIdentity();
        try {
            return enumerate(ctx);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private String enumerate(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        UserManager um = ctx.getSystemService(UserManager.class);

        // 用户已有开关（pkg,uid -> state）
        String old = Settings.System.getString(ctx.getContentResolver(), SETTING_KEY);
        Map<String, String> oldStates = parseList(old);
        String marker = Settings.System.getString(ctx.getContentResolver(), MARKER_KEY);
        boolean managed = "1".equals(marker);

        // XML 原生默认（pkg -> state，部分游戏在固件里默认关）；尽力解析，失败则忽略
        Map<String, String> defaults = new HashMap<>();
        Set<String> nativePkgs = new HashSet<>();
        try {
            String raw = (String) mAudioSystemGetParameters.invoke(null, KEY_WHITELIST);
            if (raw != null) {
                for (String entry : raw.split(";")) {
                    String[] parts = entry.split(",");
                    if (parts.length >= 1 && !parts[0].trim().isEmpty()) {
                        nativePkgs.add(parts[0].trim());
                    }
                    if (parts.length >= 2 && parts[parts.length - 1].trim().matches("^[01]$")) {
                        defaults.put(parts[0].trim(), parts[parts.length - 1].trim());
                    }
                }
            }
        } catch (Throwable t) {
            log(5, TAG, "native defaults unavailable");
        }

        StringBuilder out = new StringBuilder();
        Set<String> seen = new HashSet<>();
        List<UserHandle> profiles = um.getUserProfiles();
        if (profiles == null || profiles.isEmpty()) {
            // 兜底：主用户 + 荣耀应用分身（与原生 loadCloneApps 的行为对齐）
            profiles = new ArrayList<>();
            profiles.add((UserHandle) mUserHandleCtor.newInstance(0));
            profiles.add((UserHandle) mUserHandleCtor.newInstance(128));
        }
        for (UserHandle uh : profiles) {
            int userId = (Integer) mUserHandleGetIdentifier.invoke(uh);
            // 该 profile 下有 launcher 入口的包集合（应用抽屉标准，排除纯服务/组件类）
            Set<String> launchable = new HashSet<>();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<?> ris = (List<?>) mQueryIntentActivitiesAsUser.invoke(pm, main, 0, userId);
            if (ris == null) continue;
            for (Object riObj : ris) {
                ResolveInfo ri = (ResolveInfo) riObj;
                if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    launchable.add(ri.activityInfo.packageName);
                }
            }
            List<?> apps = (List<?>) mGetInstalledApplicationsAsUser.invoke(pm, 0, userId);
            if (apps == null) continue;
            for (Object aiObj : apps) {
                ApplicationInfo ai = (ApplicationInfo) aiObj;
                String pkg = ai.packageName;
                if (pkg == null || "android".equals(pkg)) continue;
                if (!launchable.contains(pkg)) continue;
                if (!ai.enabled) continue;
                String entryKey = pkg + "," + ai.uid;
                if (!seen.add(entryKey)) continue;
                // 状态优先级：用户开关 > 固件默认 > 关（注入前非白名单应用本就没有低音）。
                // 首跑（无托管标记）时，非白名单应用的旧"1"是旧版默认开污染的，归零自愈。
                String state;
                if (oldStates.containsKey(entryKey)) {
                    state = oldStates.get(entryKey);
                    if (!managed && !nativePkgs.contains(pkg) && !"0".equals(state)) {
                        state = "0";
                    }
                } else if (defaults.containsKey(pkg)) {
                    state = defaults.get(pkg);
                } else {
                    state = "0";
                }
                out.append(pkg).append(',').append(ai.uid).append(',').append(state).append(';');
            }
        }
        // 合并保留本次未枚举到的旧条目：boot 早期 PM 未就绪时枚举是残缺子集，
        // 直接覆写会丢失用户开关。子集 ∪ 原列表 = 原列表时不会触发写入。
        if (!oldStates.isEmpty()) {
            for (String entry : old.split(";")) {
                String[] parts = entry.split(",");
                if (parts.length == 3) {
                    String key = parts[0].trim() + "," + parts[1].trim();
                    if (seen.add(key)) {
                        out.append(parts[0].trim()).append(',')
                           .append(parts[1].trim()).append(',')
                           .append(parts[2].trim()).append(';');
                    }
                }
            }
        }
        if (out.length() > 0) out.setLength(out.length() - 1);
        String result = out.toString();

        if (!result.equals(old)) {
            Settings.System.putString(ctx.getContentResolver(), SETTING_KEY, result);
            log(4, TAG, "updated " + SETTING_KEY + " (" + seen.size() + " apps)");
        }
        if (!managed && !result.isEmpty()) {
            Settings.System.putString(ctx.getContentResolver(), MARKER_KEY, "1");
            log(4, TAG, "managed marker set, states now trusted");
        }
        return result;
    }

    /** 解析 "pkg,uid,state;..." 格式，返回 "pkg,uid" -> "state" 映射 */
    private static Map<String, String> parseList(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String entry : raw.split(";")) {
            String[] parts = entry.split(",");
            if (parts.length == 3) {
                map.put(parts[0].trim() + "," + parts[1].trim(), parts[2].trim());
            }
        }
        return map;
    }
}
