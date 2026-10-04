package com.magicmirror.collect.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * 智能地垫（BLE 电子秤）连接管理器。
 *
 * 协议见 docs/03-硬件选型与接口协议.md：
 *   Service 0xFFF0
 *     0xFFF1 Notify  实时重量
 *     0xFFF2 Notify  稳定重量（只在读数稳定后上报一次）
 *
 * 采集侧只在收到「稳定重量」时才推进业务，避免把站上去的瞬时抖动当成测量值。
 */
class MatScaleManager(private val context: Context) {

    sealed interface Event {
        data class Found(val name: String, val address: String) : Event
        data class Connected(val name: String) : Event
        data class StableWeight(val kg: Double) : Event
        data class LiveWeight(val kg: Double) : Event
        data class Error(val message: String) : Event
        data object Disconnected : Event
    }

    companion object {
        private const val TAG = "MatScale"

        val SERVICE_UUID: UUID = from16Bit(0xFFF0)
        val CHAR_LIVE: UUID = from16Bit(0xFFF1)
        val CHAR_STABLE: UUID = from16Bit(0xFFF2)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 低于该重量视为宠物或杂物踩踏，不纳入采集 */
        const val MIN_VALID_KG = 3.0

        private fun from16Bit(value: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", value))
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    fun hasPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermission()) {
            emit(Event.Error("缺少蓝牙权限"))
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            emit(Event.Error("蓝牙未开启"))
            return
        }
        if (scanning) return

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            emit(Event.Error("设备不支持 BLE 扫描"))
            return
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        scanner.startScan(filters, settings, scanCallback)
        Log.i(TAG, "开始扫描地垫")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        Log.i(TAG, "停止扫描")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = try {
                device.name ?: "智能地垫"
            } catch (e: SecurityException) {
                "智能地垫"
            }
            emit(Event.Found(name, device.address))
            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            emit(Event.Error("扫描失败，错误码 $errorCode"))
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasPermission()) {
            emit(Event.Error("缺少蓝牙连接权限"))
            return
        }
        close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    val name = try {
                        g.device.name ?: "智能地垫"
                    } catch (e: SecurityException) {
                        "智能地垫"
                    }
                    emit(Event.Connected(name))
                    g.discoverServices()
                }
                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    emit(Event.Disconnected)
                    g.close()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID) ?: run {
                emit(Event.Error("地垫未提供约定的测量服务"))
                return
            }
            subscribe(g, service.getCharacteristic(CHAR_STABLE))
            subscribe(g, service.getCharacteristic(CHAR_LIVE))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleValue(characteristic.uuid, value)
        }

        @Deprecated("Android 13 以下兼容路径")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleValue(characteristic.uuid, characteristic.value ?: return)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        g.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    private fun handleValue(uuid: UUID, value: ByteArray) {
        val kg = parseWeight(value)
        if (kg <= 0.0) return
        when (uuid) {
            CHAR_STABLE -> if (kg >= MIN_VALID_KG) emit(Event.StableWeight(kg))
            CHAR_LIVE -> emit(Event.LiveWeight(kg))
        }
    }

    /** 协议：4 字节小端无符号整数，单位 0.1 g。 */
    private fun parseWeight(data: ByteArray): Double {
        if (data.size < 4) return 0.0
        val raw = (data[0].toInt() and 0xFF) or
                ((data[1].toInt() and 0xFF) shl 8) or
                ((data[2].toInt() and 0xFF) shl 16) or
                ((data[3].toInt() and 0xFF) shl 24)
        return raw / 10000.0
    }

    private fun emit(event: Event) {
        _events.tryEmit(event)
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "关闭 GATT 失败: ${e.message}")
        } finally {
            gatt = null
        }
    }
}
