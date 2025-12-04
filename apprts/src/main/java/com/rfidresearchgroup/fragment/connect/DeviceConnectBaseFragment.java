package com.rfidresearchgroup.fragment.connect;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;

import com.rfidresearchgroup.adapter.DevArrayAdapter;
import com.rfidresearchgroup.fragment.base.BaseFragment;
import com.rfidresearchgroup.rfidtools.R;
import com.rfidresearchgroup.util.Commons;
import com.rfidresearchgroup.view.DeviceAttachView;
import com.rfidresearchgroup.view.DeviceExistsView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.rfidresearchgroup.javabean.DevBean;
import com.rfidresearchgroup.common.util.ViewUtil;
import com.rfidresearchgroup.common.util.AppUtil;

public abstract class DeviceConnectBaseFragment
        extends BaseFragment
        implements DeviceAttachView, DeviceExistsView {

    // USB相关
    private static final String ACTION_USB_PERMISSION = "com.rfidresearchgroup.USB_PERMISSION";
    private static final int REQUEST_PERMISSION_CODE = 1001;
    protected UsbManager usbManager;
    private PendingIntent permissionIntent;
    
    // 线程池
    private ExecutorService executorService;

    //展示数据的列表视图!
    protected ListView listViewShowDevs;
    //搜索到的设备的数列
    protected ArrayList<DevBean> devicesList = new ArrayList<>();
    //集合适配器
    protected ArrayAdapter<DevBean> arrayAdapter;
    //下拉刷新控件!
    protected SwipeRefreshLayout srDiscovery;
    //无数据时的内容填充!
    protected RelativeLayout layout_404_device;
    //连接过程提示框
    protected AlertDialog dialogConnectTips = null;
    //是否是自带NFC的标志位!
    protected boolean isDefaultNfc = false;
    //缓存连接的bean实例!
    protected DevBean mDevBean;
    //显示消息的视图
    protected View msgView;

    // USB权限广播接收器
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(LOG_TAG, "USB广播接收: " + action);
            
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.d(LOG_TAG, "USB权限已授予: " + device.getDeviceName());
                            onUsbPermissionGranted(device);
                        }
                    } else {
                        Log.d(LOG_TAG, "USB权限被拒绝");
                        showToast(getString(R.string.usb_permission_denied));
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    Log.d(LOG_TAG, "USB设备已连接: " + device.getDeviceName());
                    onUsbDeviceAttached(device);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    Log.d(LOG_TAG, "USB设备已断开: " + device.getDeviceName());
                    onUsbDeviceDetached(device);
                }
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dev_connect, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 初始化线程池
        executorService = Executors.newSingleThreadExecutor();
        
        // 初始化USB管理器
        if (getContext() != null) {
            usbManager = (UsbManager) requireContext().getSystemService(Context.USB_SERVICE);
            
            // 创建PendingIntent
            int flags = PendingIntent.FLAG_MUTABLE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = PendingIntent.FLAG_MUTABLE;
            }
            permissionIntent = PendingIntent.getBroadcast(
                requireContext(), 
                0, 
                new Intent(ACTION_USB_PERMISSION), 
                flags
            );
            
            // 注册USB广播接收器
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_USB_PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                requireContext().registerReceiver(usbReceiver, filter);
            }
        }

        //初始化适配器!
        arrayAdapter = new DevArrayAdapter(requireContext(), R.layout.dev_info, devicesList);
        initViews();
        initActions();
        initDialogs();
        
        // 检查并请求权限
        checkAndRequestPermissions();
        
        //子类实现初始化子类的资源!
        initResource();
        
        // 初始扫描USB设备
        scanUsbDevices();
    }

    /**
     * 检查并请求必要权限
     */
    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ArrayList<String> permissions = new ArrayList<>();
            
            if (ContextCompat.checkSelfPermission(requireContext(), 
                    android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.BLUETOOTH_SCAN);
            }
            
            if (ContextCompat.checkSelfPermission(requireContext(), 
                    android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT);
            }
            
            if (!permissions.isEmpty()) {
                ActivityCompat.requestPermissions(requireActivity(), 
                    permissions.toArray(new String[0]), 
                    REQUEST_PERMISSION_CODE);
            }
        }
    }

    /**
     * 扫描USB设备
     */
    protected void scanUsbDevices() {
        if (usbManager == null) {
            Log.e(LOG_TAG, "UsbManager为空，无法扫描USB设备");
            return;
        }
        
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        Log.d(LOG_TAG, "扫描到USB设备数量: " + deviceList.size());
        
        if (deviceList.isEmpty()) {
            Log.d(LOG_TAG, "没有找到USB设备");
            if (devicesList.isEmpty()) {
                showEmptyView();
            }
            return;
        }
        
        for (UsbDevice device : deviceList.values()) {
            Log.d(LOG_TAG, String.format("发现USB设备: VID=0x%04X, PID=0x%04X, Name=%s",
                device.getVendorId(), device.getProductId(), device.getDeviceName()));
            
            // 转换为DevBean并添加到列表
            DevBean devBean = convertUsbDeviceToDevBean(device);
            if (devBean != null) {
                devAttach(devBean);
            }
            
            // 请求USB权限
            if (!usbManager.hasPermission(device)) {
                Log.d(LOG_TAG, "请求USB设备权限: " + device.getDeviceName());
                usbManager.requestPermission(device, permissionIntent);
            } else {
                Log.d(LOG_TAG, "USB设备已有权限: " + device.getDeviceName());
            }
        }
    }

    /**
     * 将UsbDevice转换为DevBean
     */
    private DevBean convertUsbDeviceToDevBean(UsbDevice device) {
        DevBean devBean = new DevBean();
        
        // 使用USB设备信息构造DevBean
        String deviceName = device.getDeviceName();
        String displayName = String.format("USB设备 (VID:0x%04X PID:0x%04X)", 
            device.getVendorId(), device.getProductId());
        
        // 如果有产品名称，使用产品名称
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String productName = device.getProductName();
            if (productName != null && !productName.isEmpty()) {
                displayName = productName;
            }
        }
        
        devBean.setDeviceName(displayName);
        devBean.setMacAddress(deviceName); // 使用设备路径作为地址
        
        // 设置设备类型 (根据你的DevBean实现调整)
        // devBean.setDeviceType("USB");
        
        Log.d(LOG_TAG, "转换USB设备为DevBean: " + displayName);
        return devBean;
    }

    /**
     * USB权限授予回调
     */
    protected void onUsbPermissionGranted(UsbDevice device) {
        Log.d(LOG_TAG, "USB权限授予成功，设备: " + device.getDeviceName());
        // 子类可以重写此方法处理权限授予后的逻辑
    }

    /**
     * USB设备连接回调
     */
    protected void onUsbDeviceAttached(UsbDevice device) {
        DevBean devBean = convertUsbDeviceToDevBean(device);
        if (devBean != null) {
            devAttach(devBean);
        }
        
        // 自动请求权限
        if (usbManager != null && !usbManager.hasPermission(device)) {
            usbManager.requestPermission(device, permissionIntent);
        }
    }

    /**
     * USB设备断开回调
     */
    protected void onUsbDeviceDetached(UsbDevice device) {
        DevBean devBean = convertUsbDeviceToDevBean(device);
        if (devBean != null) {
            devDetach(devBean);
        }
    }

    private void initViews() {
        View view = getView();
        if (view != null) {
            listViewShowDevs = view.findViewById(R.id.lstvShowDev);
            //设置适配器进入列表视图中!
            listViewShowDevs.setAdapter(arrayAdapter);
            listViewShowDevs.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    Log.d(LOG_TAG, "点击了设备，将会开始进行连接!");
                    //开始尝试连接
                    DevBean devBean = arrayAdapter.getItem(position);
                    if (devBean != null) {
                        Log.d(LOG_TAG, "devBean不为空，将会进入下一步!");
                        //缓存到全局!
                        mDevBean = devBean;
                        //取出信息，进行判断处理!
                        String address = devBean.getMacAddress();
                        if (address != null) {
                            //此处调用子类实现的连接!
                            Log.d(LOG_TAG, "设备地址不为空，将会调用子类的实现进行连接设备!");
                            
                            // 使用线程池替代直接创建Thread
                            executorService.execute(() -> onConnectDev(address));
                            
                            //标记当前驱动信息
                            isDefaultNfc = address.equals("00:00:00:00:00:02");
                            //弹窗显示连接提示
                            if (dialogConnectTips != null) {
                                dialogConnectTips.show();
                            }
                        } else {
                            showToast("addr为空!");
                        }
                    } else {
                        showToast("bean为空!");
                    }
                }
            });
            layout_404_device = view.findViewById(R.id.layout_404_device);
            srDiscovery = view.findViewById(R.id.srDiscovery);
        }
    }

    private void initActions() {
        srDiscovery.setOnRefreshListener(() -> {
            //监听到刷新事件!
            Log.d(LOG_TAG, "下拉刷新，重新扫描设备");
            onDiscovery();
            // 同时扫描USB设备
            scanUsbDevices();
            srDiscovery.setRefreshing(false);
        });
    }

    private void initDialogs() {
        //初始化msgView
        msgView = ViewUtil.inflate(requireContext(), R.layout.dialog_working_msg);
        
        //初始化连接过程弹窗提示对象 - 使用Builder模式
        dialogConnectTips = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.title_connecting))
                .setView(msgView)
                .setCancelable(false)
                .setNegativeButton(getString(R.string.title_connect_cancel), (dialog, which) -> {
                    dialog.dismiss();
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.warning)
                            .setMessage(getString(R.string.cancel_tips))
                            .setCancelable(false)
                            .setPositiveButton(R.string.restart, (d, w) -> {
                                //断开链接
                                onDisconnect();
                                //销毁当前栈中所有的实例!
                                AppUtil.getInstance().finishAll();
                                showToast(getString(R.string.tips_app_reopen));
                            })
                            .show();
                })
                .create();
    }

    //TODO 由父类控制初始化!
    protected abstract void initResource();

    //留一个抽象方法让子类实现，实现下拉刷新时的相关设备!
    protected abstract void onDiscovery();

    //在点击了连接的时候回调!
    protected abstract void onConnectDev(String address);

    //在断开连接的时候的操作!
    protected abstract void onDisconnect();

    //在初始化NFC设备的回调!
    protected abstract void onInitNfcDev();

    //在设备初始化成功时候的回调!
    protected abstract void onInitSuccess();

    //显示empty视图!
    protected void showEmptyView() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                listViewShowDevs.setVisibility(View.GONE);
                layout_404_device.setVisibility(View.VISIBLE);
            });
        }
    }

    //隐藏empty视图!
    protected void dismissEmptyView() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                listViewShowDevs.setVisibility(View.VISIBLE);
                layout_404_device.setVisibility(View.GONE);
            });
        }
    }

    @Override
    public void onRegisterError(String name) {
    }

    @Override
    public void showExistsDev(DevBean[] devList) {
        if (devList != null) {
            Log.d(LOG_TAG, "showExistsDev() 设备列表不为空!");
            if (getActivity() != null)
                getActivity().runOnUiThread(() -> {
                    ArrayList<DevBean> tmpList = new ArrayList<>();
                    for (DevBean devBean : devList) {
                        boolean isExists = false;
                        if (devicesList.isEmpty()) {
                            Log.d(LOG_TAG, "长度为零，直接添加!");
                            devicesList.addAll(Arrays.asList(devList));
                            arrayAdapter.notifyDataSetChanged();
                            dismissEmptyView();
                            return;
                        }
                        for (DevBean existingDevice : devicesList) {
                            if (Commons.equalDebBean(devBean, existingDevice)) {
                                isExists = true;
                                break;
                            }
                        }
                        if (!isExists) {
                            tmpList.add(devBean);
                        }
                    }
                    if (!tmpList.isEmpty()) {
                        devicesList.addAll(tmpList);
                        arrayAdapter.notifyDataSetChanged();
                        dismissEmptyView();
                    }
                });
        }
    }

    @Override
    public void devAttach(DevBean dev) {
        if (dev == null)
            return;
        if (getActivity() != null)
            getActivity().runOnUiThread(() -> {
                boolean isExists = false;
                for (DevBean devs : devicesList) {
                    if (Commons.equalDebBean(dev, devs)) {
                        isExists = true;
                        break;
                    }
                }
                if (!isExists) {
                    devicesList.add(dev);
                    arrayAdapter.notifyDataSetChanged();
                    dismissEmptyView();
                    Log.d(LOG_TAG, "设备已添加到列表: " + dev.getDeviceName());
                }
            });
    }

    @Override
    public void devDetach(DevBean bean) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                Commons.removeDevByList(bean, devicesList);
                arrayAdapter.notifyDataSetChanged();
                if (arrayAdapter.getCount() == 0) {
                    showEmptyView();
                }
                Log.d(LOG_TAG, "设备已从列表移除: " + bean.getDeviceName());
            });
        }
    }

    @Override
    public void onConnectFail() {
        if (getActivity() != null)
            getActivity().runOnUiThread(() -> {
                showToast(getString(R.string.com_err_tips));
                if (dialogConnectTips != null) {
                    dialogConnectTips.dismiss();
                }
            });
    }

    @Override
    public void onConnectSuccess() {
        if (getActivity() != null)
            getActivity().runOnUiThread(() -> {
                showToast(getString(R.string.com_normal_tips));
                onInitNfcDev();
            });
    }

    @Override
    public void onInitNfcAdapterSuccess() {
        if (getActivity() != null)
            getActivity().runOnUiThread(() -> {
                if (dialogConnectTips != null) {
                    dialogConnectTips.dismiss();
                }
                onInitSuccess();
            });
    }

    @Override
    public void onInitNfcAdapterFail() {
        if (getActivity() != null)
            getActivity().runOnUiThread(() -> {
                showToast(getString(R.string.device_init_err));
                if (dialogConnectTips != null) {
                    dialogConnectTips.dismiss();
                }
            });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        // 注销USB广播接收器
        try {
            if (getContext() != null) {
                requireContext().unregisterReceiver(usbReceiver);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "注销USB广播接收器失败", e);
        }
        
        // 关闭线程池
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Log.d(LOG_TAG, "所有权限已授予");
                scanUsbDevices();
            } else {
                Log.d(LOG_TAG, "部分权限被拒绝");
                showToast("需要蓝牙权限才能扫描设备");
            }
        }
    }
}