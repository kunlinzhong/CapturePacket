package com.capeast.capturepacket;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.minhui.vpn.ProxyConfig;
import com.minhui.vpn.VPNConstants;
import com.minhui.vpn.nat.NatSession;
import com.minhui.vpn.processparse.AppInfo;
import com.minhui.vpn.utils.ThreadProxy;
import com.minhui.vpn.utils.TimeFormatUtil;
import com.minhui.vpn.utils.VpnServiceHelper;

import java.util.Iterator;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.minhui.vpn.VPNConstants.DEFAULT_PACKAGE_ID;
import static com.minhui.vpn.utils.VpnServiceHelper.getContext;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private Button startBtn,stopBtn;
    private TextView count_tv,show_tv;

    private ScheduledExecutorService timer;
    private List<NatSession> allNetConnection;
    private Handler handler;
    private static final int REQUEST_STORAGE_PERMISSION = 104;
    String[] needPermissions = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestStoragePermission();
        initView();
    }
    private void initView(){
        startBtn = (Button)findViewById(R.id.start_btn);
        stopBtn = (Button)findViewById(R.id.stop_btn);
        count_tv = (TextView)findViewById(R.id.count_tv);
        show_tv = (TextView)findViewById(R.id.show_tv);
        startBtn.setOnClickListener(this);
        stopBtn.setOnClickListener(this);
        handler = new Handler();
        ProxyConfig.Instance.registerVpnStatusListener(listener);
        if (VpnServiceHelper.vpnRunningStatus()) {
            startTimer();
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.start_btn:{
                startVPN();
                break;
            }
            case R.id.stop_btn:{
                closeVpn();
                break;
            }
            default:
                break;
        }
    }

    private void requestStoragePermission() {
        ActivityCompat.requestPermissions(this, needPermissions, REQUEST_STORAGE_PERMISSION);
    }
    private void closeVpn() {
        VpnServiceHelper.changeVpnRunningStatus(this,false);
    }

    private void startVPN() {
        VpnServiceHelper.changeVpnRunningStatus(this,true);
    }
    ProxyConfig.VpnStatusListener listener = new ProxyConfig.VpnStatusListener() {

        @Override
        public void onVpnStart(Context context) {
            startTimer();
        }

        @Override
        public void onVpnEnd(Context context) {
            cancelTimer();
        }
    };

    private void startTimer() {
        timer = Executors.newSingleThreadScheduledExecutor();

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                getDataAndRefreshView();
            }
        }, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    private void getDataAndRefreshView() {

        ThreadProxy.getInstance().execute(new Runnable() {
            @Override
            public void run() {
                allNetConnection = VpnServiceHelper.getAllSession();
                if (allNetConnection == null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshView(allNetConnection);
                        }
                    });
                    return;
                }
                Iterator<NatSession> iterator = allNetConnection.iterator();
                String packageName = MainActivity.this.getPackageName();

                SharedPreferences sp = getContext().getSharedPreferences(VPNConstants.VPN_SP_NAME, Context.MODE_PRIVATE);
                boolean isShowUDP = sp.getBoolean(VPNConstants.IS_UDP_SHOW, false);
                String selectPackage = sp.getString(DEFAULT_PACKAGE_ID, null);
                while (iterator.hasNext()) {
                    NatSession next = iterator.next();
                    if (next.bytesSent == 0 && next.receiveByteNum == 0) {
                        iterator.remove();
                        continue;
                    }
                    if (NatSession.UDP.equals(next.type) && !isShowUDP) {
                        iterator.remove();
                        continue;
                    }
                    AppInfo appInfo = next.appInfo;

                    if (appInfo != null) {
                        String appPackageName = appInfo.pkgs.getAt(0);
                        if (packageName.equals(appPackageName) ) {
                            iterator.remove();
                            continue;
                        }
                        if((selectPackage != null && !selectPackage.equals(appPackageName))){
                            iterator.remove();
                        }


                    }
                }
                if (handler == null) {
                    return;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshView(allNetConnection);
                    }
                });
            }
        });
    }

    private void refreshView(List<NatSession> allNetConnection) {
        count_tv.setText("抓到的数据包数："+allNetConnection.size());
        if(allNetConnection==null||allNetConnection.size()==0)
            return;
        NatSession connection = allNetConnection.get(allNetConnection.size()-1);
        StringBuffer stringBuffer = new StringBuffer();
//        if (connection.type!=null)
//            stringBuffer.append(connection.type);
        if (connection.getAppInfo() != null) {
            if (connection.getAppInfo().allAppName != null)
                stringBuffer.append(connection.getAppInfo().allAppName+" ");
            if (connection.getAppInfo().pkgs != null)
                stringBuffer.append(connection.getAppInfo().pkgs+" ");
            if (connection.getAppInfo().leaderAppName != null)
                stringBuffer.append(connection.getAppInfo().leaderAppName+" ");
        }
        if (connection.getIpAndPort()!=null)
            stringBuffer.append(connection.getIpAndPort()+" ");

        int sumByte = (int) (connection.bytesSent + connection.getReceiveByteNum());
        String showSum;
        if (sumByte > 1000000) {
            showSum = String.valueOf((int) (sumByte / 1000000.0 + 0.5)) + "mb";
        } else if (sumByte > 1000) {
            showSum = String.valueOf((int) (sumByte / 1000.0 + 0.5)) + "kb";
        } else {
            showSum = String.valueOf(sumByte) + "b";
        }
        String hostName = "";
        if (connection.getRequestUrl() != null) {
            hostName = connection.getRequestUrl();
        } else {
            hostName = connection.getRemoteHost();
        }
        stringBuffer.append(TimeFormatUtil.formatHHMMSSMM(connection.getRefreshTime())+"   ");
        stringBuffer.append(showSum);
        stringBuffer.append("\n");
        stringBuffer.append(hostName+" ");
        show_tv.setText(stringBuffer);
    }

    private void cancelTimer() {
        if (timer == null) {
            return;
        }
        timer.shutdownNow();
        timer = null;
    }
}
