/*
 * Copyright 2015 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.webbluetoothcg.bletestperipheral;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

import android.support.v4.app.ActivityCompat;
import io.github.webbluetoothcg.bletestperipheral.ServiceFragment.ServiceFragmentDelegate;

public class Peripheral extends Activity implements ServiceFragmentDelegate {

  private static final int REQUEST_ENABLE_BT = 1;
  private static final String TAG = Peripheral.class.getCanonicalName();
  private static final String CURRENT_FRAGMENT_TAG = "CURRENT_FRAGMENT";

  private static final UUID CHARACTERISTIC_USER_DESCRIPTION_UUID = UUID
      .fromString("00002901-0000-1000-8000-00805f9b34fb");
  //이게 CCCD UUID이다.
  private static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID
      .fromString("00002902-0000-1000-8000-00805f9b34fb");

  private static final int MULTIPLE_PERMISSION = 1004;
  private String[] PERMISSIONS = {Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
  public boolean runtimeCheckPermission(Context context, String... permissions) {
    if (context != null && permissions != null) {
      for (String permission : permissions) {
        if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
          return false;

        }
      }
    }
    return true;
  }
  private TextView mAdvStatus;
  private TextView mConnectionStatus;
  private ServiceFragment mCurrentServiceFragment;
  private BluetoothGattService mBluetoothGattService;
  private HashSet<BluetoothDevice> mBluetoothDevices;
  private BluetoothManager mBluetoothManager;
  private BluetoothAdapter mBluetoothAdapter;
  private AdvertiseData mAdvData;
  private AdvertiseData mAdvScanResponse;
  private AdvertiseSettings mAdvSettings;
  private BluetoothLeAdvertiser mAdvertiser;
  private final AdvertiseCallback mAdvCallback = new AdvertiseCallback() {
    @Override
    public void onStartFailure(int errorCode) {
      super.onStartFailure(errorCode);
      Log.e(TAG, "Not broadcasting: " + errorCode);
      int statusText;
      switch (errorCode) {
        case ADVERTISE_FAILED_ALREADY_STARTED:
          statusText = R.string.status_advertising;
          Log.w(TAG, "App was already advertising");
          break;
        case ADVERTISE_FAILED_DATA_TOO_LARGE:
          statusText = R.string.status_advDataTooLarge;
          break;
        case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
          statusText = R.string.status_advFeatureUnsupported;
          break;
        case ADVERTISE_FAILED_INTERNAL_ERROR:
          statusText = R.string.status_advInternalError;
          break;
        case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
          statusText = R.string.status_advTooManyAdvertisers;
          break;
        default:
          statusText = R.string.status_notAdvertising;
          Log.wtf(TAG, "Unhandled error: " + errorCode);
      }
      mAdvStatus.setText(statusText);
    }

    @Override
    public void onStartSuccess(AdvertiseSettings settingsInEffect) {
      super.onStartSuccess(settingsInEffect);
      Log.v(TAG, "Broadcasting");
      mAdvStatus.setText(R.string.status_advertising);
    }
  };
  /*
  BluetoothGATT 서버를 연다고 함은 이렇게 외부 요청 같은 것들을 계속 Callback 받으면서 각각의 요청들에 대한
  세세한 설정 들을 해놓는 것이다.
  예를 들어서 Write요청을 받게 된다면 WriteCharacteristic 같은 펑션 콜을 한다든가 하는 것이
  다 여기에 구현이 되어있다.

  다른 모든 fragment들에서 공통적으로 BLUETOOTHGATT 서버를 열어서 사용하기 때문에 fragment가 아닌
  안드로이드에서 구조적으로 윗 단계인 Activity에 이걸 구현한 것.
   */
  private BluetoothGattServer mGattServer;
  private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
    @Override
    public void onConnectionStateChange(BluetoothDevice device, final int status, int newState) {
      super.onConnectionStateChange(device, status, newState);
      if (status == BluetoothGatt.GATT_SUCCESS) {
        if (newState == BluetoothGatt.STATE_CONNECTED) {
          mBluetoothDevices.add(device);
          updateConnectedDevicesStatus();
          Log.v(TAG, "Connected to device: " + device.getAddress());
        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
          mBluetoothDevices.remove(device);
          updateConnectedDevicesStatus();
          Log.v(TAG, "Disconnected from device");
        }
      } else {
        mBluetoothDevices.remove(device);
        updateConnectedDevicesStatus();
        // There are too many gatt errors (some of them not even in the documentation) so we just
        // show the error to the user.
        final String errorMessage = getString(R.string.status_errorWhenConnecting) + ": " + status;
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            Toast.makeText(Peripheral.this, errorMessage, Toast.LENGTH_LONG).show();
          }
        });
        Log.e(TAG, "Error when connecting: " + status);
      }
    }

    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
        BluetoothGattCharacteristic characteristic) {
      super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
      Log.d(TAG, "Device tried to read characteristic: " + characteristic.getUuid());
      Log.d(TAG, "Value: " + Arrays.toString(characteristic.getValue()) +  " that is: " + bytesToString(characteristic.getValue()));
      if (offset != 0) {
        mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
            /* value (optional) */ null);
        return;
      }
      mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
          offset, characteristic.getValue());
    }

    @Override
    public void onNotificationSent(BluetoothDevice device, int status) {
      super.onNotificationSent(device, status);
      Log.v(TAG, "Notification sent. Status: " + status);
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
        BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
        int offset, byte[] value) {
      super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
          responseNeeded, offset, value);
      int status = mCurrentServiceFragment.writeCharacteristic(characteristic, offset, value);
      Log.v(TAG, "Characteristic Write request: " + Arrays.toString(value) + "/ status: " + status +  " that is: " + bytesToString(value));
      if (responseNeeded) {
        mGattServer.sendResponse(device, requestId, status,
            /* No need to respond with an offset */ 0,
            /* No need to respond with a value */ null);
      }
    }

    @Override
    public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
        int offset, BluetoothGattDescriptor descriptor) {
      super.onDescriptorReadRequest(device, requestId, offset, descriptor);
      Log.d(TAG, "Device tried to read descriptor: " + descriptor.getUuid());
      Log.d(TAG, "Value: " + Arrays.toString(descriptor.getValue()) +  " that is: " + bytesToString(descriptor.getValue()));
      if (offset != 0) {
        mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
            /* value (optional) */ null);
        return;
      }
      mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
          descriptor.getValue());
    }

    @Override
    public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
        BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
        int offset,
        byte[] value) {
      super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded,
          offset, value);
      Log.v(TAG, "Descriptor Write Request " + descriptor.getUuid() + " " + Arrays.toString(value)  +  " that is: " + bytesToString(value));
      int status = BluetoothGatt.GATT_SUCCESS;
      if (descriptor.getUuid() == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        boolean supportsNotifications = (characteristic.getProperties() &
            BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        boolean supportsIndications = (characteristic.getProperties() &
            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

        if (!(supportsNotifications || supportsIndications)) {
          status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
        } else if (value.length != 2) {
          status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
        } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
          status = BluetoothGatt.GATT_SUCCESS;
          mCurrentServiceFragment.notificationsDisabled(characteristic);
          descriptor.setValue(value);
        } else if (supportsNotifications &&
            Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
          status = BluetoothGatt.GATT_SUCCESS;
          mCurrentServiceFragment.notificationsEnabled(characteristic, false /* indicate */);
          descriptor.setValue(value);
        } else if (supportsIndications &&
            Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
          status = BluetoothGatt.GATT_SUCCESS;
          mCurrentServiceFragment.notificationsEnabled(characteristic, true /* indicate */);
          descriptor.setValue(value);
        } else {
          status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
        }
      } else {
        status = BluetoothGatt.GATT_SUCCESS;
        descriptor.setValue(value);
      }
      if (responseNeeded) {
        mGattServer.sendResponse(device, requestId, status,
            /* No need to respond with offset */ 0,
            /* No need to respond with a value */ null);
      }
    }
  };

  /////////////////////////////////
  ////// Lifecycle Callbacks //////
  /////////////////////////////////


  //////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////////////
  //        여기는 ON CREATE VIEW 등등 액티비티의 시작화면 관련 설정
  //////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////////////

  @Override
  protected void onCreate(Bundle savedInstanceState) {


    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_peripherals);

    //블루투스 권한 요청
    if (!runtimeCheckPermission(this, PERMISSIONS)) {
      ActivityCompat.requestPermissions(this, PERMISSIONS, MULTIPLE_PERMISSION);
    } else {
      Log.i("권한 테스트", "권한이 있네요");
    }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    mAdvStatus = (TextView) findViewById(R.id.textView_advertisingStatus);
    mConnectionStatus = (TextView) findViewById(R.id.textView_connectionStatus);
    mBluetoothDevices = new HashSet<>();
    mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
    mBluetoothAdapter = mBluetoothManager.getAdapter();


    //정확히 여기서 4가지 서비스 중 한개로 넘어가는 것 ㅇㅇ Peripherals에서 받아온 리스트 인덱스 번호
    //EXTRA_PERIPHERAL_INDEX를 가지고 어떤 서비스로 넘어갈지 판단한다.
    //If we are not being restored from a previous state then create and add the fragment.
    if (savedInstanceState == null) {
      int peripheralIndex = getIntent().getIntExtra(Peripherals.EXTRA_PERIPHERAL_INDEX,
          /* default */ -1);
      if (peripheralIndex == 0) {
        mCurrentServiceFragment = new BatteryServiceFragment();
      } else if (peripheralIndex == 1) {
        mCurrentServiceFragment = new HeartRateServiceFragment();
      } else if (peripheralIndex == 2) {
        mCurrentServiceFragment = new HealthThermometerServiceFragment();
      } else if (peripheralIndex == 3) {
        mCurrentServiceFragment = new NordicUartServiceFragment();
      } else {
        Log.wtf(TAG, "Service doesn't exist");
      }
      getFragmentManager()
          .beginTransaction()
          .add(R.id.fragment_container, mCurrentServiceFragment, CURRENT_FRAGMENT_TAG)
          .commit();
    } else {
      mCurrentServiceFragment = (ServiceFragment) getFragmentManager()
          .findFragmentByTag(CURRENT_FRAGMENT_TAG);
    }
    mBluetoothGattService = mCurrentServiceFragment.getBluetoothGattService();

    mAdvSettings = new AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .setConnectable(true)
        .build();
    mAdvData = new AdvertiseData.Builder()
        .setIncludeTxPowerLevel(true)
        .addServiceUuid(mCurrentServiceFragment.getServiceUUID())
        .build();
    mAdvScanResponse = new AdvertiseData.Builder()
        .setIncludeDeviceName(true)
        .build();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.menu_peripheral, menu);
    return true /* show menu */;
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_ENABLE_BT) {
      if (resultCode == RESULT_OK) {
        if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
          Toast.makeText(this, R.string.bluetoothAdvertisingNotSupported, Toast.LENGTH_LONG).show();
          Log.e(TAG, "Advertising not supported");
        }
        onStart();
      } else {
        //TODO(g-ortuno): UX for asking the user to activate bt
        Toast.makeText(this, R.string.bluetoothNotEnabled, Toast.LENGTH_LONG).show();
        Log.e(TAG, "Bluetooth not enabled");
        finish();
      }
    }
  }


  @Override
  protected void onStart() {
    super.onStart();
    resetStatusViews();
    // If the user disabled Bluetooth when the app was in the background,
    // openGattServer() will return null.
    mGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
    if (mGattServer == null) {
      ensureBleFeaturesAvailable();
      return;
    }
    // Add a service for a total of three services (Generic Attribute and Generic Access
    // are present by default).
    mGattServer.addService(mBluetoothGattService);

    if (mBluetoothAdapter.isMultipleAdvertisementSupported()) {
      mAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
      mAdvertiser.startAdvertising(mAdvSettings, mAdvData, mAdvScanResponse, mAdvCallback);
    } else {
      mAdvStatus.setText(R.string.status_noLeAdv);
    }
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == R.id.action_disconnect_devices) {
      disconnectFromDevices();
      return true /* event_consumed */;
    }
    return false /* event_consumed */;
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (mGattServer != null) {
      mGattServer.close();
    }
    if (mBluetoothAdapter.isEnabled() && mAdvertiser != null) {
      // If stopAdvertising() gets called before close() a null
      // pointer exception is raised.
      mAdvertiser.stopAdvertising(mAdvCallback);
    }
    resetStatusViews();
  }

  @Override
  public void sendNotificationToDevices(BluetoothGattCharacteristic characteristic) {
    boolean indicate = (characteristic.getProperties()
        & BluetoothGattCharacteristic.PROPERTY_INDICATE)
        == BluetoothGattCharacteristic.PROPERTY_INDICATE;
    for (BluetoothDevice device : mBluetoothDevices) {
      // true for indication (acknowledge) and false for notification (unacknowledge).
      mGattServer.notifyCharacteristicChanged(device, characteristic, indicate);
    }
  }

  private void resetStatusViews() {
    mAdvStatus.setText(R.string.status_notAdvertising);
    updateConnectedDevicesStatus();
  }

  private void updateConnectedDevicesStatus() {
    final String message = getString(R.string.status_devicesConnected) + " "
        + mBluetoothManager.getConnectedDevices(BluetoothGattServer.GATT).size();
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        mConnectionStatus.setText(message);
      }
    });
  }

  ///////////////////////
  ////// Bluetooth //////
  ///////////////////////

  //여기서 처음에 디스크립터의 기본 값들 설정해줌
  public static BluetoothGattDescriptor getClientCharacteristicConfigurationDescriptor() {
    BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
        CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
        (BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
    descriptor.setValue(new byte[]{0, 0});
    return descriptor;
  }

  public static BluetoothGattDescriptor getCharacteristicUserDescriptionDescriptor(String defaultValue) {
    BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
        CHARACTERISTIC_USER_DESCRIPTION_UUID,
        (BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
    try {
      descriptor.setValue(defaultValue.getBytes("UTF-8"));
    } finally {
      return descriptor;
    }
  }

  private void ensureBleFeaturesAvailable() {
    if (mBluetoothAdapter == null) {
      Toast.makeText(this, R.string.bluetoothNotSupported, Toast.LENGTH_LONG).show();
      Log.e(TAG, "Bluetooth not supported");
      finish();
    } else if (!mBluetoothAdapter.isEnabled()) {
      // Make sure bluetooth is enabled.
      Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
      startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
    }
  }
  private void disconnectFromDevices() {
    Log.d(TAG, "Disconnecting devices...");
    for (BluetoothDevice device : mBluetoothManager.getConnectedDevices(
        BluetoothGattServer.GATT)) {
      Log.d(TAG, "Devices: " + device.getAddress() + " " + device.getName());
      mGattServer.cancelConnection(device);
    }
  }

  //이건 개인적으로 추가해준 byte array(Ascii array)에서 string으로 변환 함수.
  public String bytesToString(byte[] value) {
    String converted = "";
    for (int i : value) {
      converted = converted.concat(Character.toString((char) i));
    }
    return converted;
  }
}
