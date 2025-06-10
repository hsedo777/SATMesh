package org.sedo.satmesh.ui;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class WelcomeViewModel extends ViewModel {

	private final MutableLiveData<String> userName = new MutableLiveData<>("");

	protected WelcomeViewModel() {
	}

	public MutableLiveData<String> getUserName() {
		return userName;
	}
}
