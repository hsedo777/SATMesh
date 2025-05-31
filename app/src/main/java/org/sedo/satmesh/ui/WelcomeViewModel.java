package org.sedo.satmesh.ui;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;

public class WelcomeViewModel extends ViewModel {

	private final MutableLiveData<String> userName = new MutableLiveData<>("");
	private final MutableLiveData<Boolean> isButtonEnabled = new MutableLiveData<>(false);
	private static final int MAX_LENGTH = 60;


	protected WelcomeViewModel(){}

	public void onUserNameChanged(String name) {
		String value  = (null == name) ? null : name.trim();
		userName.setValue(value);
		isButtonEnabled.setValue(value != null && !value.isEmpty() && value.length() <= MAX_LENGTH);
	}

	public String getUserName() {
		return userName.getValue();
	}

	/**
	 * Observe the state of the enabling button
	 */
	@MainThread
	public void observe(@NonNull LifecycleOwner owner, @NonNull Observer<? super Boolean> observer) {
		isButtonEnabled.observe(owner, observer);
	}
}
