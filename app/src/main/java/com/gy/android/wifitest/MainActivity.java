package com.gy.android.wifitest;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.gy.android.wifitest.WifiAutoConnectManager.*;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    TextView mWifiState;//wifi状态
    TextView mWifiName;//Wi-Fi名称
    TextView mMac;//物理地址
    TextView mIP;//ip地址
    TextView mGateway;//网关地址
    ListView mListWifi;//Wi-Fi列表
    Button mBtnSearch;//搜索Wi-Fi
    Button mBtnConnect;//连接Wi-Fi
    WifiListAdapter mWifiListAdapter;
    public static final int WIFI_SCAN_PERMISSION_CODE = 2;
    WorkAsyncTask mWorkAsyncTask = null;
    ConnectAsyncTask mConnectAsyncTask = null;
    List<ScanResult> mScanResultList = new ArrayList<>();
    String ssid = "";
    WifiCipherType type = WifiCipherType.WIFICIPHER_NOPASS;
    String password = "";
    FrameLayout progressbar;
    boolean isLinked = false;

    String gateway = "";
    String mac = "";
    /**
     * 处理信号量改变或者扫描结果改变的广播
     */
    private BroadcastReceiver mWifiSearchBroadcastReceiver;
    private IntentFilter mWifiSearchIntentFilter;
    private BroadcastReceiver mWifiConnectBroadcastReceiver;
    private IntentFilter mWifiConnectIntentFilter;
    private WifiAutoConnectManager mWifiAutoConnectManager;

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mWifiSearchBroadcastReceiver, mWifiSearchIntentFilter);
        registerReceiver(mWifiConnectBroadcastReceiver, mWifiConnectIntentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mWifiSearchBroadcastReceiver);
        unregisterReceiver(mWifiConnectBroadcastReceiver);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        //初始化wifi工具
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mWifiAutoConnectManager = newInstance(wifiManager);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // 获取wifi连接需要定位权限,没有获取权限
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
            }, WIFI_SCAN_PERMISSION_CODE);
            return;
        }
        //设置监听wifi状态变化广播
        initWifiSate();
    }

    private void initWifiSate() {
        //wifi 搜索结果接收广播
        mWifiSearchBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                assert action != null;
                if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {// 扫描结果改表
                    mScanResultList = getScanResults();
                    if (mWifiListAdapter != null) {
                        mWifiListAdapter.setmWifiList(mScanResultList);
                        mWifiListAdapter.notifyDataSetChanged();
                    }
                }
            }
        };
        mWifiSearchIntentFilter = new IntentFilter();
        mWifiSearchIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mWifiSearchIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mWifiSearchIntentFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);

        //wifi 状态变化接收广播
        mWifiConnectBroadcastReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                    int wifState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                    if (wifState != WifiManager.WIFI_STATE_ENABLED) {
                        Toast.makeText(MainActivity.this, "没有wifi", Toast.LENGTH_SHORT).show();
                    }
                } else if (action.equals(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)) {
                    int linkWifiResult = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 123);
                    if (linkWifiResult == WifiManager.ERROR_AUTHENTICATING) {
                        Toast.makeText(MainActivity.this, "密码错误", Toast.LENGTH_SHORT).show();
                    }
                } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    NetworkInfo.DetailedState state = ((NetworkInfo) intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)).getDetailedState();
                    setWifiState(state);
                }
            }
        };
        mWifiConnectIntentFilter = new IntentFilter();
        mWifiConnectIntentFilter.addAction(WifiManager.ACTION_PICK_WIFI_NETWORK);
        mWifiConnectIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mWifiConnectIntentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        mWifiConnectIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
    }

    /**
     * 显示wifi状态
     *
     * @param state
     */
    @SuppressLint("SetTextI18n")
    public void setWifiState(final NetworkInfo.DetailedState state) {
        if (state == NetworkInfo.DetailedState.CONNECTED) {
            progressbar.setVisibility(View.GONE);
            isLinked = true;
            mWifiState.setText("连接状态:连接成功");
            mWifiName.setText("wifi名称:" + getSSID());
            mIP.setText("ip地址:" + getIpAddress());
            mGateway.setText("网关:" + getGateway());
            mMac.setText("物理地址:" + getMacAddress());
            gateway = getGateway();
            mac = getMacAddress();
        } else if (state == NetworkInfo.DetailedState.CONNECTING) {
            isLinked = false;
            mWifiState.setText("连接状态:连接中...");
            mWifiName.setText("wifi名称:" + getSSID());
            mIP.setText("ip地址");
            mGateway.setText("网关");
        } else if (state == NetworkInfo.DetailedState.DISCONNECTED) {
            isLinked = false;
            mWifiState.setText("连接状态:断开连接");
            mWifiName.setText("wifi名称");
            mIP.setText("ip地址");
            mGateway.setText("网关");
        } else if (state == NetworkInfo.DetailedState.DISCONNECTING) {
            isLinked = false;
            mWifiState.setText("连接状态:断开连接中...");
        } else if (state == NetworkInfo.DetailedState.FAILED) {
            isLinked = false;
            mWifiState.setText("连接状态:连接失败");
        }
    }

    private void initView() {
        progressbar = findViewById(R.id.progressbar);
        mWifiState = findViewById(R.id.wifi_state);
        mWifiName = findViewById(R.id.wifi_name);
        mIP = findViewById(R.id.ip_address);
        mGateway = findViewById(R.id.ip_gateway);
        mListWifi = findViewById(R.id.list_wifi);
        mBtnSearch = findViewById(R.id.search_wifi);
        mMac = findViewById(R.id.wifi_mac);
        mBtnConnect = findViewById(R.id.connect_wifi);

        mBtnSearch.setOnClickListener(this);
        mBtnConnect.setOnClickListener(this);

        mListWifi.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                mWifiListAdapter.setSelectItem(i);
                mWifiListAdapter.notifyDataSetChanged();
                ScanResult scanResult = mScanResultList.get(i);
                ssid = scanResult.SSID;
                type = getCipherType(ssid);
            }
        });
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.search_wifi:
                if (mWorkAsyncTask != null) {
                    mWorkAsyncTask.cancel(true);
                    mWorkAsyncTask = null;
                }
                mWorkAsyncTask = new WorkAsyncTask();
                mWorkAsyncTask.execute();
                break;
            case R.id.connect_wifi:
                if (ssid.equals(getSSID())) {
                    return;
                }
                if (mConnectAsyncTask != null) {
                    mConnectAsyncTask.cancel(true);
                    mConnectAsyncTask = null;
                }
                mConnectAsyncTask = new ConnectAsyncTask(ssid, password, type);
                mConnectAsyncTask.execute();
                break;
            default:
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case WIFI_SCAN_PERMISSION_CODE:
                if (grantResults.length <= 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    // 不允许
                    Toast.makeText(this, "开启权限失败", Toast.LENGTH_SHORT).show();
                }
                break;
            default:
                break;
        }
    }

    /**
     * 获取wifi列表
     */
    @SuppressLint("StaticFieldLeak")
    private class WorkAsyncTask extends AsyncTask<Void, Void, List<ScanResult>> {
        private List<ScanResult> mScanResult = new ArrayList<>();

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressbar.setVisibility(View.VISIBLE);
        }

        @Override
        protected List<ScanResult> doInBackground(Void... params) {
            if (startStan()) {
                mScanResult = getScanResults();
            }
            List<ScanResult> filterScanResultList = new ArrayList<>();
            if (mScanResult != null) {
                filterScanResultList.addAll(mScanResult);
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return filterScanResultList;
        }

        @Override
        protected void onPostExecute(final List<ScanResult> result) {
            super.onPostExecute(result);
            progressbar.setVisibility(View.GONE);
            mScanResultList = result;
            mWifiListAdapter = new WifiListAdapter(result, LayoutInflater.from(MainActivity.this));
            mListWifi.setAdapter(mWifiListAdapter);

        }
    }

    /**
     * wifi列表适配器
     */
    class WifiListAdapter extends BaseAdapter {

        private List<ScanResult> mWifiList;
        private LayoutInflater mLayoutInflater;

        WifiListAdapter(List<ScanResult> wifiList, LayoutInflater layoutInflater) {
            this.mWifiList = wifiList;
            this.mLayoutInflater = layoutInflater;
        }

        @Override
        public int getCount() {
            return mWifiList.size();
        }

        @Override
        public Object getItem(int position) {
            return mWifiList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @SuppressLint({"InflateParams", "SetTextI18n"})
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            if (convertView == null) {
                convertView = mLayoutInflater.inflate(R.layout.fragment_wifi_list_item, null);
            }
            ScanResult sr = mWifiList.get(position);
            convertView.setTag(sr);
            TextView wifi_name = convertView.findViewById(R.id.wifi_item_name);
            TextView pwd_type = convertView.findViewById(R.id.pwd_type);
            TextView wifi_icon = convertView.findViewById(R.id.wifi_icon);
            int numLevel = getSignalNumsLevel(sr.level, 5);
            String password = sr.capabilities;
            if (password.contains("WPA") || password.contains("wpa")) {
                password = "WPA";
            } else if (password.contains("WEP") || password.contains("wep")) {
                password = "WEP";
            } else {
                password = "";
            }
            wifi_name.setText(sr.SSID);
            pwd_type.setText(password);
            switch (numLevel) {
                case 1:
                    wifi_icon.setBackgroundResource(R.mipmap.ic_wifi_lock_signal_0);
                    break;
                case 2:
                    wifi_icon.setBackgroundResource(R.mipmap.ic_wifi_lock_signal_1);
                    break;
                case 3:
                    wifi_icon.setBackgroundResource(R.mipmap.ic_wifi_lock_signal_2);
                    break;
                case 4:
                    wifi_icon.setBackgroundResource(R.mipmap.ic_wifi_lock_signal_3);
                    break;
                case 5:
                    wifi_icon.setBackgroundResource(R.mipmap.ic_wifi_lock_signal_4);
                    break;
                default:
                    break;
            }
            convertView.setBackgroundColor(Color.WHITE);
            if (position == selectItem) {
                wifi_name.setTextColor(Color.GREEN);
            } else {
                wifi_name.setTextColor(Color.BLACK);
            }
            return convertView;
        }

        public void setSelectItem(int selectItem) {
            this.selectItem = selectItem;
        }

        private int selectItem = -1;

        public void setmWifiList(List<ScanResult> mWifiList) {
            this.mWifiList = mWifiList;
        }
    }


    /**
     * 连接指定的wifi
     */
    @SuppressLint("StaticFieldLeak")
    class ConnectAsyncTask extends AsyncTask<Void, Void, Boolean> {
        private String ssid;
        private String password;
        private WifiCipherType type;
        WifiConfiguration tempConfig;

        ConnectAsyncTask(String ssid, String password, WifiCipherType type) {
            this.ssid = ssid;
            this.password = password;
            this.type = type;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            //progressbar.setVisibility(View.VISIBLE);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            // 打开wifi
            mWifiAutoConnectManager.openWifi();
            // 开启wifi功能需要一段时间(我在手机上测试一般需要1-3秒左右)，所以要等到wifi
            // 状态变成WIFI_STATE_ENABLED的时候才能执行下面的语句
            while (wifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLING) {
                try {
                    // 为了避免程序一直while循环，让它睡个100毫秒检测……
                    Thread.sleep(100);

                } catch (InterruptedException ignored) {
                }
            }

            tempConfig = mWifiAutoConnectManager.isExsits(ssid);
            //禁掉所有wifi
            for (WifiConfiguration c : wifiManager.getConfiguredNetworks()) {
                wifiManager.disableNetwork(c.networkId);
            }
            if (tempConfig != null) {
                boolean result = wifiManager.enableNetwork(tempConfig.networkId, true);
                if (!isLinked && type != WifiCipherType.WIFICIPHER_NOPASS) {
                    try {
                        Thread.sleep(5000);//超过5s提示失败
                        if (!isLinked) {
                            wifiManager.disableNetwork(tempConfig.networkId);
                            /*runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    progressbar.setVisibility(View.GONE);
                                    Toast.makeText(getApplicationContext(), "连接失败!请在系统里删除wifi连接，重新连接。", Toast.LENGTH_SHORT).show();
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("连接失败！")
                                            .setMessage("请在系统里删除wifi连接，重新连接。")
                                            .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int which) {
                                                    dialog.dismiss();
                                                }
                                            })
                                            .setPositiveButton("好的", new DialogInterface.OnClickListener() {
                                                public void onClick(DialogInterface dialog, int which) {
                                                    Intent intent = new Intent();
                                                    intent.setAction("android.net.wifi.PICK_WIFI_NETWORK");
                                                    startActivity(intent);
                                                }
                                            }).show();
                                }
                            });*/
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                return result;
            } else {
                if (type != WifiCipherType.WIFICIPHER_NOPASS) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            final View view = LayoutInflater.from(MainActivity.this).inflate(R.layout.input_pwd, null);
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("请输入密码")
                                    .setView(view)
                                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            dialog.dismiss();
                                        }
                                    })
                                    .setPositiveButton("连接", new DialogInterface.OnClickListener() {

                                        public void onClick(DialogInterface dialog, int which) {
                                            EditText editText = view.findViewById(R.id.input_pwd);
                                            password = editText.getText().toString();
                                            if ("".equals(password)) {
                                                editText.setHintTextColor(Color.RED);
                                            } else if (password.length() < 8) {
                                                editText.setHint("请至少输入8位数");
                                            } else {
                                                new Thread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        WifiConfiguration wifiConfig = mWifiAutoConnectManager.createWifiInfo(ssid, password,
                                                                type);
                                                        if (wifiConfig == null) {
                                                            return;
                                                        }
                                                        int netID = wifiManager.addNetwork(wifiConfig);
                                                        boolean enabled = wifiManager.enableNetwork(netID, true);
                                                        wifiManager.reconnect();
                                                    }
                                                }).start();
                                            }
                                        }
                                    }).show();
                        }
                    });
                } else {
                    WifiConfiguration wifiConfig = mWifiAutoConnectManager.createWifiInfo(ssid, password, type);
                    if (wifiConfig == null) {
                        return false;
                    }
                    int netID = wifiManager.addNetwork(wifiConfig);
                    boolean enabled = wifiManager.enableNetwork(netID, true);
                    return wifiManager.reconnect();
                }
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean aBoolean) {
            super.onPostExecute(aBoolean);
            mConnectAsyncTask = null;
            //progressbar.setVisibility(View.GONE);
        }
    }
}
