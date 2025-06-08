package org.sedo.satmesh.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

public class ViewModelFactory implements ViewModelProvider.Factory {

	private final Application application;

	public ViewModelFactory(@NonNull Application application) {
		this.application = application;
	}

	/** @noinspection unchecked*/
	@NonNull
	public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
		if (modelClass == WelcomeViewModel.class) {
			return (T) new WelcomeViewModel();
		}
		if (modelClass == NearbyDiscoveryViewModel.class){
			return (T) new NearbyDiscoveryViewModel(application);
		}
		if (modelClass == ChatViewModel.class){
			return (T) new ChatViewModel(application);
		}
		return ViewModelProvider.Factory.super.create(modelClass);
	}
}
