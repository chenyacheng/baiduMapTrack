package com.chenyacheng.track;

import android.app.Application;

import com.baidu.mapapi.SDKInitializer;

/**
 * @author chenyacheng
 * @date 2020/03/27
 */
public class TrackApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 初始化百度地图sdk
        SDKInitializer.initialize(this);
    }
}
