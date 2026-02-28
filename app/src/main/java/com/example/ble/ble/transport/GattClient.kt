package com.example.ble.ble.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.example.ble.ble.model.LogChannel
import com.example.ble.ble.model.LogDirection
import com.example.ble.ble.model.LogLevel
import com.example.ble.ble.protocol.SofarProtocol
import com.example.ble.ble.protocol.toHexString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout

class GattClient(
    context: Context,
    private val log: (LogLevel, LogChannel, LogDirection, String, String?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val notifications = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val _disconnectEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var cccdDescriptor: BluetoothGattDescriptor? = null

    private var connectDeferred: CompletableDeferred<Result<Unit>>? = null
    private var serviceDeferred: CompletableDeferred<Result<Unit>>? = null
    private var mtuDeferred: CompletableDeferred<Result<Int>>? = null
    private var descriptorDeferred: CompletableDeferred<Result<Unit>>? = null
    private var disconnectDeferred: CompletableDeferred<Unit>? = null

    val disconnectEvents: SharedFlow<Int> = _disconnectEvents.asSharedFlow()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log(LogLevel.INFO, LogChannel.GATT, LogDirection.EVT, "connection state status=$status state=$newState", null)
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                connectDeferred?.complete(Result.success(Unit))
                connectDeferred = null
                return
            }

            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                val failure = if (status == BluetoothGatt.GATT_SUCCESS) {
                    IllegalStateException("GATT disconnected")
                } else {
                    IllegalStateException("GATT disconnected, status=$status")
                }
                connectDeferred?.complete(Result.failure(failure))
                serviceDeferred?.complete(Result.failure(failure))
                mtuDeferred?.complete(Result.failure(failure))
                descriptorDeferred?.complete(Result.failure(failure))
                connectDeferred = null
                serviceDeferred = null
                mtuDeferred = null
                descriptorDeferred = null
                disconnectDeferred?.complete(Unit)
                disconnectDeferred = null
                _disconnectEvents.tryEmit(status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                serviceDeferred?.complete(Result.success(Unit))
            } else {
                serviceDeferred?.complete(Result.failure(IllegalStateException("service discovery failed: $status")))
            }
            serviceDeferred = null
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuDeferred?.complete(Result.success(mtu))
            } else {
                mtuDeferred?.complete(Result.failure(IllegalStateException("MTU request failed: $status")))
            }
            mtuDeferred = null
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descriptorDeferred?.complete(Result.success(Unit))
            } else {
                descriptorDeferred?.complete(Result.failure(IllegalStateException("descriptor write failed: $status")))
            }
            descriptorDeferred = null
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleNotification(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic, value)
        }
    }

    private fun handleNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        notifications.trySend(value.copyOf())
        log(LogLevel.INFO, LogChannel.GATT, LogDirection.RX, "notify ${characteristic.uuid}", value.toHexString())
    }

    suspend fun connect(device: BluetoothDevice, timeoutMs: Long): Result<Unit> {
        return try {
            disconnectAndClose()
            val deferred = CompletableDeferred<Result<Unit>>()
            connectDeferred = deferred
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, callback)
            }
            bluetoothGatt = gatt
            log(LogLevel.INFO, LogChannel.GATT, LogDirection.EVT, "connect ${device.address}", null)
            val result = await(timeoutMs, deferred, "connect timeout")
            connectDeferred = null
            if (result.isFailure) {
                disconnectAndClose()
            }
            result
        } catch (throwable: Throwable) {
            connectDeferred = null
            disconnectAndClose()
            Result.failure(throwable)
        }
    }

    suspend fun discoverServices(timeoutMs: Long): Result<Unit> {
        return try {
            val gatt = bluetoothGatt ?: return Result.failure(IllegalStateException("BluetoothGatt unavailable"))
            val deferred = CompletableDeferred<Result<Unit>>()
            serviceDeferred = deferred
            if (!gatt.discoverServices()) {
                serviceDeferred = null
                return Result.failure(IllegalStateException("discoverServices returned false"))
            }
            val result = await(timeoutMs, deferred, "service discovery timeout")
            serviceDeferred = null
            if (result.isSuccess) {
                bindCharacteristics()
            }
            result
        } catch (throwable: Throwable) {
            serviceDeferred = null
            Result.failure(throwable)
        }
    }

    suspend fun requestMtu(mtu: Int, timeoutMs: Long): Result<Int> {
        return try {
            val gatt = bluetoothGatt ?: return Result.failure(IllegalStateException("BluetoothGatt unavailable"))
            val deferred = CompletableDeferred<Result<Int>>()
            mtuDeferred = deferred
            if (!gatt.requestMtu(mtu)) {
                mtuDeferred = null
                return Result.failure(IllegalStateException("requestMtu returned false"))
            }
            await(timeoutMs, deferred, "MTU request timeout").also {
                mtuDeferred = null
            }
        } catch (throwable: Throwable) {
            mtuDeferred = null
            Result.failure(throwable)
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean, timeoutMs: Long): Result<Unit> {
        return try {
            val gatt = bluetoothGatt ?: return Result.failure(IllegalStateException("BluetoothGatt unavailable"))
            val notify = notifyCharacteristic ?: return Result.failure(IllegalStateException("FFF2 not found"))
            val descriptor = cccdDescriptor ?: return Result.failure(IllegalStateException("CCCD not found"))
            if (!gatt.setCharacteristicNotification(notify, enabled)) {
                return Result.failure(IllegalStateException("setCharacteristicNotification returned false"))
            }
            val deferred = CompletableDeferred<Result<Unit>>()
            descriptorDeferred = deferred
            val value = if (enabled) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = value
                    gatt.writeDescriptor(descriptor)
                }
            }
            if (!started) {
                descriptorDeferred = null
                return Result.failure(IllegalStateException("descriptor write start failed"))
            }
            await(timeoutMs, deferred, "descriptor write timeout").also {
                descriptorDeferred = null
            }
        } catch (throwable: Throwable) {
            descriptorDeferred = null
            Result.failure(throwable)
        }
    }

    fun isReady(): Boolean = bluetoothGatt != null && writeCharacteristic != null && notifyCharacteristic != null

    fun sendCommand(frame: ByteArray): Result<Unit> {
        val gatt = bluetoothGatt ?: return Result.failure(IllegalStateException("BluetoothGatt unavailable"))
        val characteristic = writeCharacteristic ?: return Result.failure(IllegalStateException("FFF1 not found"))
        log(LogLevel.INFO, LogChannel.GATT, LogDirection.TX, "write ${characteristic.uuid}", frame.toHexString())
        return try {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.value = frame
                    gatt.writeCharacteristic(characteristic)
                }
            }
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("command write failed"))
            }
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    suspend fun nextNotification(): ByteArray = notifications.receive()

    fun clearPendingNotifications() {
        while (notifications.tryReceive().isSuccess) {
        }
    }

    suspend fun requestDisconnect(timeoutMs: Long): Boolean {
        val gatt = bluetoothGatt ?: return true
        val deferred = CompletableDeferred<Unit>()
        disconnectDeferred = deferred
        return try {
            gatt.disconnect()
            withTimeout(timeoutMs) {
                deferred.await()
                true
            }
        } catch (_: TimeoutCancellationException) {
            false
        } catch (_: Throwable) {
            false
        } finally {
            disconnectDeferred = null
        }
    }

    fun close() {
        clearPendingNotifications()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        cccdDescriptor = null
    }

    fun disconnectAndClose() {
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Throwable) {
        }
        close()
    }

    private fun bindCharacteristics() {
        val service: BluetoothGattService = bluetoothGatt
            ?.getService(SofarProtocol.serviceUuid)
            ?: error("FFF0 service not found")
        writeCharacteristic = service.getCharacteristic(SofarProtocol.writeUuid)
        notifyCharacteristic = service.getCharacteristic(SofarProtocol.notifyUuid)
        cccdDescriptor = notifyCharacteristic?.getDescriptor(SofarProtocol.cccdUuid)
        if (writeCharacteristic == null || notifyCharacteristic == null || cccdDescriptor == null) {
            error("incomplete FFF1/FFF2/CCCD")
        }
        log(LogLevel.INFO, LogChannel.GATT, LogDirection.EVT, "bound FFF0/FFF1/FFF2/CCCD", null)
    }

    private suspend fun <T> await(
        timeoutMs: Long,
        deferred: CompletableDeferred<Result<T>>,
        timeoutLabel: String,
    ): Result<T> {
        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            Result.failure(IllegalStateException(timeoutLabel))
        }
    }
}
