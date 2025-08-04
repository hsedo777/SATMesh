package org.sedo.satmesh.service;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public class SATMeshServiceStatus {
    private static final SATMeshServiceStatus INSTANCE = new SATMeshServiceStatus();

    private final MutableLiveData<Boolean> serviceReady = new MutableLiveData<>(false);
    private volatile Boolean wasBluetoothEnabled;
    private volatile Boolean wasWifiEnabled;

    private SATMeshServiceStatus() {
    }

    public static SATMeshServiceStatus getInstance() {
        return INSTANCE;
    }

    @NonNull
    public LiveData<Boolean> getServiceReady() {
        return serviceReady;
    }

    public void setServiceReady(boolean ready) {
        serviceReady.postValue(ready);
    }

    public synchronized boolean needsToDisableBluetooth() {
        return wasBluetoothEnabled == null || !wasBluetoothEnabled;
    }

    public synchronized void setWasBluetoothEnabled(boolean wasBluetoothEnabled) {
        if (this.wasBluetoothEnabled == null) {
            this.wasBluetoothEnabled = wasBluetoothEnabled;
        }
    }

    public synchronized boolean needsToDisableWifi() {
        return wasWifiEnabled == null || !wasWifiEnabled;
    }

    public synchronized void setWasWifiEnabled(boolean wasWifiEnabled) {
        if (this.wasWifiEnabled == null) {
            this.wasWifiEnabled = wasWifiEnabled;
        }
    }
}
