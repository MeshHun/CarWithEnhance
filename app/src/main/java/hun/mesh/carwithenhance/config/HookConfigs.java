package hun.mesh.carwithenhance.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局优化配置表
 */
public class HookConfigs {
    
    // SharedPreferences 文件名（复用 CarWith 自身已有的配置文件）
    public static final String PREFS_FILE = "file_prefer_app_091703";

    // 1. QQ 音乐完美续播
    public static final HookItem AUTOPLAY = new HookItem(
            "hook_autoplay_enabled",
            "QQ音乐完美续播",
            "开启后阻止上车续播自动播放歌单《巅峰潮流榜》",
            true
    );

    // 2. 高画质 QP 注入
    public static final HookItem QP = new HookItem(
            "hook_qp_enabled",
            "CarLife高画质 QP注入",
            "限制视频编码QP量化参数区间，改善高帧率场景下画面突变导致的画面模糊",
            true
    );

    // 3. 无感触发后断开蓝牙
    public static final HookItem DISCONNECT_BT = new HookItem(
            "hook_disconnect_bt_enabled",
            "无感触发后断开蓝牙",
            "无感连接触发后自动断开手机对车机的蓝牙音频(A2DP/通话)，防止音频输出设备冲突。作者自用，非必要不开启！！",
            false
    );

    // 4. Android 13 强开高级材质
    public static final HookItem ADVANCED_BLUR = new HookItem(
            "hook_advanced_blur_enabled",
            "Android13跳过高级材质验证",
            "在安卓13不支持的设备上允许使用毛玻璃和高级材质",
            true
    );

    private static final List<HookItem> ALL_ITEMS = new ArrayList<>();

    static {
        ALL_ITEMS.add(AUTOPLAY);
        ALL_ITEMS.add(QP);
        ALL_ITEMS.add(DISCONNECT_BT);
        ALL_ITEMS.add(ADVANCED_BLUR);
    }

    /**
     * 获取所有配置项列表
     */
    public static List<HookItem> getAllItems() {
        return ALL_ITEMS;
    }

    /**
     * 根据 key 查找配置项
     */
    public static HookItem findByKey(String key) {
        if (key == null) return null;
        for (HookItem item : ALL_ITEMS) {
            if (item.key.equals(key)) {
                return item;
            }
        }
        return null;
    }
}
