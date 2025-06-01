package org.sedo.satmesh.ui;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

public class ViewModelFactory implements ViewModelProvider.Factory {

	private static ViewModelFactory factory;

	private ViewModelFactory() {}

	public static ViewModelFactory getInstance() {
		if (factory == null) {
			synchronized (ViewModelFactory.class) {
				if (factory == null) {
					factory = new ViewModelFactory();
				}
			}
		}
		return factory;
	}

	/** @noinspection unchecked*/
	@NonNull
	public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
		if (modelClass == WelcomeViewModel.class) {
			return (T) new WelcomeViewModel();
		}
		if (modelClass == NearbyDiscoveryViewModel.class){
			return (T) new NearbyDiscoveryViewModel();
		}
		return ViewModelProvider.Factory.super.create(modelClass);
	}
}
