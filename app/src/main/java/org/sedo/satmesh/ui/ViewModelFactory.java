package org.sedo.satmesh.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

public class ViewModelFactory implements ViewModelProvider.Factory {

	// 1. Private static instance of the factory
	private static volatile ViewModelFactory INSTANCE;

	private final Application application;

	// 2. Private constructor to prevent direct instantiation
	private ViewModelFactory(@NonNull Application application) {
		this.application = application;
	}

	// 3. Public static method to get the instance
	public static ViewModelFactory getInstance(@NonNull Application application) {
		if (INSTANCE == null) {
			synchronized (ViewModelFactory.class) { // Ensure thread-safe initialization
				if (INSTANCE == null) {
					INSTANCE = new ViewModelFactory(application);
				}
			}
		}
		return INSTANCE;
	}

	@SuppressWarnings("unchecked")
	@NonNull
	@Override // It's good practice to add @Override for clarity
	public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
		if (modelClass.isAssignableFrom(WelcomeViewModel.class)) { // Use isAssignableFrom for better type checking
			return (T) new WelcomeViewModel();
		}
		if (modelClass.isAssignableFrom(NearbyDiscoveryViewModel.class)) {
			return (T) new NearbyDiscoveryViewModel(application);
		}
		if (modelClass.isAssignableFrom(ChatViewModel.class)) {
			return (T) new ChatViewModel(application);
		}
		if (modelClass.isAssignableFrom(ChatListViewModel.class)){
			return (T) new ChatListViewModel(application);
		}
		// If no matching ViewModel is found, throw an IllegalArgumentException
		throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
	}
}