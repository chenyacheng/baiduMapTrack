package com.chenyacheng.track;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ZoomControls;

import androidx.appcompat.app.AppCompatActivity;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptor;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.MapStatusUpdate;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.Marker;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.OverlayOptions;
import com.baidu.mapapi.map.Polyline;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.model.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    public final double DISTANCE = 0.00002;
    /**
     * 起点
     */
    private BitmapDescriptor startAddressBD = BitmapDescriptorFactory.fromResource(R.drawable.activity_travel_start_address);
    /**
     * 终点
     */
    private BitmapDescriptor endAddressBD = BitmapDescriptorFactory.fromResource(R.drawable.activity_travel_end_address);
    /**
     * 移动的小车
     */
    private BitmapDescriptor moveCarBD = BitmapDescriptorFactory.fromResource(R.drawable.activity_travel_move_car);
    private MapView mapView;
    private BaiduMap baiduMap;
    private Marker moveCarMarker;
    private List<LatLng> latLngs = new ArrayList<>();
    private ThreadPoolExecutor executorPool;
    /**
     * 小车是否在行驶，默认false
     */
    private boolean isRoutePlaying = false;
    /**
     * 是否运行新的线程，默认true
     */
    private boolean isRun = true;
    /**
     * 是否终止线程，默认false
     */
    private volatile boolean isInterrupted = false;
    private ImageButton imageBtnPlay;
    private ImageButton imageBtnPause;
    private ImageButton imageBtnStop;
    private List<Double> latitudeList = new ArrayList<>();
    private List<Double> longitudeList = new ArrayList<>();
    private double maxLatitude;
    private double minLatitude;
    private double maxLongitude;
    private double minLongitude;
    private double distance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 地图初始化
        mapView = findViewById(R.id.map_view_travel_trajectory);
        baiduMap = mapView.getMap();
        // 开启定位图层
        baiduMap.setMyLocationEnabled(true);
        // 隐藏logo
        View child = mapView.getChildAt(1);
        if (null != child) {
            if ((child instanceof ImageView || child instanceof ZoomControls)) {
                child.setVisibility(View.INVISIBLE);
            }
        }
        // 隐藏地图上比例尺和缩放控件
        mapView.showScaleControl(false);
        mapView.showZoomControls(false);

        LatLng latLng = new LatLng(20.006436, 110.345358);
        // 定义地图状态
        MapStatus mMapStatus = new MapStatus.Builder().target(latLng).zoom(16).build();
        // 定义MapStatusUpdate对象，以便描述地图状态将要发生的变化
        MapStatusUpdate mMapStatusUpdate = MapStatusUpdateFactory.newMapStatus(mMapStatus);
        // 改变地图状态
        baiduMap.setMapStatus(mMapStatusUpdate);
        imageBtnPlay = findViewById(R.id.image_btn_play_travel);
        imageBtnPause = findViewById(R.id.image_btn_pause_travel);
        imageBtnStop = findViewById(R.id.image_btn_stop_travel);
        imageBtnPlay.setEnabled(false);
        imageBtnPause.setEnabled(false);
        imageBtnStop.setEnabled(false);
        imageBtnPlay.setOnClickListener(this);
        imageBtnPause.setOnClickListener(this);
        imageBtnStop.setOnClickListener(this);
        // 创建线程工厂
        final AtomicInteger threadNumber = new AtomicInteger(1);
        ThreadFactory namedThreadFactory = r -> {
            Thread t = new Thread(r, "MessageProcessor" + threadNumber.getAndIncrement());
            if (t.isDaemon()) {
                t.setDaemon(true);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        };
        // 创建一个核心线程数为1、最大线程数为10，缓存队列大小为5的线程池
        executorPool = new ThreadPoolExecutor(1, 10, 60, TimeUnit.SECONDS, new LinkedBlockingDeque<>(16), namedThreadFactory, new ThreadPoolExecutor.AbortPolicy());
        // 线程池中corePoolSize线程空闲时间达到keepAliveTime也将关闭
        executorPool.allowCoreThreadTimeOut(true);

        double latitude;
        double longitude;
        for (int i = 0; i < Constant.latAndLng.length; ++i) {
            String[] ll = Constant.latAndLng[i].split(",");
            LatLng sourceLatLng = new LatLng(Double.valueOf(ll[0]), Double.valueOf(ll[1]));
            latLngs.add(sourceLatLng);
            latitude = latLngs.get(i).latitude;
            longitude = latLngs.get(i).longitude;
            latitudeList.add(latitude);
            longitudeList.add(longitude);
        }
        // 比较选出集合中最大经纬度
        maxLatitude = Collections.max(latitudeList);
        minLatitude = Collections.min(latitudeList);
        maxLongitude = Collections.max(longitudeList);
        minLongitude = Collections.min(longitudeList);
        // 计算两个Marker之间的距离
        distance = getDistance(maxLatitude, maxLongitude, minLatitude, minLongitude);
        // 地图事件
        baiduMapEvent();
        // 只有当大于或等于两个点，才添加小车图层和点的连线
        int two = 2;
        if (latLngs.size() >= two) {
            imageBtnPlay.setEnabled(true);
            imageBtnPlay.setBackgroundResource(R.drawable.activity_travel_play_enables);
        }
    }

    private void baiduMapEvent() {
        float level;
        int[] zoom = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 1000, 2000, 25000, 50000, 100000, 200000, 500000, 1000000, 2000000};
        for (int i = 0; i < zoom.length; ++i) {
            int zoomNow = zoom[i];
            if (zoomNow - distance * 1000 > 0) {
                level = 18 - i + 6;
                // 设置地图显示级别为计算所得level
                baiduMap.setMapStatus(MapStatusUpdateFactory.newMapStatus(new MapStatus.Builder().zoom(level).build()));
                break;
            }
        }
        LatLng center = new LatLng((maxLatitude + minLatitude) / 2, (maxLongitude + minLongitude) / 2);
        MapStatusUpdate status = MapStatusUpdateFactory.newLatLng(center);
        baiduMap.animateMapStatus(status);
        // 添加起点图层
        // 地图标记覆盖物参数配置类
        MarkerOptions oStart = new MarkerOptions();
        // 覆盖物位置点，第一个点为起点
        oStart.position(latLngs.get(0));
        // 设置覆盖物图片
        oStart.icon(startAddressBD);
        // 设置覆盖物Index
        oStart.zIndex(2);
        //在地图上添加此图层
        baiduMap.addOverlay(oStart);
        // 添加终点图层，并将图层添加到地图上
        MarkerOptions oFinish = new MarkerOptions().position(latLngs.get(latLngs.size() - 1)).icon(endAddressBD).zIndex(3);
        baiduMap.addOverlay(oFinish);
        // 只有当大于或等于两个点，才添加小车图层和点的连线
        int two = 2;
        if (latLngs.size() >= two) {
            // 添加小车图层，并将图层添加到地图上
            MarkerOptions moveCar = new MarkerOptions().position(latLngs.get(0)).icon(moveCarBD).zIndex(4);
            moveCarMarker = (Marker) baiduMap.addOverlay(moveCar);
            // 设置小车方向
            moveCarMarker.setRotate((float) getAngle(latLngs.get(0), latLngs.get(1)));
            // 设置点连成一条线的宽度颜色，并添加到地图上
            OverlayOptions overlayOptions = new PolylineOptions().width(12).color(0xFFFE6D56).points(latLngs);
            Polyline polyline = (Polyline) baiduMap.addOverlay(overlayOptions);
            polyline.setZIndex(1);
        }
    }

    private double getDistance(double lat1, double lng1, double lat2, double lng2) {
        double radLat1 = rad(lat1);
        double radLat2 = rad(lat2);
        double a = radLat1 - radLat2;
        double b = rad(lng1) - rad(lng2);
        double s = 2 * Math.asin(Math.sqrt(Math.pow(Math.sin(a / 2), 2)
                + Math.cos(radLat1) * Math.cos(radLat2)
                * Math.pow(Math.sin(b / 2), 2)));
        s = s * 6378.137;
        return s;
    }

    private double rad(double d) {
        return d * Math.PI / 180.0;
    }

    /**
     * 获取的角度
     *
     * @param fromPoint 当前点
     * @param toPoint   下一个点
     * @return 角度值
     */
    public double getAngle(LatLng fromPoint, LatLng toPoint) {
        double slope = getSlope(fromPoint, toPoint);
        if (slope == Double.MAX_VALUE) {
            if (toPoint.latitude > fromPoint.latitude) {
                return 0;
            } else {
                return 180;
            }
        }
        float defaultAngle = 0;
        if ((toPoint.latitude - fromPoint.latitude) * slope < 0) {
            defaultAngle = 180;
        }
        double radio = Math.atan(slope);
        return 180 * (radio / Math.PI) + defaultAngle - 90;
    }

    /**
     * 算斜率
     *
     * @param fromPoint 当前点
     * @param toPoint   下一个点
     * @return 斜率的值
     */
    public double getSlope(LatLng fromPoint, LatLng toPoint) {
        if (toPoint.longitude == fromPoint.longitude) {
            return Double.MAX_VALUE;
        }
        return ((toPoint.latitude - fromPoint.latitude) / (toPoint.longitude - fromPoint.longitude));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        startAddressBD.recycle();
        endAddressBD.recycle();
        moveCarBD.recycle();
        // 退出时销毁定位
        // 关闭定位图层
        baiduMap.setMyLocationEnabled(false);
        mapView.getMap().clear();
        mapView.onDestroy();
        mapView = null;
        executorPool.shutdown();
        executorPool.shutdownNow();
        try {
            executorPool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.image_btn_play_travel:
                imageBtnPlay.setEnabled(false);
                imageBtnPlay.setBackgroundResource(R.drawable.activity_travel_play_disables);
                imageBtnPause.setEnabled(true);
                imageBtnPause.setBackgroundResource(R.drawable.activity_travel_pause_enables);
                imageBtnStop.setEnabled(true);
                imageBtnStop.setBackgroundResource(R.drawable.activity_travel_stop_enables);
                isInterrupted = false;
                if (isRun) {
                    isRoutePlaying = true;
                    executorPool.execute(new MoveRunnable());
                } else {
                    isRoutePlaying = true;
                }
                break;
            case R.id.image_btn_pause_travel:
                imageBtnPause.setEnabled(false);
                imageBtnPause.setBackgroundResource(R.drawable.activity_travel_pause_disables);
                imageBtnPlay.setEnabled(true);
                imageBtnPlay.setBackgroundResource(R.drawable.activity_travel_play_enables);
                imageBtnStop.setEnabled(true);
                imageBtnStop.setBackgroundResource(R.drawable.activity_travel_stop_enables);
                isRoutePlaying = false;
                isRun = false;
                break;
            case R.id.image_btn_stop_travel:
                imageBtnStop.setEnabled(false);
                imageBtnStop.setBackgroundResource(R.drawable.activity_travel_stop_disables);
                imageBtnPlay.setEnabled(true);
                imageBtnPlay.setBackgroundResource(R.drawable.activity_travel_play_enables);
                imageBtnPause.setEnabled(false);
                imageBtnPause.setBackgroundResource(R.drawable.activity_travel_pause_disables);
                isInterrupted = true;
                moveCarMarker.remove();
                moveCarMarker = null;
                // 添加小车图层，并将图层添加到地图上，设置小车方向
                MarkerOptions moveCar = new MarkerOptions().position(latLngs.get(0)).icon(moveCarBD).zIndex(3);
                moveCarMarker = (Marker) baiduMap.addOverlay(moveCar);
                moveCarMarker.setRotate((float) getAngle(latLngs.get(0), latLngs.get(1)));
                isRun = true;
                break;
            default:
                break;
        }
    }

    private void imageBtnStatusUpdate() {
        imageBtnStop.setEnabled(false);
        imageBtnStop.setBackgroundResource(R.drawable.activity_travel_stop_disables);
        imageBtnPlay.setEnabled(true);
        imageBtnPlay.setBackgroundResource(R.drawable.activity_travel_play_enables);
        imageBtnPause.setEnabled(false);
        imageBtnPause.setBackgroundResource(R.drawable.activity_travel_pause_disables);
    }

    /**
     * 根据点和斜率算取截距
     *
     * @param slope 斜率
     * @param point 起点
     * @return 截距
     */
    public double getInterception(double slope, LatLng point) {
        return point.latitude - slope * point.longitude;
    }

    /**
     * 计算x方向每次移动的距离
     *
     * @param slope 斜率
     * @return 距离
     */
    public double getXMoveDistance(double slope) {
        if (slope == Double.MAX_VALUE) {
            return DISTANCE;
        }
        return Math.abs((DISTANCE * slope) / Math.sqrt(1 + slope * slope));
    }

    private void endAddressUpdate() {
        runOnUiThread(this::imageBtnStatusUpdate);
    }

    private class MoveRunnable implements Runnable {
        @Override
        public void run() {
            for (int i = 0; i < latLngs.size() - 1; ++i) {
                if (isInterrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (isRoutePlaying) {
                    LatLng startPoint = latLngs.get(i);
                    LatLng endPoint = latLngs.get(i + 1);
                    moveCarMarker.setPosition(startPoint);
                    moveCarMarker.setRotate((float) getAngle(startPoint, endPoint));
                    double slope = getSlope(startPoint, endPoint);
                    // 是不是正向的标示（向上设为正向）
                    boolean isReverse = (startPoint.latitude > endPoint.latitude);
                    double intercept = getInterception(slope, startPoint);
                    double xMoveDistance = isReverse ? getXMoveDistance(slope) : -1 * getXMoveDistance(slope);
                    for (double j = startPoint.latitude; ((j > endPoint.latitude) == isReverse); j = j - xMoveDistance) {
                        LatLng latLng;
                        if (slope != Double.MAX_VALUE) {
                            latLng = new LatLng(j, (j - intercept) / slope);
                        } else {
                            latLng = new LatLng(j, startPoint.longitude);
                        }
                        moveCarMarker.setPosition(latLng);
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        // 捕获到异常之后，执行break跳出循环
                        break;
                    }
                } else {
                    // 暂停
                    do {
                        try {
                            Thread.sleep(500);
                            if (isInterrupted) {
                                Thread.currentThread().interrupt();
                                i = 0;
                                break;
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            // 捕获到异常之后，执行break跳出循环
                            break;
                        }
                    } while (!isRoutePlaying);
                }
            }
            endAddressUpdate();
        }
    }
}
