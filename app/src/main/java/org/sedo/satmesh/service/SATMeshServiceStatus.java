package org.sedo.satmesh.service;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public class SATMeshServiceStatus {
	private static final SATMeshServiceStatus INSTANCE = new SATMeshServiceStatus();
	private final MutableLiveData<Boolean> serviceReady = new MutableLiveData<>(false);

	private SATMeshServiceStatus() {
	}

	public static SATMeshServiceStatus getInstance() {
		return INSTANCE;
	}

	public LiveData<Boolean> getServiceReady() {
		return serviceReady;
	}

	public void setServiceReady(boolean ready) {
		serviceReady.postValue(ready);
	}
}
