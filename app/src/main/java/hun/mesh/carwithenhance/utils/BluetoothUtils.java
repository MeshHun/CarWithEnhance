package hun.mesh.carwithenhance.utils;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import java.lang.reflect.Method;

/**
 * 蓝牙控制连接与断开的底层工具类
 */
public class BluetoothUtils {

    /**
     * 自动延迟并断开指定 MAC 地址车机蓝牙设备的音频和电话Profile(A2DP/HFP)
     */
    public static void disconnectBluetoothDevice(final Context context, String mac) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            XLog.e("BluetoothAdapter is null, can't disconnect bluetooth!");
            return;
        }

        final BluetoothDevice targetDevice;
        try {
            targetDevice = adapter.getRemoteDevice(mac);
        } catch (IllegalArgumentException e) {
            XLog.e("Invalid Bluetooth MAC address: " + mac);
            return;
        }

        XLog.i(">> [蓝牙工具] 准备断开设备 " + mac + " 的通话音频 (HEADSET)...");
        // 1. 断开通话音频 (HFP / HEADSET)
        adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    Method disconnectMethod = proxy.getClass().getMethod("disconnect", BluetoothDevice.class);
                    disconnectMethod.setAccessible(true);
                    disconnectMethod.invoke(proxy, targetDevice);
                    XLog.i(">> [蓝牙工具] 成功调用断开通话音频 HEADSET");
                    adapter.closeProfileProxy(profile, proxy);
                } catch (Exception e) {
                    XLog.e("断开 HEADSET Profile 失败: ", e);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.HEADSET);

        XLog.i(">> [蓝牙工具] 准备断开设备 " + mac + " 的媒体音频 (A2DP)...");
        // 2. 断开媒体音频 (A2DP)
        adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    Method disconnectMethod = proxy.getClass().getMethod("disconnect", BluetoothDevice.class);
                    disconnectMethod.setAccessible(true);
                    disconnectMethod.invoke(proxy, targetDevice);
                    XLog.i(">> [蓝牙工具] 成功调用断开媒体音频 A2DP");
                    adapter.closeProfileProxy(profile, proxy);
                } catch (Exception e) {
                    XLog.e("断开 A2DP Profile 失败: ", e);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
    }
}
