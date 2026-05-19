package hun.mesh.carwithenhance.config;

/**
 * 优化项数据模型
 */
public class HookItem {
    public final String key;
    public final String title;
    public final String subTitle;
    public final boolean defaultValue;

    public HookItem(String key, String title, String subTitle, boolean defaultValue) {
        this.key = key;
        this.title = title;
        this.subTitle = subTitle;
        this.defaultValue = defaultValue;
    }
}
